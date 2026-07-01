package com.dex.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * 轻量级广告拦截器——用域名黑名单拦截已知广告/跟踪域名。
 * 对匹配到的请求直接返回空响应，WebView 不会发出网络请求。
 */
class AdBlocker(context: Context) {

    private val blockedDomains = HashSet<String>(4096)
    var enabled: Boolean = true

    companion object {
        private val EMPTY_RESPONSE = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )

        /** 内置的广告/追踪域名列表（常见的） */
        private val BUILT_IN = arrayOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "facebook.com/tr", "connect.facebook.net",
            "analytics.twitter.com", "ads-twitter.com",
            "ads.yahoo.com", "advertising.com",
            "adnxs.com", "adsrvr.org", "adform.net",
            "moatads.com", "adsymptotic.com",
            "taboola.com", "outbrain.com",
            "amazon-adsystem.com", "aax.amazon-adsystem.com",
            "scorecardresearch.com", "quantserve.com",
            "pubmatic.com", "criteo.com", "criteo.net",
            "rubiconproject.com", "casalemedia.com",
            "openx.net", "bidswitch.net",
            "medianet.com", "media.net",
            "tradedoubler.com", "awin1.com",
            "hotjar.com", "fullstory.com",
            "segment.io", "segment.com",
            "mixpanel.com", "amplitude.com",
            "chartbeat.com", "optimizely.com",
            "crazyegg.com", "clicktale.net",
            "ad.doubleclick.net", "stats.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "securepubads.g.doubleclick.net",
            "cnzz.com", "51.la", "hm.baidu.com",
            "cpro.baidu.com", "pos.baidu.com",
            "s.union.360.cn", "miaozhen.com",
            "tanx.com", "mmstat.com",
            "yt-video-annotations", "youtubei.googleapis.com/ads"
        )
    }

    init {
        BUILT_IN.forEach { blockedDomains.add(it) }
        // 可从本地文件加载自定义规则
        loadCustomRules(context)
    }

    fun shouldBlock(url: String): Boolean {
        if (!enabled) return false
        return try {
            val host = URI(url).host ?: return false
            isBlocked(host)
        } catch (e: Exception) {
            false
        }
    }

    fun createBlockedResponse(): WebResourceResponse = EMPTY_RESPONSE

    fun addCustomRule(domain: String) {
        blockedDomains.add(domain.lowercase().trim())
    }

    fun removeRule(domain: String) {
        blockedDomains.remove(domain.lowercase().trim())
    }

    private fun isBlocked(host: String): Boolean {
        val h = host.lowercase()
        if (blockedDomains.contains(h)) return true
        // 检查父域名
        val parts = h.split(".")
        for (i in 1 until parts.size - 1) {
            val parent = parts.subList(i, parts.size).joinToString(".")
            if (blockedDomains.contains(parent)) return true
        }
        return false
    }

    private fun loadCustomRules(context: Context) {
        val prefs = context.getSharedPreferences("adblock_rules", Context.MODE_PRIVATE)
        val custom = prefs.getStringSet("custom_domains", emptySet()) ?: emptySet()
        custom.forEach { blockedDomains.add(it) }
    }

    fun saveCustomRules(context: Context, rules: Set<String>) {
        context.getSharedPreferences("adblock_rules", Context.MODE_PRIVATE)
            .edit().putStringSet("custom_domains", rules).apply()
    }
}
