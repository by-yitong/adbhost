package com.adbhost.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbMessageTest {

    private fun headerBytes(command: Long, arg0: Long, arg1: Long, dataLen: Long, checksum: Long, magic: Long): ByteArray {
        val header = ByteArray(AdbMessage.headerSize)
        writeUInt32Le(command, header, 0)
        writeUInt32Le(arg0, header, 4)
        writeUInt32Le(arg1, header, 8)
        writeUInt32Le(dataLen, header, 12)
        writeUInt32Le(checksum, header, 16)
        writeUInt32Le(magic, header, 20)
        return header
    }

    private fun encode(message: AdbMessage): ByteArray = ByteArrayOutputStream().also { message.encode(it) }.toByteArray()

    @Test
    fun encodeThenReadRoundTrips() {
        val payload = "host::features=shell_v2,stat_v2".toByteArray()
        val message = AdbMessage(AdbCommand.CNXN, 0x01000001L, 262144L, payload)
        val bytes = encode(message)
        assertEquals(AdbMessage.headerSize + payload.size, bytes.size)
        val decoded = AdbMessage.read(ByteArrayInputStream(bytes), AdbMessage.MAX_PAYLOAD)
        assertEquals(message, decoded)
        assertEquals(0x01000001L, decoded!!.arg0)
        assertEquals(262144L, decoded.arg1)
        assertEquals("host::features=shell_v2,stat_v2", String(decoded.payload, Charsets.UTF_8))
    }

    @Test
    fun checksumAndMagicMatchProtocolConstants() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val message = AdbMessage(AdbCommand.CNXN, 0L, 0L, payload)
        assertEquals(10L, message.checksum())
        assertEquals(0xB1A7B1BCL, message.magic())
        val zero = AdbMessage(AdbCommand.OKAY, 1L, 2L, ByteArray(0))
        assertEquals(0L, zero.checksum())
        assertEquals(AdbCommand.OKAY, 0x59414B4FL)
        assertEquals(AdbCommand.AUTH, 0x48555441L)
        assertEquals(AdbCommand.OPEN, 0x4E45504FL)
        assertEquals(AdbCommand.CLSE, 0x45534C43L)
        assertEquals(AdbCommand.WRTE, 0x45545257L)
        assertEquals(AdbCommand.STLS, 0x534C5453L)
    }

    @Test
    fun readRejectsPayloadLargerThanMaxPayload() {
        val header = headerBytes(AdbCommand.WRTE, 1, 2, 100, 0, AdbCommand.WRTE xor 0xFFFFFFFFL)
        assertNull(AdbMessage.read(ByteArrayInputStream(header), 50))
    }

    @Test
    fun readRejectsBadMagic() {
        val bytes = encode(AdbMessage(AdbCommand.OKAY, 1, 2, "x".toByteArray()))
        bytes[20] = (bytes[20].toInt() xor 0x55).toByte()
        assertNull(AdbMessage.read(ByteArrayInputStream(bytes), AdbMessage.MAX_PAYLOAD))
    }

    @Test
    fun readAcceptsAnyChecksum() {
        val bytes = encode(AdbMessage(AdbCommand.WRTE, 1, 2, "hello".toByteArray()))
        bytes[16] = 0
        bytes[17] = 0
        bytes[18] = 0
        bytes[19] = 0
        val message = AdbMessage.read(ByteArrayInputStream(bytes), AdbMessage.MAX_PAYLOAD)
        assertEquals("hello", message?.payload?.toString(Charsets.UTF_8))
    }

    @Test
    fun readRejectsOversizedDeclaredPayloadAgainstAbsoluteMax() {
        val header = headerBytes(AdbCommand.WRTE, 1, 2, AdbMessage.MAX_PAYLOAD.toLong() + 1, 0, AdbCommand.WRTE xor 0xFFFFFFFFL)
        assertNull(AdbMessage.read(ByteArrayInputStream(header), AdbMessage.MAX_PAYLOAD))
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        try {
            encode(AdbMessage(AdbCommand.WRTE, 1, 2, ByteArray(AdbMessage.MAX_PAYLOAD + 1)))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun readReturnsNullOnEof() {
        assertNull(AdbMessage.read(ByteArrayInputStream(ByteArray(0)), AdbMessage.MAX_PAYLOAD))
        assertNull(AdbMessage.read(ByteArrayInputStream(ByteArray(10)), AdbMessage.MAX_PAYLOAD))
    }
}
