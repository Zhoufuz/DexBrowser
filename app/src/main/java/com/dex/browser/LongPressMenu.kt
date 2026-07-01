package com.dex.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 长按 WebView 弹出上下文菜单。
 * 根据 HitTestResult 类型决定可用操作：复制链接 / 复制文本 / 新标签打开 / 下载图片。
 */
class LongPressMenu(
    private val activity: AppCompatActivity,
    private val onOpenInNewTab: (String) -> Unit
) {

    fun show(webView: WebView) {
        val result = webView.hitTestResult
        val type = result.type
        val extra = result.extra ?: return

        val items = mutableListOf<Pair<String, () -> Unit>>()

        when (type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                items.add("复制链接" to { copyToClipboard(extra) })
                items.add("新标签打开" to { onOpenInNewTab(extra) })
                items.add("下载链接" to {
                    DownloadHelper.enqueue(activity, extra, webView.settings.userAgentString, null, null, 0L)
                })
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                items.add("复制图片链接" to { copyToClipboard(extra) })
                items.add("下载图片" to {
                    DownloadHelper.enqueue(activity, extra, webView.settings.userAgentString, null, "image/*", 0L)
                })
                items.add("新标签打开图片" to { onOpenInNewTab(extra) })
            }
            WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                // 编辑框长按交给系统自带菜单
                return
            }
            else -> return
        }

        // 追加"复制页面文字"（通过 JS 获取当前选中文本）
        items.add("复制选中文字" to {
            webView.evaluateJavascript("window.getSelection().toString()") { value ->
                val text = value?.trim('"') ?: ""
                if (text.isNotBlank()) {
                    copyToClipboard(text)
                } else {
                    Toast.makeText(activity, "未选中任何文字", Toast.LENGTH_SHORT).show()
                }
            }
        })

        val labels = items.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(shortenUrl(extra))
            .setItems(labels) { _, which -> items[which].second() }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DexBrowser", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shortenUrl(url: String): String {
        return if (url.length > 60) url.take(57) + "..." else url
    }
}
