package com.dex.browser

import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray

/**
 * 翻译管理器（性能优化版）。
 *
 * 优化策略（解决 Claude/ChatGPT 长对话页面卡顿）：
 * 1. 只收集视口内可见文本节点（viewportOnly = true），滚动后再增量翻译新内容。
 * 2. 收集和应用操作均通过 evaluateJavascript 在 WebView 线程执行，
 *    但 JSON 解析和 API 调用走协程线程池。
 * 3. 批量翻译：多段文字合并为一次 API 请求，减少网络往返。
 * 4. 并发控制：最多 3 个并发请求（Semaphore），避免短时间大量请求被限流。
 * 5. 翻译结果逐批 apply，用户看到渐进式翻译效果，而不是等全部完成。
 */
class TranslateManager(
    private val activity: AppCompatActivity,
    private val settings: BrowserSettings
) {
    private val client = TranslateClient(settings)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val semaphore = Semaphore(3)

    private var currentJob: Job? = null
    private var isShowingTranslation = false

    /** 注入翻译框架 JS（页面加载完成后调用一次） */
    fun injectScript(webView: WebView) {
        webView.evaluateJavascript(TRANSLATE_JS, null)
    }

    /** 翻译当前可视区域 */
    fun translateViewport(webView: WebView) {
        if (settings.getDsAuth().isBlank()) {
            Toast.makeText(activity, "请先在翻译设置里填写 API Key", Toast.LENGTH_LONG).show()
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            doTranslate(webView, viewportOnly = true)
        }
    }

    /** 翻译全页（慎用，大页面耗时长） */
    fun translateFullPage(webView: WebView) {
        if (settings.getDsAuth().isBlank()) {
            Toast.makeText(activity, "请先在翻译设置里填写 API Key", Toast.LENGTH_LONG).show()
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            doTranslate(webView, viewportOnly = false)
        }
    }

    /** 切换显示：原文/译文 */
    fun toggleOriginal(webView: WebView) {
        if (isShowingTranslation) {
            webView.evaluateJavascript(
                "if(window.__dexTranslate)window.__dexTranslate.showOriginal();", null
            )
            isShowingTranslation = false
        } else {
            webView.evaluateJavascript(
                "if(window.__dexTranslate)window.__dexTranslate.showTranslated();", null
            )
            isShowingTranslation = true
        }
    }

    /** 检查是否有译文缓存 */
    fun hasCache(webView: WebView, callback: (Boolean) -> Unit) {
        webView.evaluateJavascript(
            "(window.__dexTranslate && window.__dexTranslate.hasCache())?'1':'0';"
        ) { result ->
            callback(result?.contains("1") == true)
        }
    }

    /** 划词翻译 */
    fun translateSelection(webView: WebView, selectedText: String) {
        if (settings.getDsAuth().isBlank()) {
            Toast.makeText(activity, "请先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val result = client.translateOne(selectedText, settings.translateLang)
            result.onSuccess { translated ->
                // 在页面底部显示一个浮层（通过JS注入toast）
                val escaped = translated.replace("'", "\\'").replace("\n", "\\n")
                webView.evaluateJavascript("""
                    (function(){
                        var d = document.getElementById('__dex_sel_result');
                        if(!d){
                            d = document.createElement('div');
                            d.id='__dex_sel_result';
                            d.style.cssText='position:fixed;bottom:60px;left:10px;right:10px;padding:12px 16px;background:#333;color:#fff;border-radius:8px;z-index:999999;font-size:14px;max-height:40vh;overflow:auto;box-shadow:0 4px 12px rgba(0,0,0,.3);';
                            d.onclick=function(){d.remove();};
                            document.body.appendChild(d);
                        }
                        d.textContent='$escaped';
                        setTimeout(function(){d.remove();},10000);
                    })();
                """.trimIndent(), null)
            }
            result.onFailure { e ->
                Toast.makeText(activity, "翻译失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun destroy() {
        scope.cancel()
    }

    // ===== 核心翻译流程 =====

    private suspend fun doTranslate(webView: WebView, viewportOnly: Boolean) {
        Toast.makeText(activity, "正在提取页面文字...", Toast.LENGTH_SHORT).show()

        // 1. 收集文本节点
        val collectArg = if (viewportOnly) "true" else "false"
        val json = suspendEvalJs(webView,
            "window.__dexTranslate ? window.__dexTranslate.collect($collectArg) : '[]';"
        )

        if (json.isNullOrBlank() || json == "\"[]\"" || json == "null") {
            Toast.makeText(activity, "页面没有可翻译的文字", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 解析 JSON（子线程）
        val items = withContext(Dispatchers.Default) {
            try {
                val cleaned = json.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                val arr = JSONArray(cleaned)
                val list = mutableListOf<Pair<Int, String>>() // id to text
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(obj.getInt("id") to obj.getString("text"))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(activity, "解析页面文字失败", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "正在翻译 ${items.size} 段文字...", Toast.LENGTH_SHORT).show()

        // 3. 分批翻译 + 逐批应用（渐进式翻译）
        val batchSize = 20 // 每批最多 20 段
        val batches = items.chunked(batchSize)
        var doneCount = 0
        var failCount = 0

        val jobs = batches.map { batch ->
            scope.async {
                semaphore.withPermit {
                    val texts = batch.map { it.second }
                    val result = client.translateBatch(texts, settings.translateLang)
                    result.onSuccess { translated ->
                        // 逐段 apply 到页面
                        withContext(Dispatchers.Main) {
                            for (i in batch.indices) {
                                if (i < translated.size) {
                                    val id = batch[i].first
                                    val t = translated[i]
                                        .replace("\\", "\\\\")
                                        .replace("'", "\\'")
                                        .replace("\n", "\\n")
                                    webView.evaluateJavascript(
                                        "window.__dexTranslate.apply($id,'$t');", null
                                    )
                                }
                            }
                            doneCount += batch.size
                        }
                    }
                    result.onFailure { failCount += batch.size }
                }
            }
        }

        jobs.forEach { it.await() }
        isShowingTranslation = true

        val msg = if (failCount == 0) "翻译完成" else "翻译完成（${failCount}段失败）"
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /** 挂起函数包装 evaluateJavascript */
    private suspend fun suspendEvalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value, null)
            }
        }

    companion object {
        /** 注入到网页的翻译框架 JS（与原版一致，但增加了增量翻译支持） */
        val TRANSLATE_JS = """
(function(){
  if (window.__dexTranslate) return;
  window.__dexTranslate = {
    counter: 0,
    nodes: {},
    originals: {},
    translations: {},
    translatedIds: {},

    collect: function(viewportOnly){
      var self = this;
      var result = [];
      var skipTags = {SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,CODE:1,PRE:1,INPUT:1};
      var walker = document.createTreeWalker(
        document.body, NodeFilter.SHOW_TEXT, null, false);
      var node;
      while(node = walker.nextNode()){
        var text = node.nodeValue ? node.nodeValue.trim() : '';
        if(text.length < 2) continue;
        var parent = node.parentElement;
        if(!parent) continue;
        if(skipTags[parent.tagName]) continue;
        if(parent.getAttribute('data-dex-translated')) continue;
        var style = window.getComputedStyle(parent);
        if(style.display==='none'||style.visibility==='hidden') continue;
        if(viewportOnly){
          var rect = parent.getBoundingClientRect();
          if(rect.bottom < -100 || rect.top > window.innerHeight + 100) continue;
        }
        var id = ++self.counter;
        self.nodes[id] = node;
        self.originals[id] = node.nodeValue;
        result.push({id:id, text:text});
      }
      return JSON.stringify(result);
    },

    apply: function(id, translated){
      if(this.translatedIds[id]) return;
      var node = this.nodes[id];
      if(!node) return;
      var raw = this.originals[id] || node.nodeValue || '';
      var lead = raw.match(/^\s*/)[0];
      var trail = raw.match(/\s*$/)[0];
      this.translations[id] = lead + translated + trail;
      node.nodeValue = this.translations[id];
      this.translatedIds[id] = true;
      var parent = node.parentElement;
      if(parent) parent.setAttribute('data-dex-translated','1');
    },

    hasCache: function(){
      for(var id in this.translations){ return true; }
      return false;
    },

    showTranslated: function(){
      for(var id in this.translations){
        var node = this.nodes[id];
        if(node) node.nodeValue = this.translations[id];
      }
    },

    showOriginal: function(){
      for(var id in this.originals){
        var node = this.nodes[id];
        if(node) node.nodeValue = this.originals[id];
      }
    },

    restore: function(){
      this.showOriginal();
      this.translations = {};
      this.translatedIds = {};
      var marked = document.querySelectorAll('[data-dex-translated]');
      for(var j=0;j<marked.length;j++){
        marked[j].removeAttribute('data-dex-translated');
      }
    }
  };

  document.addEventListener('mouseup', function(){
    setTimeout(function(){
      var sel = window.getSelection().toString().trim();
      if(sel.length > 0 && sel.length < 500){
        if(window.DexTranslateBridge && DexTranslateBridge.onTextSelected){
          DexTranslateBridge.onTextSelected(sel);
        }
      }
    }, 10);
  });
})();
        """.trimIndent()
    }
}
