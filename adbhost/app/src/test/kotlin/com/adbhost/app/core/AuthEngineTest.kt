package com.adbhost.app.core

import java.math.BigInteger
import java.security.SecureRandom
import java.security.Signature
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seededEngine(dir: java.io.File): AuthEngine {
        val store = TrustStore(dir)
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        return AuthEngine(RsaIdentity(TestKeys.privateKeyFile(dir)), store)
    }

    private fun sign(identity: RsaIdentity, token: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(identity.keyPair.private)
        signer.update(token)
        return signer.sign()
    }

    @Test
    fun validSignatureVerifies() {
        val engine = seededEngine(tmp.newFolder())
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        assertTrue(engine.verifySignature(token, sign(engine.identity, token)))
    }

    @Test
    fun corruptedSignatureFails() {
        val engine = seededEngine(tmp.newFolder())
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val signature = sign(engine.identity, token)
        signature[7] = (signature[7].toInt() xor 0x01).toByte()
        assertFalse(engine.verifySignature(token, signature))
    }

    @Test
    fun untrustedKeyFails() {
        val dir = tmp.newFolder()
        val engine = AuthEngine(RsaIdentity(TestKeys.privateKeyFile(dir)), TrustStore(dir))
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        assertFalse(engine.verifySignature(token, sign(engine.identity, token)))
    }

    @Test
    fun tamperedTokenFails() {
        val engine = seededEngine(tmp.newFolder())
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val signature = sign(engine.identity, token)
        assertFalse(engine.verifySignature(ByteArray(20), signature))
    }

    @Test
    fun hostCertDecisionFollowsTrustStore() {
        val dir = tmp.newFolder()
        val identity = RsaIdentity(TestKeys.privateKeyFile(dir))
        val store = TrustStore(dir)
        val engine = AuthEngine(identity, store)
        val cert = JcaX509CertificateConverter().getCertificate(
            X509v3CertificateBuilder(
                X500Name("CN=testhost"),
                BigInteger.ONE,
                Date(),
                Date(System.currentTimeMillis() + 60_000L),
                X500Name("CN=testhost"),
                SubjectPublicKeyInfo.getInstance(identity.keyPair.public.encoded),
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(identity.keyPair.private)),
        )
        assertEquals(CertDecision.NEW, engine.verifyHostCert(cert))
        store.addCertFingerprint(sha1FingerprintHex(cert.publicKey.encoded))
        assertEquals(CertDecision.TRUSTED, engine.verifyHostCert(cert))
    }
}
