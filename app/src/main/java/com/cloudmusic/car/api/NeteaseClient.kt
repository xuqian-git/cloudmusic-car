package com.cloudmusic.car.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ApiException(val code: Int, message: String) : IOException(message)

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

    private lateinit var prefs: SharedPreferences
    private val cookies = mutableMapOf<String, String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("netease_cookies", Context.MODE_PRIVATE)
        synchronized(cookies) {
            prefs.all.forEach { (k, v) -> if (v is String) cookies[k] = v }
        }
    }

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
    }

    private fun cookieHeader(overrides: Map<String, String>): String {
        val all = synchronized(cookies) { cookies.toMutableMap() }
        all.putIfAbsent("os", "pc")
        all.putIfAbsent("appver", "3.1.17")
        all.putAll(overrides)
        return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun weapi(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) { weapiBlocking(path, payload, cookieOverrides) }

    suspend fun eapi(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) { eapiBlocking(path, payload, cookieOverrides) }

    fun weapiBlocking(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject {
        val csrf = cookie("__csrf").orEmpty()
        payload.put("csrf_token", csrf)
        var fullPath = path
        if (csrf.isNotEmpty()) fullPath += (if ("?" in fullPath) "&" else "?") + "csrf_token=$csrf"
        val form = NeteaseCrypto.weapi(payload.toString())
        return post("https://music.163.com/weapi$fullPath", form, cookieOverrides)
    }

    fun eapiBlocking(
        path: String,
        payload: JSONObject = JSONObject(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): JSONObject {
        val apiPath = "/api$path"
        val header = JSONObject().apply {
            put("os", "pc")
            put("appver", "3.1.17")
            put("osver", "Version 14.0 (Build 23A344)")
            put("deviceId", "cloudmusic")
            put("requestId", Random.nextInt(20_000_000, 30_000_000).toString())
            put("clientSign", "")
            put("versioncode", "140")
            put("buildver", (System.currentTimeMillis() / 1000).toString())
            put("resolution", "1920x1080")
            put("channel", "")
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
