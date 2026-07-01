package com.dex.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 Google versionhistory API 获取最新 Chrome 稳定版号，
 * 用于动态填充 User-Agent 中的 %VER% 占位。
 */
object VersionUpdater {

    private const val API_URL =
        "https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions"

    suspend fun fetchLatestChromeVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) return@withContext null

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val versions = json.getJSONArray("versions")
            if (versions.length() > 0) {
                versions.getJSONObject(0).getString("version")
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
