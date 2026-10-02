package com.cloudmusic.car.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.paopao.music.link.EngineLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ApiException(val code: Int, message: String) : IOException(message), EngineLink.CodedException {
    override val errorCode: Int get() = code
}

/**
 * 网易云音乐传输层：管理 Cookie，执行 weapi / eapi 加密请求。
 * 移植自 Kumone 的 NeteaseClient.swift。
 */
object NeteaseClient {
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val TAG = "NeteaseAuth"

    // 与 NeteaseCloudMusicApi 的 osMap.pc 一致
    private const val OS = "pc"
    private const val APPVER = "3.1.17.204416"
    private const val OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val CHANNEL = "netease"

    // 旧版本所有车机共用的设备号；升级前已登录的会话沿用到下次登录，免得升级就被踢
    private const val LEGACY_DEVICE_ID = "cloudmusic"

    /** 续签间隔：车机桌面进程常驻好几天，不能只在启动时续一次。 */
    const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val REFRESH_DEDUP_MS = 60 * 1000L

    private val AUTH_PATHS = setOf(
        "/login/token/refresh", "/logout", "/login/qrcode/unikey", "/login/qrcode/client/login",
    )

    private lateinit var prefs: SharedPreferences
    /** 设备号、上次续签时间；不能放 netease_cookies，那里每个键都会被当成 Cookie 发出去。 */
    private lateinit var session: SharedPreferences
    private val cookies = mutableMapOf<String, String>()
    private val refreshLock = Any()
    @Volatile private var deviceId = LEGACY_DEVICE_ID

    /** 服务端判定登录失效（续签也被拒）后回调；本地 Cookie 已清掉。 */
    @Volatile var onSessionExpired: (() -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("netease_cookies", Context.MODE_PRIVATE)
        session = context.getSharedPreferences("netease_session", Context.MODE_PRIVATE)
        synchronized(cookies) {
            prefs.all.forEach { (k, v) -> if (v is String) cookies[k] = v }
        }
        deviceId = session.getString("deviceId", null)
            ?: (if (isLoggedIn) LEGACY_DEVICE_ID else newDeviceId()).also {
                session.edit().putString("deviceId", it).apply()
            }
        if (cookie("_ntes_nuid") == null) {
            val nuid = randomHex(32, upper = false)
            setCookies(mapOf(
                "_ntes_nuid" to nuid,
                "_ntes_nnid" to "$nuid,${System.currentTimeMillis()}",
                "WNMCID" to "${randomLetters(6)}.${System.currentTimeMillis()}.01.0",
                "WEVNSM" to "1.0.0",
            ))
        }
    }

    private fun newDeviceId() = randomHex(52, upper = true)

    private fun randomHex(length: Int, upper: Boolean): String {
        val chars = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
    }

    private fun randomLetters(length: Int): String =
        buildString { repeat(length) { append('a' + Random.nextInt(26)) } }

    val isLoggedIn: Boolean get() = cookie("MUSIC_U") != null

    fun cookie(name: String): String? = synchronized(cookies) { cookies[name] }

    private fun setCookies(new: Map<String, String>) {
        synchronized(cookies) { cookies.putAll(new) }
        prefs.edit().apply { new.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    fun clearAuthCookies() {
        synchronized(cookies) {
            cookies.remove("MUSIC_U")
            cookies.remove("__csrf")
        }
        prefs.edit().remove("MUSIC_U").remove("__csrf").apply()
        // 下次扫码换一台自己的设备号
        deviceId = newDeviceId()
        session.edit().putString("deviceId", deviceId).remove("lastRefreshAt").apply()
    }

    /** 扫码成功：Cookie 刚下发，算作刚续签过。 */
    fun markLoginFresh() {
        session.edit().putLong("lastRefreshAt", System.currentTimeMillis()).apply()
    }

    enum class RefreshResult { OK, EXPIRED, FAILED }

    /** 距上次续签超过 [REFRESH_INTERVAL_MS] 才续；没到期只比一次时间戳。可在任意后台线程调用。 */
    fun refreshIfDueBlocking() {
        if (!isLoggedIn) return
        val last = session.getLong("lastRefreshAt", 0L)
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) return
        refreshSessionBlocking()
    }

    suspend fun refreshIfDue() = withContext(Dispatchers.IO) { refreshIfDueBlocking() }

    /**
     * 续签登录态（与 NeteaseCloudMusicApi login_refresh 一致走 eapi，失败再试一次 weapi）。
     * 续签明确返回 301 说明 MUSIC_U 已作废：清掉本地登录并回调 [onSessionExpired]。
     */
    fun refreshSessionBlocking(): RefreshResult = synchronized(refreshLock) {
        if (!isLoggedIn) return RefreshResult.EXPIRED
        val last = session.getLong("lastRefreshAt", 0L)
        if (System.currentTimeMillis() - last < REFRESH_DEDUP_MS) return RefreshResult.OK
        val codes = mutableListOf<Int>()
        val result = runCatching {
            val eapiCode = eapiRaw("/login/token/refresh", JSONObject(), emptyMap()).optInt("code", 200)
            codes += eapiCode
            if (eapiCode == 200) return@runCatching RefreshResult.OK
            val weapiCode = weapiRaw("/login/token/refresh", JSONObject(), emptyMap()).optInt("code", 200)
            codes += weapiCode
            when {
                weapiCode == 200 -> RefreshResult.OK
                eapiCode == 301 && weapiCode == 301 -> RefreshResult.EXPIRED
                else -> RefreshResult.FAILED
            }
        }.getOrElse {
            Log.w(TAG, "refresh failed: ${it.message}")
            RefreshResult.FAILED
        }
        Log.i(TAG, "refresh result=$result codes=$codes device=${if (deviceId == LEGACY_DEVICE_ID) "legacy" else "own"}")
        when (result) {
            RefreshResult.OK -> markLoginFresh()
            RefreshResult.EXPIRED -> {
                clearAuthCookies()
                onSessionExpired?.invoke()
            }
            RefreshResult.FAILED -> Unit
        }
        result
    }

    private fun cookieHeader(overrides: Map<String, String>): String {
        val all = synchronized(cookies) { cookies.toMutableMap() }
        all["os"] = OS
        all["appver"] = APPVER
        all["osver"] = OSVER
        all["channel"] = CHANNEL
        all["deviceId"] = deviceId
        all["__remember_me"] = "true"
        all.putAll(overrides)
        return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun weapi(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) remote("weapi", path, payload, cookieOverrides) else weapiBlocking(path, payload, cookieOverrides)
    }

    suspend fun eapi(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        if (EngineLink.isUi) remote("eapi", path, payload, cookieOverrides) else eapiBlocking(path, payload, cookieOverrides)
    }

    /**
     * 界面进程不持有 Cookie：请求整份交给 :music 里的引擎发，Cookie、续签、退登都只在那边发生。
     * 引擎报的业务码原样还原成 [ApiException]，界面里的错误提示不变。
     */
    private fun remote(kind: String, path: String, payload: JSONObject, cookieOverrides: Map<String, String>): JSONObject {
        val args = Bundle().apply {
            putString(NET_KIND, kind)
            putString(NET_PATH, path)
            putString(NET_COOKIES, JSONObject(cookieOverrides).toString())
            EngineLink.putText(this, payload.toString())
        }
        val reply = try {
            EngineLink.UiSide.call(NET_COMMAND, args)
        } catch (e: EngineLink.EngineCallException) {
            throw ApiException(e.code, e.message.orEmpty())
        } catch (e: EngineLink.EngineUnavailable) {
            throw IOException(e.message, e)
        }
        val text = EngineLink.readText(reply).orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** 引擎侧：接住界面转来的请求。 */
    fun serveRemote(args: Bundle): Bundle {
        val path = requireNotNull(args.getString(NET_PATH))
        val payload = JSONObject(EngineLink.readText(args) ?: "{}")
        val overrides = JSONObject(args.getString(NET_COOKIES) ?: "{}").let { o ->
            o.keys().asSequence().associateWith { o.getString(it) }
        }
        val result = when (args.getString(NET_KIND)) {
            "eapi" -> eapiBlocking(path, payload, overrides)
            else -> weapiBlocking(path, payload, overrides)
        }
        return EngineLink.text(result.toString())
    }

    const val NET_COMMAND = "net.request"
    private const val NET_KIND = "kind"
    private const val NET_PATH = "path"
    private const val NET_COOKIES = "cookies"

    fun weapiBlocking(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withAuthRetry(path) { weapiRaw(path, payload, cookieOverrides) }

    fun eapiBlocking(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withAuthRetry(path) { eapiRaw(path, payload, cookieOverrides) }

    /** 已登录却收到 301：先续签一次再重试；续签被拒则由 [refreshSessionBlocking] 退登。 */
    private inline fun withAuthRetry(path: String, request: () -> JSONObject): JSONObject {
        val first = request()
        if (first.optInt("code", 200) != 301 || !isLoggedIn || path.substringBefore('?') in AUTH_PATHS) return first
        Log.i(TAG, "301 on $path, refreshing")
        return if (refreshSessionBlocking() == RefreshResult.OK) request() else first
    }

    private fun weapiRaw(
        path: String,
        payload: JSONObject,
        cookieOverrides: Map<String, String>,
    ): JSONObject {
        val csrf = cookie("__csrf").orEmpty()
        payload.put("csrf_token", csrf)
        var fullPath = path
        if (csrf.isNotEmpty()) fullPath += (if ("?" in fullPath) "&" else "?") + "csrf_token=$csrf"
        val form = NeteaseCrypto.weapi(payload.toString())
        return post("https://music.163.com/weapi$fullPath", form, cookieOverrides)
    }

    private fun eapiRaw(
        path: String,
        payload: JSONObject,
        cookieOverrides: Map<String, String>,
    ): JSONObject {
        val apiPath = "/api$path"
        val header = JSONObject().apply {
            put("os", OS)
            put("appver", APPVER)
            put("osver", OSVER)
            put("deviceId", deviceId)
            put("requestId", Random.nextInt(20_000_000, 30_000_000).toString())
            put("clientSign", "")
            put("versioncode", "140")
            put("buildver", (System.currentTimeMillis() / 1000).toString())
            put("resolution", "1920x1080")
            put("channel", CHANNEL)
            cookie("MUSIC_U")?.let { put("MUSIC_U", it) }
            cookie("__csrf")?.let { put("__csrf", it) }
        }
        payload.put("header", header)
        val form = NeteaseCrypto.eapi(apiPath, payload.toString())
        return post("https://interface.music.163.com/eapi$path", form, cookieOverrides)
    }

    private fun post(url: String, form: Map<String, String>, cookieOverrides: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .header("Cookie", cookieHeader(cookieOverrides))
            .build()
        http.newCall(request).execute().use { response ->
            absorbSetCookies(response.headers("Set-Cookie"))
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            val text = response.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun absorbSetCookies(headers: List<String>) {
        val parsed = mutableMapOf<String, String>()
        for (raw in headers) {
            val pair = raw.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (value.isNotEmpty() && value != "\"\"") parsed[name] = value
        }
        if (parsed.isNotEmpty()) setCookies(parsed)
    }

    /** 检查业务码：非 200 抛出异常。 */
    fun JSONObject.checked(): JSONObject {
        val code = optInt("code", 200)
        if (code != 200) {
            val message = optString("message").ifEmpty { optString("msg") }
            throw ApiException(code, if (code == 301) "需要登录" else message.ifEmpty { "接口错误 ($code)" })
        }
        return this
    }
}
