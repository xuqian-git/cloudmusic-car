package com.qqmusic.car.api

import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal class QQAndroidIdentity(
    private val prefs: SharedPreferences,
    private val http: OkHttpClient,
) {
    private data class Qimei(val q16: String, val q36: String)
    private data class Session(val uid: String, val sid: String)

    @Synchronized
    fun commonParams(auth: QQCredential?, overrides: Map<String, Any?>): JSONObject {
        val qimei = ensureQimei()
        val session = ensureSession(qimei)
        return baseParams(qimei, session).apply {
            auth?.let {
                put("qq", it.musicId.toString())
                put("authst", it.musicKey)
                put("tmeLoginType", it.loginType)
            }
            overrides.forEach { (key, value) -> if (value != null) put(key, value) }
        }
    }

    private fun baseParams(qimei: Qimei, session: Session?): JSONObject = JSONObject().apply {
        put("ct", 11)
        put("cv", 14090008)
        put("v", 14090008)
        put("chid", "10003505")
        put("tmeAppID", "qqmusic")
        put("QIMEI36", qimei.q36)
        put("OpenUDID", stable("identity.openUdid") { UUID.randomUUID().toString().replace("-", "") })
        put("OpenUDID2", stable("identity.openUdid2") { UUID.randomUUID().toString().replace("-", "") })
        put("udid", stable("identity.openUdid") { UUID.randomUUID().toString().replace("-", "") })
        put("aid", stable("identity.androidId") { randomHex(16) })
        put("os_ver", Build.VERSION.RELEASE)
        put("phonetype", Build.MODEL)
        session?.let {
            put("uid", it.uid)
            put("sid", it.sid)
        }
    }

    private fun ensureQimei(): Qimei {
        val savedAt = prefs.getLong("identity.qimeiSavedAt", 0)
        val cached = Qimei(
            prefs.getString("identity.q16", "").orEmpty(),
            prefs.getString("identity.q36", "").orEmpty(),
        )
        if (cached.q16.isNotBlank() && cached.q36.isNotBlank() && System.currentTimeMillis() - savedAt < 86_400_000L) {
            return cached
        }

        val cryptKey = randomHex(16)
        val nonce = randomHex(16)
        val timestamp = System.currentTimeMillis() / 1000
        val encryptedKey = Base64.encodeToString(rsaEncrypt(cryptKey.toByteArray()), Base64.NO_WRAP)
        val encryptedParams = Base64.encodeToString(aesEncrypt(cryptKey.toByteArray(), qimeiPayload().toString().toByteArray()), Base64.NO_WRAP)
        val extra = "{\"appKey\":\"0AND0HD6FE4HY80F\"}"
        val requestSign = md5(encryptedKey + encryptedParams + timestamp * 1000 + nonce + "ZdJqM15EeO2zWc08" + extra)
        val body = JSONObject()
            .put("app", 0)
            .put("os", 1)
            .put("qimeiParams", JSONObject()
                .put("key", encryptedKey)
                .put("params", encryptedParams)
                .put("time", timestamp.toString())
                .put("nonce", nonce)
                .put("sign", requestSign)
                .put("extra", extra))
        val request = Request.Builder()
            .url("https://api.tencentmusic.com/tme/trpc/proxy")
            .post(body.toString().toRequestBody(JSON))
            .header("method", "GetQimei")
            .header("service", "trpc.tme_datasvr.qimeiproxy.QimeiProxy")
            .header("appid", "qimei_qq_android")
            .header("sign", md5("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH$timestamp"))
            .header("timestamp", timestamp.toString())
            .header("User-Agent", "QQMusic")
            .build()
        val outer = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "QQ 音乐设备认证失败 (${response.code})")
            JSONObject(response.body?.string().orEmpty())
        }
        val wrapped = JSONObject(outer.optString("data"))
        val data = wrapped.optJSONObject("data") ?: error("QQ 音乐设备认证返回为空")
        val result = Qimei(data.optString("q16"), data.optString("q36"))
        check(result.q16.isNotBlank() && result.q36.isNotBlank()) { "QQ 音乐设备认证无效" }
        prefs.edit()
            .putString("identity.q16", result.q16)
            .putString("identity.q36", result.q36)
            .putLong("identity.qimeiSavedAt", System.currentTimeMillis())
            .apply()
        return result
    }

    private fun ensureSession(qimei: Qimei): Session {
        val today = LocalDate.now().toString()
        val cached = Session(
            prefs.getString("identity.sessionUid", "").orEmpty(),
            prefs.getString("identity.sessionSid", "").orEmpty(),
        )
        if (cached.uid.isNotBlank() && cached.sid.isNotBlank() && prefs.getString("identity.sessionDate", "") == today) {
            return cached
        }
        val payload = JSONObject()
            .put("comm", baseParams(qimei, null))
            .put("req_0", JSONObject()
                .put("module", "music.getSession.session")
                .put("method", "GetSession")
                .put("param", JSONObject()
                    .put("uid", cached.uid)
                    .put("vkey", 0)
                    .put("caller", if (cached.uid.isBlank()) 2 else 1)))
        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(payload.toString().toRequestBody(JSON))
            .header("User-Agent", "QQMusic 14090008(android ${Build.VERSION.RELEASE})")
            .build()
        val root = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "QQ 音乐会话初始化失败 (${response.code})")
            JSONObject(response.body?.string().orEmpty())
        }
        val item = root.optJSONObject("req_0") ?: error("QQ 音乐会话返回为空")
        val code = item.optInt("code")
        if (code != 0) throw ApiException(code, item.optString("message").ifBlank { "QQ 音乐会话初始化失败 ($code)" })
        val data = item.optJSONObject("data")?.optJSONObject("session") ?: error("QQ 音乐会话无效")
        val result = Session(data.opt("uid")?.toString().orEmpty(), data.optString("sid"))
        check(result.uid.isNotBlank() && result.sid.isNotBlank()) { "QQ 音乐会话无效" }
        prefs.edit()
            .putString("identity.sessionUid", result.uid)
            .putString("identity.sessionSid", result.sid)
            .putString("identity.sessionDate", today)
            .apply()
        return result
    }

    private fun qimeiPayload(): JSONObject {
        val androidId = stable("identity.androidId") { randomHex(16) }
        val model = Build.MODEL.ifBlank { "V2408A" }
        val reserved = JSONObject()
            .put("harmony", "0").put("clone", "0").put("containe", "")
            .put("oz", token(androidId)).put("oo", token(model)).put("kelong", "0")
            .put("ip", privateIp(androidId)).put("uptimes", java.time.ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(Random.nextLong(14_401)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .put("multiUser", "0").put("bod", Build.BOARD).put("brd", Build.BRAND)
            .put("dv", Build.DEVICE).put("firstLevel", Build.VERSION.SDK_INT.toString())
            .put("manufact", Build.MANUFACTURER).put("name", Build.PRODUCT).put("host", Build.HOST)
            .put("kernel", System.getProperty("os.version").orEmpty()).put("pre", "0")
            .put("av", "14.9.0.8").put("ch", "")
        return JSONObject()
            .put("androidId", androidId).put("platformId", 1).put("appKey", "0AND0HD6FE4HY80F")
            .put("appVersion", "14.9.0.8").put("beaconIdSrc", beaconId()).put("brand", Build.BRAND)
            .put("channelId", "10003505").put("cid", "").put("imei", stable("identity.imei", ::randomImei))
            .put("imsi", "").put("mac", "").put("model", model).put("networkType", "wifi")
            .put("oaid", "").put("osVersion", "Android ${Build.VERSION.RELEASE},level ${Build.VERSION.SDK_INT}")
            .put("qimei", "").put("qimei36", "").put("sdkVersion", "1.2.13.6")
            .put("targetSdkVersion", "30").put("audit", "").put("userId", "{}")
            .put("packageId", "com.tencent.qqmusic").put("deviceType", "Phone").put("sdkName", "")
            .put("reserved", reserved.toString())
    }

    private fun stable(key: String, create: () -> String): String =
        prefs.getString(key, null)?.takeIf(String::isNotBlank) ?: create().also { prefs.edit().putString(key, it).apply() }

    private fun token(value: String): String = Base64.encodeToString(
        aesEncrypt("lvcwmSYVr2Axv1gn".toByteArray(), value.toByteArray(), "Zs0ntDqG2jyhKN0c".toByteArray()),
        Base64.NO_WRAP,
    )

    private fun privateIp(androidId: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
        return "192.168.${digest[0].toInt() and 0xff}.${(digest[1].toInt() and 0xff) % 253 + 2}"
    }

    private fun beaconId(): String {
        val month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val first = Random.nextInt(100_000, 1_000_000)
        val second = Random.nextInt(100_000_000, 1_000_000_000)
        return (1..40).joinToString("", postfix = "") { index ->
            val value = when (index) {
                1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38 -> "$month$first.$second"
                3 -> "0000000000000000"
                4 -> randomHex(16).replace('0', '1')
                else -> Random.nextInt(10_000).toString()
            }
            "k$index:$value;"
        }
    }

    private fun rsaEncrypt(value: ByteArray): ByteArray {
        val publicKey = Base64.decode(RSA_PUBLIC_KEY, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE, key); doFinal(value) }
    }

    private fun aesEncrypt(key: ByteArray, value: ByteArray, iv: ByteArray = key): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(value)
        }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun randomHex(length: Int): String = ByteArray((length + 1) / 2).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }.take(length)

    private fun randomImei(): String {
        val digits = MutableList(14) { Random.nextInt(10) }
        val sum = digits.mapIndexed { index, digit ->
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return digits.joinToString("") + ((10 - sum % 10) % 10)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKpqLQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y6B9NMbHddGSAUmRTCrHQIDAQAB"
    }
}
