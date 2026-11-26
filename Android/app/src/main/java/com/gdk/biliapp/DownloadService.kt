package com.gdk.biliapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 下载状态总线：前台服务和播放页在同一个进程内共享进度 */
object DownloadBus {
    data class State(
        val title: String,
        val running: Boolean,
        val progress: Int,
        val text: String,
        val fileName: String = "",
        val error: String = "",
        val batchIndex: Int = 1,
        val batchTotal: Int = 0
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
 * 后台下载服务。
 *
 * 使用 dataSync 前台服务，退出播放页、切到后台甚至划掉任务后仍会继续下载；
 * 支持一次接收多集任务，按顺序下载，单集失败不会中断后面的任务。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.gdk.biliapp.action.START_DOWNLOAD"
        const val EXTRA_TITLE = "download_title"
        const val EXTRA_BVID = "download_bvid"
        const val EXTRA_CID = "download_cid"
        const val EXTRA_EP_ID = "download_ep_id"
        const val EXTRA_SEASON_ID = "download_season_id"
        const val EXTRA_COURSE_EP_ID = "download_course_ep_id"
        const val EXTRA_QN = "download_qn"
        const val EXTRA_REFERER = "download_referer"

        /** 批量任务：JSON 数组，字段与上面的单个 extra 同名 */
        const val EXTRA_ITEMS_JSON = "download_items_json"

        private const val CHANNEL_ID = "biliapp_download"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_QUEUE = 200
    }

    private data class Request(
        val title: String,
        val bvid: String,
        val cid: Long,
        val epId: Long,
        val seasonId: Long,
        val courseEpId: Long,
        val qn: Int,
        val referer: String
    )

    private val executor = Executors.newSingleThreadExecutor()
    private var active = false
    private var queue: List<Request> = emptyList()
    private var queueIndex = 0
    private var current: Request? = null
    private var successCount = 0
    private var failedCount = 0
    private var lastError = ""
    private var lastFile: File? = null
    private var lastTitle = ""
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
        if (active) return START_NOT_STICKY

        val items = readQueue(intent)
        if (items.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        active = true
        queue = items
        queueIndex = 0
        successCount = 0
        failedCount = 0
        lastError = ""
        lastFile = null
        lastTitle = ""
        lastPush = 0L
        current = items.first()

        val firstText = "准备下载…"
        startForegroundCompat(buildNotification(
            items.first(), 0, firstText, true, batchIndex = 1, batchTotal = items.size
        ))
        DownloadBus.update(
            DownloadBus.State(
                items.first().title, true, 0, queuePrefix(1) + firstText,
                batchIndex = 1, batchTotal = items.size
            )
        )
        executor.execute { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun readQueue(intent: Intent): List<Request>? {
        val raw = intent.getStringExtra(EXTRA_ITEMS_JSON)
        if (raw.isNullOrBlank()) return readRequest(intent)?.let { listOf(it) }

        val list = ArrayList<Request>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                if (list.size >= MAX_QUEUE) break
                val o = arr.optJSONObject(i) ?: continue
                val req = requestFromJson(o) ?: continue
                list.add(req)
            }
        } catch (_: Exception) {
            return null
        }
        return list
    }

    private fun readRequest(intent: Intent): Request? = request(
        title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty(),
        bvid = intent.getStringExtra(EXTRA_BVID).orEmpty(),
        cid = intent.getLongExtra(EXTRA_CID, 0L),
        epId = intent.getLongExtra(EXTRA_EP_ID, 0L),
        seasonId = intent.getLongExtra(EXTRA_SEASON_ID, 0L),
        courseEpId = intent.getLongExtra(EXTRA_COURSE_EP_ID, 0L),
        qn = intent.getIntExtra(EXTRA_QN, PlaybackPrefs.DEFAULT_QN),
        referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
    )

    private fun requestFromJson(o: JSONObject): Request? = request(
        title = o.optString("title", "").trim(),
        bvid = o.optString("bvid", ""),
        cid = o.optLong("cid", 0L),
        epId = o.optLong("ep_id", 0L),
        seasonId = o.optLong("season_id", 0L),
        courseEpId = o.optLong("course_ep_id", 0L),
        qn = o.optInt("qn", PlaybackPrefs.DEFAULT_QN),
        referer = o.optString("referer", "")
    )

    private fun request(
        title: String,
        bvid: String,
        cid: Long,
        epId: Long,
        seasonId: Long,
        courseEpId: Long,
        qn: Int,
        referer: String
    ): Request? {
        if (seasonId <= 0L && epId <= 0L && (bvid.isEmpty() || cid <= 0L)) return null
        if (seasonId > 0L && courseEpId <= 0L) return null
        val safeQn = qn.takeIf { it > 0 } ?: PlaybackPrefs.DEFAULT_QN
        return Request(
            title = title.ifBlank { "BiliDiy视频" },
            bvid = bvid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
            courseEpId = courseEpId,
            qn = safeQn,
            referer = referer.ifBlank { "https://www.bilibili.com/" }
        )
    }

    private fun runQueue() {
        for (i in queue.indices) {
            queueIndex = i
            current = queue[i]
            val ok = runDownload(queue[i])
            if (ok) successCount++ else failedCount++
        }
        finishBatch()
    }

    private fun runDownload(req: Request): Boolean {
        var tmpDir: File? = null
        var temp: File? = null
        try {
            val output = VideoDownloader.outputFile(this, req.title)
            // 先写 .part，成功后再替换同名文件；失败不会破坏已经存在的旧片
            val work = File(output.parentFile, output.name + ".part")
            temp = work
            if (work.exists() && !work.delete()) throw IOException("无法清理旧临时文件")

            // 投稿 / 番剧优先取带音轨的 MP4；课程只有 DASH，走下面的合成分支
            var direct: String? = null
            if (req.seasonId <= 0L) {
                direct = try {
                    if (req.epId > 0L) {
                        VideoDownloader.firstDurlUrl(Bili.pgcDownloadData(req.epId, req.qn))
                    } else {
                        VideoDownloader.firstDurlUrl(Bili.playurlHtml5(req.bvid, req.cid, req.qn))
                    }
                } catch (_: Exception) {
                    null
                }
            }

            if (direct != null) {
                downloadTrack(direct, work, req, "下载中", 0, 100)
            } else {
                val dash = when {
                    req.seasonId > 0L -> Bili.coursePlayurl(req.courseEpId, req.qn)
                    req.epId > 0L -> Bili.pgcPlayurl(req.epId, req.qn)
                    else -> Bili.playurl(req.bvid, req.cid, req.qn)
                }
                val streams = VideoDownloader.pick(dash, req.qn)
                    ?: throw Exception("没有可下载的视频流")
                tmpDir = File(cacheDir, "dl_${System.currentTimeMillis()}_${queueIndex}")
                if (!tmpDir.exists() && !tmpDir.mkdirs()) throw Exception("无法创建临时目录")
                val videoFile = File(tmpDir, "video.m4s")
                val audioFile = File(tmpDir, "audio.m4s")

                val videoUrl = VideoDownloader.baseUrlOf(streams.video)
                if (videoUrl.isEmpty()) throw Exception("视频轨地址为空")
                downloadTrack(videoUrl, videoFile, req, "下载视频", 0, 72)

                val audio = streams.audio
                val audioUrl = if (audio != null) VideoDownloader.baseUrlOf(audio) else ""
                if (audioUrl.isNotEmpty()) {
                    downloadTrack(audioUrl, audioFile, req, "下载音频", 72, 18)
                }

                push(req, 90, "正在合成 MP4…", true)
                VideoDownloader.mux(videoFile, if (audioUrl.isNotEmpty()) audioFile else null, work)
            }
            replaceFile(work, output)
            temp = null
            finishItemSuccess(req, output)
            return true
        } catch (e: Exception) {
            temp?.delete()
            finishItemError(req, e.message ?: "未知错误")
            return false
        } finally {
            tmpDir?.deleteRecursively()
        }
    }

    /** 同名文件覆盖：优先原子替换，失败再退回删除后重命名 */
    private fun replaceFile(temp: File, output: File) {
        if (temp.renameTo(output)) return
        try {
            Files.move(
                temp.toPath(), output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: Exception) {
            // 某些文件系统不支持原子移动，走普通替换
        }
        try {
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // 最后兜底
        }
        if (output.exists() && !output.delete()) throw IOException("无法覆盖同名文件")
        if (!temp.renameTo(output)) throw IOException("无法保存下载文件")
    }

    private fun downloadTrack(
        url: String,
        out: File,
        req: Request,
        label: String,
        basePct: Int,
        spanPct: Int
    ) {
        VideoDownloader.download(url, out, req.referer) { done, total ->
            if (total > 0) {
                val pct = basePct + (spanPct * done / total).toInt()
                push(req, pct, "$label $pct%")
            } else {
                push(req, basePct, "$label ${fmtSize(done)}")
            }
        }
    }

    private fun queuePrefix(index: Int): String =
        if (queue.size > 1) "第 ${index.coerceAtMost(queue.size)}/${queue.size} 个 · " else ""

    private fun overallProgress(pct: Int): Int =
        if (queue.isEmpty()) pct
        else ((queueIndex * 100 + pct.coerceIn(0, 100)) / queue.size).coerceIn(0, 100)

    private fun push(req: Request, pct: Int, text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 500 && pct < 100) return
        lastPush = now
        val prefix = queuePrefix(queueIndex + 1)
        DownloadBus.update(
            DownloadBus.State(
                req.title, true, overallProgress(pct), prefix + text,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
        nm.notify(
            NOTIFICATION_ID,
            buildNotification(
                req, pct, prefix + text, true,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
    }

    private fun finishItemSuccess(req: Request, file: File) {
        lastFile = file
        lastTitle = req.title
        val prefix = queuePrefix(queueIndex + 1)
        val text = "已完成 · ${file.name}"
        DownloadBus.update(
            DownloadBus.State(
                req.title, true, overallProgress(100), prefix + text,
                fileName = file.name,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
        nm.notify(
            NOTIFICATION_ID,
            buildNotification(
                req, 100, prefix + text, true,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
    }

    private fun finishItemError(req: Request, msg: String) {
        lastError = msg
        val prefix = queuePrefix(queueIndex + 1)
        val text = "失败：$msg"
        DownloadBus.update(
            DownloadBus.State(
                req.title, true, overallProgress(0), prefix + text,
                error = msg,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
        nm.notify(
            NOTIFICATION_ID,
            buildNotification(
                req, 0, prefix + text, true,
                batchIndex = queueIndex + 1, batchTotal = queue.size
            )
        )
    }

    private fun finishBatch() {
        active = false
        val total = queue.size
        val single = total == 1
        val first = queue.firstOrNull()
        if (first == null) {
            stopSelf()
            return
        }
        val summary = when {
            single && successCount == 1 && lastFile != null ->
                "已保存到 Movies/BiliDiy/${lastFile!!.name}"
            single -> "下载失败：$lastError"
            failedCount > 0 -> "批量下载完成：成功 $successCount 个，失败 $failedCount 个"
            else -> "批量下载完成：共 $successCount 个"
        }
        val title = if (single) lastTitle.ifBlank { first.title } else "批量下载"
        val error = if (successCount == 0 && failedCount > 0) lastError else ""
        DownloadBus.update(
            DownloadBus.State(
                title, false, 100, summary,
                fileName = if (single) lastFile?.name.orEmpty() else "",
                error = error,
                batchIndex = total, batchTotal = total
            )
        )
        val n = buildNotification(
            first,
            100, summary, false,
            localPath = if (single && successCount == 1) lastFile?.absolutePath else null,
            openLocalList = !single,
            titleOverride = if (single) null else "批量下载完成",
            batchIndex = total, batchTotal = total
        )
        nm.notify(NOTIFICATION_ID, n)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(
        req: Request,
        pct: Int,
        text: String,
        ongoing: Boolean,
        localPath: String? = null,
        openLocalList: Boolean = false,
        titleOverride: String? = null,
        batchIndex: Int = 1,
        batchTotal: Int = 1
    ): Notification {
        // 单集完成点通知直接进本地播放；批量完成进本地列表；下载中回到在线播放页
        val open = when {
            !ongoing && !localPath.isNullOrBlank() -> Intent(this, LocalPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("path", localPath)
                putExtra("title", req.title)
            }
            !ongoing && openLocalList -> Intent(this, LocalVideosActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            else -> Intent(this, VideoActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("bvid", req.bvid)
                putExtra("ep_id", req.epId)
                putExtra("season_id", req.seasonId)
                putExtra("course_ep_id", req.courseEpId)
                putExtra("title", req.title)
            }
        }
        val pi = PendingIntent.getActivity(
            this, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentTitle = titleOverride
            ?: if (batchTotal > 1) "${req.title}（$batchIndex/$batchTotal）" else req.title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle(contentTitle)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pi)
            .apply {
                if (ongoing) {
                    val overall = if (batchTotal > 1) {
                        (((batchIndex - 1) * 100 + pct) / batchTotal).coerceIn(0, 100)
                    } else {
                        pct.coerceIn(0, 100)
                    }
                    if (overall > 0) setProgress(100, overall, false)
                    else setProgress(0, 0, true)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BiliDiy 后台下载进度"
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
