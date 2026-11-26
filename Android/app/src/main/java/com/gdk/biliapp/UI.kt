package com.gdk.biliapp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object AppCtx {
    @Volatile lateinit var app: Context
        private set

    fun init(c: Context) {
        if (!::app.isInitialized) {
            app = c.applicationContext
            BiliUser.init(app)
            MemberShare.init(app)
        }
    }
}

object Ui {
    private var toastRef: Toast? = null

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** 应用固定浅色主题，所有页面统一使用深色系统栏图标 */
    fun isLight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES

    fun stripEm(s: String): String =
        s.replace(Regex("</?em[^>]*>"), "").replace("&quot;", "\"").trim()

    fun fmtCount(n: Long): String = when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
        else -> n.toString()
    }

    fun fmtDur(sec: Long): String {
        val s = if (sec < 0) 0 else sec
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** 卡片 / 入口的按压回弹，不拦截点击事件 */
    fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    /** 列表 / 面板子项的错峰入场，需要父布局已布局完成 */
    fun staggerIn(parent: ViewGroup, startDelay: Long = 0, step: Long = 42, duration: Long = 320) {
        val dy = dp(parent.context, 20).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.alpha = 0f
            child.translationY = dy
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + i * step)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun toast(context: Context, msg: String) {
        val app = context.applicationContext
        val view = LayoutInflater.from(app).inflate(R.layout.view_toast, null)
        view.findViewById<TextView>(R.id.toastText).text = msg
        view.contentDescription = msg

        toastRef?.cancel()
        toastRef = Toast(app).apply {
            duration = Toast.LENGTH_SHORT
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(app, 72))
            @Suppress("DEPRECATION")
            setView(view)
            show()
        }
    }

    /**
     * 全局沉浸式：内容延伸到状态栏 / 导航栏下，并默认隐藏系统栏。
     * 各页面仍按 insets 留白；用户从屏幕边缘滑动可临时唤出系统栏。
     * @param lightBars true = 深色图标（浅色背景用）；false = 白色图标（深色/视频背景用）
     */
    fun edgeToEdge(activity: Activity, lightBars: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = activity.window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = attrs
        }

        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        c.isAppearanceLightStatusBars = lightBars
        c.isAppearanceLightNavigationBars = lightBars
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 不改变图标明暗，仅重新隐藏系统栏；用于返回前台 / 焦点恢复 */
    fun rehideSystemBars(activity: Activity) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 分别控制状态栏 / 导航栏图标的明暗，适配「上深下浅」这类页面 */
    fun barIcons(activity: Activity, darkStatus: Boolean, darkNav: Boolean) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        c.isAppearanceLightStatusBars = darkStatus
        c.isAppearanceLightNavigationBars = darkNav
    }

    /** 横屏播放时隐藏系统栏，滑动可临时唤出 */
    fun setSystemBarsHidden(activity: Activity, hidden: Boolean) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hidden) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
        }
    }
}
