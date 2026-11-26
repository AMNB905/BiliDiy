package com.gdk.biliapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * App 内更新入口。
 *
 * 下载交给系统 [UpdateDownload]（DownloadManager），由系统进程托管；
 * App 退出页面、切后台或被清理后下载仍继续，完成后发通知进入系统安装器。
 */
object AppUpdater {

    private const val REQ_NOTIFY = 101

    @Volatile private var pending: Cloud.Upd? = null

    /** 用户点「立即更新」 */
    fun request(activity: Activity, upd: Cloud.Upd) {
        if (upd.url.isBlank()) {
            Ui.toast(activity, "管理员还没有配置下载地址")
            return
        }
        if (upd.code <= Cloud.versionCode(activity)) {
            Ui.toast(activity, "当前已是最新版本")
            return
        }
        if (!canInstall(activity)) {
            pending = upd
            showPermissionGuide(activity)
            return
        }
        pending = null
        start(activity, upd)
    }

    /** 从「安装未知应用」设置页返回后自动继续 */
    fun onResume(activity: Activity) {
        val p = pending ?: return
        if (canInstall(activity)) {
            pending = null
            start(activity, p)
        }
    }

    private fun start(activity: Activity, upd: Cloud.Upd) {
        if (UpdateBus.state?.running == true) {
            Ui.toast(activity, "更新包正在后台下载，请稍候")
            return
        }

        requestNotificationPermission(activity)

        runCatching {
            UpdateDownload.enqueue(activity, upd)
            UpdateBus.update(UpdateBus.State(upd.code, true, 0, "已交给系统在后台下载…"))
            Ui.toast(activity, "更新包已交给系统后台下载，可退出 App")
        }.onFailure {
            Ui.toast(activity, "无法启动后台更新下载：${it.message}")
        }
    }

    private fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFY
        )
        Ui.toast(activity, "通知权限未开启：下载仍会继续，完成后请回到设置页安装")
    }

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    private fun showPermissionGuide(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("需要安装权限")
            .setMessage("第一次在 App 内更新，需要允许 BiliDiy 安装应用。\n\n点「去设置」打开开关，返回 App 后会自动继续下载安装。")
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    ))
                }.onFailure {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    Ui.toast(activity, "请手动允许「安装未知应用」后重试")
                }
            }
            .setNegativeButton("取消") { _, _ -> pending = null }
            .setOnCancelListener { pending = null }
            .show()
    }
}
