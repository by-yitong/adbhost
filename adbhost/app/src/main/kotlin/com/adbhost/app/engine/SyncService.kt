package com.adbhost.app.engine

import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.ServiceHandler
import com.adbhost.app.core.readUInt32Le
import com.adbhost.app.core.uint32LeBytes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object SyncProtocol {
    const val LSTAT = 0x4154534CL
    const val STAT = 0x54415453L
    const val LIST = 0x5453494CL
    const val SEND = 0x444E4553L
    const val RECV = 0x56434552L
    const val DENT = 0x544E4544L
    const val DONE = 0x454E4F44L
    const val DATA = 0x41544144L
    const val OKAY = 0x59414B4FL
    const val FAIL = 0x4C494146L
    const val QUIT = 0x54495551L
    const val STAT_V2 = 0x32415453L
    const val LSTAT_V2 = 0x3254534CL
    const val LIST_V2 = 0x3253494CL
    const val DENT_V2 = 0x324E4544L
}

object SyncPathMapper {
    fun toLocal(syncRoot: File, remotePath: String): File? {
        val cleaned = remotePath.trim().trimEnd('\u0000')
        if (cleaned.isEmpty() || !cleaned.startsWith("/")) return null
        val relative = when {
            cleaned == SDCARD_ROOT -> ""
            cleaned.startsWith("$SDCARD_ROOT/") -> cleaned.removePrefix("$SDCARD_ROOT/")
            cleaned == TMP_ROOT -> "tmp"
            cleaned.startsWith("$TMP_ROOT/") -> "tmp/" + cleaned.removePrefix("$TMP_ROOT/")
            else -> cleaned.removePrefix("/")
        }
        if (relative.isEmpty()) return syncRoot
        val candidate = File(syncRoot, relative)
        val rootPath = runCatching { syncRoot.canonicalPath }.getOrDefault(syncRoot.absolutePath)
        val resolved = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
        return if (resolved == rootPath || resolved.startsWith(rootPath + File.separator)) candidate else null
    }

    private const val SDCARD_ROOT = "/sdcard"
    private const val TMP_ROOT = "/data/local/tmp"
}

object SyncStatV2Codec {
    fun encode(
        id: Long,
        error: Long,
        dev: Long,
        ino: Long,
        mode: Long,
        nlink: Long,
        uid: Long,
        gid: Long,
        size: Long,
        atimeSeconds: Long,
        mtimeSeconds: Long,
        ctimeSeconds: Long,
    ): ByteArray = uint32LeBytes(id) +
        uint32LeBytes(error) +
        uint64LeBytes(dev) +
        uint64LeBytes(ino) +
        uint32LeBytes(mode) +
        uint32LeBytes(nlink) +
        uint32LeBytes(uid) +
        uint32LeBytes(gid) +
        uint64LeBytes(size) +
        int64LeBytes(atimeSeconds) +
        int64LeBytes(mtimeSeconds) +
        int64LeBytes(ctimeSeconds)

    const val ENOENT = 2L

    private fun uint64LeBytes(value: Long): ByteArray {
        val low = value and 0xFFFFFFFFL
        val high = (value ushr 32) and 0xFFFFFFFFL
        return uint32LeBytes(low) + uint32LeBytes(high)
    }

    private fun int64LeBytes(value: Long): ByteArray = uint64LeBytes(value)
}

class SyncService(
    private val syncRoot: File,
    private val conn: AdbConnection,
    private val remoteId: Long,
    private val peerLocalId: Long,
    private val log: (String) -> Unit,
    private val onFileReceived: (File, String) -> Unit = { _, _ -> },
) : ServiceHandler {

    private var pending = ByteArray(0)
    private var sendTarget: FileOutputStream? = null
    private var sendFile: File? = null
    private val closed = AtomicBoolean(false)

    override fun onPayload(data: ByteArray) {
        if (closed.get()) return
        if (pending.size + data.size > MAX_BUFFER) {
            fail("sync buffer overflow")
            closeSession()
            return
        }
        pending += data
        process()
    }

    override fun onClosed() {
        close()
    }

    override fun close() {
        closed.set(true)
        closeSendTarget()
    }

    private fun process() {
        while (!closed.get()) {
            if (pending.size < REQUEST_HEADER) return
            val id = readUInt32Le(pending, 0)
            val arg = readUInt32Le(pending, 4)
            val bodyLength = when (id) {
                SyncProtocol.SEND,
                SyncProtocol.RECV,
                SyncProtocol.LSTAT,
                SyncProtocol.STAT,
                SyncProtocol.LIST,
                SyncProtocol.STAT_V2,
                SyncProtocol.LSTAT_V2,
                SyncProtocol.LIST_V2,
                SyncProtocol.DATA,
                SyncProtocol.FAIL,
                SyncProtocol.OKAY,
                SyncProtocol.DENT,
                SyncProtocol.DENT_V2,
                -> arg
                SyncProtocol.DONE, SyncProtocol.QUIT -> 0L
                else -> {
                    fail("invalid sync command")
                    closeSession()
                    return
                }
            }
            if (bodyLength > MAX_BODY) {
                fail("sync body too large")
                closeSession()
                return
            }
            val total = REQUEST_HEADER + bodyLength
            if (pending.size < total) return
            val body = pending.copyOfRange(REQUEST_HEADER, total.toInt())
            pending = pending.copyOfRange(total.toInt(), pending.size)
            if (!dispatch(id, arg, body)) {
                closeSession()
                return
            }
        }
    }

    private fun dispatch(id: Long, arg: Long, body: ByteArray): Boolean = when (id) {
        SyncProtocol.SEND -> beginSend(pathOf(body))
        SyncProtocol.DATA -> writeSendChunk(body)
        SyncProtocol.DONE -> finishSend(arg)
        SyncProtocol.RECV -> doRecv(pathOf(body))
        SyncProtocol.LSTAT, SyncProtocol.STAT -> doStatV1(SyncProtocol.STAT, pathOf(body))
        SyncProtocol.LSTAT_V2, SyncProtocol.STAT_V2 -> doStatV2(id, pathOf(body))
        SyncProtocol.LIST -> doListV1(pathOf(body))
        SyncProtocol.LIST_V2 -> doListV2(pathOf(body))
        SyncProtocol.QUIT -> false
        SyncProtocol.FAIL, SyncProtocol.OKAY, SyncProtocol.DENT, SyncProtocol.DENT_V2 -> true
        else -> true
    }

    private fun pathOf(body: ByteArray): String = body.toString(Charsets.UTF_8).trimEnd('\u0000')

    private fun resolve(remotePath: String): File? =
        SyncPathMapper.toLocal(syncRoot, remotePath)

    private fun beginSend(remotePathWithMode: String): Boolean {
        val lastComma = remotePathWithMode.lastIndexOf(',')
        val remotePath = if (lastComma > 0 && remotePathWithMode.drop(lastComma + 1).all { it.isDigit() }) {
            remotePathWithMode.take(lastComma)
        } else {
            remotePathWithMode
        }
        val file = resolve(remotePath) ?: return fail("path rejected: $remotePath")
        return runCatching {
            file.parentFile?.mkdirs()
            sendTarget = FileOutputStream(file, false)
            sendFile = file
            true
        }.getOrElse {
            sendTarget = null
            sendFile = null
            fail("cannot open ${file.path}: ${it.message}")
        }
    }

    private fun writeSendChunk(body: ByteArray): Boolean {
        val target = sendTarget ?: return fail("DATA before SEND")
        return runCatching<Boolean> { target.write(body); true }.getOrElse { fail("write failed: ${it.message}") }
    }

    private fun finishSend(mtimeSeconds: Long): Boolean {
        val file = sendFile
        closeSendTarget()
        if (file != null && mtimeSeconds > 0) {
            runCatching { file.setLastModified(mtimeSeconds * 1000L) }
        }
        log("sync SEND 完成 ${file?.path ?: "?"}")
        if (file != null) notifyReceived(file)
        return sendSync(uint32LeBytes(SyncProtocol.OKAY) + uint32LeBytes(0L))
    }

    private fun notifyReceived(file: File) {
        val relative = runCatching { file.relativeTo(syncRoot).path }.getOrNull() ?: return
        if (!ReceivedFileStore.shouldRecord(relative)) return
        onFileReceived(file, relative)
    }

    private fun closeSendTarget() {
        runCatching { sendTarget?.close() }
        sendTarget = null
        sendFile = null
    }

    private fun doRecv(remotePath: String): Boolean {
        val file = resolve(remotePath) ?: return fail("path rejected: $remotePath")
        if (!file.exists()) return fail("stat failed: No such file or directory")
        if (file.isDirectory) return fail("recv on directory: $remotePath")
        val input = runCatching { FileInputStream(file) }.getOrElse {
            return fail("cannot open ${file.path}: ${it.message}")
        }
        input.use { stream ->
            val buffer = ByteArray(RECV_CHUNK)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                if (!sendSync(uint32LeBytes(SyncProtocol.DATA) + uint32LeBytes(n.toLong()) + buffer.copyOf(n))) return false
            }
        }
        log("sync RECV 完成 ${file.path}")
        return sendSync(uint32LeBytes(SyncProtocol.DONE) + uint32LeBytes(0L))
    }

    private fun doStatV1(replyId: Long, remotePath: String): Boolean {
        val file = resolve(remotePath)
        val exists = file?.isFile == true || file?.isDirectory == true
        val mode = if (exists) fileMode(file!!) else 0L
        val size = if (exists && file.isFile) file.length() else 0L
        val time = if (exists) file.lastModified() / 1000L else 0L
        val payload = uint32LeBytes(replyId) + uint32LeBytes(mode) + uint32LeBytes(size) + uint32LeBytes(time)
        return sendSync(payload)
    }

    private fun doStatV2(requestId: Long, remotePath: String): Boolean {
        val file = resolve(remotePath)
        val exists = file != null && (file.isFile || file.isDirectory)
        val payload = if (!exists) {
            SyncStatV2Codec.encode(requestId, SyncStatV2Codec.ENOENT, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
        } else {
            val mode = fileMode(file!!)
            val seconds = file.lastModified() / 1000L
            SyncStatV2Codec.encode(
                id = requestId,
                error = 0L,
                dev = 0L,
                ino = 0L,
                mode = mode,
                nlink = 1L,
                uid = 0L,
                gid = 0L,
                size = if (file.isFile) file.length() else 0L,
                atimeSeconds = seconds,
                mtimeSeconds = seconds,
                ctimeSeconds = seconds,
            )
        }
        return sendSync(payload)
    }

    private fun doListV1(remotePath: String): Boolean {
        val entries = listDirectory(remotePath) ?: return fail("opendir failed: $remotePath")
        entries.forEach { file ->
            val name = file.name.toByteArray(Charsets.UTF_8)
            val payload = uint32LeBytes(SyncProtocol.DENT) +
                uint32LeBytes(fileMode(file)) +
                uint32LeBytes(if (file.isFile) file.length() else 0L) +
                uint32LeBytes(file.lastModified() / 1000L) +
                uint32LeBytes(name.size.toLong()) +
                name
            if (!sendSync(payload)) return false
        }
        val terminator = uint32LeBytes(SyncProtocol.DONE) + ByteArray(16)
        return sendSync(terminator)
    }

    private fun doListV2(remotePath: String): Boolean {
        val entries = listDirectory(remotePath) ?: return fail("opendir failed: $remotePath")
        entries.forEach { file ->
            val seconds = file.lastModified() / 1000L
            val stat = SyncStatV2Codec.encode(
                id = SyncProtocol.DENT_V2,
                error = 0L,
                dev = 0L,
                ino = 0L,
                mode = fileMode(file),
                nlink = 1L,
                uid = 0L,
                gid = 0L,
                size = if (file.isFile) file.length() else 0L,
                atimeSeconds = seconds,
                mtimeSeconds = seconds,
                ctimeSeconds = seconds,
            )
            val name = file.name.toByteArray(Charsets.UTF_8)
            if (!sendSync(stat + uint32LeBytes(name.size.toLong()) + name)) return false
        }
        val terminator = uint32LeBytes(SyncProtocol.DONE) + ByteArray(72)
        return sendSync(terminator)
    }

    private fun listDirectory(remotePath: String): List<File>? {
        val dir = resolve(remotePath) ?: return null
        if (!dir.isDirectory) return null
        return dir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    private fun fileMode(file: File): Long =
        if (file.isDirectory) DIR_MODE else FILE_MODE

    private fun fail(message: String): Boolean {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return sendSync(uint32LeBytes(SyncProtocol.FAIL) + uint32LeBytes(bytes.size.toLong()) + bytes)
    }

    private fun sendSync(rawPayload: ByteArray): Boolean {
        if (closed.get()) return false
        return runCatching {
            conn.sendMessage(AdbMessage(AdbCommand.WRTE, remoteId, peerLocalId, rawPayload))
            true
        }.getOrElse { false }
    }

    private fun closeSession() {
        closed.set(true)
        closeSendTarget()
        runCatching { conn.closeService(remoteId) }
    }

    companion object {
        private const val REQUEST_HEADER = 8
        private const val MAX_BUFFER = 1 shl 20
        private const val MAX_BODY = 1 shl 20
        private const val RECV_CHUNK = 16 * 1024
        private const val DIR_MODE = 0x4000L or 0x1EDL
        private const val FILE_MODE = 0x8000L or 0x1A4L
    }
}
