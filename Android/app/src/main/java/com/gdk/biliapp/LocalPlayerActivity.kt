package com.gdk.biliapp

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** 站内本地播放器：直接播放下载目录里的 mp4，不依赖网络 */
class LocalPlayerActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var videoFrame: FrameLayout
    private lateinit var btnRatio: TextView
    private lateinit var videoStage: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        Ui.edgeToEdge(this, false)
        setContentView(R.layout.activity_local_player)

        // 本地播放期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        videoFrame = findViewById(R.id.videoFrame)
        btnRatio = findViewById(R.id.btnRatio)
        videoStage = (videoFrame.parent as? View) ?: videoFrame

        val path = intent.getStringExtra("path").orEmpty()
        val file = File(path)
        if (path.isBlank() || !file.exists()) {
            Ui.toast(this, "本地文件不存在")
            finish()
            return
        }

        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnRatio.text = "画面 ${VideoFit.label(PlaybackPrefs.videoFit(this))}"
        btnRatio.setOnClickListener { showRatioDialog() }

        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Ui.toast(this@LocalPlayerActivity, "播放失败：${error.message ?: "文件损坏或格式不支持"}")
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = true

        applyInsets()
        videoStage.post { applyVideoFit() }
    }

    private fun applyVideoFit() {
        VideoFit.apply(videoStage, videoFrame, playerView, PlaybackPrefs.videoFit(this))
    }

    private fun showRatioDialog() {
        val cur = PlaybackPrefs.videoFit(this)
        val labels = VideoFit.modes.map {
            when (it) {
                VideoFit.R16_9 -> "16:9"
                VideoFit.R4_3 -> "4:3"
                VideoFit.COVER -> "撑满屏幕"
                else -> "原始比例"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(labels, VideoFit.modes.indexOf(cur)) { d, which ->
                val mode = VideoFit.modes.getOrNull(which) ?: return@setSingleChoiceItems
                PlaybackPrefs.saveVideoFit(this, mode)
                btnRatio.text = "画面 ${VideoFit.label(mode)}"
                videoStage.post { applyVideoFit() }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        videoStage.post { applyVideoFit() }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                Ui.dp(this, 10),
                bars.top + Ui.dp(this, 8),
                Ui.dp(this, 14),
                Ui.dp(this, 18)
            )
            insets
        }
        ViewCompat.requestApplyInsets(topBar)
    }
}
