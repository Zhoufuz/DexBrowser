package com.dex.browser

import android.webkit.WebView

data class BrowserTab(
    val id: Int,
    var title: String = "新标签",
    var url: String = "",
    var webView: WebView? = null
)

/**
 * 多标签管理器。
 * 保持 WebView 实例在内存中（最多限制数量防 OOM），
 * 切换标签时只做 addView/removeView。
 */
class TabManager {
    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private var idCounter = 0

    val tabCount: Int get() = tabs.size
    val currentTab: BrowserTab? get() = tabs.getOrNull(activeIndex)
    val currentIndex: Int get() = activeIndex
    val allTabs: List<BrowserTab> get() = tabs.toList()

    fun addTab(webView: WebView, url: String = ""): BrowserTab {
        val tab = BrowserTab(id = ++idCounter, url = url, webView = webView)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        return tab
    }

    fun switchTo(index: Int): BrowserTab? {
        if (index < 0 || index >= tabs.size) return null
        activeIndex = index
        return tabs[activeIndex]
    }

    fun closeTab(index: Int): BrowserTab? {
        if (index < 0 || index >= tabs.size) return null
        val removed = tabs.removeAt(index)
        removed.webView?.destroy()
        removed.webView = null

        // 调整 activeIndex
        if (tabs.isEmpty()) {
            activeIndex = -1
            return null
        }
        if (activeIndex >= tabs.size) {
            activeIndex = tabs.size - 1
        }
        return tabs.getOrNull(activeIndex)
    }

    fun closeAll() {
        tabs.forEach {
            it.webView?.destroy()
            it.webView = null
        }
        tabs.clear()
        activeIndex = -1
    }

    fun updateTitle(title: String) {
        currentTab?.title = title.ifBlank { currentTab?.url ?: "新标签" }
    }

    fun updateUrl(url: String) {
        currentTab?.url = url
    }

    fun indexOf(tab: BrowserTab): Int = tabs.indexOf(tab)
}
