package com.adbhost.app.core

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class RsaIdentity(private val keyFile: File) {

    val keyPair: KeyPair = loadOrCreate()

    fun adbPublicKeyLine(name: String): String {
        val struct = AdbPublicKeyCodec().encodeStruct(keyPair.public as RSAPublicKey)
        return Base64.getEncoder().encodeToString(struct) + " " + name
    }

    private fun loadOrCreate(): KeyPair = if (keyFile.exists()) load(keyFile.readBytes()) else generateAndStore()

    private fun load(raw: ByteArray): KeyPair {
        val isDer = raw.size > 1 && raw[0] == 0x30.toByte()
        val der = if (isDer) raw else decodePem(raw)
        val pkcs8 = if (!isDer && raw.toString(Charsets.US_ASCII).contains(PKCS1_MARKER)) pkcs1ToPkcs8(der) else der
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent))
        return KeyPair(publicKey, privateKey)
    }

    private fun generateAndStore(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        keyFile.parentFile?.mkdirs()
        keyFile.writeText("-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n")
        return pair
    }

    private fun decodePem(raw: ByteArray): ByteArray =
        Base64.getMimeDecoder().decode(
            raw.toString(Charsets.US_ASCII).lines().filterNot { it.startsWith("-----") }.joinToString(""),
        )

    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val algorithm = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
        )
        return derWrap(0x30, version + algorithm + derWrap(0x04, pkcs1))
    }

    private fun derWrap(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLengthBytes(content.size) + content

    private fun derLengthBytes(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        size < 0x10000 -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        else -> byteArrayOf(0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }

    companion object {
        private const val PKCS1_MARKER = "RSA PRIVATE KEY"
    }
}
