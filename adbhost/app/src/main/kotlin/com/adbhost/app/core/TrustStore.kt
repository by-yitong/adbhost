package com.adbhost.app.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.interfaces.RSAPublicKey

data class TrustEntry(val kind: String, val fingerprint: String, val name: String, val addedAt: Long)

class TrustStore(private val dir: File) {

    private val codec = AdbPublicKeyCodec()
    private val adbKeysFile = File(dir, "adb_keys")
    private val certFile = File(dir, "cert_fps.txt")

    init {
        dir.mkdirs()
    }

    fun trustedAdbPublicKeys(): List<RSAPublicKey> =
        readLines(adbKeysFile).mapNotNull { line ->
            runCatching { codec.decodeFromAdbFormat(line.toByteArray()) }.getOrNull()
        }

    fun trustedCertFingerprints(): Set<String> =
        readLines(certFile).map { it.substringBefore('|') }.toSet()

    fun addAdbPublicKey(line: String) {
        val normalized = line.trim().trimEnd('\u0000')
        if (normalized.isEmpty()) return
        val fingerprint = runCatching { codec.fingerprintOfAdbPayload(normalized.toByteArray()) }.getOrNull() ?: return
        val existing = readLines(adbKeysFile)
        if (existing.any { runCatching { codec.fingerprintOfAdbPayload(it.toByteArray()) }.getOrNull() == fingerprint }) return
        atomicWrite(adbKeysFile, (existing + normalized).joinToString("\n") + "\n")
    }

    fun addCertFingerprint(fp: String, name: String = "") {
        val normalized = fp.trim().uppercase()
        if (normalized.isEmpty()) return
        val safeName = name.trim().replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
        val existing = readLines(certFile)
        val index = existing.indexOfFirst { it.substringBefore('|').uppercase() == normalized }
        if (index >= 0) {
            val parts = existing[index].split('|')
            val currentName = parts.getOrElse(1) { "" }
            val timestamp = parts.getOrElse(2) { "" }.toLongOrNull() ?: System.currentTimeMillis()
            if (currentName == safeName) return
            val updated = existing.mapIndexed { i, line ->
                if (i == index) "$normalized|$safeName|$timestamp" else line
            }
            atomicWrite(certFile, updated.joinToString("\n") + "\n")
            return
        }
        atomicWrite(certFile, (existing + "$normalized|$safeName|${System.currentTimeMillis()}").joinToString("\n") + "\n")
    }

    fun removeAdbPublicKey(fp: String): Boolean {
        val target = fp.trim().uppercase()
        val existing = readLines(adbKeysFile)
        val kept = existing.filter {
            runCatching { codec.fingerprintOfAdbPayload(it.toByteArray()) }.getOrNull()?.uppercase() != target
        }
        if (kept.size == existing.size) return false
        atomicWrite(adbKeysFile, if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        return true
    }

    fun removeCertFingerprint(fp: String): Boolean {
        val target = fp.trim().uppercase()
        val existing = readLines(certFile)
        val kept = existing.filter { it.substringBefore('|').uppercase() != target }
        if (kept.size == existing.size) return false
        atomicWrite(certFile, if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        return true
    }

    fun clearAll() {
        atomicWrite(adbKeysFile, "")
        atomicWrite(certFile, "")
    }

    fun listEntries(): List<TrustEntry> {
        val adbEntries = readLines(adbKeysFile).mapNotNull { line ->
            val fingerprint = runCatching { codec.fingerprintOfAdbPayload(line.toByteArray()) }.getOrNull()
                ?: return@mapNotNull null
            TrustEntry(KIND_ADB, fingerprint, line.substringAfter(' ', ""), adbKeysFile.lastModified())
        }
        val certEntries = readLines(certFile).map { line ->
            val parts = line.split('|')
            TrustEntry(
                KIND_CERT,
                parts.getOrElse(0) { "" },
                parts.getOrElse(1) { "" },
                parts.getOrElse(2) { "" }.toLongOrNull() ?: 0L,
            )
        }
        return adbEntries + certEntries
    }

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines().map { it.trim().trimEnd('\u0000') }.filter { it.isNotEmpty() } else emptyList()

    private fun atomicWrite(target: File, content: String) {
        val temp = File.createTempFile(target.name, ".tmp", dir)
        temp.writeText(content)
        runCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            temp.delete()
            throw it
        }
    }

    companion object {
        const val KIND_ADB = "adb"
        const val KIND_CERT = "cert"
    }
}
