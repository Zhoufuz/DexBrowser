package com.dex.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 处理网页 <input type="file"> 的文件选择。
 *
 * 关键：WebChromeClient.onShowFileChooser 必须正确返回 true 并在
 * 最终（含取消场景）回调 filePathCallback，否则网页的文件选择框会永久卡死，
 * 后续再也无法上传 —— 这是很多自制浏览器上传功能失效的根本原因。
 */
class FileUploadHandler(private val activity: AppCompatActivity) {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    companion object {
        const val REQ_CODE_FILE_CHOOSER = 0x7002
    }

    fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        // 若已有未完成的回调，先安全释放，避免卡死
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback

        val acceptTypes = params.acceptTypes
        val allowMultiple =
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val mimeType = resolveMimeType(acceptTypes)

        // 内容选择 Intent（相册 / 文件管理器）
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        // 可选的“拍照/录像”捕获 Intent
        val captureIntents = buildCaptureIntents(acceptTypes)

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_TITLE, "选择文件")
            if (captureIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, captureIntents.toTypedArray())
            }
        }

        return try {
            activity.startActivityForResult(chooser, REQ_CODE_FILE_CHOOSER)
            true
        } catch (e: Exception) {
            // 无法拉起选择器：必须回调 null 释放，否则网页卡死
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            false
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_CODE_FILE_CHOOSER) return
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return

        if (resultCode != Activity.RESULT_OK) {
            // 用户取消：必须回调 null，否则输入框永久锁死
            cb.onReceiveValue(null)
            cameraPhotoUri = null
            return
        }

        val results: Array<Uri>? = parseResult(data)
        cb.onReceiveValue(results)
        cameraPhotoUri = null
    }

    private fun parseResult(data: Intent?): Array<Uri>? {
        // 优先：文件管理器/相册返回的数据
        if (data != null) {
            data.clipData?.let { clip ->
                val list = ArrayList<Uri>()
                for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                if (list.isNotEmpty()) return list.toTypedArray()
            }
            data.data?.let { return arrayOf(it) }
        }
        // 其次：拍照/录像捕获的结果
        cameraPhotoUri?.let { return arrayOf(it) }
        return null
    }

    private fun buildCaptureIntents(acceptTypes: Array<String>): List<Intent> {
        val joined = acceptTypes.joinToString(",").lowercase()
        val wantImage = joined.contains("image") || joined.isBlank()
        val wantVideo = joined.contains("video")
        val list = mutableListOf<Intent>()

        if (wantImage) {
            val photoFile = createMediaFile("IMG_", ".jpg")
            if (photoFile != null) {
                val uri = FileProvider.getUriForFile(
                    activity, "${activity.packageName}.fileprovider", photoFile
                )
                cameraPhotoUri = uri
                list.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                })
            }
        }
        if (wantVideo) {
            list.add(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
        }
        return list
    }

    private fun createMediaFile(prefix: String, ext: String): File? = try {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        File.createTempFile("${prefix}${time}_", ext, dir)
    } catch (e: Exception) {
        null
    }

    private fun resolveMimeType(acceptTypes: Array<String>): String {
        val types = acceptTypes.filter { it.isNotBlank() }
        if (types.isEmpty()) return "*/*"
        // 若全部是同一大类可直接用；否则退回 */*
        val first = types[0]
        return if (first.contains("/") || first.startsWith(".")) {
            if (first.startsWith(".")) "*/*" else first
        } else "*/*"
    }
}
