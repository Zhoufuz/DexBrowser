package com.dex.browser

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 统一处理网页 getUserMedia 发起的摄像头 / 麦克风权限请求。
 *
 * 流程：
 * 1. 网页调用 navigator.mediaDevices.getUserMedia -> WebChromeClient.onPermissionRequest
 * 2. 先确保 App 自身已获得对应 Android 运行时权限（CAMERA / RECORD_AUDIO）
 * 3. 再弹出确认框让用户决定是否授予该网站
 * 4. request.grant(...) / request.deny()
 */
class WebPermissionHandler(private val activity: AppCompatActivity) {

    // 记录待处理的网页权限请求（等待系统权限回调后继续）
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources: Array<String>? = null

    companion object {
        const val REQ_CODE_WEB_MEDIA = 0x7001
    }

    fun onPermissionRequest(request: PermissionRequest) {
        val requested = request.resources
        val needCamera = requested.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val needMic = requested.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        // 计算需要向系统申请的运行时权限
        val missing = mutableListOf<String>()
        if (needCamera && !hasPermission(Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (needMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }

        if (missing.isNotEmpty()) {
            // 需要先申请系统权限，暂存请求
            pendingRequest = request
            pendingResources = requested
            activity.requestPermissions(missing.toTypedArray(), REQ_CODE_WEB_MEDIA)
        } else {
            // 系统权限已具备，直接询问用户是否授予网站
            confirmGrantToSite(request, requested)
        }
    }

    /** 系统权限回调后调用 */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQ_CODE_WEB_MEDIA) return
        val req = pendingRequest
        val res = pendingResources
        pendingRequest = null
        pendingResources = null
        if (req == null || res == null) return

        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            confirmGrantToSite(req, res)
        } else {
            req.deny()
        }
    }

    private fun confirmGrantToSite(request: PermissionRequest, resources: Array<String>) {
        val what = buildString {
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) append("摄像头")
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                if (isNotEmpty()) append("、")
                append("麦克风")
            }
        }
        val host = request.origin?.host ?: request.origin?.toString() ?: "该网站"

        AlertDialog.Builder(activity)
            .setTitle("权限请求")
            .setMessage("$host 请求使用你的$what，是否允许？")
            .setPositiveButton("允许") { _, _ -> request.grant(resources) }
            .setNegativeButton("拒绝") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
}
