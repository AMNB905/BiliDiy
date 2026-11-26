package com.gdk.biliapp

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** 画面比例：与 Windows 端一致的四种模式 */
object VideoFit {
    const val ORIGINAL = "original"
    const val R16_9 = "16:9"
    const val R4_3 = "4:3"
    const val COVER = "cover"

    val modes = listOf(ORIGINAL, R16_9, R4_3, COVER)

    fun label(mode: String): String = when (mode) {
        R16_9 -> "16:9"
        R4_3 -> "4:3"
        COVER -> "撑满"
        else -> "原始"
    }

    /**
     * @param container 视频舞台（横屏时是整屏）
     * @param frame 实际视频框，比例模式会把它居中缩放进舞台
     */
    fun apply(container: View, frame: FrameLayout, playerView: PlayerView, mode: String) {
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return

        val lp = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        if (mode == ORIGINAL || mode == COVER) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            frame.layoutParams = lp
            playerView.resizeMode = if (mode == ORIGINAL) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        } else {
            val ratio = if (mode == R16_9) 16f / 9f else 4f / 3f
            var fw = w
            var fh = (w / ratio).toInt()
            if (fh > h) {
                fh = h
                fw = (h * ratio).toInt()
            }
            lp.width = fw.coerceAtLeast(1)
            lp.height = fh.coerceAtLeast(1)
            frame.layoutParams = lp
            // 在固定比例框内铺满并裁切，避免 4:3 框里出现上下黑边
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        frame.requestLayout()
        playerView.requestLayout()
    }

    fun applyFromPrefs(ctx: Context, container: View, frame: FrameLayout, playerView: PlayerView) {
        apply(container, frame, playerView, PlaybackPrefs.videoFit(ctx))
    }
}
