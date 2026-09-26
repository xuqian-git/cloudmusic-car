package com.qqmusic.car.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class QQLoginState { WAITING, SCANNED, DONE, EXPIRED, REFUSED }

/** 扫码已确认、但换 QQ 音乐凭证失败：凭证只能换一次，不能拿同一个码再轮询重试，只能刷新二维码。 */
class QQLoginExchangeException(cause: Throwable) : Exception(cause.message ?: "登录失败", cause)

class QQLoginQr(val image: ByteArray, val identifier: String)

/**
 * 只保留「QQ 音乐 App 扫码」（同 QQMusicApi 的 MOBILE 登录）。QQ / 微信网页扫码走腾讯开放平台授权，
 * 授权页风控时好时坏，2026-09-26 按用户要求去掉；需要时见 git 历史（c749feb 及之前）。
 */
object QQLoginApi {
    suspend fun create(): QQLoginQr = withContext(Dispatchers.IO) { createMobile() }

    suspend fun check(qr: QQLoginQr): QQLoginState = withContext(Dispatchers.IO) { checkMobile(qr.identifier) }

    /** 扫码页离开或换码时调用：断开 MQTT 长连接。 */
    fun close(qr: QQLoginQr) {
        mobileSessions.remove(qr.identifier)?.close()
    }

    private val mobileSessions = ConcurrentHashMap<String, QQMobileLogin>()

    /** 同 QQMusicApi._get_mobile_qr：param 带安卓版本号，comm 的 ct/cv 覆盖成 23/0。 */
    private fun createMobile(): QQLoginQr {
        val data = QQMusicClient.cgiAndroidBlocking(
            "music.login.LoginServer", "CreateQRCode",
            JSONObject().put("tmeAppID", "qqmusic").put("ct", 11).put("cv", 14090008),
            mapOf("ct" to 23, "cv" to 0),
        )
        val qrcode = data.optString("qrcode")
        val id = data.optString("qrcodeID")
        if (qrcode.isBlank() || id.isBlank()) error("获取 QQ 音乐二维码失败")
        val session = QQMobileLogin(id)
        try {
            session.start()
        } catch (e: Exception) {
            session.close()
            throw e
        }
        mobileSessions[id] = session
        return QQLoginQr(Base64.decode(qrcode.substringAfterLast(','), Base64.DEFAULT), id)
    }

    private fun checkMobile(id: String): QQLoginState {
        val session = mobileSessions[id] ?: return QQLoginState.EXPIRED
        return when (session.event) {
            "scanned" -> QQLoginState.SCANNED
            "canceled" -> QQLoginState.REFUSED
            "timeout", "closed" -> QQLoginState.EXPIRED
            "loginFailed" -> throw QQLoginExchangeException(IllegalStateException("QQ 音乐 App 登录失败"))
            "cookies" -> {
                exchange {
                    val cookies = session.payload?.optJSONObject("cookies")
                    val uin = cookies?.optJSONObject("qqmusic_uin")?.optString("value")?.toLongOrNull()
                    val key = cookies?.optJSONObject("qqmusic_key")?.optString("value").orEmpty()
                    check(uin != null && key.isNotBlank()) { "QQ 音乐 App 没有返回登录凭证" }
                    val data = QQMusicClient.cgiAndroidBlocking(
                        "music.login.LoginServer", "Login",
                        JSONObject().put("musicid", uin).put("qrCodeID", id).put("token", key),
                        mapOf("tmeLoginType" to 6),
                    )
                    saveCredential(data, 6)
                }
                mobileSessions.remove(id)?.close()
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
}
