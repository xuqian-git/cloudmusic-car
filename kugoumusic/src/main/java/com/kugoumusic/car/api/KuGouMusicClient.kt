package com.kugoumusic.car.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ApiException(val code: Int, message: String) : IOException(message)

data class KuGouCredential(
    val userId: Long,
    val token: String,
    val vipType: Int = 0,
    val vipToken: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
)

enum class SignType { ANDROID, WEB, NONE }

object KuGouMusicClient {
    internal const val APP_ID = 1005
    internal const val CLIENT_VERSION = 20489
    internal const val SOURCE_APP_ID = 2919
    private const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/" +
            "gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/" +
            "X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUd" +
            "C6DJE5E221wf/4WLFxwAtRQIDAQAB"
    internal const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    private lateinit var prefs: SharedPreferences
    private val random = SecureRandom()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    lateinit var guid: String
        private set
    lateinit var mid: String
        private set
    var dfid: String = "-"
        private set
    var credential: KuGouCredential? = null
        private set

    val isLoggedIn: Boolean get() = credential?.let { it.userId > 0 && it.token.isNotBlank() } == true

    fun init(context: Context) {
        KuGouRecentStore.init(context)
        prefs = context.getSharedPreferences("kugoumusic_account", Context.MODE_PRIVATE)
        guid = prefs.getString("guid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("guid", it).apply()
        }
        mid = BigInteger(1, md5Bytes(guid)).toString()
        dfid = prefs.getString("dfid", "-") ?: "-"
        val userId = prefs.getLong("userId", 0)
        val token = prefs.getString("token", "").orEmpty()
        if (userId > 0 && token.isNotBlank()) {
            credential = KuGouCredential(
                userId,
                token,
                prefs.getInt("vipType", 0),
                prefs.getString("vipToken", "").orEmpty(),
                prefs.getString("nickname", "").orEmpty(),
                prefs.getString("avatarUrl", "").orEmpty(),
            )
        }
    }

    fun storeCredential(value: KuGouCredential) {
        credential = value
        prefs.edit()
            .putLong("userId", value.userId)
            .putString("token", value.token)
            .putInt("vipType", value.vipType)
            .putString("vipToken", value.vipToken)
            .putString("nickname", value.nickname)
            .putString("avatarUrl", value.avatarUrl)
            .apply()
    }

    fun clearAuthCookies() {
        credential = null
        prefs.edit().remove("userId").remove("token").remove("vipType").remove("vipToken")
            .remove("nickname").remove("avatarUrl").apply()
    }

    internal fun storeDfid(value: String) {
        if (value.isBlank()) return
        dfid = value
        prefs.edit().putString("dfid", value).apply()
    }

    @Synchronized
    internal fun ensureDeviceRegisteredBlocking() {
        if (dfid != "-") return
        val device = JSONObject()
            .put("availableRamSize", 4_983_533_568L)
            .put("availableRomSize", 48_114_719L)
            .put("availableSDSize", 48_114_717L)
            .put("basebandVer", "")
            .put("batteryLevel", 100)
            .put("batteryStatus", 3)
            .put("brand", "Paopao")
            .put("buildSerial", "unknown")
            .put("device", "car")
            .put("imei", guid)
            .put("imsi", "")
            .put("manufacturer", "Paopao")
            .put("uuid", guid)
        listOf("accelerometer", "gravity", "gyroscope", "light", "magnetic", "orientation", "pressure", "step_counter", "temperature")
            .forEach { device.put(it, false).put("${it}Value", "") }
        val cipher = encryptCloud(device.toString())
        val body = Base64.encodeToString(cipher.bytes, Base64.NO_WRAP)
        val p = rsaEncrypt(JSONObject().put("aes", cipher.key).put("uid", credential?.userId ?: 0).put("token", credential?.token ?: "").toString()).uppercase()
        val clientTime = System.currentTimeMillis() / 1000
        val params = linkedMapOf(
            "dfid" to "-", "mid" to mid, "uuid" to "-", "appid" to APP_ID.toString(),
            "clientver" to CLIENT_VERSION.toString(), "clienttime" to clientTime.toString(),
            "part" to "1", "platid" to "1", "p" to p,
        )
        credential?.let { params["token"] = it.token; params["userid"] = it.userId.toString() }
        params["signature"] = androidSignature(params, body)
        val url = "https://userservice.kugou.com/risk/v2/r_register_dev".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", USER_AGENT).header("dfid", "-").header("mid", mid)
            .header("clienttime", clientTime.toString()).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "设备注册失败 (${response.code})")
            val raw = response.body?.bytes() ?: byteArrayOf()
            val text = runCatching { decryptCloud(raw, cipher.key) }.getOrElse { raw.toString(Charsets.UTF_8) }
            val root = JSONObject(text)
            val value = root.optJSONObject("data")?.optString("dfid").orEmpty()
            if (root.optInt("status") != 1 || value.isBlank()) throw ApiException(root.optInt("error_code"), "酷狗设备验证失败")
            storeDfid(value)
        }
    }

    suspend fun request(
        path: String,
        method: String = "GET",
        params: Map<String, Any?> = emptyMap(),
        body: String? = null,
        baseUrl: String = "https://gateway.kugou.com",
        signType: SignType = SignType.ANDROID,
        clearDefaults: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        requestBlocking(path, method, params, body, baseUrl, signType, clearDefaults, headers)
    }

    fun requestBlocking(
        path: String,
        method: String = "GET",
        params: Map<String, Any?> = emptyMap(),
        body: String? = null,
        baseUrl: String = "https://gateway.kugou.com",
        signType: SignType = SignType.ANDROID,
        clearDefaults: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val clientTime = System.currentTimeMillis() / 1000
        val values = linkedMapOf<String, String>()
        if (!clearDefaults) {
            values["dfid"] = dfid
            values["mid"] = mid
            values["uuid"] = "-"
            values["appid"] = APP_ID.toString()
            values["clientver"] = CLIENT_VERSION.toString()
            values["clienttime"] = clientTime.toString()
            credential?.let {
                values["token"] = it.token
                values["userid"] = it.userId.toString()
            }
        }
        params.forEach { (key, value) -> if (value != null) values[key] = jsonValue(value) }
        if (signType != SignType.NONE && "signature" !in values) {
            values["signature"] = signature(values, body.orEmpty(), signType)
        }
        val url = (baseUrl.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl().newBuilder().apply {
            values.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("dfid", dfid)
            .header("mid", mid)
            .header("clienttime", values["clienttime"] ?: clientTime.toString())
            .header("kg-rc", "1")
            .header("kg-thash", "5d816a0")
            .header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
        headers.forEach(builder::header)
        if (method.equals("POST", true)) {
            builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { throw ApiException(-1, "酷狗返回数据无法解析") }
            val status = root.optInt("status", 1)
            if (status == 0) throw ApiException(root.optInt("error_code", root.optInt("errcode")), root.optString("error_msg").ifBlank { root.optString("error").ifBlank { "酷狗接口错误" } })
            return root
        }
    }

    internal fun androidSignature(params: Map<String, String>, body: String = ""): String =
        signature(params, body, SignType.ANDROID)

    internal fun webSignature(params: Map<String, String>, body: String = ""): String =
        signature(params, body, SignType.WEB)

    private fun signature(params: Map<String, String>, body: String, type: SignType): String {
        val salt = if (type == SignType.WEB) WEB_SALT else ANDROID_SALT
        val joined = params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        return md5("$salt$joined$body$salt")
    }

    internal fun signKey(hash: String, userId: Long = credential?.userId ?: 0): String =
        md5("${hash.lowercase()}57ae12eb6890223e355ccfcb74edf70d${APP_ID}${mid}$userId")

    internal fun signParamsKey(value: String): String = md5("${APP_ID}${ANDROID_SALT}${CLIENT_VERSION}$value")

    internal fun signCloudKey(hash: String, pid: Int = 20026): String =
        md5("musicclound${hash.lowercase()}$pid" + "ebd1ac3134c880bda6a2194537843caa0162e2e7")

    internal fun rsaEncrypt(value: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    internal data class TokenCipher(val key: String, val hex: String)

    internal fun encryptToken(value: String): TokenCipher {
        val alphabet = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val tempKey = buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }.lowercase()
        val digest = md5(tempKey)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.toByteArray(), "AES"), IvParameterSpec(digest.takeLast(16).toByteArray()))
            doFinal(value.toByteArray())
        }
        return TokenCipher(tempKey, encrypted.joinToString("") { "%02x".format(it) })
    }

    internal fun decryptToken(hex: String, tempKey: String): String {
        val digest = md5(tempKey)
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.toByteArray(), "AES"), IvParameterSpec(digest.takeLast(16).toByteArray()))
            doFinal(bytes).toString(Charsets.UTF_8)
        }
    }

    /** KuGou login uses raw RSA with a zero-padded 128-byte message, not PKCS#1 padding. */
    internal fun rsaRawEncrypt(value: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        val source = value.toByteArray()
        require(source.size <= 128) { "RSA login payload is too large" }
        val padded = ByteArray(128)
        source.copyInto(padded)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(padded).joinToString("") { "%02x".format(it) }
        }
    }

    internal data class CloudCipher(val key: String, val bytes: ByteArray)

    internal fun encryptCloud(value: String): CloudCipher {
        val alphabet = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val session = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }.lowercase()
        return CloudCipher(session, aes(value.toByteArray(), session, Cipher.ENCRYPT_MODE))
    }

    internal fun decryptCloud(bytes: ByteArray, session: String): String =
        aes(bytes, session, Cipher.DECRYPT_MODE).toString(Charsets.UTF_8)

    private fun aes(bytes: ByteArray, session: String, mode: Int): ByteArray {
        val digest = md5(session)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(digest.substring(0, 16).toByteArray(), "AES"), IvParameterSpec(digest.substring(16).toByteArray()))
            doFinal(bytes)
        }
    }

    internal fun md5(value: String): String = md5Bytes(value).joinToString("") { "%02x".format(it) }
    private fun md5Bytes(value: String): ByteArray = MessageDigest.getInstance("MD5").digest(value.toByteArray())

    private fun jsonValue(value: Any): String = when (value) {
        is Boolean -> if (value) "true" else "false"
        else -> value.toString()
    }
}
