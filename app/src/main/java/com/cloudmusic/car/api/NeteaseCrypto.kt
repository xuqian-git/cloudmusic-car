package com.cloudmusic.car.api

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐请求加密（weapi / eapi），移植自 Kumone 的 NeteaseCrypto.swift。
 *
 * weapi：对 JSON 做两轮 AES-128-CBC —— 先用预置 key，再用客户端自选的 secret key。
 * secret key 本应每次 RSA 加密，这里沿用 Kumone 的做法：固定 key + 预先算好的 RSA 密文。
 *
 * eapi：对 "url + payload + md5" 做 AES-128-ECB，输出大写 hex。
 */
object NeteaseCrypto {
    private const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_IV = "0102030405060708"
    // 与下方预计算的 RSA 密文配对，不能改动
    private const val WEAPI_SECRET_KEY = "kumone2026abcDEF"
    private const val WEAPI_ENC_SEC_KEY =
        "38cef2efdbcc1cfd6a44d81620dae5d23091f50ef27e01a1b1bb7e998e0fde2d" +
            "7ab6002a9e79a3c195f661cbde80e21e6245997b11b54d28407115822f95d447" +
            "7cc06b5a77de46fab6568410abf1229abef81b4c8588f386149010d190bb0b04" +
            "f064be330bd877a4d4b99514febbdb4335b10744b13d9f7ee24d314d6e62cdc9"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    fun weapi(json: String): Map<String, String> {
        val encoder = Base64.getEncoder()
        val first = encoder.encodeToString(aes(json.toByteArray(), WEAPI_PRESET_KEY, WEAPI_IV))
        val second = encoder.encodeToString(aes(first.toByteArray(), WEAPI_SECRET_KEY, WEAPI_IV))
        return mapOf("params" to second, "encSecKey" to WEAPI_ENC_SEC_KEY)
    }

    /** @param apiPath 内部接口路径，例如 "/api/song/enhance/player/url/v1" */
    fun eapi(apiPath: String, json: String): Map<String, String> {
        val digest = md5Hex("nobody${apiPath}use${json}md5forencrypt")
        val data = "$apiPath-36cd479b6b5-$json-36cd479b6b5-$digest"
        val encrypted = aes(data.toByteArray(), EAPI_KEY, iv = null)
        return mapOf("params" to encrypted.joinToString("") { "%02X".format(it) })
    }

    private fun aes(data: ByteArray, key: String, iv: String?): ByteArray {
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = if (iv != null) {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv.toByteArray()))
            }
        } else {
            Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, keySpec) }
        }
        return cipher.doFinal(data)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
