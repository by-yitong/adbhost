package com.adbhost.app.engine

import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.AuthEngine
import com.adbhost.app.core.RsaIdentity
import com.adbhost.app.core.TrustStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class ConnectionRegistry {
    var onChange: ((Int) -> Unit)? = null
    private val connections = ConcurrentHashMap<String, MutableSet<AdbConnection>>()

    fun count(): Int = connections.values.sumOf { it.size }

    fun register(fingerprint: String, connection: AdbConnection) {
        connections.getOrPut(fingerprint.uppercase()) { ConcurrentHashMap.newKeySet() }.add(connection)
        notifyChange()
    }

    fun unregister(fingerprint: String, connection: AdbConnection) {
        connections[fingerprint.uppercase()]?.remove(connection)
        notifyChange()
    }

    fun disconnect(fingerprint: String): Int {
        val peers = connections.remove(fingerprint.uppercase()) ?: return 0
        peers.forEach { runCatching { it.close() } }
        notifyChange()
        return peers.size
    }

    fun disconnectAll(): Int {
        var total = 0
        connections.keys.toList().forEach { total += disconnect(it) }
        return total
    }

    private fun notifyChange() {
        onChange?.invoke(count())
    }
}

class AdbServer(
    private val port: Int,
    private val identity: RsaIdentity,
    private val trust: TrustStore,
    identityDir: File,
    syncRoot: File,
    private val tlsMode: () -> Boolean,
    private val rootMode: () -> Boolean,
    private val authorize: (fingerprint: String, name: String, kind: String) -> Boolean,
    private val authorizeTls: (fingerprint: String, name: String) -> Pair<Boolean, Boolean>,
    private val appContext: android.content.Context? = null,
    private val log: (String) -> Unit,
    private val onFileReceived: (File, String) -> Unit = { _, _ -> },
    private val onConnectionsChanged: (Int) -> Unit = {},
) {

    val registry = ConnectionRegistry()

    init {
        registry.onChange = onConnectionsChanged
    }
    private val dispatcher = ServiceDispatcher(syncRoot, rootMode, log, appContext, onFileReceived)
    private val tlsUpgrader = TlsUpgrader(identity, trust, identityDir, { fp, name -> authorizeCert(fp, name) }, log)
    private val auth = AuthEngine(identity, trust)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(BIND_HOST, port), BACKLOG)
        serverSocket = socket
        log("端口已绑定 $BIND_HOST:$port")
        Thread({ acceptLoop(socket) }, "adb-accept-$port").apply { isDaemon = true }.start()
    }

    fun boundPort(): Int = serverSocket?.localPort ?: -1

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        registry.disconnectAll()
        log("服务监听已关闭 port=$port")
    }

    fun disconnectFingerprint(fingerprint: String): Int = registry.disconnect(fingerprint)

    fun disconnectAll(): Int = registry.disconnectAll()

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!stopped) log("accept 异常:${e.message}")
                return
            }
            Thread({ handleConnection(client) }, "adb-conn-${client.port}").apply { isDaemon = true }.start()
        }
    }

    private fun handleConnection(socket: Socket) {
        var fingerprint: String? = null
        var connection: AdbConnection? = null
        try {
            socket.tcpNoDelay = true
            val plainInput = socket.getInputStream()
            val plainOutput = socket.getOutputStream()
            val peerMaxPayload = readPeerCnxn(plainInput) ?: return
            var input: InputStream = plainInput
            var output: OutputStream = plainOutput
            var tls = false
            if (tlsMode()) {
                writeMessage(
                    plainOutput,
                    AdbMessage(AdbCommand.CNXN, VERSION, AdbMessage.MAX_PAYLOAD.toLong(), DEVICE_BANNER.toByteArray(Charsets.UTF_8))
                )
                if (!exchangeStls(plainInput, plainOutput)) return
                val (ssl, peer) = tlsUpgrader.upgrade(socket)
                fingerprint = peer
                input = ssl.inputStream
                output = ssl.outputStream
                tls = true
                log("TLS 已升级 fingerprint=$fingerprint")
            }
            var registered: AdbConnection? = null
            val onAuthorization: (String, String, (Boolean) -> Unit) -> Unit = { fp, name, respond ->
                val allowed = authorize(fp, name, TrustStore.KIND_ADB)
                if (allowed) registered?.let { registry.register(fp, it) }
                respond(allowed)
            }
            val onAuthorized: (String) -> Unit = { fp ->
                fingerprint = fp
                registered?.let { registry.register(fp, it) }
            }
            val created = AdbConnection(
                input = input,
                output = output,
                auth = auth,
                identity = identity,
                tlsMode = tls,
                preconsumedPeerMaxPayload = peerMaxPayload,
                localBanner = DEVICE_BANNER,
                onAuthorized = onAuthorized,
                services = { service, peerLocalId, remoteId, conn ->
                    dispatcher.open(service, peerLocalId, remoteId, conn)
                },
                onAuthorizationRequest = onAuthorization,
                log = log,
            )
            connection = created
            registered = created
            if (tls) fingerprint?.let { registry.register(it, created) }
            created.startHandshakeAndLoop()
        } catch (e: Exception) {
            log("连接异常:${e.message}")
        } finally {
            connection?.let { conn -> fingerprint?.let { registry.unregister(it, conn) } }
            runCatching { socket.close() }
            log("连接结束 port=${socket.port}")
        }
    }

    fun authorizeCert(fingerprint: String, name: String): Pair<Boolean, Boolean> = authorizeTls(fingerprint, name)

    private fun readPeerCnxn(input: InputStream): Int? {
        val peer = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD)
        if (peer == null || peer.command != AdbCommand.CNXN) {
            log("握手失败:首个报文不是 CNXN")
            return null
        }
        log("主机接入 banner=${peer.payload.toString(Charsets.UTF_8)}")
        return peer.arg1.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun exchangeStls(input: InputStream, output: OutputStream): Boolean {
        writeMessage(output, AdbMessage(AdbCommand.STLS, STLS_VERSION, 0L, ByteArray(0)))
        val reply = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD)
        if (reply == null || reply.command != AdbCommand.STLS) {
            log("主机不支持 TLS:未收到 A_STLS")
            return false
        }
        return true
    }

    private fun writeMessage(output: OutputStream, message: AdbMessage) {
        message.encode(output)
        output.flush()
    }

    companion object {
        const val DEVICE_BANNER =
            "device::ro.product.name=adbhost;ro.product.model=AdbHost;ro.product.device=adbhost;features=shell_v2,stat_v2"
        private const val BIND_HOST = "0.0.0.0"
        private const val BACKLOG = 8
        private const val VERSION = 0x01000001L
        private const val STLS_VERSION = 0x01000000L
    }
}
