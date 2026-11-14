package com.adbhost.app.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbConnectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class HostEnd(private val fromDevice: InputStream, private val toDevice: OutputStream) {
        fun read(): AdbMessage = requireNotNull(AdbMessage.read(fromDevice, AdbMessage.MAX_PAYLOAD)) {
            "device stream ended"
        }

        fun send(message: AdbMessage) {
            message.encode(toDevice)
            toDevice.flush()
        }
    }

    private class RecordingHandler(
        private val conn: AdbConnection,
        private val localId: Long,
        private val remoteId: Long,
        private val closeAfterPayload: Boolean = false,
    ) : ServiceHandler {
        val received = CopyOnWriteArrayList<ByteArray>()
        val payloadLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)

        @Volatile
        var closed = false

        override fun onPayload(data: ByteArray) {
            received.add(data)
            conn.sendMessage(AdbMessage(AdbCommand.WRTE, remoteId, localId, data))
            payloadLatch.countDown()
            if (closeAfterPayload) conn.closeService(remoteId)
        }

        override fun onClosed() {
            closed = true
            closeLatch.countDown()
        }

        override fun close() = onClosed()
    }

    private class Pipes {
        val hostToDeviceOut = PipedOutputStream()
        val deviceIn = PipedInputStream(hostToDeviceOut, 1 shl 16)
        val deviceToHostIn = PipedInputStream(1 shl 16)
        val deviceOut = PipedOutputStream(deviceToHostIn)
        val host = HostEnd(deviceToHostIn, hostToDeviceOut)
    }

    private fun startLoop(conn: AdbConnection): Thread =
        Thread { conn.startHandshakeAndLoop() }.apply {
            isDaemon = true
            start()
        }

    private fun signatureFor(identity: RsaIdentity, token: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(identity.keyPair.private)
        signer.update(token)
        return signer.sign()
    }

    private fun sendHostCnxn(host: HostEnd) {
        host.send(AdbMessage(AdbCommand.CNXN, 0x01000001L, AdbMessage.MAX_PAYLOAD.toLong(), "host::features=".toByteArray()))
    }

    @Test
    fun classicAuthSignaturePathThenEchoService() {
        val dir = tmp.newFolder()
        val store = TrustStore(dir)
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        val identity = RsaIdentity(TestKeys.privateKeyFile(dir))
        val engine = AuthEngine(identity, store)
        val pipes = Pipes()
        val handlers = CopyOnWriteArrayList<RecordingHandler>()
        val conn = AdbConnection(
            input = pipes.deviceIn,
            output = pipes.deviceOut,
            auth = engine,
            identity = identity,
            tlsMode = false,
            localBanner = "device::ro.product.name=adbhost;ro.product.model=AdbHost;features=shell_v2,stat_v2",
            services = { service, localId, remoteId, connection ->
                assertEquals("echo", service)
                RecordingHandler(connection, localId, remoteId).also { handlers.add(it) }
            },
            onAuthorizationRequest = { _, _, _ -> fail("authorization must not be requested for trusted key") },
            log = { },
        )
        val loop = startLoop(conn)

        sendHostCnxn(pipes.host)

        val token = pipes.host.read()
        assertEquals(AdbCommand.AUTH, token.command)
        assertEquals(AdbAuth.TOKEN, token.arg0)
        assertEquals(20, token.payload.size)

        pipes.host.send(AdbMessage(AdbCommand.AUTH, AdbAuth.SIGNATURE, 0L, signatureFor(identity, token.payload)))
        val granted = pipes.host.read()
        assertEquals(AdbCommand.CNXN, granted.command)
        assertTrue(conn.authorized)

        pipes.host.send(AdbMessage(AdbCommand.OPEN, 100L, 0L, "echo".toByteArray()))
        val okay = pipes.host.read()
        assertEquals(AdbCommand.OKAY, okay.command)
        assertEquals(100L, okay.arg1)
        val remoteId = okay.arg0
        assertTrue(remoteId > 0)

        pipes.host.send(AdbMessage(AdbCommand.WRTE, 100L, remoteId, "hello adbhost".toByteArray()))
        val ack = pipes.host.read()
        assertEquals(AdbCommand.OKAY, ack.command)
        assertEquals(remoteId, ack.arg0)
        assertEquals(100L, ack.arg1)

        val echo = pipes.host.read()
        assertEquals(AdbCommand.WRTE, echo.command)
        assertEquals(remoteId, echo.arg0)
        assertEquals(100L, echo.arg1)
        assertEquals("hello adbhost", String(echo.payload, Charsets.UTF_8))

        assertEquals(1, handlers.size)
        assertTrue(handlers[0].payloadLatch.await(2, TimeUnit.SECONDS))
        assertEquals("hello adbhost", String(handlers[0].received[0], Charsets.UTF_8))

        pipes.host.send(AdbMessage(AdbCommand.CLSE, 100L, remoteId, ByteArray(0)))
        assertTrue(handlers[0].closeLatch.await(2, TimeUnit.SECONDS))
        assertTrue(handlers[0].closed)

        pipes.hostToDeviceOut.close()
        loop.join(5000)
        assertFalse(loop.isAlive)
        conn.close()
    }

    @Test
    fun classicAuthPublicKeyAuthorizationPersistsTrust() {
        val dir = tmp.newFolder()
        val store = TrustStore(dir)
        val identity = RsaIdentity(TestKeys.privateKeyFile(dir))
        val engine = AuthEngine(identity, store)
        val pipes = Pipes()
        val requestedFingerprint = AtomicReference<String?>()
        val requestedName = AtomicReference<String?>()
        val conn = AdbConnection(
            input = pipes.deviceIn,
            output = pipes.deviceOut,
            auth = engine,
            identity = identity,
            tlsMode = false,
            localBanner = "device::ro.product.name=adbhost;features=shell_v2",
            services = { _, _, _, _ -> throw IllegalStateException("no service expected") },
            onAuthorizationRequest = { fingerprint, name, respond ->
                requestedFingerprint.set(fingerprint)
                requestedName.set(name)
                respond(true)
            },
            log = { },
        )
        val loop = startLoop(conn)

        sendHostCnxn(pipes.host)
        val token = pipes.host.read()
        assertEquals(AdbAuth.TOKEN, token.arg0)
        pipes.host.send(
            AdbMessage(AdbCommand.AUTH, AdbAuth.RSAPUBLICKEY, 0L, (TestKeys.publicKeyLine + "\u0000").toByteArray()),
        )

        val granted = pipes.host.read()
        assertEquals(AdbCommand.CNXN, granted.command)
        assertTrue(conn.authorized)
        assertTrue(requestedFingerprint.get()!!.matches(Regex("^([0-9A-F]{2}:){19}[0-9A-F]{2}$")))
        assertEquals(TestKeys.publicKeyLine.substringAfter(' '), requestedName.get())
        assertEquals(listOf(TestKeys.publicKeyLine), File(dir, "adb_keys").readLines().filter { it.isNotBlank() })
        assertEquals(1, store.trustedAdbPublicKeys().size)

        pipes.hostToDeviceOut.close()
        loop.join(5000)
        assertFalse(loop.isAlive)
        conn.close()
    }

    @Test
    fun authorizationRefusedThreeRoundsDisconnects() {
        val dir = tmp.newFolder()
        val identity = RsaIdentity(TestKeys.privateKeyFile(dir))
        val engine = AuthEngine(identity, TrustStore(dir))
        val pipes = Pipes()
        val requests = AtomicInteger(0)
        val conn = AdbConnection(
            input = pipes.deviceIn,
            output = pipes.deviceOut,
            auth = engine,
            identity = identity,
            tlsMode = false,
            localBanner = "device::ro.product.name=adbhost;features=shell_v2",
            services = { _, _, _, _ -> throw IllegalStateException("no service expected") },
            onAuthorizationRequest = { _, _, respond -> requests.incrementAndGet(); respond(false) },
            log = { },
        )
        val loop = startLoop(conn)

        sendHostCnxn(pipes.host)
        repeat(3) {
            val token = pipes.host.read()
            assertEquals(AdbAuth.TOKEN, token.arg0)
            pipes.host.send(
                AdbMessage(AdbCommand.AUTH, AdbAuth.RSAPUBLICKEY, 0L, (TestKeys.publicKeyLine + "\u0000").toByteArray()),
            )
        }
        loop.join(5000)
        assertFalse(loop.isAlive)
        assertFalse(conn.authorized)
        assertEquals(3, requests.get())
        try {
            pipes.host.read()
            fail("device should have disconnected")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(0, File(dir, "adb_keys").length())
        conn.close()
    }

    @Test
    fun tlsModeSkipsAuthAndServesStreams() {
        val dir = tmp.newFolder()
        val identity = RsaIdentity(TestKeys.privateKeyFile(dir))
        val engine = AuthEngine(identity, TrustStore(dir))
        val pipes = Pipes()
        val handlers = CopyOnWriteArrayList<RecordingHandler>()
        val conn = AdbConnection(
            input = pipes.deviceIn,
            output = pipes.deviceOut,
            auth = engine,
            identity = identity,
            tlsMode = true,
            localBanner = "device::ro.product.name=adbhost;features=shell_v2,stat_v2",
            services = { _, localId, remoteId, connection ->
                RecordingHandler(connection, localId, remoteId, closeAfterPayload = true).also { handlers.add(it) }
            },
            onAuthorizationRequest = { _, _, _ -> fail("no auth in tls mode") },
            log = { },
        )
        val loop = startLoop(conn)

        sendHostCnxn(pipes.host)
        assertEquals(AdbCommand.CNXN, pipes.host.read().command)
        assertTrue(conn.authorized)

        pipes.host.send(AdbMessage(AdbCommand.OPEN, 42L, 0L, "shell:v2".toByteArray()))
        val okay = pipes.host.read()
        assertEquals(AdbCommand.OKAY, okay.command)
        assertEquals(42L, okay.arg1)
        val remoteId = okay.arg0

        pipes.host.send(AdbMessage(AdbCommand.WRTE, 42L, remoteId, "ping".toByteArray()))
        assertEquals(AdbCommand.OKAY, pipes.host.read().command)
        val echo = pipes.host.read()
        assertEquals(AdbCommand.WRTE, echo.command)
        assertEquals("ping", String(echo.payload, Charsets.UTF_8))

        val closedByDevice = pipes.host.read()
        assertEquals(AdbCommand.CLSE, closedByDevice.command)
        assertEquals(remoteId, closedByDevice.arg0)
        assertEquals(42L, closedByDevice.arg1)
        assertTrue(handlers[0].closeLatch.await(2, TimeUnit.SECONDS))
        assertTrue(handlers[0].closed)

        pipes.hostToDeviceOut.close()
        loop.join(5000)
        assertFalse(loop.isAlive)
        conn.close()
    }
}
