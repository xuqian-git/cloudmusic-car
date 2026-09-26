package com.qqmusic.car.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random

enum class QQLoginType(val label: String) {
    QQ("QQ"), WECHAT("微信")
}

enum class QQLoginState { WAITING, SCANNED, DONE, EXPIRED, REFUSED }

/** 扫码已确认、但换 QQ 音乐凭证失败：授权码是一次性的，不能拿同一个码再轮询重试，只能刷新二维码。 */
class QQLoginExchangeException(cause: Throwable) : Exception(cause.message ?: "登录失败", cause)

data class QQLoginQr(
    val type: QQLoginType,
    val image: ByteArray,
    val identifier: String,
)

object QQLoginApi {
    private const val QQ_APP_ID = "716027609"
    private const val QQ_THIRD_APP_ID = "100497308"
    private const val WX_APP_ID = "wx48db31d50e334801"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    suspend fun create(type: QQLoginType): QQLoginQr = withContext(Dispatchers.IO) {
        when (type) {
            QQLoginType.QQ -> createQq()
            QQLoginType.WECHAT -> createWechat()
        }
    }

    suspend fun check(qr: QQLoginQr): QQLoginState = withContext(Dispatchers.IO) {
        when (qr.type) {
            QQLoginType.QQ -> checkQq(qr.identifier)
            QQLoginType.WECHAT -> checkWechat(qr.identifier)
        }
    }

    private fun createQq(): QQLoginQr {
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow".toHttpUrl(
            "appid" to QQ_APP_ID, "e" to "2", "l" to "M", "s" to "3", "d" to "72", "v" to "4",
            "t" to Random.nextDouble().toString(), "daid" to "383", "pt_3rd_aid" to QQ_THIRD_APP_ID,
        )
        QQMusicClient.http.newCall(qqWebRequest(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "获取 QQ 二维码失败")
            val qrsig = response.headers("Set-Cookie").firstNotNullOfOrNull { raw ->
                raw.substringBefore(';').takeIf { it.startsWith("qrsig=") }?.substringAfter('=')
            } ?: error("QQ 登录没有返回 qrsig")
            return QQLoginQr(QQLoginType.QQ, response.body?.bytes() ?: byteArrayOf(), qrsig)
        }
    }

    private fun createWechat(): QQLoginQr {
        val redirect = "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"
        val page = "https://open.weixin.qq.com/connect/qrconnect".toHttpUrl(
            "appid" to WX_APP_ID, "redirect_uri" to redirect, "response_type" to "code",
            "scope" to "snsapi_login", "state" to "STATE",
        )
        val html = QQMusicClient.http.newCall(Request.Builder().url(page).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "获取微信二维码失败")
            response.body?.string().orEmpty()
        }
        val uuid = Regex("qrcode/([A-Za-z0-9_-]+)").find(html)?.groupValues?.get(1)
            ?: error("微信登录没有返回二维码编号")
        val image = QQMusicClient.http.newCall(
            Request.Builder().url("https://open.weixin.qq.com/connect/qrcode/$uuid").header("Referer", page).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "下载微信二维码失败")
            response.body?.bytes() ?: byteArrayOf()
        }
        return QQLoginQr(QQLoginType.WECHAT, image, uuid)
    }

    private fun checkQq(qrsig: String): QQLoginState {
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin".toHttpUrl(
            "u1" to "https://graph.qq.com/oauth2.0/login_jump", "ptqrtoken" to qqQrToken(qrsig).toString(),
            "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1", "from_ui" to "1",
            "ptlang" to "2052", "action" to "0-0-${System.currentTimeMillis()}", "js_ver" to "20102616",
            "js_type" to "1", "pt_uistyle" to "40", "aid" to QQ_APP_ID, "daid" to "383",
            "pt_3rd_aid" to QQ_THIRD_APP_ID, "has_onekey" to "1",
        )
        val text = QQMusicClient.http.newCall(qqWebRequest(url).header("Cookie", "qrsig=$qrsig").build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "QQ 扫码状态请求失败 (${response.code})")
            response.body?.string().orEmpty()
        }
        val callback = Regex("ptuiCB\\((.*?)\\)").find(text)?.groupValues?.get(1)
            ?: error("QQ 扫码状态响应无法解析")
        val args = Regex("'((?:\\\\.|[^'])*)'").findAll(callback)
            .map { it.groupValues[1] }.toList()
        return when (args.firstOrNull()?.toIntOrNull()) {
            66 -> QQLoginState.WAITING
            67 -> QQLoginState.SCANNED
            65 -> QQLoginState.EXPIRED
            68 -> QQLoginState.REFUSED
            0 -> {
                val callback = args.getOrNull(2).orEmpty()
                val uin = Regex("[?&]uin=(.+?)&service").find(callback)?.groupValues?.get(1) ?: error("QQ 登录缺少 uin")
                val sigx = Regex("[?&]ptsigx=(.+?)&s_url").find(callback)?.groupValues?.get(1) ?: error("QQ 登录缺少签名")
                exchange { authorizeQq(uin, sigx) }
                QQLoginState.DONE
            }
            else -> error("QQ 扫码返回未知状态 (${args.firstOrNull().orEmpty()})")
        }
    }

    private fun authorizeQq(uin: String, sigx: String) {
        val checkUrl = "https://ssl.ptlogin2.graph.qq.com/check_sig".toHttpUrl(
            "uin" to uin, "pttype" to "1", "service" to "ptqrlogin", "nodirect" to "0", "ptsigx" to sigx,
            "s_url" to "https://graph.qq.com/oauth2.0/login_jump", "ptlang" to "2052", "ptredirect" to "100",
            "aid" to QQ_APP_ID, "daid" to "383", "j_later" to "0", "low_login_hour" to "0",
            "regmaster" to "0", "pt_login_type" to "3", "pt_aid" to "0", "pt_aaid" to "16",
            "pt_light" to "0", "pt_3rd_aid" to QQ_THIRD_APP_ID,
        )
        val noRedirect = QQMusicClient.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val cookies = noRedirect.newCall(Request.Builder().url(checkUrl).header("Referer", "https://xui.ptlogin2.qq.com/").build())
            .execute().use { response -> response.headers("Set-Cookie").map { it.substringBefore(';') } }
        val pSkey = cookies.firstOrNull { it.startsWith("p_skey=") }?.substringAfter('=') ?: error("QQ 授权失败")
        val body = FormBody.Builder()
            .add("response_type", "code").add("client_id", QQ_THIRD_APP_ID)
            .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
            .add("scope", "get_user_info,get_app_friends").add("state", "state").add("from_ptlogin", "1")
            .add("switch", "").add("src", "1").add("update_auth", "1").add("openapi", "1010_1030")
            .add("g_tk", QQMusicClient.hash33(pSkey).toString()).add("auth_time", System.currentTimeMillis().toString())
            .add("ui", UUID.randomUUID().toString()).build()
        val location = noRedirect.newCall(
            Request.Builder().url("https://graph.qq.com/oauth2.0/authorize").post(body).header("Cookie", cookies.joinToString("; ")).build(),
        ).execute().use { it.header("Location").orEmpty() }
        val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1) ?: error("QQ 授权码获取失败")
        val data = QQMusicClient.cgiAndroidBlocking(
            "QQConnectLogin.LoginServer", "QQLogin", JSONObject().put("code", code),
            mapOf("tmeLoginType" to 2),
        )
        saveCredential(data, 2)
    }

    private fun checkWechat(uuid: String): QQLoginState {
        val url = "https://lp.open.weixin.qq.com/connect/l/qrconnect".toHttpUrl("uuid" to uuid, "_" to System.currentTimeMillis().toString())
        val text = QQMusicClient.http.newCall(Request.Builder().url(url).header("Referer", "https://open.weixin.qq.com/").build())
            .execute().use { it.body?.string().orEmpty() }
        val match = Regex("window\\.wx_errcode=(\\d+);window\\.wx_code='([^']*)'").find(text)
            ?: return QQLoginState.WAITING
        return when (match.groupValues[1].toInt()) {
            408 -> QQLoginState.WAITING
            404 -> QQLoginState.SCANNED
            402 -> QQLoginState.EXPIRED
            403 -> QQLoginState.REFUSED
            405 -> {
                exchange {
                    val data = QQMusicClient.cgiAndroidBlocking(
                        "music.login.LoginServer", "Login",
                        JSONObject().put("code", match.groupValues[2]).put("strAppid", WX_APP_ID),
                        mapOf("tmeLoginType" to 1),
                    )
                    saveCredential(data, 1)
                }
                QQLoginState.DONE
            }
            else -> QQLoginState.WAITING
        }
    }

    private inline fun exchange(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            throw QQLoginExchangeException(e)
        }
    }

    internal fun saveCredential(root: JSONObject, fallbackType: Int) {
        val data = root.optJSONObject("data") ?: root
        val id = data.optLong("musicid").takeIf { it > 0 } ?: data.optString("str_musicid").toLongOrNull() ?: 0
        val key = data.optString("musickey")
        check(id > 0 && key.isNotBlank()) { data.optString("msg").ifBlank { "登录凭证无效" } }
        QQMusicClient.storeCredential(
            QQCredential(id, key, data.optString("encryptUin"), data.optInt("loginType", fallbackType), data.optString("nick"), data.optString("avatar")),
        )
    }

    internal fun qqQrToken(qrsig: String): Int = QQMusicClient.hash33(qrsig, seed = 0)

    private fun qqWebRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", WEB_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Referer", "https://xui.ptlogin2.qq.com/")
}

private fun String.toHttpUrl(vararg params: Pair<String, String>): String = buildString {
    append(this@toHttpUrl)
    params.forEachIndexed { index, (key, value) ->
        append(if (index == 0) '?' else '&')
        append(URLEncoder.encode(key, "UTF-8"))
        append('=')
        append(URLEncoder.encode(value, "UTF-8"))
    }
}
