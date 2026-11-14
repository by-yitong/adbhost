package com.adbhost.app.engine

import com.adbhost.app.core.ShellProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellV2FramerTest {

    @Test
    fun encoderLayout() {
        val frame = ShellV2Encoder.encode(ShellProtocol.STDOUT, "hi".toByteArray())
        assertEquals(7, frame.size)
        assertEquals(ShellProtocol.STDOUT.toByte(), frame[0])
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), frame.copyOfRange(1, 5))
        assertArrayEquals("hi".toByteArray(), frame.copyOfRange(5, 7))
    }

    @Test
    fun exitFrameCarriesLittleEndianCode() {
        val frame = ShellV2Encoder.exit(0)
        assertArrayEquals(byteArrayOf(ShellProtocol.EXIT.toByte(), 4, 0, 0, 0, 0, 0, 0, 0), frame)
        val frame127 = ShellV2Encoder.exit(127)
        assertEquals(ShellProtocol.EXIT, frame127[0].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(127, 0, 0, 0), frame127.copyOfRange(5, 9))
        val frame255 = ShellV2Encoder.exit(255)
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 0, 0), frame255.copyOfRange(5, 9))
    }

    @Test
    fun roundTripSingleFrame() {
        val wire = ShellV2Encoder.encode(ShellProtocol.STDOUT, "hello".toByteArray())
        val frames = ShellV2Framer().feed(wire)
        assertEquals(1, frames.size)
        assertEquals(ShellProtocol.STDOUT, frames[0].streamId)
        assertArrayEquals("hello".toByteArray(), frames[0].payload)
    }

    @Test
    fun splitAcrossFeeds() {
        val framer = ShellV2Framer()
        val wire = ShellV2Encoder.encode(ShellProtocol.STDERR, "oops".toByteArray())
        assertTrue(framer.feed(wire.copyOfRange(0, 3)).isEmpty())
        val frames = framer.feed(wire.copyOfRange(3, wire.size))
        assertEquals(1, frames.size)
        assertEquals(ShellProtocol.STDERR, frames[0].streamId)
        assertArrayEquals("oops".toByteArray(), frames[0].payload)
    }

    @Test
    fun multipleFramesInOneFeed() {
        val framer = ShellV2Framer()
        val wire = ShellV2Encoder.encode(ShellProtocol.STDOUT, "a".toByteArray()) +
            ShellV2Encoder.encode(ShellProtocol.STDERR, "b".toByteArray()) +
            ShellV2Encoder.exit(3)
        val frames = framer.feed(wire)
        assertEquals(3, frames.size)
        assertEquals(ShellProtocol.STDOUT, frames[0].streamId)
        assertEquals(ShellProtocol.STDERR, frames[1].streamId)
        assertEquals(ShellProtocol.EXIT, frames[2].streamId)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), frames[2].payload)
    }

    @Test
    fun emptyPayloadFrame() {
        val frames = ShellV2Framer().feed(ShellV2Encoder.encode(ShellProtocol.CLOSE, ByteArray(0)))
        assertEquals(1, frames.size)
        assertEquals(ShellProtocol.CLOSE, frames[0].streamId)
        assertEquals(0, frames[0].payload.size)
    }
}
