package com.gdk.biliapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.floor
import kotlin.math.max

/**
 * 轻量弹幕层。位置完全由播放器当前进度推算，所以暂停、拖动进度、切换
 * 比例和横竖屏时都不需要额外维护动画计时器。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var positionProvider: (() -> Long)? = null
    var playingProvider: (() -> Boolean)? = null

    private val items = ArrayList<DanmakuItem>()
    private var laneOf = IntArray(0)
    private var laneFree = LongArray(0)
    private var laneCount = 0
    private var laneHeight = 0f
    private var textPx = 0f
    private var enabledState = true
    private var playing = false
    private var textScale = PlaybackPrefs.DEFAULT_DM_SCALE
    private var areaMode = DanmakuStyle.AREA_FULL
    private var colorMode = DanmakuStyle.COLOR_ORIGINAL
    private var areaTop = 0f
    private var areaBottom = 1f
    private val fixedHoldMs = 5000L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        isSubpixelText = true
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !enabledState || !isPlayingNow()) return
            invalidate()
            if (items.isNotEmpty()) postOnAnimation(this)
        }
    }

    private fun isPlayingNow(): Boolean =
        playingProvider?.invoke() ?: playing

    fun setItems(list: List<DanmakuItem>) {
        items.clear()
        items.addAll(list.sortedBy { it.timeMs })
        playing = isPlayingNow()
        computeLayout()
        invalidate()
        restartLoop()
    }

    fun clear() {
        items.clear()
        laneOf = IntArray(0)
        removeCallbacks(ticker)
        invalidate()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    /** 字号倍率 / 显示区域 / 颜色策略，调用后立即按当前尺寸重排 */
    fun setStyle(scale: Float, area: String, color: String) {
        textScale = scale.coerceIn(0.7f, 1.6f)
        areaMode = DanmakuStyle.areaOrFull(area)
        colorMode = DanmakuStyle.colorOrDefault(color)
        computeLayout()
        invalidate()
        restartLoop()
    }

    fun setPlaying(value: Boolean) {
        playing = value
        restartLoop()
    }

    fun setEnabledState(value: Boolean) {
        enabledState = value
        if (!value) removeCallbacks(ticker)
        invalidate()
        restartLoop()
    }

    fun refresh() {
        invalidate()
        restartLoop()
    }

    private fun restartLoop() {
        removeCallbacks(ticker)
        if (enabledState && isPlayingNow() && items.isNotEmpty() && isAttachedToWindow) {
            postOnAnimation(ticker)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartLoop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.scaledDensity
        val base = density * 16f
        val natural = (height * 0.06f).coerceIn(base * 0.9f, base * 1.35f)
        textPx = (natural * textScale).coerceIn(density * 12f, height * 0.14f)
        updateAreaBounds()
        val regionHeight = (areaBottom - areaTop).coerceAtLeast(textPx)
        laneHeight = textPx * 1.35f
        laneCount = max(1, floor(regionHeight / laneHeight).toInt()).coerceAtMost(24)
        laneFree = LongArray(laneCount)
        laneOf = IntArray(items.size) { -1 }
        fillPaint.textSize = textPx
        val speed = max(80f, width / 8f)
        for (i in items.indices) {
            val item = items[i]
            var chosen = -1
            for (lane in 0 until laneCount) {
                if (laneFree[lane] <= item.timeMs) {
                    chosen = lane
                    break
                }
            }
            // 没有空轨道就丢弃这一条，避免大量弹幕在同一轨道重叠成黑条
            if (chosen < 0) continue
            val isFixed = item.mode == 4 || item.mode == 5
            val holdMs = if (isFixed) {
                fixedHoldMs
            } else {
                val textWidth = fillPaint.measureText(item.text)
                (((width + textWidth + 36f) / speed) * 1000f).toLong().coerceIn(2500L, 15000L)
            }
            laneOf[i] = chosen
            laneFree[chosen] = item.timeMs + holdMs
        }
    }

    private fun updateAreaBounds() {
        val h = height.toFloat()
        when (areaMode) {
            DanmakuStyle.AREA_TOP -> {
                areaTop = 0f
                areaBottom = h * 0.52f
            }
            DanmakuStyle.AREA_MIDDLE -> {
                areaTop = h * 0.24f
                areaBottom = h * 0.76f
            }
            DanmakuStyle.AREA_BOTTOM -> {
                areaTop = h * 0.48f
                areaBottom = h
            }
            else -> {
                areaTop = 0f
                areaBottom = h
            }
        }
        areaTop = areaTop.coerceIn(0f, h)
        areaBottom = areaBottom.coerceIn(areaTop, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledState || items.isEmpty() || width <= 0 || height <= 0) return
        val position = (positionProvider?.invoke() ?: 0L).coerceAtLeast(0L)
        fillPaint.textSize = textPx
        val speed = max(80f, width / 8f)
        canvas.save()
        canvas.clipRect(0f, areaTop, width.toFloat(), areaBottom)
        var i = 0
        // 弹幕时间轴已排序，跳过所有过期项
        while (i < items.size && items[i].timeMs < position - 15000L) i++
        while (i < items.size) {
            val item = items[i]
            if (item.timeMs > position) break
            val elapsed = position - item.timeMs
            if (elapsed <= 0L || elapsed > 15000L) {
                i++
                continue
            }
            val lane = laneOf.getOrElse(i) { -1 }
            if (lane < 0 || lane >= laneCount) {
                i++
                continue
            }
            val tw = fillPaint.measureText(item.text)
            val mode = item.mode
            val isBottom = mode == 4
            val isTop = mode == 5
            // 固定弹幕必须有固定寿命，否则轨道已释放、旧的还在画，就会叠字
            if ((isBottom || isTop) && elapsed >= fixedHoldMs) {
                i++
                continue
            }
            val baseline = when {
                isBottom -> areaBottom - laneHeight * (lane + 1) + textPx
                isTop -> areaTop + laneHeight * lane + textPx
                else -> areaTop + laneHeight * lane + textPx
            }
            val x = if (isBottom || isTop) {
                (width - tw) / 2f
            } else {
                width - (elapsed / 1000f) * speed
            }
            if (!isBottom && !isTop && x + tw < 0f) {
                i++
                continue
            }
            if (baseline - textPx > areaBottom) {
                i++
                continue
            }
            fillPaint.color = DanmakuStyle.resolve(item.color, colorMode)
            canvas.drawText(item.text, x, baseline, fillPaint)
            i++
        }
        canvas.restore()
    }
}
