package com.adbhost.app.engine

import com.adbhost.app.core.readUInt32Le
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatV2CodecTest {

    @Test
    fun layoutMatchesAospSyncStatV2Order() {
        val bytes = SyncStatV2Codec.encode(
            id = SyncProtocol.LSTAT_V2,
            error = 7L,
            dev = 0x1122334455667788L,
            ino = 0x0102030405060708L,
            mode = 0x81A4L,
            nlink = 3L,
            uid = 20004L,
            gid = 20004L,
            size = 0xAABBCCDDL,
            atimeSeconds = 1000L,
            mtimeSeconds = 2000L,
            ctimeSeconds = 3000L,
        )
        assertEquals(72, bytes.size)
        assertEquals(SyncProtocol.LSTAT_V2, readUInt32Le(bytes, 0))
        assertEquals(7L, readUInt32Le(bytes, 4))
        assertEquals(0x55667788L, readUInt32Le(bytes, 8))
        assertEquals(0x11223344L, readUInt32Le(bytes, 12))
        assertEquals(0x05060708L, readUInt32Le(bytes, 16))
        assertEquals(0x01020304L, readUInt32Le(bytes, 20))
        assertEquals(0x81A4L, readUInt32Le(bytes, 24))
        assertEquals(3L, readUInt32Le(bytes, 28))
        assertEquals(20004L, readUInt32Le(bytes, 32))
        assertEquals(20004L, readUInt32Le(bytes, 36))
        assertEquals(0xAABBCCDDL, readUInt32Le(bytes, 40))
        assertEquals(0L, readUInt32Le(bytes, 44))
        assertEquals(1000L, readUInt32Le(bytes, 48))
        assertEquals(0L, readUInt32Le(bytes, 52))
        assertEquals(2000L, readUInt32Le(bytes, 56))
        assertEquals(0L, readUInt32Le(bytes, 60))
        assertEquals(3000L, readUInt32Le(bytes, 64))
        assertEquals(0L, readUInt32Le(bytes, 68))
    }

    @Test
    fun enoentEncodingIsAllZeroExceptIdAndError() {
        val bytes = SyncStatV2Codec.encode(
            SyncProtocol.STAT_V2,
            SyncStatV2Codec.ENOENT,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
        )
        assertEquals(72, bytes.size)
        assertEquals(SyncProtocol.STAT_V2, readUInt32Le(bytes, 0))
        assertEquals(2L, readUInt32Le(bytes, 4))
        (8..68 step 4).forEach { offset ->
            assertEquals("offset $offset", 0L, readUInt32Le(bytes, offset))
        }
    }
}
