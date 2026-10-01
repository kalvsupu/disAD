package com.baidukiller.lite.dns

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * ============================================================================
 *  上游 DNS 转发
 * ============================================================================
 *  没有被拦截的域名，必须帮用户正常解析，否则上网就断了。
 *
 *  两个关键点：
 *   1. **套接字必须被 VpnService.protect() 保护**，否则我们自己发出的查询
 *      会在全局模式下再次被路由进隧道，形成死循环；
 *   2. **候选服务器不止两个**：除了用户填的主/备，还会带上手机当前网络
 *      （Wi-Fi / 移动数据）自己用的 DNS。因为有些网络只放行本地 DNS，
 *      只认 223.5.5.5 会解析失败 —— 那正是"网盘打不开"的根因之一。
 *
 *  超时收得很短（1.2 秒）：与其让用户等 5 秒，不如快速失败 ——
 *  失败后我们会回一个 SERVFAIL，系统立刻切到下一个 DNS。
 * ============================================================================
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
        const val MAX_DNS_SIZE = 1500
    }

    private val servers: List<String> by lazy {
        val list = ArrayList<String>(4)
        list.add(primary)
        list.add(secondary)
        extra.forEach { if (!list.contains(it)) list.add(it) }
        list
    }

    /**
     * 依次向上游查询并返回原始应答报文；全部失败返回 null
     * （调用方会回一个 SERVFAIL，让系统换下一个 DNS）。
     */
    fun query(payload: ByteArray, length: Int): ByteArray? {
        for (server in servers) {
            queryOnce(server, payload, length)?.let { return it }
        }
        return null
    }

    private fun queryOnce(server: String, payload: ByteArray, length: Int): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(server)     // 只填 IP，避免自己再触发一次 DNS
            socket = DatagramSocket()
            protector(socket)                                // 关键：把自己排除在隧道之外
            socket.soTimeout = TIMEOUT_MS

            socket.send(DatagramPacket(payload, length, address, 53))

            val buf = ByteArray(MAX_DNS_SIZE)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            buf.copyOf(reply.length)
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
}
