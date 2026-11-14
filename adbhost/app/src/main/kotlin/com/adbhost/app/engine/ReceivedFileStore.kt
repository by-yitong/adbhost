package com.adbhost.app.engine

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ReceivedFile(val relativePath: String, val size: Long, val receivedAt: Long)

class ReceivedFileStore(private val storeFile: File, private val syncRoot: File) {

    private val dir = storeFile.parentFile ?: File(".")

    init {
        dir.mkdirs()
        syncRoot.mkdirs()
    }

    fun list(): List<ReceivedFile> =
        readLines().mapNotNull(::parse).sortedByDescending { it.receivedAt }

    fun record(file: File, relativePath: String): ReceivedFile {
        val entry = ReceivedFile(relativePath, file.length(), System.currentTimeMillis())
        val kept = readLines().mapNotNull(::parse).filter { it.relativePath != relativePath }
        write(kept + entry)
        return entry
    }

    fun clear(): Int {
        val entries = list()
        entries.forEach { entry ->
            runCatching {
                val target = resolve(entry.relativePath)
                target?.delete()
                var parent = target?.parentFile
                while (parent != null && parent != syncRoot && parent.isDirectory && parent.list().isEmpty()) {
                    parent.delete()
                    parent = parent.parentFile
                }
            }
        }
        write(emptyList())
        return entries.size
    }

    private fun resolve(relativePath: String): File? {
        val candidate = File(syncRoot, relativePath)
        val rootPath = runCatching { syncRoot.canonicalPath }.getOrDefault(syncRoot.absolutePath)
        val resolved = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
        return if (resolved.startsWith(rootPath + File.separator)) candidate else null
    }

    private fun parse(line: String): ReceivedFile? {
        val parts = line.split('|')
        if (parts.size != 3) return null
        val receivedAt = parts[0].toLongOrNull() ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val path = runCatching { URLDecoder.decode(parts[2], "UTF-8") }.getOrNull() ?: return null
        if (path.isEmpty()) return null
        return ReceivedFile(path, size, receivedAt)
    }

    private fun readLines(): List<String> =
        if (storeFile.exists()) storeFile.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    private fun write(entries: List<ReceivedFile>) {
        val content = entries.takeLast(MAX_ENTRIES)
            .joinToString("\n") { entry ->
                "${entry.receivedAt}|${entry.size}|" +
                    URLEncoder.encode(entry.relativePath, "UTF-8")
            } + if (entries.isEmpty()) "" else "\n"
        val temp = File.createTempFile(storeFile.name, ".tmp", dir)
        temp.writeText(content)
        runCatching {
            Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            temp.delete()
            throw it
        }
    }

    companion object {
        private const val MAX_ENTRIES = 200

        fun shouldRecord(relativePath: String): Boolean =
            relativePath.isNotEmpty() && !relativePath.startsWith("tmp/")
    }
}
