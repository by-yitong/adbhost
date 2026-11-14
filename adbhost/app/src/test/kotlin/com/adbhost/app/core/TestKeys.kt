package com.adbhost.app.core

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

object TestKeys {

    val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    val publicKeyLine: String by lazy {
        val struct = AdbPublicKeyCodec().encodeStruct(keyPair.public as RSAPublicKey)
        Base64.getEncoder().encodeToString(struct) + " tester@adbhost"
    }

    fun privateKeyFile(dir: File): File {
        val file = File(dir, "adbkey")
        file.writeBytes(keyPair.private.encoded)
        return file
    }
}
