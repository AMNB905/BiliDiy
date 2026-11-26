package com.gdk.biliapp

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 更新包下载交给系统 DownloadManager。
 *
 * 下载任务由系统进程托管，App 退到后台、被厂商清理甚至进程被杀都不会中断；
 * 完成后由 [UpdateReceiver] 校验 APK 并发安装通知。
 */
object UpdateDownload {

    private const val PREF = "bilidiy_update_download"
    private const val KEY_ID = "download_id"
    private const val KEY_CODE = "version_code"
    private const val KEY_NAME = "version_name"

    private const val CHANNEL_ID = "biliapp_update"
    private const val NOTIFY_ID = 2004
    private const val MIME_APK = "application/vnd.android.package-archive"

    data class Info(val id: Long, val code: Int, val name: String)

    fun enqueue(context: Context, upd: Cloud.Upd): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = fileName(upd.code)
        val target = targetFile(context, upd.code)
            ?: throw IllegalStateException("无法访问更新下载目录")

        saved(context)?.let { old ->
            runCatching { dm.remove(old.id) }
        }
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(upd.url)).apply {
            setTitle(if (upd.name.isBlank()) "BiliDiy 更新包" else "BiliDiy ${upd.name} 更新包")
            setDescription("系统后台下载，完成后点击通知安装")
            setMimeType(MIME_APK)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val id = dm.enqueue(request)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ID, id)
            .putInt(KEY_CODE, upd.code)
            .putString(KEY_NAME, upd.name)
            .commit()
        return id
    }

    fun sync(context: Context) {
        val info = saved(context) ?: return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching {
            dm.query(DownloadManager.Query().setFilterById(info.id))
        }.getOrNull() ?: return

        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> complete(context, info.id)
                DownloadManager.STATUS_FAILED -> fail(context, info, "系统下载失败")
                else -> {
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0
                    val text = if (pct > 0) "系统后台下载中 $pct%" else "系统后台下载中…"
                    UpdateBus.update(UpdateBus.State(info.code, true, pct, text))
                }
            }
        }
    }

    /** 由 DownloadManager 完成广播或设置页轮询调用；重复调用只会刷新同一条通知 */
    fun complete(context: Context, id: Long): Boolean {
        val info = saved(context) ?: return false
        if (info.id != id) return false

        val file = targetFile(context, info.code)
        if (file == null || !file.exists() || file.length() <= 0) {
            fail(context, info, "更新包文件不存在")
            return false
        }
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull()
        if (archive == null || archive.packageName != context.packageName) {
            fail(context, info, "更新包包名异常")
            return false
        }
        val newCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (newCode != info.code.toLong()) {
            fail(context, info, "更新包版本号异常")
            return false
        }
        if (newCode <= Cloud.versionCode(context).toLong()) {
            fail(context, info, "更新包版本没有提升")
            return false
        }

        UpdateBus.update(UpdateBus.State(info.code, false, 100, "下载完成，点击通知安装", file.name))
        notifyInstall(context, info, file)
        return true
    }

    private fun fail(context: Context, info: Info, message: String) {
        UpdateBus.update(UpdateBus.State(info.code, false, 0, "下载失败", error = message))
        notifyError(context, info, message)
    }

    fun saved(context: Context): Info? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = p.getLong(KEY_ID, 0L)
        val code = p.getInt(KEY_CODE, 0)
        if (id <= 0L || code <= 0) return null
        return Info(id, code, p.getString(KEY_NAME, "").orEmpty())
    }

    fun targetFile(context: Context, code: Int): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            File(it, fileName(code))
        }

    private fun fileName(code: Int) = "BiliDiy-$code.apk"

    private fun notifyInstall(context: Context, info: Info, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFY_ID, install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle("BiliDiy ${info.name} 下载完成".trim())
            .setContentText("点击安装更新包")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
        notificationManager(context).notify(NOTIFY_ID, n)
    }

    private fun notifyError(context: Context, info: Info, message: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle("BiliDiy 更新下载失败")
            .setContentText(message)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSettingsIntent(context))
            .build()
        notificationManager(context).notify(NOTIFY_ID, n)
    }

    private fun openSettingsIntent(context: Context): PendingIntent {
        val open = Intent(context, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, NOTIFY_ID + 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationManager(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BiliDiy 更新包下载与安装提醒"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
        return nm
    }
}
