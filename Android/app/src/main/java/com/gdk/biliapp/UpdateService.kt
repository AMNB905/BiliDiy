package com.gdk.biliapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 更新包下载状态总线：服务与设置页共享进度，退出页面后仍可恢复显示 */
object UpdateBus {
    data class State(
        val code: Int,
        val running: Boolean,
        val progress: Int,
        val text: String,
        val fileName: String = "",
        val error: String = ""
    )

    @Volatile
    var state: State? = null

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun register(listener: (State) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        state?.let { s ->
            main.post { if (listeners.contains(listener)) listener(s) }
        }
    }

    fun unregister(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun update(s: State) {
        state = s
        main.post {
            for (l in listeners) l(s)
        }
    }
}

/**
 * App 自身更新包的后台下载服务。
 *
 * 使用 dataSync 前台服务，退出设置页、切到后台甚至划掉任务后仍继续下载；
 * 通知栏显示进度，完成后点击通知直接打开系统安装器。
 */
class UpdateService : Service() {

    companion object {
        const val ACTION_START = "com.gdk.biliapp.action.START_UPDATE"
        const val EXTRA_URL = "update_url"
        const val EXTRA_CODE = "update_code"
        const val EXTRA_NAME = "update_name"
        const val EXTRA_LOG = "update_log"
        const val EXTRA_FORCE = "update_force"

        private const val CHANNEL_ID = "biliapp_update"
        private const val NOTIFICATION_ID = 2002
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    private var current: Cloud.Upd? = null
    private var lastPush = 0L
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        AppCtx.init(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) {
            current?.let { nm.notify(NOTIFICATION_ID, buildNotification(it, null, 0, "更新包正在下载…", true)) }
            return START_NOT_STICKY
        }

        val upd = readRequest(intent)
        if (upd == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        active = true
        current = upd
        startForegroundCompat(buildNotification(upd, null, 0, "准备下载更新包…", true))
        UpdateBus.update(UpdateBus.State(upd.code, true, 0, "准备下载更新包…"))
        executor.execute { runDownload(upd) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun readRequest(intent: Intent): Cloud.Upd? {
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        if (url.isEmpty() || code <= 0) return null
        return Cloud.Upd(
            enabled = true,
            code = code,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            url = url,
            log = intent.getStringExtra(EXTRA_LOG).orEmpty(),
            force = intent.getBooleanExtra(EXTRA_FORCE, false)
        )
    }

    private fun runDownload(upd: Cloud.Upd) {
        val dir = File(filesDir, "updates").apply { mkdirs() }
        val apk = File(dir, "BiliDiy-${upd.code}.apk")
        val tmp = File(dir, "${apk.name}.part")
        tmp.delete()

        var c: HttpURLConnection? = null
        try {
            c = (URL(upd.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Bili.UA)
                setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val response = c.responseCode
            if (response !in 200..299) throw IOException("服务器返回 HTTP $response")

            val total = c.contentLengthLong
            var read = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            push(upd, pct, if (total > 0) "下载中 $pct%" else "下载中 ${fmtSize(read)}")
                        }
                    }
                }
            }

            if (total > 0 && tmp.length() != total) throw IOException("更新包下载不完整")
            if (tmp.length() <= 0) throw IOException("更新包为空")

            val info = packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                ?: throw IOException("无法读取更新包信息")
            if (info.packageName != packageName) throw IOException("更新包包名不一致")
            val newCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            if (newCode != upd.code.toLong()) throw IOException("更新包版本号异常")
            if (newCode <= Cloud.versionCode(this).toLong()) throw IOException("更新包版本没有提升")

            if (apk.exists() && !apk.delete()) throw IOException("无法覆盖旧更新包")
            if (!tmp.renameTo(apk)) {
                tmp.copyTo(apk, overwrite = true)
                tmp.delete()
            }
            finishSuccess(upd, apk)
        } catch (e: Exception) {
            tmp.delete()
            finishError(upd, e.message ?: "未知错误")
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    private fun push(upd: Cloud.Upd, pct: Int, text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 500 && pct < 100) return
        lastPush = now
        val p = if (pct >= 0) pct else 0
        UpdateBus.update(UpdateBus.State(upd.code, true, p, text))
        nm.notify(NOTIFICATION_ID, buildNotification(upd, null, p, text, true))
    }

    private fun finishSuccess(upd: Cloud.Upd, apk: File) {
        active = false
        val text = "下载完成，点击安装"
        UpdateBus.update(UpdateBus.State(upd.code, false, 100, text, apk.name))
        val install = installPendingIntent(apk)
        nm.notify(NOTIFICATION_ID, buildNotification(upd, install, 100, text, false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun finishError(upd: Cloud.Upd, msg: String) {
        active = false
        val text = "更新包下载失败：$msg"
        UpdateBus.update(UpdateBus.State(upd.code, false, 0, "下载失败", error = msg))
        nm.notify(NOTIFICATION_ID, buildNotification(upd, openSettingsIntent(), 0, text, false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(
        upd: Cloud.Upd,
        content: PendingIntent?,
        pct: Int,
        text: String,
        ongoing: Boolean
    ): Notification {
        val fallback = openSettingsIntent()
        val title = when {
            ongoing -> if (upd.name.isNotBlank()) "正在下载 BiliDiy ${upd.name}" else "正在下载 BiliDiy 更新"
            text.startsWith("更新包下载失败") -> "BiliDiy 更新下载失败"
            else -> if (upd.name.isNotBlank()) "BiliDiy ${upd.name} 下载完成" else "BiliDiy 更新包下载完成"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(ongoing)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(content ?: fallback)
            .apply {
                if (ongoing) {
                    if (pct > 0) setProgress(100, pct.coerceIn(0, 100), false)
                    else setProgress(0, 0, true)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun openSettingsIntent(): PendingIntent {
        val open = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun installPendingIntent(apk: File): PendingIntent {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this, NOTIFICATION_ID + 1, install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
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

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
