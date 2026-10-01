package com.baidukiller.lite.net

/**
 * ============================================================================
 *  IP 报文工具
 * ============================================================================
 *  说明：我们在隧道里手工构造 IPv4 + UDP 报文来应答 DNS 查询。
 *  UDP 校验和按 RFC 768 允许填 0（表示"发送方未计算校验和"），
 *  这样只需正确计算 IPv4 首部校验和，少一处出错的机会。
 * ============================================================================
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
