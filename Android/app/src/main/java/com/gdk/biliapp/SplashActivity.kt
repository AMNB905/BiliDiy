package com.gdk.biliapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * 启动页：品牌图标弹入 + 文案渐显，短暂停留后进入首页或登录页。
 * 不承载业务，所有校验和路由都在这里完成后交给真正的页面。
 */
class SplashActivity : BaseActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val routeTask = Runnable { route() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        Account.init(this)
        setContentView(R.layout.activity_splash)
        bindStateCopy()
        animateIn()

        if (!Security.ensure(this)) return
        main.postDelayed(routeTask, 1750L)
    }

    /** 启动页文案跟着登录状态和当前版本走，不写死 */
    private fun bindStateCopy() {
        val logged = Account.isLoggedIn(this)
        findViewById<TextView>(R.id.splashTagline).text =
            if (logged) getString(R.string.splash_tagline) else getString(R.string.splash_tagline_guest)
        findViewById<TextView>(R.id.splashFooter).text =
            getString(R.string.splash_footer_version, versionName())
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("?")

    private fun animateIn() {
        val logo = findViewById<View>(R.id.splashLogo)
        val title = findViewById<View>(R.id.splashTitle)
        val tagline = findViewById<View>(R.id.splashTagline)
        val progress = findViewById<View>(R.id.splashProgress)
        val footer = findViewById<View>(R.id.splashFooter)

        logo.alpha = 0f
        logo.scaleX = 0.72f
        logo.scaleY = 0.72f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(100L)
            .setDuration(720L)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        title.alpha = 0f
        title.translationY = 14f
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(380L)
            .setDuration(560L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        tagline.alpha = 0f
        tagline.animate()
            .alpha(1f)
            .setStartDelay(600L)
            .setDuration(480L)
            .start()

        progress.alpha = 0f
        progress.animate()
            .alpha(1f)
            .setStartDelay(760L)
            .setDuration(420L)
            .start()

        footer.alpha = 0f
        footer.animate()
            .alpha(1f)
            .setStartDelay(880L)
            .setDuration(500L)
            .start()
    }

    private fun route() {
        if (isFinishing || isDestroyed) return
        val next = if (Account.isLoggedIn(this)) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, AuthActivity::class.java).putExtra("gate", true)
        }
        startActivity(next)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(routeTask)
        super.onDestroy()
    }
}
