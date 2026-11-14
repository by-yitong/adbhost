package com.adbhost.app.engine

import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.ServiceHandler
import com.adbhost.app.core.ShellProtocol
import com.adbhost.app.core.readUInt32Le
import com.adbhost.app.core.uint32LeBytes
import com.adbhost.app.core.writeUInt32Le
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ShellRequest(val version: Int, val pty: Boolean, val raw: Boolean, val command: String?)

object ShellRequestParser {
    fun parse(service: String): ShellRequest? {
        if (service != SHELL_PREFIX &&
            !service.startsWith("$SHELL_PREFIX,") &&
            !service.startsWith("$SHELL_PREFIX:")
        ) {
            return null
        }
        var version = VERSION_V1
        var pty = false
        var raw = false
        val body = service.removePrefix(SHELL_PREFIX)
        val delimiter = body.indexOf(':')
        val command: String?
        val modifierArgs = if (delimiter >= 0) {
            command = body.substring(delimiter + 1).ifEmpty { null }
            body.substring(0, delimiter)
        } else {
            command = null
            body
        }
        for (arg in modifierArgs.split(',')) {
            when (arg) {
                "raw" -> raw = true
                "pty" -> pty = true
                "v1" -> version = VERSION_V1
                "v2" -> version = VERSION_V2
                else -> Unit
            }
        }
        return ShellRequest(version, pty, raw, command)
    }

    const val SHELL_PREFIX = "shell"
    const val VERSION_V1 = 1
    const val VERSION_V2 = 2
}

data class ShellV2Frame(val streamId: Int, val payload: ByteArray)

object ShellV2Encoder {
    fun encode(streamId: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(FRAME_HEADER_SIZE + payload.size)
        frame[0] = streamId.toByte()
        writeUInt32Le(payload.size.toLong(), frame, 1)
        payload.copyInto(frame, FRAME_HEADER_SIZE)
        return frame
    }

    fun exit(code: Int): ByteArray = encode(ShellProtocol.EXIT, uint32LeBytes(code.toLong() and 0xFFFFFFFFL))

    private const val FRAME_HEADER_SIZE = 5
}

class ShellV2Framer {
    private var pending = ByteArray(0)

    fun feed(data: ByteArray): List<ShellV2Frame> {
        pending += data
        val frames = ArrayList<ShellV2Frame>()
        while (pending.size >= FRAME_HEADER_SIZE) {
            val length = readUInt32Le(pending, 1)
            if (length > MAX_FRAME_PAYLOAD) {
                pending = ByteArray(0)
                break
            }
            if (pending.size < FRAME_HEADER_SIZE + length) break
            val streamId = pending[0].toInt() and 0xFF
            val payload = pending.copyOfRange(FRAME_HEADER_SIZE, FRAME_HEADER_SIZE + length.toInt())
            frames.add(ShellV2Frame(streamId, payload))
            pending = pending.copyOfRange(FRAME_HEADER_SIZE + length.toInt(), pending.size)
        }
        return frames
    }

    companion object {
        private const val FRAME_HEADER_SIZE = 5
        private const val MAX_FRAME_PAYLOAD = 1L shl 20
    }
}

object RootChecker {
    private val suCandidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su")

    @Volatile
    private var cached: Boolean? = null

    fun isSuAvailable(): Boolean = cached ?: detectSu().also { cached = it }

    fun suPath(): String? = suCandidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }

    private fun detectSu(): Boolean {
        val binary = suPath() ?: return false
        val probe = runCatching {
            ProcessBuilder(binary, "-c", "id").redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        return try {
            val output = String(probe.inputStream.readBytes(), Charsets.UTF_8)
            runCatching { probe.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false) &&
                probe.exitValue() == 0 &&
                output.contains("uid=")
        } finally {
            probe.destroy()
        }
    }
}

class ShellService(
    request: ShellRequest,
    rootMode: Boolean,
    private val conn: AdbConnection,
    private val remoteId: Long,
    private val peerLocalId: Long,
    private val log: (String) -> Unit,
) : ServiceHandler {

    private val version = request.version
    private val v2 = version == ShellRequestParser.VERSION_V2
    private val framer = ShellV2Framer()
    private val closed = AtomicBoolean(false)
    private val pumps = ArrayList<Thread>()
    private var process: Process? = null

    init {
        startProcess(request, rootMode)
    }

    private fun startProcess(request: ShellRequest, rootMode: Boolean) {
        val su = rootMode && RootChecker.isSuAvailable()
        val command = request.command?.trim()
        val sh = "/system/bin/sh"
        val argv = when {
            command.isNullOrEmpty() && su -> listOf(RootChecker.suPath() ?: "su", "-c", sh)
            command.isNullOrEmpty() -> listOf(sh)
            su -> listOf(RootChecker.suPath() ?: "su", "-c", "$sh -c ${'$'}command")
            else -> listOf(sh, "-c", command)
        }
        val proc = runCatching { ProcessBuilder(argv).start() }.getOrElse {
            log("shell 启动失败:$it")
            failClosed()
            return
        }
        process = proc
        pumps += spawn("shell-stdout-$remoteId") { pump(proc.inputStream, ShellProtocol.STDOUT) }.apply { start() }
        pumps += spawn("shell-stderr-$remoteId") { pump(proc.errorStream, ShellProtocol.STDERR) }.apply { start() }
        spawn("shell-exit-$remoteId") {
            val exit = runCatching { proc.waitFor() }.getOrDefault(-1)
            pumps.forEach { it.join(2000) }
            if (closed.get()) return@spawn
            if (v2) write(ShellV2Encoder.exit(exit))
            runCatching { conn.closeService(remoteId) }
        }.start()
        log("shell 启动 argv=$argv v2=$v2 remoteId=$remoteId")
    }

    private fun spawn(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true }

    private fun pump(stream: InputStream, streamId: Int) {
        val buffer = ByteArray(READ_CHUNK)
        while (!closed.get()) {
            val n = runCatching { stream.read(buffer) }.getOrDefault(-1)
            if (n <= 0) return
            val chunk = buffer.copyOf(n)
            if (v2) write(ShellV2Encoder.encode(streamId, chunk)) else write(chunk)
        }
    }

    private fun write(bytes: ByteArray) {
        if (closed.get()) return
        runCatching { conn.sendMessage(AdbMessage(AdbCommand.WRTE, remoteId, peerLocalId, bytes)) }
            .onFailure { closed.set(true) }
    }

    private fun failClosed() {
        closed.set(true)
        runCatching { conn.closeService(remoteId) }
    }

    override fun onPayload(data: ByteArray) {
        if (closed.get()) return
        if (!v2) {
            forwardStdin(data)
            return
        }
        framer.feed(data).forEach { frame ->
            when (frame.streamId) {
                ShellProtocol.STDIN -> forwardStdin(frame.payload)
                ShellProtocol.CLOSE -> runCatching { process?.outputStream?.close() }
            }
        }
    }

    private fun forwardStdin(data: ByteArray) {
        if (data.isEmpty()) return
        runCatching {
            process?.outputStream?.let { sink ->
                sink.write(data)
                sink.flush()
            }
        }
    }

    override fun onClosed() {
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process?.outputStream?.close() }
        process?.destroy()
    }

    companion object {
        private const val READ_CHUNK = 16 * 1024
    }
}
