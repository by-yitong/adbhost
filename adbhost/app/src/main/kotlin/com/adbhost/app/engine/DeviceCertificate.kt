package com.adbhost.app.engine

import com.adbhost.app.core.RsaIdentity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object DeviceCertificate {
    fun loadOrCreate(dir: File, identity: RsaIdentity): X509Certificate {
        dir.mkdirs()
        val file = File(dir, CERT_FILE_NAME)
        if (file.exists()) {
            val loaded = runCatching { readCertificate(file) }.getOrNull()
            if (loaded != null && Arrays.equals(loaded.publicKey.encoded, identity.keyPair.public.encoded)) {
                return loaded
            }
        }
        val certificate = generate(identity)
        runCatching { writePem(file, certificate) }
        return certificate
    }

    fun subjectCn(certificate: X509Certificate): String =
        runCatching {
            val name = certificate.subjectX500Principal.name
            Regex("CN=([^,]+)").find(name)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: name
        }.getOrDefault(DEFAULT_CN)

    private fun generate(identity: RsaIdentity): X509Certificate {
        val pair = identity.keyPair
        val owner = X500Name(SUBJECT)
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            owner,
            java.math.BigInteger.valueOf(now),
            Date(now - DAY_MS),
            Date(now + VALIDITY_DAYS * DAY_MS),
            owner,
            pair.public,
        ).build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(pair.private))
        return CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
    }

    private fun readCertificate(file: File): X509Certificate =
        FileInputStream(file).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }

    private fun writePem(file: File, certificate: Certificate) {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
        file.writeText("-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n")
    }

    private const val CERT_FILE_NAME = "device.crt"
    private const val SUBJECT = "CN=adbhost,O=AdbKey-0"
    const val DEFAULT_CN = "adbhost"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val VALIDITY_DAYS = 3650L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
