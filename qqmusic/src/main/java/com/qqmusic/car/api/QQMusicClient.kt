package com.qqmusic.car.api

import android.content.Context
import android.content.SharedPreferences
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

class ApiException(val code: Int, message: String) : IOException(message)

data class QQCredential(
    val musicId: Long,
    val musicKey: String,
    val encryptUin: String,
    val loginType: Int,
    val nickname: String = "",
    val avatarUrl: String = "",
)

object QQMusicClient {
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
            .apply()
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
            .apply()
        cookieStore.clear()
    }

    suspend fun cgi(module: String, method: String, param: JSONObject = JSONObject(), comm: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { cgiBlocking(module, method, param, comm) }

    suspend fun cgiAndroid(
        module: String,
        method: String,
        param: JSONObject = JSONObject(),
        overrides: Map<String, Any?> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        cgiBlocking(module, method, param, androidIdentity.commonParams(credential, overrides))
    }

    fun cgiAndroidBlocking(
        module: String,
        method: String,
        param: JSONObject = JSONObject(),
        overrides: Map<String, Any?> = emptyMap(),
    ): JSONObject = cgiBlocking(module, method, param, androidIdentity.commonParams(credential, overrides))

    fun cgiBlocking(module: String, method: String, param: JSONObject = JSONObject(), comm: JSONObject? = null): JSONObject {
        val payload = JSONObject()
            .put("comm", comm ?: commonParams())
            .put("req_0", JSONObject().put("module", module).put("method", method).put("param", param))
        val request = Request.Builder()
            .url(CGI_URL)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            val root = JSONObject(response.body?.string().orEmpty())
            val item = root.optJSONObject("req_0") ?: throw ApiException(-1, "QQ 音乐返回为空")
            val code = item.optInt("code")
            if (code != 0) throw ApiException(code, item.optString("msg").ifEmpty { "接口错误 ($code)" })
            return item.optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", "https://y.qq.com/").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "网络错误 (${response.code})")
            JSONObject(response.body?.string().orEmpty())
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

    fun hash33(value: String, seed: Int = 5381): Int {
        var hash = seed.toLong()
        value.forEach { hash += (hash shl 5) + it.code }
        return (hash and 0x7fffffff).toInt()
    }
}
