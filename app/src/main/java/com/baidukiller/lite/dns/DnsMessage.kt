package com.baidukiller.lite.dns

/**
 * ============================================================================
 *  DNS 报文解析与构造
 * ============================================================================
 *  只做三件事：
 *   1. 从查询报文里取出域名（问题段 QNAME）；
 *   2. 对需要拦截的域名，就地构造 NXDOMAIN（域名不存在）应答；
 *   3. 对"我们自己也解析不了"的域名，构造 SERVFAIL，让系统立刻换下一个 DNS。
 *
 *  DNS 报文结构（RFC 1035）：
 *    +0  ID(2)  Flags(2)  QDCOUNT(2) ANCOUNT(2) NSCOUNT(2) ARCOUNT(2)   = 12 字节头
 *    +12 问题段：QNAME（长度前缀的标签序列，以 0 结尾） QTYPE(2) QCLASS(2)
 * ============================================================================
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
     * 解析问题段。
     * @return 解析失败（多问题、压缩指针、越界等）返回 null —— 调用方会改为"放行"，
     *         宁可少拦一个域名，也不能让整个 DNS 解析出错。
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
        buildError(buf, dnsOff, q, RCODE_NXDOMAIN)

    /**
     * 构造一个错误应答（只保留问题段）。
     *
     * SERVFAIL 的用途很关键：我们自己转发失败时，如果**什么都不回**，
     * 客户端只能干等超时（数秒）；回一个 SERVFAIL，系统的 DNS 解析器会
     * **立刻切换**到下一个 DNS 服务器（我们把底层网络的 DNS 也挂在了 VPN 上）。
     */
    fun buildError(buf: ByteArray, dnsOff: Int, q: Question, rcode: Int): ByteArray? {
        val qLen = q.end - dnsOff
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
