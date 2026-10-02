package com.qqmusic.car.api

import android.os.Bundle
import android.util.Base64
import com.paopao.music.link.EngineLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class QQLoginState { WAITING, SCANNED, DONE, EXPIRED, REFUSED }

/** 扫码已确认、但换 QQ 音乐凭证失败：凭证只能换一次，不能拿同一个码再轮询重试，只能刷新二维码。 */
class QQLoginExchangeException(cause: Throwable) : Exception(cause.message ?: "登录失败", cause), EngineLink.CodedException {
    /** 跨进程时靠这个码在界面那边还原成同一个异常（扫码页据此改为「刷新二维码」）。 */
    override val errorCode: Int get() = CODE

    companion object {
        const val CODE = -7001
    }
}

class QQLoginQr(val image: ByteArray, val identifier: String)

/**
 * 只保留「QQ 音乐 App 扫码」（同 QQMusicApi 的 MOBILE 登录）。QQ / 微信网页扫码走腾讯开放平台授权，
 * 授权页风控时好时坏，2026-09-26 按用户要求去掉；需要时见 git 历史（c749feb 及之前）。
 */
object QQLoginApi {
    suspend fun create(): QQLoginQr = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) {
            val reply = QQMusicClient.engineCall(CMD_CREATE)
            QQLoginQr(
                requireNotNull(reply.getByteArray(KEY_IMAGE)) { "获取 QQ 音乐二维码失败" },
                requireNotNull(reply.getString(KEY_ID)) { "获取 QQ 音乐二维码失败" },
            )
        } else {
            createMobile()
        }
    }

    suspend fun check(qr: QQLoginQr): QQLoginState = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) {
            val reply = try {
                QQMusicClient.engineCall(CMD_CHECK, Bundle().apply { putString(KEY_ID, qr.identifier) })
            } catch (e: ApiException) {
                if (e.code == QQLoginExchangeException.CODE) throw QQLoginExchangeException(IllegalStateException(e.message))
                throw e
            }
            QQLoginState.valueOf(reply.getString(KEY_STATE) ?: QQLoginState.WAITING.name)
        } else {
            checkMobile(qr.identifier)
        }
    }

    /** 扫码页离开或换码时调用：断开 MQTT 长连接。 */
    fun close(qr: QQLoginQr) {
        if (EngineLink.isUi) {
            EngineLink.UiSide.fire(CMD_CLOSE, Bundle().apply { putString(KEY_ID, qr.identifier) })
            return
        }
        mobileSessions.remove(qr.identifier)?.close()
    }

    // ---------- 拆进程：扫码会话（MQTT 长连接）、换凭证、存凭证都只在引擎里 ----------

    private const val CMD_CREATE = "login.create"
    private const val CMD_CHECK = "login.check"
    private const val CMD_CLOSE = "login.close"
    private const val KEY_ID = "id"
    private const val KEY_IMAGE = "image"
    private const val KEY_STATE = "state"

    fun serveToUi() {
        val engine = EngineLink.EngineSide
        engine.command(CMD_CREATE) {
            val qr = createMobile()
            Bundle().apply {
                putByteArray(KEY_IMAGE, qr.image)
                putString(KEY_ID, qr.identifier)
            }
        }
        engine.command(CMD_CHECK) { args ->
            val state = checkMobile(requireNotNull(args.getString(KEY_ID)))
            Bundle().apply { putString(KEY_STATE, state.name) }
        }
        engine.command(CMD_CLOSE) { args ->
            args.getString(KEY_ID)?.let { mobileSessions.remove(it)?.close() }
            null
        }
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
        val credential = QQMusicClient.credentialFrom(root, fallbackType)
        android.util.Log.i(
            "QQAuth",
            "login saved type=${credential.loginType} refreshKey=${credential.refreshKey.isNotBlank()} " +
                "refreshToken=${credential.refreshToken.isNotBlank()} expiresIn=${credential.keyExpiresIn}",
        )
        QQMusicClient.storeCredential(credential)
    }
}
