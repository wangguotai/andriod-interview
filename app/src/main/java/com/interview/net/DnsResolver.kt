package com.interview.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 一个**够用的** DNS over UDP 解析器（纯 JDK 实现，不引第三方库）。
 *
 * ─── 为什么自己解析 DNS 报文 ───
 *
 * [InterviewDns] 需要一个可指定「解析服务器」的能力，这正是 HTTPDNS /
 * 自定义调度地址的前提；而 [InetAddress.getAllByName] 只能走
 * 系统 `net.dns1/net.dns2`，无法指定服务器、无法拿到 TTL、无法区分
 * 各 record 类型。自己实现报文层虽然啰嗦，但换来的是对整条解析链路的控制权。
 *
 * ⚠️ 与 HTTPDNS（DoH/DoT）的区别：
 *   本类走**明文 UDP 53**，和系统解析一样会被中间设备观察/劫持。
 *   它解决的是「指定服务器 + 拿 TTL + 多 IP」，**不解决防劫持**。
 *   要防劫持必须走 HTTPS（DoH）或 TLS（DoT）—— 那需要 TLS 库，
 *   本 demo 为保持「零新增依赖」不引，在 [InterviewDns] 里如实标注这个缺口。
 *
 * ─── 实现边界（如实说明，避免过度承诺）───
 *
 * · 只解析 A(1) / AAAA(28)，忽略 MX/NS/TXT —— 客户端建连只需要地址记录。
 * · 不跟随 CNAME 链式查询，但会**收集同一响应里所有 A/AAAA**：
 *   真实递归解析器通常会把 CNAME 目标记录一并放进答案段，够用。
 *   若响应只含 CNAME 而无 A，本类返回空，交由上层用系统解析兜底。
 * · 不做 DNSSEC 验证（同样是 DoH/DoT 的范畴）。
 * · 不处理截断(TC)后转 TCP —— UDP 响应超 512B 的地址记录场景罕见，
 *   罕见情况下同样由系统解析兜底。这是**有意的取舍**，不是遗漏。
 */
class DnsResolver(
    /** 上游解析服务器地址。可传运营商/HTTPDNS/自建解析的 IP:53。 */
    private val servers: List<InetSocketAddress>,
    /** 单次查询超时。弱网下不宜太长（会拖住调用线程），剩余交给多服务器重试。 */
    private val timeoutMillis: Int = 2_000,
) {

    /**
     * 查询指定记录类型。
     *
     * @param type [TYPE_A] 或 [TYPE_AAAA]
     * @return 解析到的地址列表（可能为空）；全部服务器失败时抛 IOException
     */
    @Throws(UnknownHostException::class)
    fun resolve(host: String, type: Int): List<InetAddress> {
        val query = buildQuery(host, type)
        var lastError: Exception? = null

        // 依次尝试各服务器：弱网下单一 DNS 服务器超时是最常见故障，
        // 有备用服务器时不该直接失败。
        for (server in servers) {
            try {
                val response = exchange(query, server)
                if (response == null) continue
                val addresses = parseAnswers(response)
                if (addresses.isNotEmpty()) return addresses
            } catch (e: Exception) {
                lastError = e
            }
        }
        // 全部失败：抛异常让上层兜底（系统解析）。
        throw UnknownHostException("自定义 DNS 解析失败 host=$host type=$type").apply {
            initCause(lastError)
        }
    }

    // ─────────────────────────────────────────
    // 收发包
    // ─────────────────────────────────────────

    private fun exchange(query: ByteArray, server: InetSocketAddress): ByteArray? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(query, query.size, server))

            val buffer = ByteArray(MAX_UDP_RESPONSE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet) // 超时抛 SocketTimeoutException，由调用方捕获

            val data = packet.data
            val len = packet.length
            // 校验事务 ID：UDP 上可能收到上一个请求的迟到响应，ID 不符必须丢弃，
            // 否则会把「别人的答案」当成自己的 —— DNS 缓存投毒类问题的应用层版本。
            if (len < 12 || !idMatches(query, data)) {
                Log.w(TAG, "响应事务 ID 不匹配或过短，丢弃")
                return null
            }
            // RCODE（低 4 位）非 0 = 解析器返回了错误（NXDOMAIN/SERVFAIL…）
            val rcode = data[3].toInt() and 0x0F
            if (rcode != 0) {
                Log.w(TAG, "DNS RCODE=$rcode（服务器 $server）")
                return null
            }
            return data.copyOf(len)
        }
    }

    private fun idMatches(query: ByteArray, response: ByteArray): Boolean =
        query[0] == response[0] && query[1] == response[1]

    // ─────────────────────────────────────────
    // 组包（Query）
    // ─────────────────────────────────────────

    /** internal 而非 private：报文组包/解包是纯函数，应能在 JVM 上单测（见 NetLayerTest）。 */
    internal fun buildQuery(host: String, type: Int): ByteArray {
        val labels = host.trim('.').split('.')
        val qnameSize = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + qnameSize + 4)
        var p = 0

        // ── Header ──
        out[p++] = RANDOM.nextInt(256).toByte() // ID hi
        out[p++] = RANDOM.nextInt(256).toByte() // ID lo
        out[p++] = 0x01 // flags hi: 标准查询 + 期望递归(RD)
        out[p++] = 0x00 // flags lo
        out[p++] = 0x00; out[p++] = 0x01 // QDCOUNT = 1
        out[p++] = 0x00; out[p++] = 0x00 // ANCOUNT
        out[p++] = 0x00; out[p++] = 0x00 // NSCOUNT
        out[p++] = 0x00; out[p++] = 0x00 // ARCOUNT

        // ── Question ──
        for (label in labels) {
            // 单个 label 最长 63 字节；超长的非法域名直接拒绝，避免写出畸形报文。
            require(label.length in 1..63) { "非法 DNS label: $label" }
            out[p++] = label.length.toByte()
            val bytes = label.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, out, p, bytes.size)
            p += bytes.size
        }
        out[p++] = 0x00 // 根标签
        out[p++] = (type ushr 8).toByte(); out[p++] = type.toByte()
        out[p++] = 0x00; out[p] = 0x01 // QCLASS = IN（末尾，p 不再自增）
        return out
    }

    // ─────────────────────────────────────────
    // 解包（Answer）
    // ─────────────────────────────────────────

    private fun parseAnswers(buf: ByteArray): List<InetAddress> {
        val ancount = readU16(buf, 6)
        if (ancount <= 0) return emptyList()

        // 跳过 Question 段（qname + qtype + qclass）到达 Answer 段。
        var p = skipName(buf, 12) + 4

        val result = ArrayList<InetAddress>(ancount)
        repeat(ancount) {
            if (p + 10 > buf.size) return result
            p = skipName(buf, p) // NAME（通常是压缩指针，skipName 已处理）
            val type = readU16(buf, p); p += 2
            p += 2 // CLASS
            p += 4 // TTL（本类不用，统一由上层缓存策略决定，见 InterviewDns）
            val rdLength = readU16(buf, p); p += 2
            if (p + rdLength > buf.size) return result

            when (type) {
                TYPE_A -> if (rdLength == 4) {
                    result.add(InetAddress.getByAddress(buf.copyOfRange(p, p + 4)))
                }
                TYPE_AAAA -> if (rdLength == 16) {
                    result.add(InetAddress.getByAddress(buf.copyOfRange(p, p + 16)))
                }
                // 其他类型（CNAME 等）只前进指针，不解析。
            }
            p += rdLength
        }
        return result
    }

    /** internal：与 [buildQuery] 同理，纯解析逻辑需可在 JVM 单测。 */
    internal fun parseAnswersForTest(buf: ByteArray): List<InetAddress> = parseAnswers(buf)

    /**
     * 跳过域名，返回下一个字节的位置。
     * DNS 名字有两种编码：`长度+内容` 序列，或压缩指针（高 2 位 = 11，占 2 字节）。
     * 这里只需跳过，不需要还原，所以压缩指针直接吃掉 2 字节即可。
     */
    private fun skipName(buf: ByteArray, start: Int): Int {
        var p = start
        while (p < buf.size) {
            val len = buf[p].toInt() and 0xFF
            when {
                len == 0 -> return p + 1
                len and 0xC0 == 0xC0 -> return p + 2 // 压缩指针
                else -> p += 1 + len
            }
        }
        return buf.size
    }

    private fun readU16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    companion object {
        private const val TAG = "DnsResolver"
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        private const val MAX_UDP_RESPONSE = 1500
        private val RANDOM = java.util.Random()
    }
}
