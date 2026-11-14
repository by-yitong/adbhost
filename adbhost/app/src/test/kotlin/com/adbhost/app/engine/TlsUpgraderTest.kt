package com.adbhost.app.engine

import com.adbhost.app.core.RsaIdentity
import com.adbhost.app.core.TrustStore
import com.adbhost.app.core.sha1FingerprintHex
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsUpgraderTest {

    private val pool: ExecutorService = Executors.newFixedThreadPool(2)

    @Test
    fun certificateRoundTripAndSubject() {
        val dir = Files.createTempDirectory("adbhost-cert").toFile()
        val identity = RsaIdentity(File(dir, "adbkey"))
        val certificate = DeviceCertificate.loadOrCreate(dir, identity)
        assertTrue(certificate.encoded.isNotEmpty())
        assertEquals("adbhost", DeviceCertificate.subjectCn(certificate))
        assertTrue(certificate.subjectX500Principal.name.contains("AdbKey-0"))
        val reloaded = DeviceCertificate.loadOrCreate(dir, identity)
        assertEquals(certificate.publicKey.encoded.toList(), reloaded.publicKey.encoded.toList())
    }

    @Test
    fun authorizedHostCompletesHandshakeAndFingerprintCaptured() {
        val dirs = Workspace()
        val serverSocket = ServerSocket(0)
        val clientRaw = Socket("127.0.0.1", serverSocket.localPort)
        val serverRaw = serverSocket.accept()
        val upgradeResult: Future<Pair<SSLSocket, String>> = pool.submit(
            Callable {
                dirs.upgraderRef.upgrade(serverRaw)
            },
        )
        val clientSsl = dirs.clientSocket(clientRaw)
        clientSsl.soTimeout = 15000
        clientSsl.startHandshake()
        val (serverSsl, fingerprint) = upgradeResult.get(15, TimeUnit.SECONDS)
        assertEquals(
            sha1FingerprintHex(dirs.clientCertificate.publicKey.encoded),
            fingerprint,
        )
        clientSsl.close()
        serverSsl.close()
        serverRaw.close()
        clientRaw.close()
        serverSocket.close()
    }

    @Test
    fun rejectedHostFailsHandshake() {
        val dirs = Workspace()
        val serverSocket = ServerSocket(0)
        val clientRaw = Socket("127.0.0.1", serverSocket.localPort)
        val serverRaw = serverSocket.accept()
        val upgradeResult: Future<Pair<SSLSocket, String>> = pool.submit(
            Callable {
                dirs.upgrader { _, _ -> false to false }.upgrade(serverRaw)
            },
        )
        var clientFailed = false
        val clientSsl = dirs.clientSocket(clientRaw)
        clientSsl.soTimeout = 15000
        try {
            clientSsl.startHandshake()
        } catch (expected: Exception) {
            clientFailed = true
        }
        var serverFailed = false
        try {
            upgradeResult.get(15, TimeUnit.SECONDS)
        } catch (expected: Exception) {
            serverFailed = true
        }
        assertTrue("client handshake should fail", clientFailed || serverFailed)
        assertTrue("server upgrade should fail", serverFailed || clientFailed)
        runCatching { clientSsl.close() }
        runCatching { serverRaw.close() }
        runCatching { clientRaw.close() }
        serverSocket.close()
    }

    private class Workspace {
        val root: File = Files.createTempDirectory("adbhost-tls").toFile()
        val serverIdentity = RsaIdentity(File(root, "server_key"))
        val clientIdentity = RsaIdentity(File(root, "client_key"))
        val clientCertificate = DeviceCertificate.loadOrCreate(File(root, "client"), clientIdentity)
        val upgraderRef = TlsUpgrader(
            serverIdentity,
            TrustStore(File(root, "trust")),
            File(root, "server"),
            { _, _ -> true to true },
            {},
        )

        fun upgrader(decide: (String, String) -> Pair<Boolean, Boolean>): TlsUpgrader = TlsUpgrader(
            serverIdentity,
            TrustStore(File(root, "trust_${decide.hashCode()}")),
            File(root, "server"),
            decide,
            {},
        )

        fun clientSocket(raw: Socket): SSLSocket {
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, "client".toCharArray())
            keyStore.setKeyEntry("client", clientIdentity.keyPair.private, "client".toCharArray(), arrayOf(clientCertificate))
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "client".toCharArray())
            val context = SSLContext.getInstance("TLS")
            context.init(keyManagerFactory.keyManagers, arrayOf(TrustAll), null)
            val ssl = context.socketFactory.createSocket(
                raw,
                raw.inetAddress.hostAddress,
                raw.port,
                true,
            ) as SSLSocket
            ssl.useClientMode = true
            if (ssl.enabledProtocols.contains("TLSv1.3")) {
                ssl.enabledProtocols = arrayOf("TLSv1.3")
            }
            return ssl
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
