package com.adbhost.app.engine

import com.adbhost.app.core.AuthEngine
import com.adbhost.app.core.CertDecision
import com.adbhost.app.core.RsaIdentity
import com.adbhost.app.core.TrustStore
import com.adbhost.app.core.sha1FingerprintHex
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class TlsUpgrader(
    identity: RsaIdentity,
    private val trust: TrustStore,
    identityDir: File,
    private val authorize: (fingerprint: String, name: String) -> Pair<Boolean, Boolean>,
    private val log: (String) -> Unit,
) {

    private val auth = AuthEngine(identity, trust)
    private val certificate = DeviceCertificate.loadOrCreate(identityDir, identity)
    private val keyManagers = buildKeyManagers(identity, certificate)
    private val pendingFingerprint = AtomicReference<String?>(null)

    fun upgrade(socket: Socket): Pair<SSLSocket, String> {
        pendingFingerprint.set(null)
        val context = SSLContext.getInstance(TLS_CONTEXT)
        context.init(keyManagers, arrayOf(clientTrustManager()), SecureRandom())
        val ssl = context.socketFactory.createSocket(
            socket,
            socket.inetAddress?.hostAddress ?: "unknown",
            socket.port,
            true,
        ) as SSLSocket
        ssl.useClientMode = false
        ssl.needClientAuth = true
        restrictToTls13IfAvailable(ssl)
        ssl.startHandshake()
        val fingerprint = pendingFingerprint.get()
            ?: throw CertificateException("host did not present a certificate")
        return ssl to fingerprint
    }

    private fun restrictToTls13IfAvailable(ssl: SSLSocket) {
        if (ssl.enabledProtocols.contains(TLS_VERSION)) {
            ssl.enabledProtocols = arrayOf(TLS_VERSION)
        }
    }

    private fun clientTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            val peer = chain.firstOrNull() ?: throw CertificateException("host did not present a certificate")
            val fingerprint = sha1FingerprintHex(peer.publicKey.encoded)
            pendingFingerprint.set(fingerprint)
            when (auth.verifyHostCert(peer)) {
                CertDecision.TRUSTED -> {
                    val name = DeviceCertificate.subjectCn(peer)
                    if (name.isNotEmpty() && name != DeviceCertificate.DEFAULT_CN) {
                        trust.addCertFingerprint(fingerprint, name)
                    }
                }
                else -> {
                    val name = DeviceCertificate.subjectCn(peer)
                    val (allowed, persist) = authorize(fingerprint, name)
                    if (!allowed) {
                        throw CertificateException("host rejected fingerprint=$fingerprint")
                    }
                    if (persist) {
                        trust.addCertFingerprint(fingerprint, name)
                    }
                }
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun buildKeyManagers(identity: RsaIdentity, certificate: X509Certificate): Array<javax.net.ssl.KeyManager> {
        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE)
        keyStore.load(null, STORE_PASSWORD)
        keyStore.setKeyEntry(KEY_ALIAS, identity.keyPair.private, KEY_PASSWORD, arrayOf(certificate))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, KEY_PASSWORD)
        return factory.keyManagers
    }

    companion object {
        const val TLS_VERSION = "TLSv1.3"
        const val TLS_CONTEXT = "TLS"
        private const val KEY_STORE_TYPE = "PKCS12"
        private const val KEY_ALIAS = "adbhost"
        private val STORE_PASSWORD = "adbhost".toCharArray()
        private val KEY_PASSWORD = "adbhost".toCharArray()
    }
}
