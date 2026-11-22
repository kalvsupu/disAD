package com.kalvsupu.disad.net

/**
 */
object PacketUtil {

    /** RFC 1071：16 位一补数校验和 */
    fun ipv4Checksum(buf: ByteArray, offset: Int, headerLen: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + headerLen
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}