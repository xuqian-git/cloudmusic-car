package com.kugoumusic.car.api

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class KuGouLoginType(val label: String) {
    MOBILE("酷狗音乐"), QQ("QQ"), WECHAT("微信")
}

enum class KuGouLoginState { WAITING, SCANNED, DONE, EXPIRED, REFUSED }

data class KuGouLoginQr(val type: KuGouLoginType, val image: ByteArray, val identifier: String)

object KuGouLoginApi {
    private const val QQ_APP_ID = "716027609"
    private const val QQ_THIRD_APP_ID = "205141"
    private const val QQ_APK_SIG = "fe4a24d80fcf253a00676a808f62c2c6"
    private const val WX_APP_ID = "wx79f2c4418704b4f8"
    private const val WX_SECRET = "4efcab88b700769e376e3f6087b8abc9"
    private val sessions = ConcurrentHashMap<String, Session>()

    private data class Session(
        val type: KuGouLoginType,
        val key: String = "",
        val cookie: MutableMap<String, String> = linkedMapOf(),
        val values: MutableMap<String, String> = linkedMapOf(),
    )

    suspend fun create(type: KuGouLoginType): KuGouLoginQr = withContext(Dispatchers.IO) {
        when (type) {
            KuGouLoginType.MOBILE -> createMobile()
            KuGouLoginType.QQ -> createQq()
            KuGouLoginType.WECHAT -> createWechat()
        }
    }

    suspend fun check(qr: KuGouLoginQr): KuGouLoginState = withContext(Dispatchers.IO) {
        val session = sessions[qr.identifier] ?: return@withContext KuGouLoginState.EXPIRED
        when (session.type) {
            KuGouLoginType.MOBILE -> checkMobile(session)
            KuGouLoginType.QQ -> checkQq(session)
            KuGouLoginType.WECHAT -> checkWechat(session)
        }.also { if (it == KuGouLoginState.DONE || it == KuGouLoginState.EXPIRED) sessions.remove(qr.identifier) }
    }

    private fun createMobile(): KuGouLoginQr {
        val root = KuGouMusicClient.requestBlocking(
            "/v2/qrcode", params = mapOf(
                "appid" to 1001, "type" to 1, "plat" to 4,
                "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KuGouMusicClient.APP_ID}&",
                "srcappid" to KuGouMusicClient.SOURCE_APP_ID,
            ), baseUrl = "https://login-user.kugou.com", signType = SignType.WEB,
        )
        val key = (root.optJSONObject("data") ?: root).stringAny("qrcode", "key")
        check(key.isNotBlank()) { "酷狗登录没有返回二维码编号" }
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(KuGouLoginType.MOBILE, key)
        val url = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"
        return KuGouLoginQr(KuGouLoginType.MOBILE, qrPng(url), id)
    }

    private fun checkMobile(session: Session): KuGouLoginState {
        val root = KuGouMusicClient.requestBlocking(
            "/v2/get_userinfo_qrcode", params = mapOf(
                "plat" to 4, "appid" to KuGouMusicClient.APP_ID, "srcappid" to KuGouMusicClient.SOURCE_APP_ID,
                "qrcode" to session.key, "dev" to KuGouMusicClient.guid,
            ), baseUrl = "https://login-user.kugou.com", signType = SignType.WEB,
        )
        val data = root.optJSONObject("data") ?: root
        return when (data.optInt("status", root.optInt("status"))) {
            0 -> KuGouLoginState.EXPIRED
            1 -> KuGouLoginState.WAITING
            2, 3 -> KuGouLoginState.SCANNED
            4 -> {
                saveCredential(data)
                KuGouLoginState.DONE
            }
            else -> KuGouLoginState.WAITING
        }
    }

    private fun createQq(): KuGouLoginQr {
        val time = System.currentTimeMillis() / 1000
        val authorize = "https://openmobile.qq.com/oauth2.0/m_authorize".toHttpUrl().newBuilder().apply {
            mapOf(
                "cancel_display" to "1", "sdkp" to "a", "display" to "mobile", "format" to "json",
                "sign" to KuGouMusicClient.md5("${QQ_APK_SIG}_$time"), "sdkv" to "3.5.11.lite",
                "response_type" to "token", "status_os" to "11", "client_id" to QQ_THIRD_APP_ID,
                "switch" to "1", "status_version" to "30", "show_download_ui" to "true",
                "pf" to "openmobile_android", "scope" to "all", "compat_v" to "1",
                "status_machine" to "Paopao+Car", "style" to "qr", "time" to time.toString(),
                "redirect_uri" to "auth://tauth.qq.com/",
            ).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val html = executeText(Request.Builder().url(authorize).header("Referer", "https://xui.ptlogin2.qq.com/").build())
        val xlogin = Regex("src = \"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\\\x([0-9A-Fa-f]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
            ?: error("QQ 授权没有返回二维码入口")
        val cookie = linkedMapOf<String, String>()
        execute(Request.Builder().url(xlogin).build()).use { collectCookies(it, cookie) }
        val imageUrl = "https://xui.ptlogin2.qq.com/ssl/ptqrshow".toHttpUrl().newBuilder()
            .addQueryParameter("s", "8").addQueryParameter("e", "0").addQueryParameter("appid", QQ_APP_ID)
            .addQueryParameter("type", "0").addQueryParameter("t", Random.nextDouble().toString())
            .addQueryParameter("daid", "381").addQueryParameter("pt_3rd_aid", QQ_THIRD_APP_ID).build()
        val imageResponse = execute(Request.Builder().url(imageUrl).header("Referer", xlogin).header("Cookie", cookieHeader(cookie)).build())
        val image = imageResponse.use { response -> collectCookies(response, cookie); response.body?.bytes() ?: byteArrayOf() }
        val qrsig = cookie["qrsig"] ?: error("QQ 登录没有返回 qrsig")
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(KuGouLoginType.QQ, qrsig, cookie, linkedMapOf(
            "xlogin" to xlogin,
            "pt_login_sig" to cookie["pt_login_sig"].orEmpty(),
            "pt_openlogin_data" to URI(xlogin).rawQuery.orEmpty(),
        ))
        return KuGouLoginQr(KuGouLoginType.QQ, image, id)
    }

    private fun checkQq(session: Session): KuGouLoginState {
        val values = session.values
        val url = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin".toHttpUrl().newBuilder().apply {
            mapOf(
                "u1" to "http://connect.qq.com", "from_ui" to "1", "type" to "1", "ptlang" to "2052",
                "ptqrtoken" to hash33(session.key).toString(), "daid" to "381", "aid" to QQ_APP_ID,
                "pt_3rd_aid" to QQ_THIRD_APP_ID, "pt_openlogin_data" to values["pt_openlogin_data"].orEmpty(),
                "device" to "2", "ptopt" to "1", "pt_uistyle" to "35", "jsver" to "v1.36.0",
                "login_sig" to values["pt_login_sig"].orEmpty(), "r" to Random.nextDouble().toString(),
            ).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val text = execute(Request.Builder().url(url).header("Referer", values["xlogin"].orEmpty()).header("Cookie", cookieHeader(session.cookie)).build()).use {
            collectCookies(it, session.cookie); it.body?.string().orEmpty()
        }
        val args = Regex("'([^']*)'").findAll(Regex("ptuiCB\\((.+)\\)").find(text)?.groupValues?.get(1).orEmpty()).map { it.groupValues[1] }.toList()
        return when (args.firstOrNull()) {
            "66" -> KuGouLoginState.WAITING
            "67" -> KuGouLoginState.SCANNED
            "65" -> KuGouLoginState.EXPIRED
            "68" -> KuGouLoginState.REFUSED
            "0" -> {
                val token = followQqAuthorization(args.getOrNull(2).orEmpty(), session)
                loginByOpenPlatform(token.first, token.second, partnerId = 1, thirdAppId = QQ_THIRD_APP_ID)
                KuGouLoginState.DONE
            }
            else -> KuGouLoginState.WAITING
        }
    }

    private fun followQqAuthorization(start: String, session: Session): Pair<String, String> {
        var current = start
        val noRedirect = KuGouMusicClient.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(10) {
            val openId = Regex("[?&]openid=([^&#]+)").find(current)?.groupValues?.get(1)
            val token = Regex("[?&]access_token=([^&#]+)").find(current)?.groupValues?.get(1)
            if (!openId.isNullOrBlank() && !token.isNullOrBlank()) return openId to token
            val response = noRedirect.newCall(Request.Builder().url(current).header("Referer", session.values["xlogin"].orEmpty())
                .header("Cookie", cookieHeader(session.cookie)).build()).execute()
            val next = response.use {
                collectCookies(it, session.cookie)
                it.header("Location") ?: it.body?.string().orEmpty().let { body ->
                    val oi = Regex("openid[\"']?[:=][\"']?([^&\"'\\s]+)").find(body)?.groupValues?.get(1)
                    val at = Regex("access_token[\"']?[:=][\"']?([^&\"'\\s]+)").find(body)?.groupValues?.get(1)
                    if (oi != null && at != null) return oi to at
                    error("QQ 登录成功但没有返回酷狗授权凭证")
                }
            }
            current = if (next.startsWith("http")) next else URI(current).resolve(next).toString()
        }
        error("QQ 授权跳转次数过多")
    }

    private fun createWechat(): KuGouLoginQr {
        val appToken = JSONObject(executeText(Request.Builder().url(
            "https://api.weixin.qq.com/cgi-bin/token".toHttpUrl().newBuilder().addQueryParameter("appid", WX_APP_ID)
                .addQueryParameter("secret", WX_SECRET).addQueryParameter("grant_type", "client_credential").build(),
        ).build())).optString("access_token")
        check(appToken.isNotBlank()) { "微信登录服务暂不可用" }
        val ticketRoot = JSONObject(executeText(Request.Builder().url(
            "https://api.weixin.qq.com/cgi-bin/ticket/getticket".toHttpUrl().newBuilder().addQueryParameter("access_token", appToken)
                .addQueryParameter("type", "2").build(),
        ).build()))
        val ticket = ticketRoot.optString("ticket")
        check(ticket.isNotBlank()) { "微信登录没有返回 ticket" }
        val timestamp = System.currentTimeMillis().toString()
        val nonce = KuGouMusicClient.md5(UUID.randomUUID().toString())
        val signature = sha1("appid=$WX_APP_ID&noncestr=$nonce&sdk_ticket=$ticket&timestamp=$timestamp")
        val root = JSONObject(executeText(Request.Builder().url(
            "https://open.weixin.qq.com/connect/sdk/qrconnect".toHttpUrl().newBuilder()
                .addQueryParameter("appid", WX_APP_ID).addQueryParameter("noncestr", nonce)
                .addQueryParameter("timestamp", timestamp).addQueryParameter("scope", "snsapi_userinfo")
                .addQueryParameter("signature", signature).build(),
        ).build()))
        val uuid = root.optString("uuid")
        val qrUrl = root.optJSONObject("qrcode")?.optString("qrcodeurl").orEmpty()
            .ifBlank { "https://open.weixin.qq.com/connect/confirm?uuid=$uuid" }
        check(uuid.isNotBlank()) { "微信登录没有返回二维码编号" }
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(KuGouLoginType.WECHAT, uuid)
        return KuGouLoginQr(KuGouLoginType.WECHAT, qrPng(qrUrl), id)
    }

    private fun checkWechat(session: Session): KuGouLoginState {
        val root = JSONObject(executeText(Request.Builder().url(
            "https://long.open.weixin.qq.com/connect/l/qrconnect".toHttpUrl().newBuilder()
                .addQueryParameter("f", "json").addQueryParameter("uuid", session.key).build(),
        ).build()))
        return when (root.optInt("wx_errcode")) {
            408 -> KuGouLoginState.WAITING
            404 -> KuGouLoginState.SCANNED
            402 -> KuGouLoginState.EXPIRED
            403 -> KuGouLoginState.REFUSED
            405 -> {
                val code = root.optString("wx_code")
                val tokenRoot = JSONObject(executeText(Request.Builder().url(
                    "https://api.weixin.qq.com/sns/oauth2/access_token".toHttpUrl().newBuilder()
                        .addQueryParameter("secret", WX_SECRET).addQueryParameter("appid", WX_APP_ID)
                        .addQueryParameter("code", code).addQueryParameter("grant_type", "authorization_code").build(),
                ).build()))
                loginByOpenPlatform(tokenRoot.optString("openid"), tokenRoot.optString("access_token"), partnerId = 36)
                KuGouLoginState.DONE
            }
            else -> KuGouLoginState.WAITING
        }
    }

    private fun loginByOpenPlatform(openId: String, accessToken: String, partnerId: Int, thirdAppId: String? = null) {
        check(openId.isNotBlank() && accessToken.isNotBlank()) { "第三方登录凭证无效" }
        val now = System.currentTimeMillis()
        val encrypted = KuGouMusicClient.encryptToken(JSONObject().put("access_token", accessToken).toString())
        val pk = KuGouMusicClient.rsaRawEncrypt(JSONObject().put("clienttime_ms", now).put("key", encrypted.key).toString()).uppercase()
        val body = JSONObject().put("dev", KuGouMusicClient.guid).put("force_login", 1).put("partnerid", partnerId)
            .put("clienttime_ms", now).put("t1", 0).put("t2", 0).put("t3", "MCwwLDAsMCwwLDAsMCwwLDA=")
            .put("openid", openId).put("params", encrypted.hex).put("pk", pk)
            .apply { if (thirdAppId != null) put("third_appid", thirdAppId) }.toString()
        val root = KuGouMusicClient.requestBlocking("/v6/login_by_openplat", "POST", body = body, headers = mapOf("x-router" to "login.user.kugou.com"))
        val data = root.optJSONObject("data") ?: error(root.optString("error_msg").ifBlank { "酷狗第三方登录失败" })
        val secure = data.optString("secu_params").takeIf(String::isNotBlank)?.let {
            runCatching { JSONObject(KuGouMusicClient.decryptToken(it, encrypted.key)) }.getOrNull()
        }
        if (secure != null) secure.keys().forEach { key -> data.put(key, secure.opt(key)) }
        saveCredential(data)
    }

    private fun saveCredential(data: JSONObject) {
        val userId = data.longAny("userid", "user_id")
        val token = data.stringAny("token")
        check(userId > 0 && token.isNotBlank()) { data.stringAny("error_msg", "msg").ifBlank { "酷狗登录凭证无效" } }
        KuGouMusicClient.storeCredential(KuGouCredential(
            userId, token, data.optInt("vip_type"), data.stringAny("vip_token"),
            data.stringAny("nickname", "username", "nick_name"), data.stringAny("pic", "avatar", "user_img"),
        ))
    }

    private fun qrPng(content: String): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    private fun execute(request: Request) = KuGouMusicClient.http.newCall(request).execute()
    private fun executeText(request: Request): String = execute(request).use {
        if (!it.isSuccessful) throw ApiException(it.code, "登录网络错误 (${it.code})")
        it.body?.string().orEmpty()
    }

    private fun collectCookies(response: okhttp3.Response, target: MutableMap<String, String>) {
        response.headers("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val index = pair.indexOf('=')
            if (index > 0) target[pair.substring(0, index)] = pair.substring(index + 1)
        }
    }

    private fun cookieHeader(values: Map<String, String>) = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    private fun hash33(value: String): Int {
        var hash = 0L
        value.forEach { hash += (hash shl 5) + it.code }
        return (hash and 0x7fffffff).toInt()
    }
    private fun sha1(value: String) = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
