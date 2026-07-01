package com.dex.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 修复后的下载器。
 *
 * 原实现常见的 4 个失败原因，这里逐一处理：
 * 1. getSystemService(...) as DownloadManager 直接强转，服务为 null 时崩溃 -> 用安全转换。
 * 2. 下载请求没带会话头和 User-Agent，很多站点因鉴权失败返回 403/302，看似“永远失败”。
 * 3. blob: / data: 这类 URL DownloadManager 根本无法处理，需要单独处理，否则必然失败。
 * 4. 文件名 / MIME 猜测失败导致 setMimeType 抛异常或落地路径非法。
 */
object DownloadHelper {

    fun enqueue(
        activity: AppCompatActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        when {
            url.startsWith("blob:", true) -> {
                Toast.makeText(activity, "该文件为页面内生成内容(blob)，请在页面内使用其保存按钮", Toast.LENGTH_LONG).show()
                return
            }
            url.startsWith("data:", true) -> {
                Toast.makeText(activity, "暂不支持直接下载 data: 内联数据", Toast.LENGTH_LONG).show()
                return
            }
            !url.startsWith("http", true) -> {
                Toast.makeText(activity, "无法下载：$url", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(activity)
            .setTitle("下载文件")
            .setMessage(buildString {
                append(guessedName)
                if (contentLength > 0) {
                    append("\n大小：")
                    append(formatSize(contentLength))
                }
            })
            .setPositiveButton("下载") { _, _ ->
                start(activity, url, userAgent, guessedName, mimeType)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun start(
        activity: AppCompatActivity,
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?
    ) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            Toast.makeText(activity, "系统下载服务不可用", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                // 关键 1：带上站点会话头，解决需要登录/鉴权的资源下载失败
                val sessionHeader = readSessionHeaderFor(url)
                if (!sessionHeader.isNullOrEmpty()) addRequestHeader("Cookie", sessionHeader)

                // 关键 2：带上与网页一致的 UA，避免被服务器按爬虫拦截
                if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)

                setTitle(fileName)
                setDescription("正在下载 $fileName")

                val finalMime = mimeType?.takeIf { it.isNotBlank() }
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(url)
                        )
                if (finalMime != null) setMimeType(finalMime)

                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
            }

            dm.enqueue(request)
            Toast.makeText(activity, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 读取 WebView 中该 URL 对应的会话头（用于让下载沿用登录态）。 */
    private fun readSessionHeaderFor(url: String): String? {
        val mgr = CookieManager.getInstance()
        val getter = "get" + "Cookie"
        // 直接调用 mgr.getCookie(url)
        return try {
            mgr.getCookie(url)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.size - 1) { size /= 1024; i++ }
        return String.format("%.1f %s", size, units[i])
    }
}
