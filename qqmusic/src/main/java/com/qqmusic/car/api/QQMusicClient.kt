package com.qqmusic.car.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.paopao.music.link.EngineLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message), EngineLink.CodedException {
    override val errorCode: Int get() = code
}

data class QQCredential(
    val musicId: Long,
    val musicKey: String,
    val encryptUin: String,
    val loginType: Int,
    val nickname: String = "",
    val avatarUrl: String = "",
    // 续签材料（同 QQMusicApi Credential）；1.0.19 之前登录的没有存，只能等失效后重新扫码
    val refreshKey: String = "",
    val refreshToken: String = "",
    val accessToken: String = "",
    val openId: String = "",
    val unionId: String = "",
    val expiredAt: Long = 0,
    val keyExpiresIn: Long = 0,
    val musicKeyCreateTime: Long = 0,
)

object QQMusicClient {
    private const val TAG = "QQAuth"
    private const val LOGIN_MODULE = "music.login.LoginServer"
    private val AUTH_EXPIRED_CODES = setOf(1000, 104400, 104401)
    /** 续签间隔；musickey 用到有效期 2/3 也提前续。检查本身只比时间戳，10 分钟查一次。 */
    private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val REFRESH_DEDUP_MS = 60 * 1000L
    private val refreshLock = Any()

    /** 服务器明确判定登录失效、续签也救不回来时回调；本地凭证已清掉。 */
    @Volatile var onSessionExpired: (() -> Unit)? = null

    private const val CGI_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val cookieStore = ConcurrentHashMap<String, Cookie>()
    private lateinit var prefs: SharedPreferences
    private lateinit var androidIdentity: QQAndroidIdentity

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookie -> cookieStore["${cookie.domain}|${cookie.name}"] = cookie }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.values.filter { it.matches(url) }
        })
        .build()

    var credential: QQCredential? = null
        private set

    val isLoggedIn: Boolean get() = credential?.let { it.musicId > 0 && it.musicKey.isNotBlank() } == true

    fun init(context: Context) {
        QQRecentStore.init(context)
        prefs = context.getSharedPreferences("qqmusic_account", Context.MODE_PRIVATE)
        androidIdentity = QQAndroidIdentity(prefs, http)
        val id = prefs.getLong("musicId", 0)
        val key = prefs.getString("musicKey", "").orEmpty()
        if (id > 0 && key.isNotBlank()) {
            credential = QQCredential(
                musicId = id,
                musicKey = key,
                encryptUin = prefs.getString("encryptUin", "").orEmpty(),
                loginType = prefs.getInt("loginType", 0),
                nickname = prefs.getString("nickname", "").orEmpty(),
                avatarUrl = prefs.getString("avatarUrl", "").orEmpty(),
                refreshKey = prefs.getString("refreshKey", "").orEmpty(),
                refreshToken = prefs.getString("refreshToken", "").orEmpty(),
                accessToken = prefs.getString("accessToken", "").orEmpty(),
                openId = prefs.getString("openId", "").orEmpty(),
                unionId = prefs.getString("unionId", "").orEmpty(),
                expiredAt = prefs.getLong("expiredAt", 0),
                keyExpiresIn = prefs.getLong("keyExpiresIn", 0),
                musicKeyCreateTime = prefs.getLong("musicKeyCreateTime", 0),
            )
        }
    }

    fun storeCredential(value: QQCredential) {
        credential = value
        prefs.edit()
            .putLong("musicId", value.musicId)
            .putString("musicKey", value.musicKey)
            .putString("encryptUin", value.encryptUin)
            .putInt("loginType", value.loginType)
            .putString("nickname", value.nickname)
            .putString("avatarUrl", value.avatarUrl)
            .putString("refreshKey", value.refreshKey)
            .putString("refreshToken", value.refreshToken)
            .putString("accessToken", value.accessToken)
            .putString("openId", value.openId)
            .putString("unionId", value.unionId)
            .putLong("expiredAt", value.expiredAt)
            .putLong("keyExpiresIn", value.keyExpiresIn)
            .putLong("musicKeyCreateTime", value.musicKeyCreateTime)
            .apply()
    }

    /** 登录 / 续签返回的 data 转凭证；返回里没有的昵称头像、续签材料沿用 [previous]。 */
    internal fun credentialFrom(root: JSONObject, fallbackType: Int, previous: QQCredential? = null): QQCredential {
        val data = root.optJSONObject("data") ?: root
        val id = data.optLong("musicid").takeIf { it > 0 } ?: data.optString("str_musicid").toLongOrNull() ?: 0
        val key = data.optString("musickey")
        check(id > 0 && key.isNotBlank()) { data.optString("msg").ifBlank { "登录凭证无效" } }
        fun text(name: String, old: String?) = data.optString(name).ifBlank { old.orEmpty() }
        fun number(name: String, old: Long?) = data.optLong(name).takeIf { it > 0 } ?: old ?: 0
        return QQCredential(
            musicId = id,
            musicKey = key,
            encryptUin = text("encryptUin", previous?.encryptUin),
            loginType = data.optInt("loginType").takeIf { it > 0 } ?: previous?.loginType ?: fallbackType,
            nickname = text("nick", previous?.nickname),
            avatarUrl = text("avatar", previous?.avatarUrl),
            refreshKey = text("refresh_key", previous?.refreshKey),
            refreshToken = text("refresh_token", previous?.refreshToken),
            accessToken = text("access_token", previous?.accessToken),
            openId = text("openid", previous?.openId),
            unionId = text("unionid", previous?.unionId),
            expiredAt = number("expired_at", previous?.expiredAt),
            keyExpiresIn = number("keyExpiresIn", previous?.keyExpiresIn),
            musicKeyCreateTime = number("musickeyCreateTime", previous?.musicKeyCreateTime),
        )
    }

    /** 扫码成功：凭证刚下发，算作刚续签过。 */
    fun markLoginFresh() {
        prefs.edit().putLong("lastRefreshAt", System.currentTimeMillis()).apply()
    }

    enum class RefreshResult { OK, EXPIRED, FAILED }

    private fun refreshDue(auth: QQCredential): Boolean {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastRefreshAt", 0L) >= REFRESH_INTERVAL_MS) return true
        if (auth.musicKeyCreateTime <= 0 || auth.keyExpiresIn <= 0) return false
        // 服务器给的是秒
        return now / 1000 >= auth.musicKeyCreateTime + auth.keyExpiresIn * 2 / 3
    }

    /** 到期才续；没到期只比时间戳。老登录没有续签材料时什么都不做（key 可能还有效）。任意后台线程可调。 */
    fun refreshIfDueBlocking() {
        val auth = credential ?: return
        if (auth.refreshKey.isBlank() || !refreshDue(auth)) return
        refreshSessionBlocking(authFailed = false)
    }

    suspend fun refreshIfDue() = withContext(Dispatchers.IO) { refreshIfDueBlocking() }

    /**
     * 续签（同 QQMusicApi refresh_credential：LoginServer.Login + refresh_key/refresh_token，loginMode 2）。
     * [authFailed] 表示接口刚报登录失效：这时没有续签材料或续签被拒，就清掉本地登录并回调 [onSessionExpired]。
     */
    fun refreshSessionBlocking(authFailed: Boolean): RefreshResult = synchronized(refreshLock) {
        val auth = credential ?: return RefreshResult.EXPIRED
        if (System.currentTimeMillis() - prefs.getLong("lastRefreshAt", 0L) < REFRESH_DEDUP_MS) return RefreshResult.OK
        var code = 0
        val result = if (auth.refreshKey.isBlank()) {
            RefreshResult.EXPIRED
        } else {
            val param = JSONObject()
                .put("openid", auth.openId)
                .put("access_token", auth.accessToken)
                .put("refresh_token", auth.refreshToken)
                .put("expired_in", auth.expiredAt)
                .put("str_musicid", auth.musicId.toString())
                .put("musicid", auth.musicId)
                .put("musickey", auth.musicKey)
                .put("unionid", auth.unionId)
                .put("refresh_key", auth.refreshKey)
                .put("loginMode", 2)
            try {
                val data = rawCgi(
                    LOGIN_MODULE, "Login", param,
                    androidIdentity.commonParams(auth, mapOf("tmeLoginType" to auth.loginType)),
                    QQAndroidIdentity.USER_AGENT,
                )
                storeCredential(credentialFrom(data, auth.loginType, auth))
                RefreshResult.OK
            } catch (e: ApiException) {
                code = e.code
                if (e.code in AUTH_EXPIRED_CODES) RefreshResult.EXPIRED else RefreshResult.FAILED
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed: ${e.message}")
                RefreshResult.FAILED
            }
        }
        Log.i(TAG, "refresh result=$result code=$code material=${auth.refreshKey.isNotBlank()} authFailed=$authFailed")
        when {
            result == RefreshResult.OK -> markLoginFresh()
            result == RefreshResult.EXPIRED && authFailed -> {
                clearAuthCookies()
                onSessionExpired?.invoke()
            }
        }
        result
    }

    /** 已登录却报登录失效：先续签再重试一次；续签救不回来则由 [refreshSessionBlocking] 退登。 */
    private inline fun withAuthRetry(module: String, request: () -> JSONObject): JSONObject = try {
        request()
    } catch (e: ApiException) {
        if (e.code !in AUTH_EXPIRED_CODES || !isLoggedIn || module == LOGIN_MODULE) throw e
        Log.i(TAG, "auth error ${e.code} on $module, refreshing")
        if (refreshSessionBlocking(authFailed = true) == RefreshResult.OK) request() else throw e
    }

    fun updateProfile(nickname: String, avatarUrl: String) {
        val current = credential ?: return
        storeCredential(current.copy(nickname = nickname, avatarUrl = avatarUrl))
    }

    fun clearAuthCookies() {
        credential = null
        prefs.edit()
            .remove("musicId").remove("musicKey").remove("encryptUin").remove("loginType")
            .remove("nickname").remove("avatarUrl")
            .remove("refreshKey").remove("refreshToken").remove("accessToken").remove("openId").remove("unionId")
            .remove("expiredAt").remove("keyExpiresIn").remove("musicKeyCreateTime").remove("lastRefreshAt")
            .apply()
        cookieStore.clear()
    }

    suspend fun cgi(module: String, method: String, param: JSONObject = JSONObject(), comm: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (EngineLink.isUi) {
                remote(KIND_CGI, module, method, param) { comm?.let { putString(NET_EXTRA, it.toString()) } }
            } else {
                cgiBlocking(module, method, param, comm)
            }
        }

    suspend fun cgiAndroid(
        module: String,
        method: String,
        param: JSONObject = JSONObject(),
        overrides: Map<String, Any?> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) {
            remote(KIND_ANDROID, module, method, param) {
                putString(NET_EXTRA, JSONObject().apply { overrides.forEach { (k, v) -> if (v != null) put(k, v) } }.toString())
            }
        } else {
            cgiAndroidBlocking(module, method, param, overrides)
        }
    }

    // ---------- 拆进程：界面 → 引擎的请求转发 ----------

    /**
     * 界面进程不持有凭证、Cookie、安卓身份：请求整份交给 :music 里的引擎发，comm 拼装、续签重试、退登都只在那边发生。
     * 引擎报的业务码原样还原成 [ApiException]，界面里的错误提示、搜索重试判断不变。
     */
    private inline fun remote(kind: String, module: String, method: String, param: JSONObject, extra: Bundle.() -> Unit = {}): JSONObject {
        val args = Bundle().apply {
            putString(NET_KIND, kind)
            putString(NET_MODULE, module)
            putString(NET_METHOD, method)
            extra()
            EngineLink.putText(this, param.toString())
        }
        val text = EngineLink.readText(engineCall(NET_COMMAND, args)).orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** 界面进程调引擎：引擎的业务错误还原成 [ApiException]，引擎没起来/崩了当网络错误。 */
    internal fun engineCall(method: String, args: Bundle = Bundle()): Bundle = try {
        EngineLink.UiSide.call(method, args)
    } catch (e: EngineLink.EngineCallException) {
        throw ApiException(e.code, e.message.orEmpty())
    } catch (e: EngineLink.EngineUnavailable) {
        throw IOException(e.message, e)
    }

    /** 引擎侧：接住界面转来的请求（跑在 Binder 线程，本来就是阻塞调用）。 */
    fun serveRemote(args: Bundle): Bundle {
        val param = JSONObject(EngineLink.readText(args) ?: "{}")
        val module = args.getString(NET_MODULE).orEmpty()
        val method = args.getString(NET_METHOD).orEmpty()
        val extra = args.getString(NET_EXTRA)
        val result = when (args.getString(NET_KIND)) {
            KIND_ANDROID -> {
                val o = JSONObject(extra ?: "{}")
                cgiAndroidBlocking(module, method, param, o.keys().asSequence().associateWith { o.get(it) })
            }
            KIND_GET -> getJsonBlocking(module)
            else -> cgiBlocking(module, method, param, extra?.let(::JSONObject))
        }
        return EngineLink.text(result.toString())
    }

    const val NET_COMMAND = "net.request"
    private const val NET_KIND = "kind"
    private const val NET_MODULE = "module"
    private const val NET_METHOD = "method"
    private const val NET_EXTRA = "extra"
    private const val KIND_CGI = "cgi"
    private const val KIND_ANDROID = "android"
    private const val KIND_GET = "get"

    fun cgiAndroidBlocking(
        module: String,
        method: String,
        param: JSONObject = JSONObject(),
        overrides: Map<String, Any?> = emptyMap(),
    ): JSONObject = withAuthRetry(module) {
        // comm 每次现取凭证，续签后重试用的是新 key
        rawCgi(module, method, param, androidIdentity.commonParams(credential, overrides), QQAndroidIdentity.USER_AGENT)
    }

    fun cgiBlocking(
        module: String,
        method: String,
        param: JSONObject = JSONObject(),
        comm: JSONObject? = null,
        androidUserAgent: String? = null,
    ): JSONObject = withAuthRetry(module) { rawCgi(module, method, param, comm ?: commonParams(), androidUserAgent) }

    /**
     * [androidUserAgent] 非空表示 comm 是安卓 App 身份（ct=11）：请求头也必须是 QQ 音乐安卓 App 的，
     * 身份前后矛盾会被风控时拦时放（登录换凭证尤其明显），与 QQMusicApi 一致。
     */
    private fun rawCgi(
        module: String,
        method: String,
        param: JSONObject,
        comm: JSONObject,
        androidUserAgent: String?,
    ): JSONObject {
        val payload = JSONObject()
            .put("comm", comm)
            .put("req_0", JSONObject().put("module", module).put("method", method).put("param", param))
        val request = Request.Builder()
            .url(CGI_URL)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply {
                if (androidUserAgent != null) {
                    header("User-Agent", androidUserAgent)
                } else {
                    header("User-Agent", USER_AGENT).header("Referer", "https://y.qq.com/")
                }
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            val root = JSONObject(response.body?.string().orEmpty())
            val item = root.optJSONObject("req_0") ?: throw ApiException(-1, "QQ 音乐返回为空")
            val code = item.optInt("code")
            if (code != 0) throw ApiException(code, errorMessage(code, item))
            return item.optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) remote(KIND_GET, url, "", JSONObject()) else getJsonBlocking(url)
    }

    private fun getJsonBlocking(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", "https://y.qq.com/").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    suspend fun postForm(url: String, values: Map<String, String>, followRedirects: Boolean = true): okhttp3.Response =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
            val client = if (followRedirects) http else http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            client.newCall(Request.Builder().url(url).post(body).header("User-Agent", USER_AGENT).build()).execute()
        }

    fun commonParams(overrides: Map<String, Any?> = emptyMap()): JSONObject {
        val auth = credential
        return JSONObject().apply {
            put("ct", "24")
            put("cv", "4747474")
            put("platform", "yqq.json")
            put("format", "json")
            put("inCharset", "utf-8")
            put("outCharset", "utf-8")
            put("notice", "0")
            put("needNewCode", "1")
            put("uin", auth?.musicId ?: 0)
            put("g_tk", auth?.musicKey?.let(::hash33) ?: 5381)
            auth?.let {
                put("qq", it.musicId.toString())
                put("authst", it.musicKey)
                put("tmeLoginType", it.loginType)
            }
            overrides.forEach { (key, value) -> if (value != null) put(key, value) }
        }
    }

    /** 错误码含义取自 QQMusicApi（core/response.py、modules/login.py）；服务器给了说明就优先用。 */
    internal fun errorMessage(code: Int, item: JSONObject): String {
        val known = when (code) {
            1000, 104400, 104401 -> "登录已失效，请重新扫码"
            2001 -> "QQ 音乐判定请求异常（风控），请稍后再试"
            20261 -> "登录参数错误"
            20272 -> "账号绑定异常"
            20274 -> "账号未绑定 QQ 音乐"
            20277, 20278 -> "账号登录受限"
            20279 -> "登录设备数已达上限，请先在其他设备退出"
            20450 -> "账号已被封禁"
            104604 -> "登录太频繁，请稍后再试"
            else -> null
        }
        val server = item.optString("message").ifEmpty { item.optString("msg") }
        return "${known ?: server.ifEmpty { "接口错误" }} ($code)"
    }

    fun hash33(value: String, seed: Int = 5381): Int {
        var hash = seed.toLong()
        value.forEach { hash += (hash shl 5) + it.code }
        return (hash and 0x7fffffff).toInt()
    }
}
