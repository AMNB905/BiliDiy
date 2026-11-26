package com.gdk.biliapp

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigation.NavigationBarView
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 首页只保留热门推荐和继续观看。
 * 搜索、搜索历史、B 站热搜和排行榜都放到独立的 SearchActivity。
 */
class MainActivity : BaseActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var page = 1
    private var loading = false
    private var hasNext = true
    private var seq = 0

    private lateinit var pageRoot: View
    private lateinit var appBar: View
    private lateinit var headerContent: View
    private lateinit var rv: RecyclerView
    private lateinit var pb: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvAnnounce: TextView
    private lateinit var btnTop: View
    private lateinit var bottomNav: NavigationBarView
    private var topVisible = false
    private var featureSheet: BottomSheetDialog? = null

    private val homeAdapter = HomeAdapter(
        onVideo = { v ->
            if (v.optString("type") == "ketang") {
                openCourse(v.optLong("id", 0), v.optString("title", ""))
            } else {
                openVideo(v.optString("bvid", ""))
            }
        },
        onPgc = { s -> openPgc(s.optLong("season_id", 0), s.optInt("season_type", 2)) },
        onHistory = { item -> openHistory(item) },
        onClearHistory = { confirmClearHistory() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Account.init(this)
        if (!Account.isLoggedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java).putExtra("gate", true))
            finish()
            return
        }
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_main)

        pageRoot = findViewById(R.id.pageRoot)
        appBar = findViewById(R.id.appBar)
        headerContent = findViewById(R.id.headerContent)
        rv = findViewById(R.id.rvList)
        pb = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvAnnounce = findViewById(R.id.tvAnnounce)
        btnTop = findViewById(R.id.btnTop)
        bottomNav = findViewById(R.id.bottomNav)

        rv.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 220
            changeDuration = 220
            moveDuration = 220
            removeDuration = 160
        }

        val glm = GridLayoutManager(this, 2)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (homeAdapter.isFullSpan(position)) 2 else 1
        }
        rv.layoutManager = glm
        rv.adapter = homeAdapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                val lm = r.layoutManager as? GridLayoutManager ?: return
                updateTopButton(lm.findFirstVisibleItemPosition() > 5)
                if (dy <= 0) return
                val total = r.adapter?.itemCount ?: return
                if (lm.findLastVisibleItemPosition() >= total - 4) nextPage()
            }
        })
        btnTop.setOnClickListener {
            rv.smoothScrollToPosition(0)
            updateTopButton(false)
        }

        findViewById<View>(R.id.searchBox).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        Ui.pressScale(findViewById(R.id.searchBox))

        val backToTop = View.OnLongClickListener {
            rv.smoothScrollToPosition(0)
            updateTopButton(false)
            Ui.toast(this, "已回到顶部")
            true
        }
        bottomNav.setOnLongClickListener(backToTop)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navMore -> {
                    showFeatureSheet()
                    false
                }
                R.id.navLocal -> {
                    startActivity(Intent(this, LocalVideosActivity::class.java))
                    false
                }
                R.id.navProfile -> {
                    startActivity(Intent(this, UserActivity::class.java))
                    false
                }
                R.id.navHome -> {
                    rv.smoothScrollToPosition(0)
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.navHome
        tvAnnounce.setOnClickListener { Cloud.showAnnouncement(this, force = true) }

        Cloud.init(this)
        Member.init(this)
        Cloud.onChanged = { refreshCloudUi() }

        applyInsets()
        playIntro()

        homeAdapter.setTitle(
            getString(R.string.home_title_popular),
            "此刻，大家都在看。",
            "TRENDING"
        )
        refreshCloudUi()
        refreshHistory()
        Profile.sync(this)
        Member.refresh(this)
        BiliUser.refreshVip(this)
        MemberShare.refresh(this)
        loadPopular(reset = true)
        syncCloud(prompts = true)
    }

    override fun onResume() {
        super.onResume()
        Cloud.gate(this)
        AppUpdater.onResume(this)
        Member.refresh(this)
        BiliUser.refreshVip(this)
        MemberShare.refresh(this)
        refreshHistory()
        syncCloud(prompts = false)
    }

    private fun refreshHistory() {
        homeAdapter.submitHistory(History.list(this))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空观看历史")
            .setMessage("只清除这台设备的观看记录和进度，不影响 B站账号历史。")
            .setPositiveButton("清空") { _, _ ->
                History.clear(this)
                refreshHistory()
                Ui.toast(this, "观看历史已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openHistory(item: History.Item) {
        if (item.kind == "pgc" && item.epId > 0) {
            startActivity(
                Intent(this, VideoActivity::class.java)
                    .putExtra("ep_id", item.epId)
                    .putExtra("title", item.title)
                    .putExtra("start_ms", item.pos)
            )
        } else if (item.bvid.isNotEmpty()) {
            startActivity(
                Intent(this, VideoActivity::class.java)
                    .putExtra("bvid", item.bvid)
                    .putExtra("title", item.title)
                    .putExtra("start_ms", item.pos)
            )
        }
    }

    /** 拉云端配置；锁定则弹不可关闭的提示 */
    private fun syncCloud(prompts: Boolean) {
        Cloud.refresh(this, force = true) {
            if (isFinishing || isDestroyed) return@refresh
            refreshCloudUi()
            if (Cloud.gate(this)) return@refresh
            if (prompts) {
                Cloud.maybeUpdate(this)
                Cloud.showAnnouncement(this)
            }
        }
    }

    private fun refreshCloudUi() {
        val title = Cloud.bannerTitle(this)
        if (title == null) {
            tvAnnounce.visibility = View.GONE
        } else {
            tvAnnounce.text = "公告 ｜ $title"
            tvAnnounce.visibility = View.VISIBLE
        }
    }

    /** 全局沉浸式：顶部让出状态栏，底部导航栏吃掉系统导航条；键盘弹出时整体上移 */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            headerContent.setPadding(
                Ui.dp(this, 16),
                Ui.dp(this, 8) + bars.top,
                Ui.dp(this, 16),
                Ui.dp(this, 10)
            )
            bottomNav.setPadding(0, 0, 0, if (ime > 0) ime else bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    /** 首页首帧：品牌区轻落一次，之后不再动 */
    private fun playIntro() {
        appBar.alpha = 0f
        appBar.translationY = -Ui.dp(this, 8).toFloat()
        appBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** 浮岛中央「功能」：用底部面板归纳全站入口 */
    private fun showFeatureSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_features, null)
        dialog.setContentView(sheet)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val cards = listOf(
            R.id.featureFriends to Intent(this, FriendsActivity::class.java),
            R.id.featureAccount to settingsIntent("account"),
            R.id.featureSettings to Intent(this, SettingsActivity::class.java)
        )

        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            sheet.findViewById<View>(R.id.btnSheetClose).setOnClickListener { dialog.dismiss() }

            for ((id, intent) in cards) {
                val card = sheet.findViewById<View>(id)
                Ui.pressScale(card)
                card.setOnClickListener {
                    dialog.dismiss()
                    startActivity(intent)
                }
            }

            val views = cards.map { sheet.findViewById<View>(it.first) }
            val dy = Ui.dp(this, 18).toFloat()
            views.forEachIndexed { index, card ->
                card.alpha = 0f
                card.translationY = dy
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(30L + index * 34L)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
        dialog.setOnDismissListener {
            if (featureSheet === dialog) featureSheet = null
        }
        featureSheet?.dismiss()
        featureSheet = dialog
        dialog.show()
    }

    private fun settingsIntent(scrollTo: String) =
        Intent(this, SettingsActivity::class.java).putExtra("scroll_to", scrollTo)

    private fun animateListIn() {
        rv.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Ui.staggerIn(rv, 70, 38, 330)
        }, 90)
    }

    private fun updateTopButton(show: Boolean) {
        if (show == topVisible) return
        topVisible = show
        btnTop.animate().cancel()
        if (show) {
            btnTop.visibility = View.VISIBLE
            btnTop.alpha = 0f
            btnTop.animate().alpha(1f).setDuration(160).start()
        } else {
            btnTop.animate().alpha(0f).setDuration(160).withEndAction {
                if (!topVisible) btnTop.visibility = View.GONE
            }.start()
        }
    }

    private fun loadPopular(reset: Boolean) {
        if (!reset && (loading || !hasNext)) return
        if (reset) {
            page = 1
            hasNext = true
            homeAdapter.setContentMode(HomeAdapter.ContentMode.VIDEO)
            showEmpty(getString(R.string.home_loading_popular))
        }
        loading = true
        pb.visibility = View.VISIBLE
        val s = ++seq
        val p = page
        io.execute {
            val attempt = runCatching { Bili.popular(p) }
            val res = attempt.getOrNull()
            val err = attempt.exceptionOrNull()
            main.post {
                if (s != seq) return@post
                loading = false
                pb.visibility = View.GONE
                if (res == null) {
                    if (reset) showEmpty(loadErrorText(err))
                    return@post
                }
                if (reset) {
                    homeAdapter.reset(res)
                    animateListIn()
                } else {
                    homeAdapter.append(res)
                }
                hasNext = res.length() >= 20
                if (hasNext) page = p + 1
                if (homeAdapter.contentCount == 0) {
                    showEmpty(getString(R.string.home_empty_popular))
                } else {
                    hideEmpty()
                }
            }
        }
    }

    /** 列表接近底部时自动加载下一页 */
    private fun nextPage() {
        if (loading || !hasNext) return
        loadPopular(reset = false)
    }

    private fun showEmpty(text: String) {
        tvEmpty.text = text
        tvEmpty.visibility = View.VISIBLE
    }

    private fun hideEmpty() {
        tvEmpty.visibility = View.GONE
    }

    /** 按失败原因给不同提示，不把所有网络问题都说成“加载失败” */
    private fun loadErrorText(err: Throwable?): String = when {
        hasCause(err, UnknownHostException::class.java) -> getString(R.string.home_error_offline)
        hasCause(err, SocketTimeoutException::class.java) -> getString(R.string.home_error_timeout)
        else -> getString(R.string.home_error_popular)
    }

    private fun hasCause(err: Throwable?, type: Class<out Throwable>): Boolean {
        var cur = err
        var depth = 0
        while (cur != null && depth < 6) {
            if (type.isInstance(cur)) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun openVideo(bvid: String) {
        if (bvid.isEmpty()) {
            Ui.toast(this, "这条内容暂时无法播放")
            return
        }
        startActivity(Intent(this, VideoActivity::class.java).putExtra("bvid", bvid))
    }

    private fun openCourse(seasonId: Long, title: String) {
        if (seasonId <= 0) {
            Ui.toast(this, "课程信息不全，暂时打不开")
            return
        }
        startActivity(
            Intent(this, VideoActivity::class.java)
                .putExtra("season_id", seasonId)
                .putExtra("title", Ui.stripEm(title))
        )
    }

    private fun openPgc(seasonId: Long, seasonType: Int) {
        if (seasonId <= 0) return
        startActivity(
            Intent(this, PgcDetailActivity::class.java)
                .putExtra("season_id", seasonId)
                .putExtra("season_type", seasonType)
        )
    }

    override fun onDestroy() {
        seq++
        featureSheet?.dismiss()
        featureSheet = null
        Cloud.onChanged = null
        io.shutdownNow()
        super.onDestroy()
    }
}
