package com.dex.browser

import android.content.Context
import android.content.SharedPreferences

class BrowserSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dex_browser_settings", Context.MODE_PRIVATE)

    companion object {
        const val UA_CHROME = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%VER% Mobile Safari/537.36"
        const val UA_FIREFOX = "Mozilla/5.0 (Android 14; Mobile; rv:%VER%) Gecko/%VER% Firefox/%VER%"
        const val UA_EDGE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%VER% Mobile Safari/537.36 EdgA/%VER%"
        const val UA_DEFAULT = UA_CHROME

        val TIMEZONE_OPTIONS = arrayOf(
            "UTC+8 北京/上海" to "Asia/Shanghai",
            "UTC+9 东京" to "Asia/Tokyo",
            "UTC+9 首尔" to "Asia/Seoul",
            "UTC+8 新加坡" to "Asia/Singapore",
            "UTC+8 香港" to "Asia/Hong_Kong",
            "UTC+10 悉尼" to "Australia/Sydney",
            "UTC+3 莫斯科" to "Europe/Moscow",
            "UTC+1 巴黎" to "Europe/Paris",
            "UTC+1 柏林" to "Europe/Berlin",
            "UTC+0 伦敦" to "Europe/London",
            "UTC-5 纽约" to "America/New_York",
            "UTC-8 洛杉矶" to "America/Los_Angeles"
        )
    }

    private var _dsAuth: String? = null

    init {
        _dsAuth = prefs.getString("ds_auth_v3", "").takeIf { !it.isNullOrEmpty() }
    }

    fun getDsAuth(): String = _dsAuth ?: ""
    fun setDsAuth(value: String) {
        _dsAuth = value
        prefs.edit().putString("ds_auth_v3", value).apply()
    }

    var translateUrl: String
        get() = prefs.getString("translate_url_v3", "https://api.deepseek.com/chat/completions") ?: "https://api.deepseek.com/chat/completions"
        set(value) = prefs.edit().putString("translate_url_v3", value).apply()

    var translateLang: String
        get() = prefs.getString("translate_lang_v3", "中文") ?: "中文"
        set(value) = prefs.edit().putString("translate_lang_v3", value).apply()

    var userAgentTemplate: String
        get() = prefs.getString("ua_template", UA_DEFAULT) ?: UA_DEFAULT
        set(value) = prefs.edit().putString("ua_template", value).apply()

    var chromeVersion: String
        get() = prefs.getString("chrome_version", "131.0.6778.39") ?: "131.0.6778.39"
        set(value) = prefs.edit().putString("chrome_version", value).apply()

    fun buildUserAgent(): String = userAgentTemplate.replace("%VER%", chromeVersion)

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean("adblock_enabled", true)
        set(value) = prefs.edit().putBoolean("adblock_enabled", value).apply()

    var fingerprintEnabled: Boolean
        get() = prefs.getBoolean("fingerprint_enabled", false)
        set(value) = prefs.edit().putBoolean("fingerprint_enabled", value).apply()

    var spoofTimezone: String
        get() = prefs.getString("spoof_timezone", "Asia/Shanghai") ?: "Asia/Shanghai"
        set(value) = prefs.edit().putString("spoof_timezone", value).apply()

    var spoofLanguage: String
        get() = prefs.getString("spoof_language", "zh-CN") ?: "zh-CN"
        set(value) = prefs.edit().putString("spoof_language", value).apply()

    var homepage: String
        get() = prefs.getString("homepage", "https://www.bing.com") ?: "https://www.bing.com"
        set(value) = prefs.edit().putString("homepage", value).apply()

    var searchEngine: String
        get() = prefs.getString("search_engine", "https://www.google.com/search?q=%s") ?: "https://www.google.com/search?q=%s"
        set(value) = prefs.edit().putString("search_engine", value).apply()
}
