package com.kalvsupu.disad.dns

/**
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** 应答码 */
    const val RCODE_NXDOMAIN = 3
    const val RCODE_SERVFAIL = 2

    /** 解析结果：域名、查询类型、问题段结束的绝对偏移 */
    data class Question(val name: String, val type: Int, val end: Int)

    /**
     */
    fun parseQuestion(buf: ByteArray, off: Int, len: Int): Question? {
        if (len < 12) return null
        val end = off + len
        val qdCount = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
        if (qdCount != 1) return null

        var p = off + 12
        val sb = StringBuilder(64)
        var guard = 0
        while (p < end) {
            if (guard++ > 128) return null
            val l = buf[p].toInt() and 0xFF
            if (l == 0) {
                p++
                break
            }
            if ((l and 0xC0) == 0xC0) return null
            if (l > 63 || p + 1 + l > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(buf, p + 1, l, Charsets.US_ASCII))
            p += 1 + l
        }
        if (p + 4 > end) return null
        val type = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        p += 4
        val name = sb.toString().lowercase()
        if (name.isEmpty()) return null
        return Question(name, type, p)
    }

    /** 拦截时用：构造 NXDOMAIN（域名不存在） */
    fun buildNxDomain(buf: ByteArray, dnsOff: Int, q: Question): ByteArray? =
        buildError(buf, dnsOff, q.end - dnsOff, RCODE_NXDOMAIN)

    /**
     */
    /**
     */
    fun buildError(buf: ByteArray, dnsOff: Int, qLenIn: Int, rcode: Int): ByteArray? {
        // 钳制：既不能超出 buf，也不能小于 DNS 头
        val qLen = qLenIn.coerceIn(12, (buf.size - dnsOff).coerceAtLeast(0))
        if (qLen < 12) return null
        val out = ByteArray(qLen)
        System.arraycopy(buf, dnsOff, out, 0, qLen)

        val rd = out[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte()                       // QR=1 响应, Opcode=0, AA=0, TC=0, RD
        out[3] = (0x80 or (rcode and 0x0F)).toByte()         // RA=1, RCODE
        out[6] = 0; out[7] = 0                               // ANCOUNT = 0
        out[8] = 0; out[9] = 0                               // NSCOUNT = 0
        out[10] = 0; out[11] = 0                             // ARCOUNT = 0
        return out
    }
}