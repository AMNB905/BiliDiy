package com.gdk.biliapp

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 纯净播放页：只负责播放 + 画质 / 倍速 / 分P / 进度。
 * 支持两类内容：投稿视频（bvid）与番剧/影视（ep_id，走 PGC 接口）。
 * 竖屏为「视频 + 信息」上下布局，点左上角「横屏」进入全屏沉浸播放。
 */
class VideoActivity : BaseActivity() {

    private companion object {
        const val REQ_NOTIFY = 100
    }

    private var bvid = ""
    private var epId = 0L
    private var courseSeasonId = 0L
    private var courseEpId = 0L
    private var courseEpisodes: JSONArray? = null
    private var coursePreview = false
    private var aid = 0L
    private var cid = 0L
    private var durationSec = 0
    private var qn = PlaybackPrefs.DEFAULT_QN
    private var speed = 1f
    private var pages: JSONArray? = null
    private var pgcEps: JSONArray? = null
    private var titleText = ""
    private var seriesTitle = ""
    private val qualities = ArrayList<Pair<Int, String>>()
    private var downloading = false
    /** 播放器已接管画面后才允许状态文案随缓冲/播放/暂停变化 */
    private var playbackUi = false
    private var paidPreview = false
    private var resumeHint = ""
    /** 当前 PGC 会员内容使用的好友分享 ID；非空时只走共享播放，不下载 */
    private var shareGrantId = ""

    private var player: ExoPlayer? = null
    private var mp4Mode = false
    private var fallbackTried = false
    private var isLandscape = false

    /** 当前视频可用音轨；由 MpdBuilder 根据 playurl 的 dash 字段解析。 */
    private var audioTracks: List<AudioTrackInfo> = emptyList()
    /** 已经应用到播放器的音轨 representationId，避免 onTracksChanged 重复设置。 */
    private var appliedAudioId: String? = null

    /** 观看历史 / 续播 */
    private var historyKey = ""
    private var historyCat = "video"
    private var coverUrl = ""
    private var startMsExtra = 0L
    private var usedStartExtra = false
    private var lastHistorySave = 0L

    /** PGC 剧集列表，用于播放页快速上一集 / 下一集 */
    private val epIds = ArrayList<Long>()
    private var epIndex = -1

    private lateinit var pageRoot: View
    private lateinit var playerView: PlayerView
    private lateinit var playerContainer: FrameLayout
    private lateinit var overlayTop: View
    private lateinit var infoPanel: View
    private lateinit var infoContent: View
    private lateinit var chipsLandscape: View
    private lateinit var btnRotate: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDanmaku: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnAudio: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnPages: TextView
    private lateinit var btnPrevEp: TextView
    private lateinit var btnNextEp: TextView
    private lateinit var btnQualityL: TextView
    private lateinit var btnAudioL: TextView
    private lateinit var btnSpeedL: TextView
    private lateinit var btnPagesL: TextView
    private lateinit var btnDownload: TextView
    private lateinit var btnDownloadL: TextView
    private lateinit var tapLayer: View
    private lateinit var topGradient: View
    private lateinit var overlayBottom: View
    private lateinit var btnPlay: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCur: TextView
    private lateinit var tvDur: TextView
    private lateinit var pbPlayer: ProgressBar
    private lateinit var videoFrame: FrameLayout
    private lateinit var danmakuView: DanmakuView
    private lateinit var btnRatio: TextView
    private lateinit var btnRatioL: TextView
    private lateinit var btnDanmaku: TextView
    private lateinit var btnDanmakuL: TextView

    /** 弹幕只展示，不发送；关闭后停止动画并记住偏好 */
    private var danmakuEnabled = true
    private var danmakuCid = 0L
    private var danmakuRequest = 0

    /** 极简控件：默认隐藏，点一下画面才出现，播放中 3.5 秒自动收起 */
    private val hideHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var seeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Cloud.init(this)
        Member.init(this)
        // 进入播放页先恢复上次选择的画质，后续分P/下一集也沿用
        qn = PlaybackPrefs.quality(this)
        danmakuEnabled = PlaybackPrefs.danmakuEnabled(this)
        // 播放页背景是黑色视频区，图标用白色
        Ui.edgeToEdge(this, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_video)

        bvid = intent.getStringExtra("bvid") ?: ""
        epId = intent.getLongExtra("ep_id", 0L)
        courseSeasonId = intent.getLongExtra("season_id", 0L)
        courseEpId = intent.getLongExtra("course_ep_id", 0L)
        titleText = intent.getStringExtra("title") ?: ""
        startMsExtra = intent.getLongExtra("start_ms", 0L)
        if (bvid.isEmpty() && epId == 0L && courseSeasonId == 0L) { finish(); return }

        pageRoot = findViewById(R.id.pageRoot)
        playerView = findViewById(R.id.playerView)
        playerContainer = findViewById(R.id.playerContainer)
        videoFrame = findViewById(R.id.videoFrame)
        danmakuView = findViewById(R.id.danmakuView)
        overlayTop = findViewById(R.id.overlayTop)
        infoPanel = findViewById(R.id.infoPanel)
        infoContent = findViewById(R.id.infoContent)
        chipsLandscape = findViewById(R.id.chipsLandscape)
        btnRotate = findViewById(R.id.btnRotate)
        tvTitle = findViewById(R.id.tvTitle)
        tvMeta = findViewById(R.id.tvMeta)
        tvStatus = findViewById(R.id.tvStatus)
        tvDanmaku = findViewById(R.id.tvDanmaku)
        btnQuality = findViewById(R.id.btnQuality)
        btnAudio = findViewById(R.id.btnAudio)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnPages = findViewById(R.id.btnPages)
        btnPrevEp = findViewById(R.id.btnPrevEp)
        btnNextEp = findViewById(R.id.btnNextEp)
        btnQualityL = findViewById(R.id.btnQualityL)
        btnAudioL = findViewById(R.id.btnAudioL)
        btnSpeedL = findViewById(R.id.btnSpeedL)
        btnPagesL = findViewById(R.id.btnPagesL)
        btnDownload = findViewById(R.id.btnDownload)
        btnDownloadL = findViewById(R.id.btnDownloadL)
        btnRatio = findViewById(R.id.btnRatio)
        btnRatioL = findViewById(R.id.btnRatioL)
        btnDanmaku = findViewById(R.id.btnDanmaku)
        btnDanmakuL = findViewById(R.id.btnDanmakuL)
        tapLayer = findViewById(R.id.tapLayer)
        topGradient = findViewById(R.id.topGradient)
        overlayBottom = findViewById(R.id.overlayBottom)
        btnPlay = findViewById(R.id.btnPlay)
        seekBar = findViewById(R.id.seekBar)
        tvCur = findViewById(R.id.tvCur)
        tvDur = findViewById(R.id.tvDur)
        pbPlayer = findViewById(R.id.pbPlayer)

        if (titleText.isNotEmpty()) tvTitle.text = titleText

        findViewById<TextView>(R.id.btnBack).setOnClickListener { handleBack() }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnQualityL.setOnClickListener { showQualityDialog() }
        btnAudio.setOnClickListener { showAudioDialog() }
        btnAudioL.setOnClickListener { showAudioDialog() }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnSpeedL.setOnClickListener { showSpeedDialog() }
        btnPages.setOnClickListener { showPagesDialog() }
        btnPagesL.setOnClickListener { showPagesDialog() }
        btnDownload.setOnClickListener { showDownloadPicker() }
        btnDownloadL.setOnClickListener { showDownloadPicker() }
        btnRatio.setOnClickListener { showDisplayDialog() }
        btnRatioL.setOnClickListener { showDisplayDialog() }
        btnDanmaku.setOnClickListener { showDanmakuSettingsDialog() }
        btnDanmakuL.setOnClickListener { showDanmakuSettingsDialog() }
        btnPrevEp.setOnClickListener { switchSibling(-1) }
        btnNextEp.setOnClickListener { switchSibling(1) }
        btnRotate.setOnClickListener { toggleOrientation() }
        tapLayer.setOnClickListener { toggleControls() }
        btnPlay.setOnClickListener { togglePlay() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val d = player?.duration ?: 0L
                if (d > 0) tvCur.text = Ui.fmtDur(d * progress / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                hideHandler.removeCallbacks(hideTask)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val p = player
                val d = p?.duration ?: 0L
                if (p != null && d > 0) p.seekTo(d * (sb?.progress ?: 0) / 1000)
                danmakuView.refresh()
                if (controlsVisible && p?.isPlaying == true) scheduleHide()
            }
        })
        hideHandler.post(progressTask)
        setControlsVisible(true)

        danmakuView.positionProvider = { player?.currentPosition ?: 0L }
        danmakuView.playingProvider = { player?.isPlaying == true }
        danmakuView.setEnabledState(danmakuEnabled)
        danmakuView.visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
        applyDanmakuStyle()
        tvDanmaku.text = if (danmakuEnabled) "弹幕准备中" else "弹幕已关闭"

        applyInsets()
        applyOrientation(false)
        syncChips()
        playerContainer.post { applyVideoFit() }

        // 系统返回键：横屏先回竖屏，再按才退出；兼容 Android 13+ 预测式返回
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape) {
                    exitLandscape()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadInfo()
        Cloud.gate(this)
    }

    private fun handleBack() {
        if (isLandscape) exitLandscape() else finish()
    }

    private fun exitLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyOrientation(false)
    }

    // ---------- 沉浸式 / 横屏 ----------

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            overlayTop.setPadding(
                Ui.dp(this, 10), Ui.dp(this, 8) + b.top,
                Ui.dp(this, 10), Ui.dp(this, 8)
            )
            infoContent.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 18),
                Ui.dp(this, 16), Ui.dp(this, 24) + b.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun toggleOrientation() {
        if (isLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            applyOrientation(false)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            applyOrientation(true)
        }
    }

    // ---------- 极简控件 ----------

    private val hideTask = Runnable { setControlsVisible(false) }

    private val progressTask = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !seeking) {
                val d = p.duration
                if (d > 0) {
                    seekBar.progress = (p.currentPosition * 1000 / d).toInt().coerceIn(0, 1000)
                    tvDur.text = Ui.fmtDur(d)
                }
                tvCur.text = Ui.fmtDur(p.currentPosition)
                if (p.isPlaying && System.currentTimeMillis() - lastHistorySave > 5000) {
                    saveHistory(p.currentPosition)
                }
            }
            hideHandler.postDelayed(this, 500)
        }
    }

    /** 落盘观看进度；片尾 5 秒内视为看完，进度归零，下次从头开始 */
    private fun saveHistory(pos: Long) {
        if (historyKey.isEmpty() || titleText.isBlank()) return
        // 课程记录没有可回跳的 bvid/ep_id，先不写入，避免“继续观看”点开无反应
        if (courseSeasonId > 0L) return
        val dur = player?.duration?.takeIf { it > 0 } ?: (durationSec * 1000L)
        var p = pos
        if (dur > 0 && p >= dur - 5000) p = 0
        if (p < 3000) return
        History.save(
            this,
            History.Item(
                key = historyKey,
                kind = if (epId > 0) "pgc" else "video",
                bvid = bvid,
                epId = epId,
                title = titleText,
                cover = coverUrl,
                pos = p,
                dur = dur,
                at = System.currentTimeMillis(),
                cat = historyCat
            )
        )
        lastHistorySave = System.currentTimeMillis()
    }

    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideTask)
        hideHandler.postDelayed(hideTask, 3500)
    }

    private fun setControlsVisible(show: Boolean) {
        controlsVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        overlayTop.visibility = v
        overlayBottom.visibility = v
        topGradient.visibility = v
        hideHandler.removeCallbacks(hideTask)
        if (show && player?.isPlaying == true) scheduleHide()
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun refreshPlayIcon() {
        btnPlay.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        refreshPlayIcon()
        setControlsVisible(true)
    }

    /** 竖屏 = 视频 16:9 + 下方信息；横屏 = 视频铺满 + 顶部浮层操作 */
    private fun applyOrientation(land: Boolean) {
        isLandscape = land
        val lp = playerContainer.layoutParams
        if (land) {
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            infoPanel.visibility = View.GONE
            chipsLandscape.visibility = View.VISIBLE
            pageRoot.setBackgroundColor(Color.BLACK)
            btnRotate.text = "竖屏"
            Ui.rehideSystemBars(this)
            Ui.barIcons(this, false, false)
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            playerContainer.post {
                val lp2 = playerContainer.layoutParams
                lp2.height = playerContainer.width * 9 / 16
                playerContainer.layoutParams = lp2
                playerContainer.requestLayout()
            }
            infoPanel.visibility = View.VISIBLE
            chipsLandscape.visibility = View.GONE
            pageRoot.setBackgroundColor(getColor(R.color.bg))
            btnRotate.text = "横屏"
            Ui.rehideSystemBars(this)
            // 上方是黑色视频 → 状态栏白图标；下方信息区是浅色 → 导航栏深图标
            Ui.barIcons(this, false, Ui.isLight(this))
        }
        playerContainer.layoutParams = lp
        playerContainer.requestLayout()
        syncChips()
        playerContainer.post { applyVideoFit() }
        setControlsVisible(true)
    }

    // ---------- 加载信息 ----------

    private fun loadInfo() {
        if (courseSeasonId > 0L) loadCourse()
        else if (epId > 0L) loadPgc()
        else loadUgc()
    }

    @SuppressLint("SetTextI18n")
    private fun loadUgc() {
        val ref = bvid
        Bili.io {
            try {
                val v = Bili.view(ref)
                aid = v.optLong("aid", 0)
                cid = v.optLong("cid", 0)
                durationSec = v.optInt("duration", 0)
                pages = v.optJSONArray("pages")
                val title = v.optString("title", "")
                val owner = v.optJSONObject("owner")?.optString("name", "") ?: ""
                val stat = v.optJSONObject("stat")
                Bili.main {
                    shareGrantId = ""
                    titleText = title
                    seriesTitle = title
                    coverUrl = v.optString("pic", "")
                    historyKey = "v:$ref:$cid"
                    historyCat = "video"
                    tvTitle.text = title
                    tvMeta.text = "$owner · ${Ui.fmtCount(stat?.optLong("view", 0) ?: 0)}播放"
                    val ps = pages
                    if (ps != null && ps.length() > 1) {
                        btnPages.visibility = View.VISIBLE
                        btnPages.text = "分P · ${ps.length()}"
                        syncChips()
                    }
                    if (cid > 0) prepare(qn, 0)
                }
            } catch (e: Exception) {
                Bili.main { showError("加载失败: ${e.message}") }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadPgc() {
        Bili.io {
            try {
                val season = Bili.pgcSeasonByEp(epId)
                val eps = season.optJSONArray("episodes") ?: throw Exception("无剧集信息")
                var ep = eps.optJSONObject(0)
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    if (e.optLong("id", 0) == epId) { ep = e; break }
                }
                if (ep == null) throw Exception("找不到该集")
                aid = ep.optLong("aid", 0)
                cid = ep.optLong("cid", 0)
                durationSec = (ep.optLong("duration", 0) / 1000).toInt()
                val seasonTitle = Ui.stripEm(season.optString("title", ""))
                val epTitle = ep.optString("long_title", "").ifEmpty { ep.optString("title", "") }
                val seasonType = season.optInt("type", 2)
                val isAnime = seasonType == 1 || seasonType == 4
                historyCat = if (isAnime) "anime" else "movie"
                val kind = when (seasonType) {
                    1 -> "番剧"
                    4 -> "国创"
                    else -> "影视"
                }

                // 记录完整剧集列表与当前位置，供播放页「上一集 / 下一集」使用
                epIds.clear()
                epIndex = -1
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val id = e.optLong("id", 0)
                    if (id <= 0) continue
                    epIds.add(id)
                    if (id == epId) epIndex = epIds.size - 1
                }
                val vip = PgcAccess.requiresVip(season, ep)
                val shareGrant = if (vip && !BiliUser.isVip(this)) MemberShare.active(this) else null
                pgcEps = eps
                seriesTitle = seasonTitle

                val others = eps.length()
                Bili.main {
                    shareGrantId = shareGrant?.id ?: ""
                    titleText = if (epTitle.isNotEmpty()) "$seasonTitle · $epTitle" else seasonTitle
                    coverUrl = season.optString("cover", "")
                    historyKey = "p:$epId"
                    tvTitle.text = titleText
                    tvMeta.text = "$kind · 共 $others 集 · ${Ui.fmtDur(durationSec.toLong())}"
                    if (others > 1) {
                        btnPages.visibility = View.VISIBLE
                        btnPages.text = if (isAnime) "选集 · $others" else "版本 · $others"
                        syncChips()
                    }
                    updateSiblingButtons()
                    // 好友分享只提供播放，不提供下载。
                    btnDownload.visibility = if (shareGrantId.isBlank()) View.VISIBLE else View.GONE
                    btnDownloadL.visibility = if (shareGrantId.isBlank()) View.VISIBLE else View.GONE
                    if (vip && !Member.isActive(this)) {
                        playbackUi = false
                        tvStatus.text = "会员专享 · 激活后观看"
                        Member.require(this) {
                            if (!isFinishing && !isDestroyed && cid > 0) {
                                shareGrantId = MemberShare.active(this)?.id ?: ""
                                prepare(qn, 0)
                            }
                        }
                    } else if (cid > 0) {
                        prepare(qn, 0)
                    }
                }
            } catch (e: Exception) {
                Bili.main { showError("加载失败: ${e.message}") }
            }
        }
    }

    // ---------- 付费课程 ----------

    @SuppressLint("SetTextI18n")
    private fun loadCourse() {
        val sid = courseSeasonId
        Bili.io {
            try {
                val season = Bili.courseSeason(sid)
                val eps = season.optJSONArray("episodes") ?: JSONArray()
                if (eps.length() == 0) throw Exception("该课程暂无课时")
                courseEpisodes = eps

                var chosen: JSONObject? = null
                if (courseEpId > 0L) {
                    for (i in 0 until eps.length()) {
                        val e = eps.optJSONObject(i) ?: continue
                        if (e.optLong("id", 0) == courseEpId) { chosen = e; break }
                    }
                }
                if (chosen == null) {
                    for (i in 0 until eps.length()) {
                        val e = eps.optJSONObject(i) ?: continue
                        if (e.optBoolean("playable", false)) { chosen = e; break }
                    }
                }
                if (chosen == null) chosen = eps.optJSONObject(0)

                Bili.main {
                    if (isFinishing || isDestroyed) return@main
                    shareGrantId = ""
                    val seasonTitle = Ui.stripEm(season.optString("title", titleText))
                    seriesTitle = seasonTitle
                    val count = season.optInt("ep_count", 0).takeIf { it > 0 } ?: eps.length()
                    val price = season.optJSONObject("payment")?.optString("price_format", "") ?: ""
                    coverUrl = season.optString("cover", "")
                    titleText = seasonTitle.ifBlank { titleText }
                    historyCat = "video"
                    tvTitle.text = titleText
                    tvMeta.text = "付费课程 · 共 $count 课时" + if (price.isNotBlank()) " · ¥$price" else ""
                    if (eps.length() > 1) {
                        btnPages.visibility = View.VISIBLE
                        btnPages.text = "选集 · ${eps.length()}"
                        syncChips()
                    }
                    updateSiblingButtons()

                    val ep = chosen
                    if (ep == null) {
                        showError("课程课时信息不完整")
                        return@main
                    }
                    bindCourseEpisode(ep, seasonTitle)
                    val canDownload = ep.optBoolean("playable", false) ||
                        (0 until eps.length()).any {
                            eps.optJSONObject(it)?.optBoolean("playable", false) == true
                        }
                    btnDownload.visibility = if (canDownload) View.VISIBLE else View.GONE
                    btnDownloadL.visibility = if (canDownload) View.VISIBLE else View.GONE
                    if (ep.optBoolean("playable", false)) {
                        tvStatus.text = "第 ${ep.optInt("index", 1)} 课 · 免费观看"
                        prepare(qn, 0)
                    } else {
                        tvStatus.text = "本课需在 B 站购买后观看"
                    }
                }
            } catch (e: Exception) {
                Bili.main { showError("课程加载失败: ${e.message}") }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindCourseEpisode(ep: JSONObject, seasonTitle: String) {
        courseEpId = ep.optLong("id", 0)
        aid = ep.optLong("aid", 0)
        cid = ep.optLong("cid", 0)
        durationSec = ep.optInt("duration", 0)
        val epTitle = Ui.stripEm(ep.optString("title", ""))
        titleText = if (epTitle.isNotEmpty()) "$seasonTitle · $epTitle" else seasonTitle
        historyKey = "c:$courseSeasonId:$courseEpId"
        tvTitle.text = titleText
        val idx = ep.optInt("index", 0)
        if (idx > 0) tvMeta.text = "${tvMeta.text} · 第 $idx 课"
    }

    private fun showCourseEpisodesDialog() {
        val eps = courseEpisodes
        if (eps == null || eps.length() == 0) {
            Ui.toast(this, "课程信息尚未加载")
            return
        }
        val labels = Array(eps.length()) { i ->
            val e = eps.optJSONObject(i)
            val idx = e?.optInt("index", i + 1) ?: (i + 1)
            val name = e?.optString("title", "") ?: "第 $idx 课"
            val state = if (e?.optBoolean("playable", false) == true) "可观看" else "需购买"
            "$idx. $name · $state"
        }
        AlertDialog.Builder(this)
            .setTitle("选择课时")
            .setItems(labels) { d, which ->
                d.dismiss()
                val ep = eps.optJSONObject(which) ?: return@setItems
                if (!ep.optBoolean("playable", false)) {
                    showCoursePaywall()
                    return@setItems
                }
                val id = ep.optLong("id", 0)
                if (id > 0 && id != courseEpId) switchCourseEpisode(id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCoursePaywall() {
        AlertDialog.Builder(this)
            .setTitle("付费课程")
            .setMessage("该课时需要先在 B 站购买课程，BiliDiy 不能代替 B 站课程购买。当前可免费观看标注为可观看的课时。")
            .setPositiveButton("去 B 站购买") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.bilibili.com/cheese/play/ss$courseSeasonId")))
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun switchCourseEpisode(id: Long) {
        startActivity(
            Intent(this, VideoActivity::class.java)
                .putExtra("season_id", courseSeasonId)
                .putExtra("course_ep_id", id)
        )
        finish()
    }

    // ---------- 下载 ----------

    private fun currentCourseEpisode(): JSONObject? {
        val eps = courseEpisodes ?: return null
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            if (e.optLong("id", 0) == courseEpId) return e
        }
        return null
    }

    // ---------- 批量下载 ----------

    private data class DownloadChoice(val label: String, val task: JSONObject)

    private fun showDownloadPicker() {
        if (shareGrantId.isNotBlank()) {
            Ui.toast(this, "好友分享仅支持播放，不提供下载")
            return
        }
        if (downloading || DownloadBus.state?.running == true) {
            Ui.toast(this, "已有下载任务正在进行")
            return
        }
        when {
            courseSeasonId > 0L -> {
                val eps = courseEpisodes
                if (eps == null || eps.length() == 0) {
                    Ui.toast(this, "课程信息尚未加载")
                    return
                }
                showDownloadOptions(courseDownloadChoices(eps))
            }
            epId > 0L -> {
                val cached = pgcEps
                if (cached != null && cached.length() > 0) {
                    showDownloadOptions(pgcDownloadChoices(cached))
                } else {
                    loadPgcEpisodesForDownload { eps ->
                        showDownloadOptions(pgcDownloadChoices(eps))
                    }
                }
            }
            else -> showDownloadOptions(ugcDownloadChoices())
        }
    }

    private fun loadPgcEpisodesForDownload(done: (JSONArray) -> Unit) {
        val currentEp = epId
        Bili.io {
            try {
                val season = Bili.pgcSeasonByEp(currentEp)
                val eps = season.optJSONArray("episodes") ?: JSONArray()
                val st = Ui.stripEm(season.optString("title", seriesTitle))
                Bili.main {
                    if (isFinishing || isDestroyed) return@main
                    pgcEps = eps
                    if (st.isNotBlank()) seriesTitle = st
                    done(eps)
                }
            } catch (e: Exception) {
                Bili.main {
                    if (!isFinishing && !isDestroyed) Ui.toast(this@VideoActivity, "剧集加载失败：${e.message}")
                }
            }
        }
    }

    private fun showDownloadOptions(choices: List<DownloadChoice>) {
        if (choices.isEmpty()) {
            Ui.toast(this, "没有可下载的集数")
            return
        }
        if (choices.size == 1) {
            startBatch(choices.map { it.task })
            return
        }
        val labels = arrayOf(
            "下载当前集",
            "下载全部（${choices.size} 个）",
            "选择集数下载"
        )
        AlertDialog.Builder(this)
            .setTitle("下载")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> downloadCurrent()
                    1 -> startBatch(choices.map { it.task })
                    2 -> showDownloadSelection(choices)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDownloadSelection(choices: List<DownloadChoice>) {
        val checked = BooleanArray(choices.size)
        val labels = choices.map { it.label }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择要下载的集数")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("下载选中") { _, _ ->
                val selected = choices.filterIndexed { index, _ -> checked[index] }.map { it.task }
                if (selected.isEmpty()) {
                    Ui.toast(this, "请先选择至少一个集数")
                } else {
                    startBatch(selected)
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全选", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                for (i in checked.indices) {
                    checked[i] = true
                    dialog.listView.setItemChecked(i, true)
                }
            }
        }
        dialog.show()
    }

    private fun startBatch(tasks: List<JSONObject>) {
        if (tasks.isEmpty()) return
        if (downloading || DownloadBus.state?.running == true) {
            Ui.toast(this, "已有下载任务正在进行")
            return
        }
        // 首次下载先说明后台保活；批量任务会立即开始
        if (BackgroundGuard.shouldPrompt(this)) BackgroundGuard.showGuide(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        val arr = JSONArray()
        tasks.forEach { arr.put(it) }
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_ITEMS_JSON, arr.toString())
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            downloading = true
            setDownloadBusy(true)
            updateDownloadStatus("已加入批量下载（${tasks.size} 个）…")
            Ui.toast(this, "已开始批量下载，共 ${tasks.size} 个")
        } catch (e: Exception) {
            downloading = false
            setDownloadBusy(false)
            Ui.toast(this, "无法启动批量下载：${e.message}")
        }
    }

    private fun downloadTask(
        title: String,
        bvid: String,
        cid: Long,
        epIdValue: Long,
        seasonIdValue: Long,
        courseEpIdValue: Long
    ): JSONObject {
        val referer = if (seasonIdValue > 0L) {
            "https://www.bilibili.com/cheese/play/ss$courseSeasonId"
        } else {
            Bili.currentReferer
        }
        return JSONObject().apply {
            put("title", title)
            put("bvid", bvid)
            put("cid", cid)
            put("ep_id", epIdValue)
            put("season_id", seasonIdValue)
            put("course_ep_id", courseEpIdValue)
            put("qn", qn)
            put("referer", referer)
        }
    }

    private fun ugcDownloadChoices(): List<DownloadChoice> {
        val ps = pages
        if (ps == null || ps.length() == 0) {
            if (cid <= 0L) return emptyList()
            val name = titleText.ifBlank { "BiliDiy视频" }
            return listOf(
                DownloadChoice(name, downloadTask(name, bvid, cid, 0L, 0L, 0L))
            )
        }
        val list = ArrayList<DownloadChoice>()
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            val c = p.optLong("cid", 0L)
            if (c <= 0L) continue
            val page = p.optInt("page", i + 1)
            val part = p.optString("part", "").trim()
            val label = if (part.isEmpty()) "P$page" else "P$page $part"
            val name = if (ps.length() > 1) {
                "${seriesTitle.ifBlank { titleText }} · $label"
            } else {
                titleText.ifBlank { label }
            }
            list.add(DownloadChoice(label, downloadTask(name, bvid, c, 0L, 0L, 0L)))
        }
        return list
    }

    private fun pgcDownloadChoices(eps: JSONArray): List<DownloadChoice> {
        val st = seriesTitle.ifBlank { titleText.substringBefore(" · ") }
        val list = ArrayList<DownloadChoice>()
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            val id = e.optLong("id", 0L)
            if (id <= 0L) continue
            val index = e.optString("title", "").trim().ifBlank { "第 ${i + 1} 集" }
            val sub = e.optString("long_title", "").trim()
            val label = "$index $sub".trim()
            val name = "$st · $label"
            list.add(
                DownloadChoice(
                    label,
                    downloadTask(name, "", e.optLong("cid", 0L), id, 0L, 0L)
                )
            )
        }
        return list
    }

    private fun courseDownloadChoices(eps: JSONArray): List<DownloadChoice> {
        val st = seriesTitle.ifBlank { titleText.substringBefore(" · ") }
        val list = ArrayList<DownloadChoice>()
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            if (!e.optBoolean("playable", false)) continue
            val id = e.optLong("id", 0L)
            if (id <= 0L) continue
            val index = e.optInt("index", i + 1)
            val name = Ui.stripEm(e.optString("title", "")).ifBlank { "第 $index 课" }
            val label = "$index. $name"
            val title = "$st · $label"
            list.add(
                DownloadChoice(
                    label,
                    downloadTask(title, "", e.optLong("cid", 0L), 0L, courseSeasonId, id)
                )
            )
        }
        return list
    }

    private fun setDownloadBusy(busy: Boolean) {
        btnDownload.isEnabled = !busy
        btnDownloadL.isEnabled = !busy
        btnDownload.alpha = if (busy) 0.6f else 1f
        btnDownloadL.alpha = if (busy) 0.6f else 1f
        btnDownload.text = if (busy) "下载中…" else "下载到本机"
        btnDownloadL.text = if (busy) "下载中" else "下载"
    }

    private fun updateDownloadStatus(text: String) {
        Bili.main {
            if (!isFinishing && !isDestroyed) tvStatus.text = text
        }
    }

    private fun renderDownloadState(s: DownloadBus.State) {
        if (!::tvStatus.isInitialized || !::btnDownload.isInitialized) return
        val same = (titleText.isNotEmpty() && s.title == titleText) || s.batchTotal > 1
        if (s.running) {
            downloading = true
            setDownloadBusy(true)
            if (!same) {
                btnDownload.text = "队列忙碌"
                btnDownloadL.text = "排队中"
            }
            if (same || titleText.isEmpty()) tvStatus.text = s.text
        } else {
            downloading = false
            setDownloadBusy(false)
            if (same) {
                tvStatus.text = if (s.error.isNotEmpty()) {
                    "下载失败：${s.error}"
                } else if (s.fileName.isNotEmpty()) {
                    "已下载 · ${s.fileName}"
                } else {
                    s.text
                }
                if (s.error.isNotEmpty()) {
                    btnDownload.text = "重试下载"
                    btnDownloadL.text = "重试"
                } else if (s.fileName.isNotEmpty()) {
                    btnDownload.text = "重新下载"
                    btnDownloadL.text = "重下"
                }
            }
        }
    }

    private val downloadStateListener: (DownloadBus.State) -> Unit = { s ->
        if (!isFinishing && !isDestroyed) renderDownloadState(s)
    }

    @SuppressLint("SetTextI18n")
    private fun downloadCurrent() {
        if (shareGrantId.isNotBlank()) {
            Ui.toast(this, "好友分享仅支持播放，不提供下载")
            return
        }
        if (downloading || DownloadBus.state?.running == true) {
            Ui.toast(this, "已有下载任务正在进行")
            return
        }
        if (courseSeasonId > 0L) {
            val ep = currentCourseEpisode()
            if (ep == null) {
                Ui.toast(this, "课程信息尚未加载")
                return
            }
            if (!ep.optBoolean("playable", false)) {
                Ui.toast(this, "该课时需在 B 站购买后才能下载")
                return
            }
        }
        if (cid == 0L) {
            Ui.toast(this, "视频信息尚未加载")
            return
        }

        // 首次下载先说明后台保活；下载会立即开始，不被引导弹窗挡住
        if (BackgroundGuard.shouldPrompt(this)) BackgroundGuard.showGuide(this)
        startDownload()
    }

    @SuppressLint("SetTextI18n")
    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        val title = titleText.ifBlank { "BiliDiy_${System.currentTimeMillis()}" }
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_TITLE, title)
            putExtra(DownloadService.EXTRA_BVID, bvid)
            putExtra(DownloadService.EXTRA_CID, cid)
            putExtra(DownloadService.EXTRA_EP_ID, epId)
            putExtra(DownloadService.EXTRA_SEASON_ID, courseSeasonId)
            putExtra(DownloadService.EXTRA_COURSE_EP_ID, courseEpId)
            putExtra(DownloadService.EXTRA_QN, qn)
            putExtra(DownloadService.EXTRA_REFERER, Bili.currentReferer)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            downloading = true
            setDownloadBusy(true)
            updateDownloadStatus("已加入后台下载…")
            Ui.toast(this, "已开始后台下载，退出 App 也会继续")
        } catch (e: Exception) {
            downloading = false
            setDownloadBusy(false)
            Ui.toast(this, "无法启动下载：${e.message}")
        }
    }

    // ---------- 播放 ----------

    @SuppressLint("SetTextI18n")
    private fun prepare(target: Int, resumeMs: Long) {
        if (cid == 0L) return
        audioTracks = emptyList()
        appliedAudioId = null
        val resume = when {
            resumeMs > 0 -> resumeMs
            startMsExtra > 0 && !usedStartExtra -> {
                usedStartExtra = true
                startMsExtra
            }
            else -> History.get(this, historyKey)?.pos ?: 0L
        }
        val fromHistory = resumeMs == 0L && resume > 3000L
        Bili.currentReferer = if (courseSeasonId > 0L) {
            "https://www.bilibili.com/cheese/play/ss$courseSeasonId"
        } else {
            "https://www.bilibili.com/"
        }
        ensureDanmaku()
        val bv = bvid
        val cidLocal = cid
        val epLocal = epId
        val shareLocal = shareGrantId
        Bili.io {
            try {
                // 正式版：先由服务端解析 playurl，只拿 CDN 直链；账号/设备鉴权失败不回退直连
                val pu = if (courseSeasonId > 0L) {
                    try {
                        Bili.coursePlayurl(courseEpId, target)
                    } catch (e: Exception) {
                        Bili.main { showError("课程播放失败: ${e.message}") }
                        return@io
                    }
                } else {
                    if (shareLocal.isBlank()) {
                        if (Bili.cookie.isBlank()) Cloud.ensureCookie(this@VideoActivity)
                        if (Bili.cookie.isBlank()) {
                            Bili.main { showError("登录已失效，请重新登录后播放") }
                            return@io
                        }
                    }
                    try {
                        when {
                            epLocal > 0 && shareLocal.isNotBlank() ->
                                MemberShare.playurl(this@VideoActivity, shareLocal, epLocal, target)
                            epLocal > 0 -> Bili.pgcPlayurl(epLocal, target)
                            else -> Bili.playurl(bv, cidLocal, target)
                        }
                    } catch (e: Exception) {
                        Bili.main { showError("本地解析失败: ${e.message}") }
                        return@io
                    }
                }
                qn = pu.optInt("quality", target)
                coursePreview = pu.optInt("is_preview", 0) == 1
                readQualities(pu)

                val dash = pu.optJSONObject("dash")
                val tl = pu.optLong("timelength", 0L)
                val durMs = if (tl > 0) tl else durationSec * 1000L
                val mpd = File(cacheDir, "manifest.mpd")
                val buildResult = dash?.let { MpdBuilder.build(it, qn, durMs, mpd) }
                val ok = buildResult != null
                audioTracks = buildResult?.audioTracks ?: emptyList()
                appliedAudioId = null

                if (!ok) {
                    // 影视「付费」片只给试看 durl；课程同样走 durl 兜底
                    val durl = pu.optJSONArray("durl")
                    if ((epLocal > 0 || courseSeasonId > 0L) && durl != null && durl.length() > 0) {
                        val preview = pu.optInt("is_preview", 0) == 1
                        playMp4(durl.optJSONObject(0)?.optString("url", "") ?: "", preview, resume)
                        return@io
                    }
                    if (courseSeasonId > 0L) {
                        Bili.main { showError("课程视频流解析失败，可能未购买或已下架") }
                        return@io
                    }
                    fallbackMp4(resume)
                    return@io
                }

                Bili.main {
                    if (isFinishing || isDestroyed) return@main
                    releasePlayer()
                    mp4Mode = false
                    val selector = DefaultTrackSelector(this@VideoActivity)
                    val p = ExoPlayer.Builder(this@VideoActivity)
                        .setTrackSelector(selector)
                        .build()
                    player = p
                    applyAudio(p)
                    playerView.player = p
                    val source = DashMediaSource.Factory(DualDataSourceFactory())
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(mpd)))
                    p.setMediaSource(source, resume)
                    p.addListener(playListener)
                    playbackUi = true
                    paidPreview = false
                    p.prepare()
                    p.playWhenReady = true
                    if (speed != 1f) p.setPlaybackSpeed(speed)
                    playerContainer.keepScreenOn = true
                    refreshPlayIcon()
                    setControlsVisible(true)
                    syncChips()
                    resumeHint = if (fromHistory) {
                        " · 已从 ${Ui.fmtDur(resume / 1000)} 续播"
                    } else {
                        ""
                    }
                    val courseTag = if (courseSeasonId > 0L) " · 课程" else ""
                    tvStatus.text = if (fromHistory) {
                        "正在播放 · ${qualityName(qn)}$courseTag · 已从 ${Ui.fmtDur(resume / 1000)} 续播"
                    } else {
                        "正在播放 · ${qualityName(qn)}$courseTag"
                    }
                }
            } catch (e: Exception) {
                showFallbackOrError(e.message ?: "未知错误", resume)
            }
        }
    }

    private fun readQualities(pu: JSONObject) {
        qualities.clear()
        val aq = pu.optJSONArray("accept_quality")
        val ad = pu.optJSONArray("accept_description")
        if (aq != null && ad != null) {
            for (i in 0 until minOf(aq.length(), ad.length())) {
                val id = aq.optInt(i, 0)
                if (id == 126) continue          // 杜比视界：多数设备无法解码
                qualities.add(id to ad.optString(i, "${id}P"))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showFallbackOrError(msg: String, resumeMs: Long) {
        if (!fallbackTried) {
            fallbackTried = true
            fallbackMp4(resumeMs)
        } else {
            Bili.main { showError(msg) }
        }
    }

    /** DASH 不可用时的兜底：先试 html5 MP4 直链（仅投稿视频） */
    private fun fallbackMp4(resumeMs: Long) {
        if (cid == 0L || bvid.isEmpty()) {
            Bili.main { showError("该内容暂无可播放的视频流（可能需购买或已下线）") }
            return
        }
        val bv = bvid
        val cidLocal = cid
        Bili.io {
            try {
                val d = Bili.playurlHtml5(bv, cidLocal, 80)
                val durl = d.optJSONArray("durl") ?: throw Exception("无 MP4 流")
                if (durl.length() == 0) throw Exception("无 MP4 流")
                val url = durl.optJSONObject(0)?.optString("url", "") ?: ""
                if (url.isEmpty()) throw Exception("无 MP4 地址")
                playMp4(url, false, resumeMs)
            } catch (e: Exception) {
                Bili.main { showError("播放失败: ${e.message}") }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun playMp4(url: String, preview: Boolean, resumeMs: Long) {
        if (url.isEmpty()) {
            Bili.main { showError("该内容需购买后才能观看") }
            return
        }
        Bili.main {
            if (isFinishing || isDestroyed) return@main
            releasePlayer()
            mp4Mode = true
            audioTracks = emptyList()
            appliedAudioId = null
            val p = ExoPlayer.Builder(this@VideoActivity).build()
            player = p
            applyAudio(p)
            playerView.player = p
            val item = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            val source = ProgressiveMediaSource.Factory(DualDataSourceFactory())
                .createMediaSource(item)
            p.setMediaSource(source, resumeMs)
            p.addListener(playListener)
            playbackUi = true
            paidPreview = preview
            resumeHint = ""
            p.prepare()
            p.playWhenReady = true
            if (speed != 1f) p.setPlaybackSpeed(speed)
            playerContainer.keepScreenOn = true
            refreshPlayIcon()
            setControlsVisible(true)
            btnQuality.text = "单画质"
            btnQualityL.text = "单画质"
            btnAudio.text = "音轨 单轨"
            btnAudioL.text = "单轨"
            if (preview) {
                tvStatus.text = "付费影片 · 当前仅提供试看"
                Ui.toast(this, "付费影片：仅试看，完整版需在 B 站购买")
            } else {
                tvStatus.text = "MP4 兼容播放"
            }
        }
    }

    private fun applyAudio(p: ExoPlayer) {
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true)
    }

    /**
     * 当前偏好下真正能播的音轨。
     *
     * auto：杜比 > FLAC > AAC；如果杜比没有设备解码器，就自动降到 FLAC/AAC。
     * 手动选择杜比或 FLAC 但设备不支持时，同样回到 AAC，绝不让播放器选到无声轨。
     */
    private fun preferredAudio(): AudioTrackInfo? {
        val playable = audioTracks.filter { it.supported }
        if (playable.isEmpty()) return null
        fun first(kind: AudioKind) = playable.firstOrNull { it.kind == kind }
        return when (PlaybackPrefs.audioMode(this)) {
            PlaybackPrefs.AUDIO_DOLBY ->
                first(AudioKind.DOLBY) ?: first(AudioKind.AAC) ?: first(AudioKind.FLAC) ?: playable.first()
            PlaybackPrefs.AUDIO_FLAC ->
                first(AudioKind.FLAC) ?: first(AudioKind.AAC) ?: first(AudioKind.DOLBY) ?: playable.first()
            PlaybackPrefs.AUDIO_AAC ->
                first(AudioKind.AAC) ?: first(AudioKind.FLAC) ?: first(AudioKind.DOLBY) ?: playable.first()
            else ->
                first(AudioKind.DOLBY) ?: first(AudioKind.FLAC) ?: first(AudioKind.AAC) ?: playable.first()
        }
    }

    private fun audioChipLabel(): String {
        if (mp4Mode) return "单轨"
        val info = preferredAudio() ?: return "AAC"
        return if (PlaybackPrefs.audioMode(this) == PlaybackPrefs.AUDIO_AUTO) {
            "自动·${info.shortLabel}"
        } else {
            info.shortLabel
        }
    }

    private fun audioModeKind(mode: String): AudioKind? = when (mode) {
        PlaybackPrefs.AUDIO_DOLBY -> AudioKind.DOLBY
        PlaybackPrefs.AUDIO_FLAC -> AudioKind.FLAC
        PlaybackPrefs.AUDIO_AAC -> AudioKind.AAC
        else -> null
    }

    private fun audioModeLabel(mode: String): String {
        if (mode == PlaybackPrefs.AUDIO_AUTO) {
            return "自动（${preferredAudio()?.shortLabel ?: "单轨"}）"
        }
        val kind = audioModeKind(mode) ?: return "自动"
        val track = audioTracks.firstOrNull { it.kind == kind }
            ?: return "${kind.title} · 本视频无此音轨"
        val name = if (kind == AudioKind.FLAC) "FLAC 无损" else kind.title
        val unsupported = if (track.supported) "" else "（设备不支持）"
        return "$name · ${track.detail}$unsupported"
    }

    /** 把偏好音轨映射到当前 MPD 的音频 TrackGroup，并强制 ExoPlayer 选择它。 */
    private fun applyAudioSelection() {
        if (mp4Mode) return
        val p = player ?: return
        val target = preferredAudio() ?: run {
            appliedAudioId = null
            syncChips()
            return
        }
        if (appliedAudioId == target.representationId) return

        var group: Tracks.Group? = null
        var trackIndex = -1
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                val format = g.getTrackFormat(i)
                val sameId = format.id == target.representationId
                val sameCodec = !format.codecs.isNullOrBlank() && format.codecs == target.codecs
                if (sameId || sameCodec) {
                    group = g
                    trackIndex = i
                    break
                }
            }
            if (group != null) break
        }

        val audioGroup = group ?: return
        if (trackIndex < 0 || !audioGroup.isTrackSupported(trackIndex)) return

        // 先记录再设置，避免 onTracksChanged 重入时反复覆盖。
        appliedAudioId = target.representationId
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(audioGroup.mediaTrackGroup, trackIndex))
            .build()
        syncChips()
        tvStatus.text = "音轨：${target.detail}"
    }

    private fun showAudioDialog() {
        if (mp4Mode) {
            Ui.toast(this, "兼容模式只有一条 AAC 音轨")
            return
        }
        if (audioTracks.isEmpty()) {
            Ui.toast(this, "当前视频没有可切换的音轨")
            return
        }

        val modes = arrayOf(
            PlaybackPrefs.AUDIO_AUTO,
            PlaybackPrefs.AUDIO_DOLBY,
            PlaybackPrefs.AUDIO_FLAC,
            PlaybackPrefs.AUDIO_AAC
        )
        val labels = Array(modes.size) { audioModeLabel(modes[it]) }
        val current = modes.indexOf(PlaybackPrefs.audioMode(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("选择音轨")
            .setSingleChoiceItems(labels, current) { d, which ->
                val mode = modes[which]
                var accepted = true
                val kind = audioModeKind(mode)
                if (kind != null) {
                    val track = audioTracks.firstOrNull { it.kind == kind }
                    if (track == null) {
                        Ui.toast(this@VideoActivity, "本视频没有 ${kind.title} 音轨")
                        accepted = false
                    } else if (!track.supported) {
                        Ui.toast(this@VideoActivity, "设备没有 ${kind.title} 解码器，继续使用 AAC")
                        accepted = false
                    }
                }
                d.dismiss()
                if (accepted) {
                    PlaybackPrefs.saveAudioMode(this@VideoActivity, mode)
                    appliedAudioId = null
                    applyAudioSelection()
                    syncChips()
                    tvStatus.text = "音轨：${audioChipLabel()}"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val playListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlayIcon()
            danmakuView.setPlaying(isPlaying)
            if (isPlaying) {
                if (controlsVisible) scheduleHide()
            } else {
                setControlsVisible(true)
            }
            renderPlaybackStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            pbPlayer.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            renderPlaybackStatus()
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyAudioSelection()
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackUi = false
            tvStatus.text = "播放失败：${error.errorCodeName}"
            val cause = error.cause?.message ?: error.message ?: ""
            if (cause.isNotEmpty()) tvStatus.text = "播放失败：$cause"
            if (!fallbackTried) {
                fallbackTried = true
                fallbackMp4(player?.currentPosition ?: 0)
            }
        }
    }

    /** 播放状态文案：缓冲 / 播放 / 暂停 / 结束，下载和付费试看另加前缀 */
    @SuppressLint("SetTextI18n")
    private fun renderPlaybackStatus() {
        if (!playbackUi || downloading || !::tvStatus.isInitialized) return
        val p = player ?: return
        val q = if (mp4Mode) "单画质" else qualityName(qn)
        val tag = if (courseSeasonId > 0L) " · 课程" else ""
        val hint = resumeHint
        val hasNext = epIds.isNotEmpty() && epIndex >= 0 && epIndex < epIds.size - 1
        tvStatus.text = when {
            p.playbackState == Player.STATE_ENDED ->
                if (hasNext) getString(R.string.play_status_ended_next)
                else getString(R.string.play_status_ended, tag)
            p.playbackState == Player.STATE_BUFFERING && paidPreview ->
                getString(R.string.play_status_preview_buffering, q)
            p.isPlaying && paidPreview ->
                getString(R.string.play_status_preview_playing, q)
            p.isPlaying ->
                getString(R.string.play_status_playing, q, tag) + hint
            paidPreview ->
                getString(R.string.play_status_preview_paused, q)
            p.playbackState == Player.STATE_BUFFERING ->
                getString(R.string.play_status_buffering, q, tag)
            else ->
                getString(R.string.play_status_paused, q, tag) + hint
        }
        if (p.isPlaying || p.playbackState == Player.STATE_READY) resumeHint = ""
    }

    private fun releasePlayer() {
        playbackUi = false
        paidPreview = false
        resumeHint = ""
        player?.release()
        player = null
        playerView.player = null
        pbPlayer.visibility = View.GONE
        appliedAudioId = null
        refreshPlayIcon()
    }

    private fun qualityName(q: Int): String =
        qualities.firstOrNull { it.first == q }?.second ?: "${q}P"

    private fun speedLabel(): String =
        if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"

    /** 竖屏信息区与横屏浮层上的按钮文案保持同步 */
    private fun syncChips() {
        val q = if (mp4Mode) "单画质" else if (qualities.isNotEmpty()) qualityName(qn) else "画质"
        btnQuality.text = q
        btnQualityL.text = q
        val audio = audioChipLabel()
        btnAudio.text = "音轨 $audio"
        btnAudioL.text = audio
        val sp = "倍速 ${speedLabel()}"
        btnSpeed.text = sp
        btnSpeedL.text = sp
        btnPagesL.text = btnPages.text
        btnPagesL.visibility = btnPages.visibility
        val fit = "画面 ${VideoFit.label(PlaybackPrefs.videoFit(this))}"
        btnRatio.text = fit
        btnRatioL.text = fit
        btnDanmaku.text = "弹幕设置"
        btnDanmakuL.text = "弹幕"
    }

    /** 把当前视频框按偏好缩放；横竖屏切换后由 post 重新测量再调用 */
    private fun applyVideoFit() {
        if (!::videoFrame.isInitialized || !::playerView.isInitialized) return
        VideoFit.apply(playerContainer, videoFrame, playerView, PlaybackPrefs.videoFit(this))
        danmakuView.refresh()
    }

    private fun applyDanmakuStyle(refreshItems: Boolean = false) {
        if (!::danmakuView.isInitialized) return
        danmakuView.setStyle(
            PlaybackPrefs.danmakuScale(this),
            PlaybackPrefs.danmakuArea(this),
            PlaybackPrefs.danmakuColor(this)
        )
        if (refreshItems) danmakuView.refresh()
    }

    /** 拉取弹幕：只在 cid 变化时请求，画质切换不会重复拉 */
    private fun ensureDanmaku(force: Boolean = false) {
        if (cid <= 0L || !danmakuEnabled) {
            tvDanmaku.text = if (danmakuEnabled) "弹幕准备中" else "弹幕已关闭"
            return
        }
        if (!force && danmakuCid == cid) return
        danmakuCid = cid
        val token = ++danmakuRequest
        val c = cid
        val dur = durationSec
        val ref = Bili.currentReferer
        danmakuView.clear()
        tvDanmaku.text = "正在加载弹幕…"
        Bili.io {
            val items = try {
                DanmakuRepository.load(c, dur, ref)
            } catch (e: Exception) {
                emptyList()
            }
            Bili.main {
                if (token != danmakuRequest || cid != c || isFinishing || isDestroyed) return@main
                danmakuView.setItems(items)
                danmakuView.setPlaying(player?.isPlaying == true)
                tvDanmaku.text = if (items.isEmpty()) {
                    "暂无弹幕"
                } else {
                    "弹幕 ${items.size} 条"
                }
            }
        }
    }

    private fun setDanmakuEnabled(on: Boolean) {
        danmakuEnabled = on
        PlaybackPrefs.saveDanmakuEnabled(this, on)
        danmakuView.visibility = if (on) View.VISIBLE else View.GONE
        danmakuView.setEnabledState(on)
        if (!on) {
            danmakuView.clear()
            danmakuCid = 0L
            tvDanmaku.text = "弹幕已关闭"
        } else {
            ensureDanmaku()
            danmakuView.refresh()
        }
    }

    private fun showDisplayDialog() {
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, (4 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = "画面比例"
            setTextColor(getColor(R.color.textMain))
            textSize = 13f
            setPadding(0, 0, 0, (6 * d).toInt())
        })
        val group = RadioGroup(this)
        val cur = PlaybackPrefs.videoFit(this)
        VideoFit.modes.forEachIndexed { index, mode ->
            val rb = RadioButton(this).apply {
                id = 2000 + index
                text = when (mode) {
                    VideoFit.R16_9 -> "16:9"
                    VideoFit.R4_3 -> "4:3"
                    VideoFit.COVER -> "撑满屏幕"
                    else -> "原始比例"
                }
                textSize = 14f
                setTextColor(getColor(R.color.textMain))
                setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                isChecked = mode == cur
            }
            group.addView(
                rb,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(group)
        val sw = Switch(this).apply {
            text = "显示弹幕"
            textSize = 14f
            isChecked = danmakuEnabled
            setPadding(0, (12 * d).toInt(), 0, (2 * d).toInt())
        }
        root.addView(sw)

        val dlg = AlertDialog.Builder(this)
            .setTitle("画面与弹幕")
            .setView(root)
            .setPositiveButton("完成", null)
            .setNegativeButton("取消", null)
            .create()
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = checkedId - 2000
            val mode = VideoFit.modes.getOrNull(index) ?: return@setOnCheckedChangeListener
            PlaybackPrefs.saveVideoFit(this, mode)
            applyVideoFit()
            syncChips()
            tvStatus.text = "画面：${VideoFit.label(mode)}"
        }
        sw.setOnCheckedChangeListener { _, checked ->
            setDanmakuEnabled(checked)
            tvStatus.text = if (checked) "弹幕已开启" else "弹幕已关闭"
        }
        dlg.show()
    }

    private fun showDanmakuSettingsDialog() {
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, (4 * d).toInt())
        }

        fun title(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.textMain))
                textSize = 13f
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            })
        }

        val sw = Switch(this).apply {
            text = "显示弹幕"
            textSize = 14f
            isChecked = danmakuEnabled
            setPadding(0, 0, 0, (2 * d).toInt())
        }
        content.addView(sw)
        val origEnabled = danmakuEnabled

        title("弹幕字号")
        val scale = PlaybackPrefs.danmakuScale(this)
        val sizeLabel = TextView(this).apply {
            text = "字号 ${(scale * 100).roundToInt()}%"
            setTextColor(getColor(R.color.textSecondary))
            textSize = 12f
        }
        content.addView(sizeLabel)
        val seek = SeekBar(this).apply {
            max = 90
            progress = ((scale * 100).roundToInt() - 70).coerceIn(0, 90)
            setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
        }
        content.addView(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = (progress + 70) / 100f
                PlaybackPrefs.saveDanmakuScale(this@VideoActivity, value)
                sizeLabel.text = "字号 ${(value * 100).roundToInt()}%"
                applyDanmakuStyle()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) {
                applyDanmakuStyle(refreshItems = true)
            }
        })

        title("显示范围")
        val areaGroup = RadioGroup(this)
        val curArea = PlaybackPrefs.danmakuArea(this)
        DanmakuStyle.areas.forEachIndexed { index, option ->
            areaGroup.addView(RadioButton(this).apply {
                id = 3000 + index
                text = option.label
                textSize = 14f
                setTextColor(getColor(R.color.textMain))
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                isChecked = option.key == curArea
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(areaGroup)
        areaGroup.setOnCheckedChangeListener { _, checkedId ->
            val option = DanmakuStyle.areas.getOrNull(checkedId - 3000) ?: return@setOnCheckedChangeListener
            PlaybackPrefs.saveDanmakuArea(this, option.key)
            applyDanmakuStyle()
            tvStatus.text = "弹幕显示范围：${option.label}"
        }

        title("弹幕颜色")
        val colorGroup = RadioGroup(this)
        val curColor = PlaybackPrefs.danmakuColor(this)
        DanmakuStyle.colors.forEachIndexed { index, option ->
            colorGroup.addView(RadioButton(this).apply {
                id = 4000 + index
                text = option.label
                textSize = 14f
                setTextColor(getColor(R.color.textMain))
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                isChecked = option.key == curColor
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(colorGroup)
        colorGroup.setOnCheckedChangeListener { _, checkedId ->
            val option = DanmakuStyle.colors.getOrNull(checkedId - 4000) ?: return@setOnCheckedChangeListener
            PlaybackPrefs.saveDanmakuColor(this, option.key)
            applyDanmakuStyle()
            tvStatus.text = "弹幕颜色：${option.label}"
        }

        sw.setOnCheckedChangeListener { _, checked ->
            setDanmakuEnabled(checked)
            tvStatus.text = if (checked) "弹幕已开启" else "弹幕已关闭"
        }

        val scroll = ScrollView(this).apply {
            addView(content)
        }
        AlertDialog.Builder(this)
            .setTitle("弹幕设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .setNegativeButton("取消") { _, _ ->
                PlaybackPrefs.saveDanmakuScale(this, scale)
                PlaybackPrefs.saveDanmakuArea(this, curArea)
                PlaybackPrefs.saveDanmakuColor(this, curColor)
                applyDanmakuStyle(refreshItems = true)
                if (origEnabled != danmakuEnabled) setDanmakuEnabled(origEnabled)
                syncChips()
            }
            .show()
    }

    private fun showError(msg: String) {
        playbackUi = false
        tvStatus.text = msg
        Ui.toast(this, msg)
    }

    // ---------- 菜单 ----------

    private fun showQualityDialog() {
        if (mp4Mode) { Ui.toast(this, "当前为兼容模式，无多画质"); return }
        if (qualities.isEmpty()) { Ui.toast(this, "画质列表尚未加载"); return }
        val names = qualities.map { it.second }.toTypedArray()
        val cur = qualities.indexOfFirst { it.first == qn }
        AlertDialog.Builder(this)
            .setTitle("选择画质")
            .setSingleChoiceItems(names, cur) { d, which ->
                d.dismiss()
                val target = qualities[which].first
                // 记住本次选择，之后进入其它视频 / 重启 App 继续沿用
                PlaybackPrefs.save(this@VideoActivity, target, qualities[which].second)
                if (target != qn) {
                    fallbackTried = false
                    prepare(target, player?.currentPosition ?: 0)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val cur = values.indexOfFirst { it == speed }
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, cur) { d, which ->
                d.dismiss()
                speed = values[which]
                player?.setPlaybackSpeed(speed)
                tvStatus.text = "倍速 ${labels[which]}"
                syncChips()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** UGC 切分P；PGC 切集 / 切版本 */
    private fun showPagesDialog() {
        if (courseSeasonId > 0L) { showCourseEpisodesDialog(); return }
        if (epId > 0L) { showEpisodesDialog(); return }
        val ps = pages ?: return
        val names = Array(ps.length()) { i ->
            val p = ps.optJSONObject(i)
            "P${p?.optInt("page", i + 1)} ${p?.optString("part", "")} (${Ui.fmtDur(p?.optLong("duration", 0) ?: 0)})"
        }
        AlertDialog.Builder(this)
            .setTitle("选择分P")
            .setItems(names) { d, which ->
                d.dismiss()
                switchPage(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEpisodesDialog() {
        val epLocal = epId
        Bili.io {
            try {
                val season = Bili.pgcSeasonByEp(epLocal)
                val eps = season.optJSONArray("episodes") ?: JSONArray()
                val list = ArrayList<Ep>()
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val id = e.optLong("id", 0)
                    if (id <= 0) continue
                    var index = e.optString("title", "")
                    if (index.isBlank()) index = "第 ${i + 1} 集"
                    list.add(
                        Ep(
                            id = id,
                            index = index,
                            sub = e.optString("long_title", ""),
                            badge = e.optString("badge", ""),
                            duration = e.optLong("duration", 0)
                        )
                    )
                }
                Bili.main {
                    if (isFinishing || isDestroyed) return@main
                    val sheet = BottomSheetDialog(this@VideoActivity)
                    val view = layoutInflater.inflate(R.layout.dialog_episode_picker, null)
                    val sheetH = (resources.displayMetrics.heightPixels * 0.74f).toInt()
                    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetH)
                    sheet.setContentView(view)
                    val behavior = sheet.behavior
                    behavior.skipCollapsed = true
                    behavior.peekHeight = sheetH
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED

                    val title = view.findViewById<TextView>(R.id.tvPickTitle)
                    val count = view.findViewById<TextView>(R.id.tvPickCount)
                    val filter = view.findViewById<EditText>(R.id.etEpFilter)
                    val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPick)
                    title.text = if (season.optInt("type", 2) == 1) "选集" else "选择版本"
                    count.text = "共 ${list.size} 集"

                    val adapter = EpAdapter { ep ->
                        sheet.dismiss()
                        if (ep.id != epLocal) {
                            startActivity(
                                Intent(this@VideoActivity, VideoActivity::class.java)
                                    .putExtra("ep_id", ep.id)
                            )
                            finish()
                        }
                    }
                    rv.layoutManager = GridLayoutManager(this@VideoActivity, 4)
                    rv.adapter = adapter
                    adapter.submit(list, epLocal)

                    val current = list.indexOfFirst { it.id == epLocal }
                    if (current > 0) rv.post { rv.scrollToPosition(current) }

                    filter.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val q = s?.toString()?.trim().orEmpty()
                            val filtered = if (q.isEmpty()) list else list.filter {
                                it.index.contains(q, true) || it.sub.contains(q, true)
                            }
                            adapter.submit(filtered, epLocal)
                            count.text = if (q.isEmpty()) "共 ${list.size} 集" else "匹配 ${filtered.size} 集"
                        }
                    })
                    sheet.show()
                }
            } catch (e: Exception) {
                Bili.main { Ui.toast(this@VideoActivity, "剧集加载失败: ${e.message}") }
            }
        }
    }

    /** 播放页顶部的上一集 / 下一集 */
    private fun updateSiblingButtons() {
        if (courseSeasonId > 0L) {
            btnPrevEp.visibility = View.GONE
            btnNextEp.visibility = View.GONE
            return
        }
        val hasPrev = epIndex > 0
        val hasNext = epIndex >= 0 && epIndex < epIds.size - 1
        btnPrevEp.visibility = if (hasPrev) View.VISIBLE else View.GONE
        btnNextEp.visibility = if (hasNext) View.VISIBLE else View.GONE
    }

    private fun switchSibling(delta: Int) {
        val target = epIndex + delta
        if (target < 0 || target >= epIds.size) return
        val newEp = epIds[target]
        if (newEp == epId) return
        startActivity(
            Intent(this, VideoActivity::class.java)
                .putExtra("ep_id", newEp)
        )
        finish()
    }

    private fun switchPage(idx: Int) {
        val ps = pages ?: return
        val p = ps.optJSONObject(idx) ?: return
        val newCid = p.optLong("cid", 0)
        if (newCid == 0L || newCid == cid) return
        cid = newCid
        historyKey = "v:$bvid:$newCid"
        val pageMs = p.optLong("duration", 0)
        if (pageMs > 0) durationSec = (pageMs / 1000).toInt()
        fallbackTried = false
        tvStatus.text = "已切换至：${p.optString("part", "")}"
        prepare(PlaybackPrefs.quality(this), 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != isLandscape) applyOrientation(land)
    }

    override fun onStart() {
        super.onStart()
        DownloadBus.register(downloadStateListener)
    }

    override fun onResume() {
        super.onResume()
        if (Cloud.gate(this)) player?.pause()
    }

    override fun onStop() {
        DownloadBus.unregister(downloadStateListener)
        saveHistory(player?.currentPosition ?: 0L)
        CloudHistory.flush(this)
        player?.pause()
        hideHandler.removeCallbacks(hideTask)
        super.onStop()
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        saveHistory(player?.currentPosition ?: 0L)
        danmakuView.clear()
        releasePlayer()
        super.onDestroy()
    }
}
