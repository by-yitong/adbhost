package com.adbhost.app.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ServiceHandler : Closeable {
    fun onPayload(data: ByteArray)
    fun onClosed()
}

class AdbConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val auth: AuthEngine,
    identity: RsaIdentity,
    private val tlsMode: Boolean,
    maxPayload: Int = AdbMessage.MAX_PAYLOAD,
    private val preconsumedPeerMaxPayload: Int? = null,
    private val localBanner: String,
    private val services: (service: String, localId: Long, remoteId: Long, conn: AdbConnection) -> ServiceHandler,
    private val onAuthorizationRequest: (fingerprint: String, name: String, respond: (Boolean) -> Unit) -> Unit,
    private val onAuthorized: (String) -> Unit = {},
    private val log: (String) -> Unit,
) : Closeable {

    @Volatile
    private var authorizedState = false
    val authorized: Boolean get() = authorizedState

    @Volatile
    private var closed = false
    private val configuredMaxPayload = maxPayload
    private var maxPayloadLimit = maxPayload
    private val identityFingerprint: String =
        runCatching { AdbPublicKeyCodec().fingerprintSha1(identity.keyPair.public as RSAPublicKey) }.getOrDefault("unknown")
    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Long, ActiveStream>()
    private val nextRemoteId = AtomicLong(1)
    private val codec = AdbPublicKeyCodec()

    private class ActiveStream(val peerLocalId: Long, val remoteId: Long) {
        @Volatile
        var handler: ServiceHandler? = null
        private val ready = java.util.concurrent.CountDownLatch(1)

        fun attach(target: ServiceHandler) {
            handler = target
            ready.countDown()
        }

        fun awaitHandler(): ServiceHandler? {
            ready.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return handler
        }
    }

    fun startHandshakeAndLoop() {
        try {
            if (preconsumedPeerMaxPayload != null) {
                maxPayloadLimit = minOf(preconsumedPeerMaxPayload, configuredMaxPayload)
                if (tlsMode) {
                    authorizedState = true
                    sendConnectionBanner()
                }
            } else {
                val peerCnxn = AdbMessage.read(input, configuredMaxPayload)
                if (peerCnxn == null || peerCnxn.command != AdbCommand.CNXN) {
                    log("handshake failed: first message must be CNXN")
                    return
                }
                val peerMax = peerCnxn.arg1.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                maxPayloadLimit = minOf(peerMax, configuredMaxPayload)
                log("host connected banner=${peerCnxn.payload.toString(Charsets.UTF_8)} maxPayload=$maxPayloadLimit identity=$identityFingerprint")
                if (tlsMode) {
                    authorizedState = true
                    sendConnectionBanner()
                }
            }
            if (!authorizedState && !runClassicAuth()) {
                log("authorization failed, disconnecting")
                return
            }
            onlineLoop()
        } catch (e: Exception) {
            if (!closed) log("connection loop ended: ${e.message}")
        } finally {
            closeStreams()
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    fun sendMessage(msg: AdbMessage) {
        synchronized(writeLock) {
            msg.encode(output)
            output.flush()
        }
    }

    fun closeService(remoteId: Long) {
        val stream = streams.remove(remoteId) ?: return
        sendClose(stream)
    }

    override fun close() {
        if (closed) return
        closed = true
        closeStreams()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun runClassicAuth(): Boolean {
        val random = SecureRandom()
        repeat(MAX_AUTH_ROUNDS) {
            val token = ByteArray(TOKEN_SIZE).also(random::nextBytes)
            sendMessage(AdbMessage(AdbCommand.AUTH, AdbAuth.TOKEN, 0L, token))
            val msg = AdbMessage.read(input, maxPayloadLimit) ?: return false
            when {
                msg.command == AdbCommand.AUTH && msg.arg0 == AdbAuth.SIGNATURE -> {
                    if (auth.verifySignature(token, msg.payload)) {
                        grant()
                        return true
                    }
                    log("signature verification failed, requesting public key")
                }
                msg.command == AdbCommand.AUTH && msg.arg0 == AdbAuth.RSAPUBLICKEY -> {
                    if (handlePublicKey(msg)) return true
                }
                else -> log("unexpected message during auth: $msg")
            }
        }
        return false
    }

    private fun handlePublicKey(msg: AdbMessage): Boolean {
        val line = msg.payload.toString(Charsets.UTF_8).trimEnd('\u0000').trim()
        val fingerprint = runCatching { codec.fingerprintOfAdbPayload(msg.payload) }.getOrNull()
        if (line.isEmpty() || fingerprint == null) {
            log("malformed RSAPUBLICKEY payload")
            return false
        }
        val name = line.substringAfter(' ', "").trim()
        var granted = false
        val respond: (Boolean) -> Unit = { allow ->
            if (allow) {
                auth.trust.addAdbPublicKey(line)
                grant()
                granted = true
            }
        }
        onAuthorizationRequest(fingerprint, name, respond)
        if (granted) log("host authorized via user consent name=$name")
        else log("host authorization refused fingerprint=$fingerprint")
        return granted
    }

    private fun grant() {
        sendConnectionBanner()
        authorizedState = true
        onAuthorized(identityFingerprint)
    }

    private fun sendConnectionBanner() {
        sendMessage(AdbMessage(AdbCommand.CNXN, A_VERSION, configuredMaxPayload.toLong(), localBanner.toByteArray()))
    }

    private fun onlineLoop() {
        while (!closed) {
            val msg = AdbMessage.read(input, maxPayloadLimit)
            if (msg == null) {
                log("online loop ended: peer closed or bad frame")
                break
            }
            when (msg.command) {
                AdbCommand.OPEN -> handleOpen(msg)
                AdbCommand.WRTE -> handleWrite(msg)
                AdbCommand.CLSE -> handlePeerClose(msg)
                AdbCommand.OKAY, AdbCommand.CNXN -> Unit
                else -> log("unexpected message online: $msg")
            }
        }
    }

    private fun handleOpen(msg: AdbMessage) {
        val peerLocalId = msg.arg0
        val service = msg.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
        val remoteId = nextRemoteId.getAndIncrement()
        val stream = ActiveStream(peerLocalId, remoteId)
        streams[remoteId] = stream
        sendMessage(AdbMessage(AdbCommand.OKAY, remoteId, peerLocalId, ByteArray(0)))
        val handler = runCatching { services(service, peerLocalId, remoteId, this) }
            .onFailure { log("service '$service' creation failed: ${it.message}") }
            .getOrNull()
        if (handler == null) {
            streams.remove(remoteId)
            sendMessage(AdbMessage(AdbCommand.CLSE, remoteId, peerLocalId, ByteArray(0)))
            return
        }
        stream.attach(handler)
        log("stream opened service=$service localId=$peerLocalId remoteId=$remoteId")
    }

    private fun handleWrite(msg: AdbMessage) {
        val stream = streams[msg.arg1]
        if (stream == null) {
            sendMessage(AdbMessage(AdbCommand.CLSE, msg.arg1, msg.arg0, ByteArray(0)))
            return
        }
        sendMessage(AdbMessage(AdbCommand.OKAY, msg.arg1, msg.arg0, ByteArray(0)))
        val handler = stream.awaitHandler()
        if (handler == null) {
            streams.remove(stream.remoteId)
            sendClose(stream)
            return
        }
        runCatching { handler.onPayload(msg.payload) }
            .onFailure {
                log("handler error remoteId=${stream.remoteId}: ${it.message}")
                streams.remove(stream.remoteId)
                sendClose(stream)
            }
    }

    private fun handlePeerClose(msg: AdbMessage) {
        val stream = streams.remove(msg.arg1)
            ?: streams.values.firstOrNull { it.peerLocalId == msg.arg0 }?.also { streams.remove(it.remoteId) }
            ?: return
        log("stream closed by peer remoteId=${stream.remoteId}")
        closeStreamHandler(stream)
    }

    private fun sendClose(stream: ActiveStream) {
        runCatching { sendMessage(AdbMessage(AdbCommand.CLSE, stream.remoteId, stream.peerLocalId, ByteArray(0))) }
        closeStreamHandler(stream)
    }

    private fun closeStreamHandler(stream: ActiveStream) {
        val handler = stream.handler ?: return
        runCatching { handler.onClosed() }
    }

    private fun closeStreams() {
        streams.values.forEach { closeStreamHandler(it) }
        streams.clear()
    }

    companion object {
        private const val A_VERSION = 0x01000001L
        private const val TOKEN_SIZE = 20
        private const val MAX_AUTH_ROUNDS = 3
    }
}
