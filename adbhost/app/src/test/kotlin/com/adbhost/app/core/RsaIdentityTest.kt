package com.adbhost.app.core

import java.io.File
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RsaIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun generatesAndReloadsKeyPair() {
        val file = File(tmp.newFolder(), "generated_adbkey")
        val first = RsaIdentity(file)
        assertEquals(2048, (first.keyPair.public as RSAPublicKey).modulus.bitLength())
        assertTrue(file.exists())
        assertTrue(file.readText(Charsets.US_ASCII).contains("BEGIN PRIVATE KEY"))
        val second = RsaIdentity(file)
        assertEquals(
            (first.keyPair.public as RSAPublicKey).modulus,
            (second.keyPair.public as RSAPublicKey).modulus,
        )
    }

    @Test
    fun adbPublicKeyLineDecodesBackToSameKey() {
        val identity = RsaIdentity(File(tmp.newFolder(), "adbkey"))
        val line = identity.adbPublicKeyLine("tester@laptop")
        val codec = AdbPublicKeyCodec()
        assertEquals(
            (identity.keyPair.public as RSAPublicKey).modulus,
            codec.decodeFromAdbFormat(line.toByteArray()).modulus,
        )
        assertTrue(line.endsWith(" tester@laptop"))
        val reencoded = codec.encodeToAdbFormat(identity.keyPair.public as RSAPublicKey, "tester@laptop")
        assertEquals(
            line.substringBefore(' '),
            Base64.getEncoder().encodeToString(reencoded.copyOfRange(0, 524)),
        )
    }

    @Test
    fun loadsResourcePkcs8PemKey() {
        val identity = RsaIdentity(TestKeys.privateKeyFile(tmp.newFolder()))
        val codec = AdbPublicKeyCodec()
        assertEquals(
            codec.decodeFromAdbFormat(TestKeys.publicKeyLine.toByteArray()).modulus,
            (identity.keyPair.public as RSAPublicKey).modulus,
        )
    }
}
