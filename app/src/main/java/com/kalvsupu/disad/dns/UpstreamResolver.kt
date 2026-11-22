package com.kalvsupu.disad.dns

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 上游转发。
 *
 * 关键点（都是踩过的坑）：
 *  · socket 用 **connect()** 连到具体服务器：内核只把该地址+端口的数据报交给我们，
 *    天然过滤掉其他来源的伪造包；
 *  · 校验事务 ID、QR 标志、问题段域名，任何一个对不上就丢弃；
 *  · 接收缓冲按 UDP 最大值（64KB 级）准备，避免大响应被截断；
 *  · protect() 失败必须当作失败（否则自己的查询会被自己的隧道绕回去，形成环路）。
 */
class UpstreamResolver(
    private val primary: String,
    private val secondary: String,
    private val extra: List<String>,
    private val protector: (DatagramSocket) -> Boolean
) {

    private companion object {
        const val TAG = "disAD"
        const val TIMEOUT_MS = 1200
        /** UDP 数据报理论上限 65507；这里留足余量，避免大响应被截断 */
        const val MAX_DNS_SIZE = 65_535
    }

    private val servers: List<String> by lazy {
        val list = ArrayList<String>(4)
        list.add(primary)
        list.add(secondary)
        extra.forEach { if (!list.contains(it)) list.add(it) }
        list
    }

    fun query(payload: ByteArray, length: Int): ByteArray? {
        for (server in servers) {
            queryOnce(server, payload, length)?.let { return it }
        }
        return null
    }

    private fun queryOnce(server: String, payload: ByteArray, length: Int): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            // 只填 IP，避免解析上游自己再触发一次 DNS
            val address = InetAddress.getByName(server)
            val s = DatagramSocket()
            socket = s
            if (!protector(s)) {
                Log.w(TAG, "上游 $server：protect() 失败，放弃（避免环路）")
                return null
            }
            s.soTimeout = TIMEOUT_MS
            // ★ connected socket：内核按 (地址,端口) 过滤来源，伪造包进不来
            s.connect(InetSocketAddress(address, 53))

            s.send(DatagramPacket(payload, length))

            val buf = ByteArray(MAX_DNS_SIZE)
            val reply = DatagramPacket(buf, buf.size)
            s.receive(reply)

            val got = buf.copyOf(reply.length)
            if (!matchesQuery(payload, length, got)) {
                Log.w(TAG, "上游 $server：响应与请求不匹配，丢弃")
                return null
            }
            got
        } catch (t: Throwable) {
            Log.v(TAG, "上游 $server 查询失败：${t.message}")
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }
    /** 事务 ID、QR 位、问题段域名必须与请求一致 */
    private fun matchesQuery(query: ByteArray, qLen: Int, reply: ByteArray): Boolean {
        if (reply.size < 12) return false
        // QR 必须为 1（响应）
        if ((reply[2].toInt() and 0x80) == 0) return false
        // 事务 ID 一致
        if (reply[0] != query[0] || reply[1] != query[1]) return false
        // 问题段（QDCOUNT 应 >= 1）逐字节比对域名部分
        if (qLen < 12) return true
        var offset = 12
        val qEnd = minOf(qLen, query.size)
        while (offset < qEnd) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) {
                // 到根结束：再比 type+class 的 4 字节
                val tail = offset + 1
                if (tail + 4 <= qEnd && tail + 4 <= reply.size) {
                    for (i in tail until tail + 4) {
                        if (query[i] != reply[i]) return false
                    }
                }
                return true
            }
            if (len > 63 || offset + 1 + len > qEnd) return true   // 压缩指针等，不做更严校验
            offset += 1 + len
        }
        return true
    }
}
