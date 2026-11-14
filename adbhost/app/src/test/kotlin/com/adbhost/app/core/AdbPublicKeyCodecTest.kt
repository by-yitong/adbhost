package com.adbhost.app.core

import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbPublicKeyCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val codec = AdbPublicKeyCodec()

    @Test
    fun decodeEncodeRoundTripsResourceKey() {
        val line = TestKeys.publicKeyLine
        val base64 = line.substringBefore(' ')
        val name = line.substringAfter(' ')
        val publicKey = codec.decodeFromAdbFormat(line.toByteArray())
        assertEquals(2048, publicKey.modulus.bitLength())
        val reencoded = codec.encodeToAdbFormat(publicKey, name)
        assertEquals(524 + 1 + name.toByteArray().size + 1, reencoded.size)
        assertEquals(base64, Base64.getEncoder().encodeToString(reencoded.copyOfRange(0, 524)))
        assertEquals(0, reencoded[reencoded.size - 1].toInt())
        assertEquals(' '.code.toByte(), reencoded[524])
        assertEquals(name, String(reencoded, 525, reencoded.size - 526, Charsets.UTF_8))
    }

    @Test
    fun fingerprintIsColonSeparatedUppercaseHex() {
        val publicKey = codec.decodeFromAdbFormat(TestKeys.publicKeyLine.toByteArray())
        val fingerprint = codec.fingerprintSha1(publicKey)
        assertTrue(fingerprint.matches(Regex("^([0-9A-F]{2}:){19}[0-9A-F]{2}$")))
        assertEquals(fingerprint, codec.fingerprintOfAdbPayload(TestKeys.publicKeyLine.toByteArray()))
        assertEquals(fingerprint, codec.fingerprintOfAdbPayload((TestKeys.publicKeyLine + "\u0000").toByteArray()))
    }

    @Test
    fun decodedKeyVerifiesSignatureFromResourcePrivateKey() {
        val publicKey = codec.decodeFromAdbFormat(TestKeys.publicKeyLine.toByteArray())
        val identity = RsaIdentity(TestKeys.privateKeyFile(tmp.newFolder()))
        assertEquals(publicKey.modulus, (identity.keyPair.public as RSAPublicKey).modulus)
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(identity.keyPair.private)
        signer.update(token)
        val signature = signer.sign()
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKey)
        verifier.update(token)
        assertTrue(verifier.verify(signature))
    }
}
