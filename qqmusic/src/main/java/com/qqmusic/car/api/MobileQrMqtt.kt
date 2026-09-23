package com.qqmusic.car.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

internal object MobileQrMqtt {
    private const val HOST = "mu.y.qq.com"
    private const val PATH = "/ws/handshake"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun await(identifier: String): QQLoginState {
        val session = sessions.getOrPut(identifier) { Session(identifier).also(Session::connect) }
        delay(300)
        return session.state
    }

    private class Session(private val identifier: String) : WebSocketListener() {
        @Volatile var state = QQLoginState.WAITING
        private var socket: WebSocket? = null
        private var path = PATH
        private var closed = false

        fun connect() {
            if (closed) return
            val request = Request.Builder()
                .url("wss://$HOST$path")
                .header("Origin", "https://y.qq.com")
                .header("Referer", "https://y.qq.com/")
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()
            socket = QQMusicClient.http.newWebSocket(request, this)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(connectPacket(identifier).toByteString())
            scope.launch {
                while (!closed) {
                    delay(30_000)
                    socket?.send(byteArrayOf(0xC0.toByte(), 0).toByteString())
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val packet = bytes.toByteArray()
            when (packet.firstOrNull()?.toInt()?.and(0xF0)) {
                0x20 -> handleConnAck(packet)
                0x30 -> handlePublish(packet)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed) scope.launch { delay(1_500); connect() }
        }

        private fun handleConnAck(packet: ByteArray) {
            val reader = PacketReader(packet, 1)
            reader.varInt()
            reader.byte()
            val reason = reader.byte()
            val properties = reader.properties()
            if (reason == 0) {
                socket?.send(subscribePacket(identifier).toByteString())
            } else if (reason == 0x9D) {
                properties.serverReference?.let { reference ->
                    socket?.close(1000, "redirect")
                    path = "$PATH/$reference"
                    scope.launch { delay(200); connect() }
                }
            }
        }

        private fun handlePublish(packet: ByteArray) {
            val reader = PacketReader(packet, 1)
            reader.varInt()
            reader.utf8()
            val qos = (packet[0].toInt() shr 1) and 3
            if (qos > 0) reader.short()
            val properties = reader.properties()
            val payload = runCatching { JSONObject(reader.remaining().toString(Charsets.UTF_8)) }.getOrNull()
            when (properties.userProperties["type"]) {
                "scanned" -> state = QQLoginState.SCANNED
                "canceled" -> finish(QQLoginState.REFUSED)
                "timeout" -> finish(QQLoginState.EXPIRED)
                "loginFailed" -> finish(QQLoginState.REFUSED)
                "cookies" -> payload?.let(::handleCookies)
            }
        }

        private fun handleCookies(payload: JSONObject) {
            val cookies = payload.optJSONObject("cookies") ?: return
            val uin = cookies.optJSONObject("qqmusic_uin")?.optString("value").orEmpty()
            val key = cookies.optJSONObject("qqmusic_key")?.optString("value").orEmpty()
            if (uin.isBlank() || key.isBlank()) return
            scope.launch {
                runCatching {
                    val data = QQMusicClient.cgiBlocking(
                        "music.login.LoginServer", "Login",
                        JSONObject().put("musicid", uin.toLong()).put("qrCodeID", identifier).put("token", key),
                        QQMusicClient.commonParams(mapOf("tmeLoginType" to 6)),
                    )
                    QQLoginApi.saveCredential(data, 6)
                }.onSuccess { finish(QQLoginState.DONE) }
            }
        }

        private fun finish(finalState: QQLoginState) {
            state = finalState
            closed = true
            socket?.close(1000, "finished")
        }
    }
}

private data class MqttProperties(
    val userProperties: Map<String, String>,
    val serverReference: String?,
)

private class PacketReader(private val data: ByteArray, start: Int = 0) {
    private var position = start
    fun byte(): Int = data[position++].toInt() and 0xff
    fun short(): Int = (byte() shl 8) or byte()
    fun utf8(): String {
        val size = short()
        return data.copyOfRange(position, position + size).toString(Charsets.UTF_8).also { position += size }
    }
    fun varInt(): Int {
        var multiplier = 1
        var value = 0
        while (position < data.size) {
            val encoded = byte()
            value += (encoded and 127) * multiplier
            if ((encoded and 128) == 0) break
            multiplier *= 128
        }
        return value
    }
    fun properties(): MqttProperties {
        val length = varInt()
        val end = (position + length).coerceAtMost(data.size)
        val users = mutableMapOf<String, String>()
        var server: String? = null
        while (position < end) {
            when (val id = byte()) {
                0x26 -> users[utf8()] = utf8()
                0x1c -> server = utf8()
                0x1f, 0x15 -> utf8()
                0x13, 0x21, 0x22, 0x23 -> short()
                0x11, 0x18, 0x27 -> repeat(4) { byte() }
                0x12, 0x16 -> byte()
                else -> {
                    position = end
                    throw IllegalArgumentException("Unsupported MQTT property $id")
                }
            }
        }
        return MqttProperties(users, server)
    }
    fun remaining(): ByteArray = data.copyOfRange(position, data.size)
}

private fun connectPacket(identifier: String): ByteArray {
    val properties = ByteArrayOutputStream().apply {
        propertyString(0x15, "pass")
        listOf(
            "tmeAppID" to "qqmusic", "business" to "management", "hashTag" to identifier,
            "clientTag" to "management.user", "userID" to identifier,
        ).forEach { (key, value) -> propertyPair(key, value) }
    }.toByteArray()
    val body = ByteArrayOutputStream().apply {
        utf8("MQTT")
        write(5)
        write(2)
        short(45)
        variable(properties.size)
        write(properties)
        utf8("${System.currentTimeMillis()}${(1000..9999).random()}")
    }.toByteArray()
    return packet(0x10, body)
}

private fun subscribePacket(identifier: String): ByteArray {
    val properties = ByteArrayOutputStream().apply {
        propertyPair("authorization", "tmelogin")
        propertyPair("pubsub", "unicast")
    }.toByteArray()
    val body = ByteArrayOutputStream().apply {
        short(1)
        variable(properties.size)
        write(properties)
        utf8("management.qrcode_login/$identifier")
        write(0)
    }.toByteArray()
    return packet(0x82, body)
}

private fun packet(header: Int, body: ByteArray) = ByteArrayOutputStream().apply {
    write(header)
    variable(body.size)
    write(body)
}.toByteArray()

private fun ByteArrayOutputStream.short(value: Int) {
    write(value shr 8)
    write(value and 0xff)
}

private fun ByteArrayOutputStream.utf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    short(bytes.size)
    write(bytes)
}

private fun ByteArrayOutputStream.variable(value: Int) {
    var rest = value
    do {
        var encoded = rest % 128
        rest /= 128
        if (rest > 0) encoded = encoded or 128
        write(encoded)
    } while (rest > 0)
}

private fun ByteArrayOutputStream.propertyString(id: Int, value: String) {
    write(id)
    utf8(value)
}

private fun ByteArrayOutputStream.propertyPair(key: String, value: String) {
    write(0x26)
    utf8(key)
    utf8(value)
}

private fun ByteArray.toByteString() = ByteString.of(*this)
