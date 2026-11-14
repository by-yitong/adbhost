package com.adbhost.app.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbPublicKeyCodec {

    fun encodeToAdbFormat(publicKey: RSAPublicKey, name: String): ByteArray {
        val struct = encodeStruct(publicKey)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val result = ByteArray(struct.size + 1 + nameBytes.size + 1)
        struct.copyInto(result)
        result[struct.size] = ' '.code.toByte()
        nameBytes.copyInto(result, struct.size + 1)
        result[result.size - 1] = 0
        return result
    }

    fun decodeFromAdbFormat(b64Payload: ByteArray): RSAPublicKey {
        val struct = adbStructBytes(b64Payload)
        val words = readUInt32Le(struct, 0).toInt()
        require(words in 8..1024) { "unsupported key length $words" }
        require(struct.size >= 12 + words * 8) { "truncated android_pubkey struct" }
        val modulus = bigIntegerFromLittleEndian(struct.copyOfRange(8, 8 + words * 4))
        val exponent = readUInt32Le(struct, 8 + words * 8)
        val factory = KeyFactory.getInstance("RSA")
        return factory.generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent))) as RSAPublicKey
    }

    fun fingerprintSha1(publicKey: RSAPublicKey): String = sha1FingerprintHex(encodeStruct(publicKey))

    fun fingerprintOfAdbPayload(b64Payload: ByteArray): String = sha1FingerprintHex(adbStructBytes(b64Payload))

    internal fun encodeStruct(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        val words = modulus.bitLength() / 32
        require(words in 8..1024) { "unsupported key size ${modulus.bitLength()}" }
        require(exponent.signum() > 0 && exponent < BigInteger.ONE.shiftLeft(32)) { "unsupported exponent $exponent" }
        val radix = BigInteger.ONE.shiftLeft(32)
        val n0inv = radix.subtract(modulus.mod(radix).modInverse(radix))
        val rr = BigInteger.TWO.modPow(BigInteger.valueOf(words.toLong() * 64), modulus)
        val struct = ByteArray(12 + words * 8)
        writeUInt32Le(words.toLong(), struct, 0)
        writeUInt32Le(n0inv.toLong() and 0xFFFFFFFFL, struct, 4)
        bigIntegerToLittleEndian(modulus, words * 4).copyInto(struct, 8)
        bigIntegerToLittleEndian(rr, words * 4).copyInto(struct, 8 + words * 4)
        writeUInt32Le(exponent.toLong(), struct, 8 + words * 8)
        return struct
    }

    private fun adbStructBytes(b64Payload: ByteArray): ByteArray {
        val text = b64Payload.toString(Charsets.UTF_8).trimEnd('\u0000', '\n', '\r', ' ').trim()
        val base64 = text.substringBefore(' ')
        return Base64.getDecoder().decode(base64)
    }
}

internal fun sha1FingerprintHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { "%02X".format(it) }
