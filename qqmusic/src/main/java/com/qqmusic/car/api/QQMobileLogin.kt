package com.qqmusic.car.api

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 「QQ 音乐 App 扫码」登录：二维码由 LoginServer.CreateQRCode 生成，扫码进度由服务端经 MQTT 5（wss://mu.y.qq.com/ws/handshake）推送。
 * 对照 QQMusicApi 的 checking_mobile_qrcode / utils/mqtt.py，只实现这里用到的最小子集：
 * CONNECT（含节点重定向 0x9C/0x9D）、SUBSCRIBE、QoS 0 的 PUBLISH、PINGREQ。
 * 扫码页仍按轮询读状态：[event] 保存最近一次推送，[QQLoginApi.check] 读它。
 * 等待用 java.util.concurrent 而不是协程：CompletableDeferred / withTimeout 不在跑跑桌面共享库清单里，正式桌面会崩。
 * [start] 会阻塞等握手，只能在 IO 线程调用。
 */
internal class QQMobileLogin(private val qrcodeId: String) {
    /** 最近一次推送：scanned / canceled / timeout / loginFailed / cookies；连接断开记 closed。 */
    @Volatile var event: String? = null
        private set
    @Volatile var payload: JSONObject? = null
        private set

    private var keepAlive: ScheduledExecutorService? = null
    @Volatile private var socket: WebSocket? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var connack: CompletableFuture<Pair<Int, String?>>? = null
    @Volatile private var suback: CompletableFuture<Int>? = null

    fun start() {
        var path = "/ws/handshake"
        repeat(MAX_REDIRECTS + 1) {
            val (reason, serverReference) = connect(path)
            if (reason == 0) {
                subscribe()
                keepAlive = Executors.newSingleThreadScheduledExecutor().also { timer ->
                    timer.scheduleAtFixedRate(
                        { socket?.send(byteArrayOf(0xC0.toByte(), 0).toByteString()) },
                        KEEP_ALIVE_SECONDS.toLong(), KEEP_ALIVE_SECONDS.toLong(), TimeUnit.SECONDS,
                    )
                }
                return
            }
            socket?.close(1000, null)
            if ((reason == 0x9C || reason == 0x9D) && !serverReference.isNullOrBlank()) {
                path = redirectPath(path, serverReference)
            } else {
                throw IOException("QQ 音乐扫码服务连接失败 (0x${reason.toString(16)})")
            }
        }
        throw IOException("QQ 音乐扫码服务重定向次数过多")
    }

    fun close() {
        keepAlive?.shutdownNow()
        keepAlive = null
        socket?.let {
            it.send(byteArrayOf(0xE0.toByte(), 0).toByteString())
            it.close(1000, null)
        }
        socket = null
    }

    private fun connect(path: String): Pair<Int, String?> {
        synchronized(buffer) { buffer.reset() }
        val waiter = CompletableFuture<Pair<Int, String?>>().also { connack = it }
        val request = Request.Builder()
            .url("wss://mu.y.qq.com$path")
            .header("Sec-WebSocket-Protocol", "mqtt")
            .header("Origin", "https://y.qq.com")
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", MQTT_USER_AGENT)
            .build()
        socket = QQMusicClient.http.newWebSocket(request, listener)
        val properties = Mqtt.properties {
            string(0x15, "pass")
            userProperty("tmeAppID", "qqmusic")
            userProperty("business", "management")
            userProperty("hashTag", qrcodeId)
            userProperty("clientTag", "management.user")
            userProperty("userID", qrcodeId)
        }
        val clientId = "${System.currentTimeMillis()}${1000 + (Math.random() * 9000).toInt()}"
        val variable = Mqtt.string("MQTT") + byteArrayOf(5, 0x02, 0, KEEP_ALIVE_SECONDS.toByte()) + properties
        socket?.send(Mqtt.packet(0x10, variable + Mqtt.string(clientId)).toByteString())
        return await(waiter)
    }

    private fun subscribe() {
        val waiter = CompletableFuture<Int>().also { suback = it }
        val properties = Mqtt.properties {
            userProperty("authorization", "tmelogin")
            userProperty("pubsub", "unicast")
        }
        val body = byteArrayOf(0, 1) + properties + Mqtt.string("management.qrcode_login/$qrcodeId") + byteArrayOf(0)
        socket?.send(Mqtt.packet(0x82, body).toByteString())
        val reason = await(waiter)
        if (reason >= 0x80) throw IOException("QQ 音乐扫码订阅失败 (0x${reason.toString(16)})")
    }

    private fun <T> await(future: CompletableFuture<T>): T = try {
        future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        throw IOException("QQ 音乐扫码服务连接超时")
    } catch (e: java.util.concurrent.ExecutionException) {
        throw IOException("QQ 音乐扫码服务连接失败：${e.cause?.message.orEmpty()}")
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== socket) return
            synchronized(buffer) {
                buffer.write(bytes.toByteArray())
                while (true) {
                    val data = buffer.toByteArray()
                    val packet = Mqtt.readPacket(data) ?: break
                    buffer.reset()
                    buffer.write(data, packet.consumed, data.size - packet.consumed)
                    handle(packet.type, packet.flags, packet.body)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            connack?.completeExceptionally(t)
            suback?.completeExceptionally(t)
            if (event == null || event == "scanned") event = "closed"
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            if (event == null || event == "scanned") event = "closed"
        }
    }

    private fun handle(type: Int, flags: Int, body: ByteArray) {
        val reader = Mqtt.Reader(body)
        when (type) {
            2 -> { // CONNACK
                reader.byte()
                val reason = reader.byte()
                val props = reader.properties()
                connack?.complete(reason to props.strings[0x1C])
            }
            9 -> { // SUBACK
                reader.short()
                reader.properties()
                suback?.complete(if (reader.remaining() > 0) reader.byte() else 0)
            }
            3 -> { // PUBLISH
                reader.string()
                if ((flags shr 1) and 3 > 0) reader.short()
                val props = reader.properties()
                val text = String(reader.rest(), Charsets.UTF_8)
                payload = runCatching { JSONObject(text) }.getOrNull()
                event = props.userProperties["type"]
            }
            14 -> if (event == null || event == "scanned") event = "closed" // DISCONNECT
        }
    }

    internal companion object {
        private const val KEEP_ALIVE_SECONDS = 45
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MQTT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

        /** 同 QQMusicApi：路径末段是 host:port 时替换，否则追加。 */
        fun redirectPath(path: String, serverReference: String): String {
            val parts = path.trimEnd('/').split('/').toMutableList()
            return if (parts.isNotEmpty() && ':' in parts.last()) {
                parts[parts.lastIndex] = serverReference
                parts.joinToString("/")
            } else {
                "${path.trimEnd('/')}/$serverReference"
            }
        }
    }
}

/** MQTT 5 报文编解码（只覆盖扫码登录用到的部分）。 */
internal object Mqtt {
    class Packet(val type: Int, val flags: Int, val body: ByteArray, val consumed: Int)
    class Properties(val strings: Map<Int, String>, val userProperties: Map<String, String>)

    fun string(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    fun varInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = value
        do {
            var digit = x and 0x7F
            x = x shr 7
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    fun packet(header: Int, body: ByteArray): ByteArray = byteArrayOf(header.toByte()) + varInt(body.size) + body

    class PropertiesBuilder {
        val out = ByteArrayOutputStream()
        fun string(id: Int, value: String) {
            out.write(id)
            out.write(Mqtt.string(value))
        }
        fun userProperty(key: String, value: String) {
            out.write(0x26)
            out.write(Mqtt.string(key))
            out.write(Mqtt.string(value))
        }
    }

    fun properties(block: PropertiesBuilder.() -> Unit): ByteArray {
        val bytes = PropertiesBuilder().apply(block).out.toByteArray()
        return varInt(bytes.size) + bytes
    }

    /** 缓冲区里凑齐一个完整报文才返回；WebSocket 帧可能拆开或合并 MQTT 报文。 */
    fun readPacket(data: ByteArray): Packet? {
        if (data.size < 2) return null
        var multiplier = 1
        var length = 0
        var index = 1
        while (true) {
            if (index >= data.size) return null
            val digit = data[index++].toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
            if (index > 4) throw IOException("MQTT 报文长度非法")
        }
        if (data.size < index + length) return null
        val head = data[0].toInt() and 0xFF
        return Packet(head shr 4, head and 0x0F, data.copyOfRange(index, index + length), index + length)
    }

    class Reader(private val data: ByteArray) {
        private var pos = 0
        fun remaining() = data.size - pos
        fun byte(): Int = data[pos++].toInt() and 0xFF
        fun short(): Int = (byte() shl 8) or byte()
        fun int(): Int = (short() shl 16) or short()
        fun varInt(): Int {
            var multiplier = 1
            var value = 0
            do {
                val digit = byte()
                value += (digit and 0x7F) * multiplier
                multiplier *= 128
            } while (digit and 0x80 != 0)
            return value
        }
        fun string(): String {
            val size = short()
            return String(data, pos, size, Charsets.UTF_8).also { pos += size }
        }
        fun rest(): ByteArray = data.copyOfRange(pos, data.size).also { pos = data.size }

        /** 按 MQTT 5 属性表逐个跳过不关心的属性，只收集字符串属性和用户属性。 */
        fun properties(): Properties {
            if (remaining() == 0) return Properties(emptyMap(), emptyMap())
            val end = varInt() + pos
            val strings = mutableMapOf<Int, String>()
            val users = mutableMapOf<String, String>()
            while (pos < end) {
                when (val id = varInt()) {
                    0x01, 0x17, 0x19, 0x24, 0x25, 0x28, 0x29, 0x2A -> byte()
                    0x13, 0x21, 0x22, 0x23 -> short()
                    0x02, 0x11, 0x18, 0x27 -> int()
                    0x0B -> varInt()
                    0x03, 0x08, 0x12, 0x15, 0x1A, 0x1C, 0x1F -> strings[id] = string()
                    0x09, 0x16 -> pos += short()
                    0x26 -> users[string()] = string()
                    else -> throw IOException("未知的 MQTT 属性 0x${id.toString(16)}")
                }
            }
            return Properties(strings, users)
        }
    }
}
