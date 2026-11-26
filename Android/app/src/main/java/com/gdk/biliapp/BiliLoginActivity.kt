package com.gdk.biliapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 用 B 站官方网页登录，拿到 Cookie 后交给 [BiliUser] 校验并加密保存。
 *
 * 不使用账号密码接口，也不把 Cookie 发到本应用服务器：
 * - WebView 只允许停留在 bilibili.com / hdslb.com 域名；
 * - 关闭文件访问、混合内容和多窗口；
 * - 登录成功后立刻读取 Cookie，校验 nav 接口，再清掉 WebView 会话。
 */
class BiliLoginActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var web: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvSubTitle: TextView

    private var binding = false
    private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_bili_login)

        pageRoot = findViewById(R.id.pageRoot)
        web = findViewById(R.id.webView)
        tvStatus = findViewById(R.id.tvStatus)
        tvSubTitle = findViewById(R.id.tvSubTitle)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        web.setBackgroundColor(getColor(R.color.surface))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tvStatus.text = "正在加载 B站登录页…"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (binding || destroyed) return
                // 登录成功后的跳转可能分几次完成，多抓一次不影响结果。
                view?.postDelayed({ captureCookie() }, 650)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !binding) {
                    tvStatus.text = "登录页加载失败：${error?.description ?: "网络错误"}"
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return true
                val scheme = url.scheme ?: return true
                if (scheme != "https") return true
                val host = url.host ?: return true
                val allowed = host == "bilibili.com" ||
                    host.endsWith(".bilibili.com") ||
                    host.endsWith(".hdslb.com") ||
                    host.endsWith(".biligame.com")
                if (!allowed) {
                    tvStatus.text = "为保护账号，只允许在 B站页面内完成登录"
                }
                return !allowed
            }
        }

        applyInsets()
        // 先清掉 WebView 里可能残留的旧账号，再打开官方登录页。
        cm.removeAllCookies {
            runOnUiThread {
                if (!destroyed) {
                    cm.flush()
                    web.loadUrl("https://passport.bilibili.com/login")
                }
            }
        }
    }

    private fun captureCookie() {
        if (binding || destroyed) return
        val cm = CookieManager.getInstance()
        cm.flush()
        val parts = listOf(
            "https://www.bilibili.com",
            "https://passport.bilibili.com",
            "https://api.bilibili.com"
        ).mapNotNull { cm.getCookie(it)?.trim()?.takeIf { c -> c.isNotBlank() } }

        val raw = parts.joinToString("; ")
        if (!raw.contains("SESSDATA=")) return

        binding = true
        tvStatus.text = "已拿到登录 Cookie，正在校验账号…"
        BiliUser.bind(this, raw) { ok, msg, info ->
            if (destroyed || isFinishing) return@bind
            if (ok && info != null) {
                tvStatus.text = "绑定成功：${info.name}"
                tvSubTitle.text = "UID ${info.mid} · ${info.vip}"
                Ui.toast(this, msg)
                // 本机已有加密 Cookie，不再保留 WebView 的登录会话。
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                setResult(RESULT_OK)
                web.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 500)
            } else {
                binding = false
                tvStatus.text = msg
                Ui.toast(this, msg)
            }
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        destroyed = true
        runCatching {
            web.stopLoading()
            web.webViewClient = WebViewClient()
            web.destroy()
        }
        super.onDestroy()
    }
}
