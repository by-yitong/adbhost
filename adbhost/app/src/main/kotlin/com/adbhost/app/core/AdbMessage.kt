package com.adbhost.app.core

import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

object AdbCommand {
    const val CNXN = 0x4E584E43L
    const val AUTH = 0x48555441L
    const val STLS = 0x534C5453L
    const val OPEN = 0x4E45504FL
    const val OKAY = 0x59414B4FL
    const val CLSE = 0x45534C43L
    const val WRTE = 0x45545257L
}

object AdbAuth {
    const val TOKEN = 1L
    const val SIGNATURE = 2L
    const val RSAPUBLICKEY = 3L
}

object ShellProtocol {
    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val CLOSE = 4
}

data class AdbMessage(val command: Long, val arg0: Long, val arg1: Long, val payload: ByteArray) {

    fun checksum(): Long {
        var sum = 0L
        for (byte in payload) sum += byte.toLong() and 0xFF
        return sum and 0xFFFFFFFFL
    }

    fun magic(): Long = command xor 0xFFFFFFFFL

    fun encode(out: OutputStream) {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} exceeds MAX_PAYLOAD $MAX_PAYLOAD" }
        val header = ByteArray(headerSize)
        writeUInt32Le(command, header, 0)
        writeUInt32Le(arg0, header, 4)
        writeUInt32Le(arg1, header, 8)
        writeUInt32Le(payload.size.toLong(), header, 12)
        writeUInt32Le(checksum(), header, 16)
        writeUInt32Le(magic(), header, 20)
        out.write(header)
        out.write(payload)
    }

    override fun equals(other: Any?): Boolean =
        other is AdbMessage &&
            command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            Arrays.equals(payload, other.payload)

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + arg0.hashCode()
        result = 31 * result + arg1.hashCode()
        result = 31 * result + Arrays.hashCode(payload)
        return result
    }

    override fun toString(): String =
        "AdbMessage(command=0x%08X, arg0=%d, arg1=%d, dataLen=%d)".format(command, arg0, arg1, payload.size)

    companion object {
        const val MAX_PAYLOAD = 256 * 1024
        const val headerSize = 24

        fun read(input: InputStream, maxPayload: Int): AdbMessage? {
            val header = ByteArray(headerSize)
            if (!readFully(input, header)) return null
            val command = readUInt32Le(header, 0)
            val arg0 = readUInt32Le(header, 4)
            val arg1 = readUInt32Le(header, 8)
            val dataLen = readUInt32Le(header, 12)
            val magic = readUInt32Le(header, 20)
            if (magic != command xor 0xFFFFFFFFL) return null
            if (dataLen > maxPayload.toLong()) return null
            val payload = ByteArray(dataLen.toInt())
            if (!readFully(input, payload)) return null
            return AdbMessage(command, arg0, arg1, payload)
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) return false
                offset += read
            }
            return true
        }
    }
}
