package com.dex.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var settings: BrowserSettings
    private lateinit var adBlocker: AdBlocker
    private lateinit var historyManager: HistoryManager
    private lateinit var tabManager: TabManager
    private lateinit var translateManager: TranslateManager
    private lateinit var webPermissionHandler: WebPermissionHandler
    private lateinit var fileUploadHandler: FileUploadHandler
    private lateinit var longPressMenu: LongPressMenu

    // Views
    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var btnMenu: ImageButton

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initModules()
        initViews()
        setupUrlBar()
        openNewTab(settings.homepage)
        updateChromeVersion()
    }

    private fun initModules() {
        settings = BrowserSettings(this)
        adBlocker = AdBlocker(this).also { it.enabled = settings.adBlockEnabled }
        historyManager = HistoryManager.getInstance(this)
        tabManager = TabManager()
        translateManager = TranslateManager(this, settings)
        webPermissionHandler = WebPermissionHandler(this)
        fileUploadHandler = FileUploadHandler(this)
        longPressMenu = LongPressMenu(this) { url -> openNewTab(url) }
    }

    private fun initViews() {
        webContainer = findViewById(R.id.web_container)
        urlBar = findViewById(R.id.url_bar)
        progressBar = findViewById(R.id.progress_bar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        btnHome = findViewById(R.id.btn_home)
        btnTabs = findViewById(R.id.btn_tabs)
        btnMenu = findViewById(R.id.btn_menu)

        btnBack.setOnClickListener { currentWebView()?.goBack() }
        btnForward.setOnClickListener { currentWebView()?.goForward() }
        btnReload.setOnClickListener { currentWebView()?.reload() }
        btnHome.setOnClickListener { currentWebView()?.loadUrl(settings.homepage) }
        btnTabs.setOnClickListener { showTabSwitcher() }
        btnMenu.setOnClickListener { showMainMenu() }
    }

    private fun setupUrlBar() {
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateTo(urlBar.text.toString())
                hideKeyboard()
                true
            } else false
        }
    }

    // ===== WebView 创建与配置 =====

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createWebView(): WebView {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = settings.buildUserAgent()
            setSupportMultipleWindows(false)
        }

        // JS Bridge: 剪贴板 + 划词翻译
        val bridge = DexJsBridge(this) { text ->
            runOnUiThread { translateManager.translateSelection(webView, text) }
        }
        webView.addJavascriptInterface(bridge, "DexBridge")
        webView.addJavascriptInterface(bridge, "DexTranslateBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlBar.setText(url)
                tabManager.updateUrl(url)
                progressBar.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.isVisible = false
                tabManager.updateTitle(view.title ?: url)
                historyManager.addEntry(view.title ?: "", url)

                // 注入剪贴板 polyfill
                view.evaluateJavascript(CLIPBOARD_POLYFILL_JS, null)
                // 注入翻译框架
                translateManager.injectScript(view)
                // 指纹伪装
                if (settings.fingerprintEnabled) {
                    val js = FingerprintSpoof.buildScript(
                        settings.spoofLanguage, settings.spoofTimezone
                    )
                    view.evaluateJavascript(js, null)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (adBlocker.shouldBlock(url)) {
                    return adBlocker.createBlockedResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // intent: / market: 等交给系统
                if (!url.startsWith("http", true)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) progressBar.isVisible = false
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabManager.updateTitle(title ?: "")
            }

            // 文件上传
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                return fileUploadHandler.onShowFileChooser(filePathCallback, fileChooserParams)
            }

            // 摄像头 / 麦克风权限
            override fun onPermissionRequest(request: PermissionRequest) {
                webPermissionHandler.onPermissionRequest(request)
            }

            // 全屏视频支持
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                // 简化处理：隐藏 URL 栏
            }
        }

        // 下载监听
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            DownloadHelper.enqueue(
                this, url, userAgent, contentDisposition, mimeType, contentLength
            )
        }

        // 长按菜单
        webView.setOnLongClickListener {
            longPressMenu.show(webView)
            true
        }

        return webView
    }

    // ===== 标签管理 =====

    private fun openNewTab(url: String) {
        val webView = createWebView()
        tabManager.addTab(webView, url)
        showWebView(webView)
        if (url.isNotBlank()) webView.loadUrl(url)
    }

    private fun showWebView(webView: WebView) {
        webContainer.removeAllViews()
        webContainer.addView(webView)
    }

    private fun currentWebView(): WebView? = tabManager.currentTab?.webView

    private fun showTabSwitcher() {
        val tabs = tabManager.allTabs
        if (tabs.isEmpty()) return

        val items = tabs.mapIndexed { i, tab ->
            "${i + 1}. ${tab.title.take(30)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("标签页 (${tabs.size})")
            .setItems(items) { _, which ->
                val tab = tabManager.switchTo(which)
                tab?.webView?.let { showWebView(it) }
            }
            .setPositiveButton("新标签") { _, _ -> openNewTab(settings.homepage) }
            .setNegativeButton("关闭当前") { _, _ ->
                val next = tabManager.closeTab(tabManager.currentIndex)
                if (next?.webView != null) {
                    showWebView(next.webView!!)
                } else {
                    openNewTab(settings.homepage)
                }
            }
            .show()
    }

    // ===== 导航 =====

    private fun navigateTo(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> settings.searchEngine.replace("%s", Uri.encode(trimmed))
        }
        currentWebView()?.loadUrl(url)
    }

    // ===== 主菜单 =====

    private fun showMainMenu() {
        val items = arrayOf(
            "翻译当前页面（可视区域）",
            "翻译全页",
            "显示原文/译文",
            "历史记录",
            "清空全部历史",
            "广告拦截：${if (adBlocker.enabled) "开" else "关"}",
            "指纹伪装：${if (settings.fingerprintEnabled) "开" else "关"}",
            "翻译设置",
            "UA 设置",
            "清除缓存",
            "关于"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> currentWebView()?.let { translateManager.translateViewport(it) }
                    1 -> currentWebView()?.let { translateManager.translateFullPage(it) }
                    2 -> currentWebView()?.let { translateManager.toggleOriginal(it) }
                    3 -> showHistory()
                    4 -> clearAllHistory()
                    5 -> toggleAdBlock()
                    6 -> toggleFingerprint()
                    7 -> showTranslateSettings()
                    8 -> showUaSettings()
                    9 -> clearCache()
                    10 -> showAbout()
                }
            }
            .show()
    }

    private fun showHistory() {
        val entries = historyManager.getAll(100)
        if (entries.isEmpty()) {
            Toast.makeText(this, "暂无浏览记录", Toast.LENGTH_SHORT).show()
            return
        }
        val items = entries.map { "${it.title.take(40)}\n${it.url.take(50)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("历史记录")
            .setItems(items) { _, which ->
                currentWebView()?.loadUrl(entries[which].url)
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空全部") { _, _ -> clearAllHistory() }
            .show()
    }

    private fun clearAllHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认")
            .setMessage("确定清空所有浏览记录？")
            .setPositiveButton("清空") { _, _ ->
                historyManager.clearAll()
                Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleAdBlock() {
        adBlocker.enabled = !adBlocker.enabled
        settings.adBlockEnabled = adBlocker.enabled
        Toast.makeText(this, "广告拦截：${if (adBlocker.enabled) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFingerprint() {
        settings.fingerprintEnabled = !settings.fingerprintEnabled
        Toast.makeText(this, "指纹伪装：${if (settings.fingerprintEnabled) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        currentWebView()?.reload()
    }

    private fun showTranslateSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_translate_settings, null)
        val etKey = view.findViewById<EditText>(R.id.et_api_key)
        val etUrl = view.findViewById<EditText>(R.id.et_api_url)
        val etLang = view.findViewById<EditText>(R.id.et_target_lang)

        etKey.setText(settings.getDsAuth())
        etUrl.setText(settings.translateUrl)
        etLang.setText(settings.translateLang)

        MaterialAlertDialogBuilder(this)
            .setTitle("翻译设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                settings.setDsAuth(etKey.text.toString().trim())
                settings.translateUrl = etUrl.text.toString().trim()
                settings.translateLang = etLang.text.toString().trim()
                Toast.makeText(this, "翻译设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUaSettings() {
        val options = arrayOf("Chrome", "Firefox", "Edge")
        val templates = arrayOf(
            BrowserSettings.UA_CHROME,
            BrowserSettings.UA_FIREFOX,
            BrowserSettings.UA_EDGE
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("User-Agent")
            .setItems(options) { _, which ->
                settings.userAgentTemplate = templates[which]
                currentWebView()?.settings?.userAgentString = settings.buildUserAgent()
                currentWebView()?.reload()
                Toast.makeText(this, "UA 已切换为 ${options[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun clearCache() {
        currentWebView()?.clearCache(true)
        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Dex 浏览器")
            .setMessage("版本 2.0\n\n一个基于 Android WebView 的浏览器。\n\n已有功能：多标签、广告拦截、AI 翻译（DeepSeek）、指纹伪装、下载管理、文件上传、摄像头/麦克风权限、长按菜单。")
            .setPositiveButton("确定", null)
            .show()
    }

    // ===== 工具方法 =====

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun updateChromeVersion() {
        scope.launch {
            val ver = VersionUpdater.fetchLatestChromeVersion()
            if (ver != null) {
                settings.chromeVersion = ver
                currentWebView()?.settings?.userAgentString = settings.buildUserAgent()
            }
        }
    }

    // ===== 生命周期与回调 =====

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fileUploadHandler.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        webPermissionHandler.onRequestPermissionsResult(requestCode, grantResults)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        val wv = currentWebView()
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        translateManager.destroy()
        tabManager.closeAll()
        scope.cancel()
        super.onDestroy()
    }
}
