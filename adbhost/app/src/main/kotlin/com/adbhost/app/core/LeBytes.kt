package com.adbhost.app.core

import java.math.BigInteger

internal fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

internal fun writeUInt32Le(value: Long, target: ByteArray, offset: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    target[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

internal fun uint32LeBytes(value: Long): ByteArray = ByteArray(4).also { writeUInt32Le(value, it, 0) }

internal fun bigIntegerFromLittleEndian(bytes: ByteArray): BigInteger {
    val reversed = ByteArray(bytes.size)
    for (index in bytes.indices) reversed[index] = bytes[bytes.size - 1 - index]
    return BigInteger(1, reversed)
}

internal fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
    val magnitude = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    require(magnitude.size <= size) { "value does not fit $size bytes" }
    val result = ByteArray(size)
    for (index in magnitude.indices) result[index] = magnitude[magnitude.size - 1 - index]
    return result
}
