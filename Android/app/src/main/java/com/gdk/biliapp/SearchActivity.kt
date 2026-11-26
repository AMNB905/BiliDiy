package com.gdk.biliapp

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * 独立搜索页：
 * - 未输入时显示本机搜索历史、B 站实时热搜、全站排行榜；
 * - 输入后在同一页显示视频 / 番剧 / 影视搜索结果；
 * - 搜索历史存本机 SharedPreferences，最多 20 条。
 */
class SearchActivity : BaseActivity() {

    private enum class PageState { HOME, RESULTS }

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private var state = PageState.HOME
    private var kind = SearchKind.VIDEO
    private var keyword = ""
    private var page = 1
    private var loading = false
    private var hasNext = true
    private var seq = 0

    private lateinit var pageRoot: View
    private lateinit var etSearch: EditText
    private lateinit var btnClear: TextView
    private lateinit var scrollHome: NestedScrollView
    private lateinit var resultsPanel: View
    private lateinit var rvResults: RecyclerView
    private lateinit var pb: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var chipHistory: ChipGroup
    private lateinit var chipHot: ChipGroup
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var tvHotState: TextView
    private lateinit var listRanking: LinearLayout
    private lateinit var tvRankState: TextView
    private lateinit var tvRankTitle: TextView
    private lateinit var btnHistoryClear: TextView
    private lateinit var btnKindFilter: TextView

    private val resultsAdapter = HomeAdapter(
        onVideo = { v ->
            if (v.optString("type") == "ketang") {
                openCourse(v.optLong("id", 0), v.optString("title", ""))
            } else {
                openVideo(v.optString("bvid", ""))
            }
        },
        onPgc = { s -> openPgc(s.optLong("season_id", 0), s.optInt("season_type", 2)) },
        onHistory = {},
        onClearHistory = {}
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
        setContentView(R.layout.activity_search)

        pageRoot = findViewById(R.id.pageRoot)
        etSearch = findViewById(R.id.etSearch)
        btnClear = findViewById(R.id.btnClear)
        scrollHome = findViewById(R.id.scrollHome)
        resultsPanel = findViewById(R.id.resultsPanel)
        rvResults = findViewById(R.id.rvResults)
        pb = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        chipHistory = findViewById(R.id.chipHistory)
        chipHot = findViewById(R.id.chipHot)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        tvHotState = findViewById(R.id.tvHotState)
        listRanking = findViewById(R.id.listRanking)
        tvRankState = findViewById(R.id.tvRankState)
        tvRankTitle = findViewById(R.id.tvRankTitle)
        btnHistoryClear = findViewById(R.id.btnHistoryClear)
        btnKindFilter = findViewById(R.id.btnKindFilter)

        val glm = GridLayoutManager(this, 2)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (resultsAdapter.isFullSpan(position)) 2 else 1
        }
        rvResults.layoutManager = glm
        rvResults.adapter = resultsAdapter
        resultsAdapter.setHistoryVisible(false)
        rvResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = r.layoutManager as? GridLayoutManager ?: return
                val total = r.adapter?.itemCount ?: return
                if (lm.findLastVisibleItemPosition() >= total - 4) nextPage()
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { back() }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            runSearch(etSearch.text.toString(), remember = true)
        }
        btnClear.setOnClickListener { etSearch.setText("") }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(etSearch.text.toString(), remember = true); true
            } else false
        }
        etSearch.doAfterTextChanged { t ->
            btnClear.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
            if (t.isNullOrBlank() && state == PageState.RESULTS) {
                showHome(clearText = false, hideKeyboard = false)
            }
        }

        btnKindFilter.setOnClickListener { showKindMenu() }
        btnHistoryClear.setOnClickListener { confirmClearHistory() }
        tvEmpty.setOnClickListener {
            if (state == PageState.RESULTS && keyword.isNotEmpty()) loadResults(reset = true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        applyInsets()
        styleKind()
        renderHistory()
        showHome(clearText = true, hideKeyboard = false)

        // 从主页点搜索进来时，直接把焦点放到输入框
        etSearch.requestFocus()
        etSearch.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 180)

        loadHot()
        loadRanking()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
            val side = Ui.dp(this, 12)
            rvResults.setPadding(side, Ui.dp(this, 2), side, Ui.dp(this, 16) + bars.bottom)
            scrollHome.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun styleKind() {
        val label = kindLabel(kind)
        btnKindFilter.text = "$label  ▾"
        btnKindFilter.contentDescription = "搜索类型：$label"
    }

    private fun showKindMenu() {
        val popup = PopupMenu(this, btnKindFilter)
        popup.menu.add(0, 1, 0, kindLabel(SearchKind.VIDEO))
        popup.menu.add(0, 2, 1, kindLabel(SearchKind.BANGUMI))
        popup.menu.add(0, 3, 2, kindLabel(SearchKind.MOVIE))
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.findItem(1)?.isChecked = kind == SearchKind.VIDEO
        popup.menu.findItem(2)?.isChecked = kind == SearchKind.BANGUMI
        popup.menu.findItem(3)?.isChecked = kind == SearchKind.MOVIE
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> selectKind(SearchKind.VIDEO)
                2 -> selectKind(SearchKind.BANGUMI)
                3 -> selectKind(SearchKind.MOVIE)
            }
            true
        }
        popup.show()
    }

    private fun selectKind(k: SearchKind) {
        if (kind == k) return
        kind = k
        styleKind()
        if (state == PageState.RESULTS && keyword.isNotEmpty()) {
            runSearch(keyword, remember = false)
        }
    }

    private fun renderHistory() {
        val items = SearchHistory.list(this)
        chipHistory.removeAllViews()
        btnHistoryClear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvHistoryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        chipHistory.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (kw in items) {
            addChip(chipHistory, kw, closable = true) {
                etSearch.setText(kw)
                etSearch.setSelection(etSearch.text.length)
                runSearch(kw, remember = true)
            }
        }
    }

    private fun confirmClearHistory() {
        if (SearchHistory.list(this).isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空搜索历史")
            .setMessage("只清除本机的搜索记录，不影响观看历史。")
            .setPositiveButton("清空") { _, _ ->
                SearchHistory.clear(this)
                renderHistory()
                Ui.toast(this, "搜索历史已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addChip(group: ChipGroup, text: String, closable: Boolean, onClick: () -> Unit) {
        val chip = Chip(this)
        chip.text = text
        chip.isCheckable = false
        chip.isClickable = true
        chip.isFocusable = true
        chip.isAllCaps = false
        chip.setTextColor(getColor(R.color.textMain))
        chip.textSize = 12f
        chip.maxLines = 1
        chip.ellipsize = TextUtils.TruncateAt.END
        chip.maxWidth = Ui.dp(this, 260)
        chip.chipBackgroundColor = ColorStateList.valueOf(getColor(R.color.surface))
        chip.chipStrokeWidth = Ui.dp(this, 1).toFloat()
        chip.chipStrokeColor = ColorStateList.valueOf(getColor(R.color.line))
        chip.chipCornerRadius = Ui.dp(this, 18).toFloat()
        chip.chipMinHeight = Ui.dp(this, 34).toFloat()
        chip.chipStartPadding = Ui.dp(this, 12).toFloat()
        chip.chipEndPadding = Ui.dp(this, 12).toFloat()
        chip.setOnClickListener { onClick() }
        if (closable) {
            chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener {
                SearchHistory.remove(this, text)
                renderHistory()
            }
        }
        group.addView(chip)
    }

    private fun loadHot() {
        chipHot.visibility = View.GONE
        tvHotState.visibility = View.VISIBLE
        tvHotState.text = "正在加载 B 站热搜…"
        tvHotState.isClickable = false
        tvHotState.setOnClickListener(null)

        io.execute {
            val attempt = runCatching { Bili.hotSearch() }
            val arr = attempt.getOrNull()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (arr == null) {
                    tvHotState.text = "热搜加载失败，点此重试"
                    tvHotState.isClickable = true
                    tvHotState.setOnClickListener { loadHot() }
                    return@post
                }
                val words = ArrayList<String>()
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kw = o.optString("keyword", "")
                        .ifBlank { o.optString("show_name", "") }
                        .trim()
                    if (kw.isNotEmpty() && seen.add(kw)) words.add(kw)
                    if (words.size >= 12) break
                }
                chipHot.removeAllViews()
                if (words.isEmpty()) {
                    tvHotState.text = "热搜暂时没有内容"
                    tvHotState.visibility = View.VISIBLE
                    return@post
                }
                for (kw in words) {
                    addChip(chipHot, kw, closable = false) {
                        etSearch.setText(kw)
                        etSearch.setSelection(etSearch.text.length)
                        runSearch(kw, remember = true)
                    }
                }
                chipHot.visibility = View.VISIBLE
                tvHotState.visibility = View.GONE
            }
        }
    }

    private fun loadRanking() {
        listRanking.removeAllViews()
        tvRankState.visibility = View.VISIBLE
        tvRankState.text = "正在加载排行榜…"
        tvRankState.isClickable = false
        tvRankState.setOnClickListener(null)

        io.execute {
            val attempt = runCatching { Bili.ranking() }
            val feed = attempt.getOrNull()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (feed == null) {
                    tvRankState.text = "排行榜加载失败，点此重试"
                    tvRankState.isClickable = true
                    tvRankState.setOnClickListener { loadRanking() }
                    return@post
                }
                tvRankTitle.text = if (feed.fallback) "热门排行" else "全站排行榜"
                val count = minOf(10, feed.items.length())
                if (count == 0) {
                    tvRankState.text = "排行榜暂时没有内容"
                    tvRankState.visibility = View.VISIBLE
                    return@post
                }
                for (i in 0 until count) {
                    val o = feed.items.optJSONObject(i) ?: continue
                    addRankRow(i + 1, o)
                }
                tvRankState.visibility = View.GONE
            }
        }
    }

    private fun addRankRow(rank: Int, raw: JSONObject) {
        val o = normalizeRank(raw)
        val row = layoutInflater.inflate(R.layout.item_rank_row, listRanking, false)
        val tvRank = row.findViewById<TextView>(R.id.tvRank)
        val cover = row.findViewById<ImageView>(R.id.ivRankCover)
        val duration = row.findViewById<TextView>(R.id.tvRankDuration)
        val title = row.findViewById<TextView>(R.id.tvRankTitleItem)
        val meta = row.findViewById<TextView>(R.id.tvRankMeta)

        tvRank.text = rank.toString()
        tvRank.setTextColor(getColor(if (rank <= 3) R.color.pink else R.color.textSecondary))
        ImageLoader.load(cover, o.optString("pic", ""))
        val durValue = o.opt("duration")
        val dur = when (durValue) {
            is Number -> Ui.fmtDur(durValue.toLong())
            is String -> durValue
            else -> ""
        }
        duration.text = dur
        duration.visibility = if (dur.isNotEmpty()) View.VISIBLE else View.GONE

        title.text = o.optString("title", "")
        val author = o.optString("author", "")
        val play = o.optLong("play", 0)
        meta.text = if (author.isNotEmpty()) {
            "$author · ${Ui.fmtCount(play)}播放"
        } else {
            "${Ui.fmtCount(play)}播放"
        }
        row.setOnClickListener { openVideo(o.optString("bvid", "")) }
        listRanking.addView(row)
    }

    private fun normalizeRank(raw: JSONObject): JSONObject {
        val title = Ui.stripEm(raw.optString("title", ""))
            .ifBlank { raw.optString("name", "") }
        val pic = raw.optString("pic", "").ifBlank { raw.optString("cover", "") }
        val owner = raw.optJSONObject("owner")?.optString("name", "").orEmpty()
            .ifBlank { raw.optString("author", "") }
        val play = raw.optJSONObject("stat")?.optLong("view", 0)
            ?: raw.optLong("play", 0)
        return JSONObject().apply {
            put("title", title)
            put("pic", pic)
            put("bvid", raw.optString("bvid", ""))
            put("author", owner)
            put("play", play)
            put("duration", raw.opt("duration") ?: "")
        }
    }

    private fun runSearch(raw: String, remember: Boolean) {
        val kw = raw.trim()
        if (kw.isEmpty()) {
            Ui.toast(this, "先输入要搜的内容")
            return
        }
        keyword = kw
        state = PageState.RESULTS
        if (remember) {
            SearchHistory.add(this, kw)
            renderHistory()
        }
        if (etSearch.text.toString() != kw) {
            etSearch.setText(kw)
            etSearch.setSelection(etSearch.text.length)
        }
        hideKeyboard()
        scrollHome.visibility = View.GONE
        resultsPanel.visibility = View.VISIBLE
        resultsAdapter.setContentMode(
            if (kind == SearchKind.VIDEO) HomeAdapter.ContentMode.VIDEO
            else HomeAdapter.ContentMode.PGC
        )
        resultsAdapter.reset(null)
        tvEmpty.visibility = View.GONE
        rvResults.scrollToPosition(0)
        page = 1
        hasNext = true
        loading = false
        loadResults(reset = true)
    }

    private fun loadResults(reset: Boolean) {
        if (loading && !reset) return
        if (state != PageState.RESULTS || keyword.isEmpty()) return
        loading = true
        pb.visibility = View.VISIBLE
        val s = ++seq
        val k = kind
        val kw = keyword
        val p = if (reset) 1 else page
        if (reset) showEmpty(getString(R.string.home_loading_search, kw))
        io.execute {
            val attempt = runCatching { Bili.search(k, kw, p) }
            val arr = attempt.getOrNull()
            val err = attempt.exceptionOrNull()
            main.post {
                if (s != seq) return@post
                loading = false
                pb.visibility = View.GONE
                if (arr == null) {
                    if (reset) {
                        resultsAdapter.reset(null)
                        showEmpty(errorText(err))
                    }
                    return@post
                }
                if (reset) resultsAdapter.reset(arr) else resultsAdapter.append(arr)
                hasNext = arr.length() >= 20
                if (hasNext) page = p + 1
                resultsAdapter.setTitle(
                    getString(R.string.home_title_result, kw, kindLabel(k)),
                    "按「$kw」找到的内容。",
                    "RESULTS"
                )
                if (resultsAdapter.contentCount == 0) {
                    showEmpty(emptyText(k, kw))
                } else {
                    hideEmpty()
                }
            }
        }
    }

    private fun nextPage() {
        if (state != PageState.RESULTS || loading || !hasNext || keyword.isEmpty()) return
        loadResults(reset = false)
    }

    private fun kindLabel(k: SearchKind = kind) = when (k) {
        SearchKind.VIDEO -> "视频"
        SearchKind.BANGUMI -> "番剧"
        SearchKind.MOVIE -> "影视"
    }

    private fun emptyText(k: SearchKind, kw: String) = when (k) {
        SearchKind.MOVIE -> getString(R.string.home_empty_search_movie, kw)
        SearchKind.BANGUMI -> getString(R.string.home_empty_search_bangumi, kw)
        else -> getString(R.string.home_empty_search_video, kw)
    }

    private fun errorText(err: Throwable?) = when {
        hasCause(err, UnknownHostException::class.java) -> getString(R.string.home_error_offline)
        hasCause(err, SocketTimeoutException::class.java) -> getString(R.string.home_error_timeout)
        else -> getString(R.string.home_error_search)
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

    private fun showHome(clearText: Boolean, hideKeyboard: Boolean) {
        state = PageState.HOME
        keyword = ""
        seq++
        loading = false
        pb.visibility = View.GONE
        if (clearText && etSearch.text.isNotEmpty()) etSearch.setText("")
        resultsAdapter.reset(null)
        scrollHome.visibility = View.VISIBLE
        resultsPanel.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        renderHistory()
        if (hideKeyboard) hideKeyboard()
    }

    private fun back() {
        if (state == PageState.RESULTS) {
            showHome(clearText = true, hideKeyboard = true)
        } else {
            finish()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun showEmpty(text: String) {
        tvEmpty.text = text
        tvEmpty.visibility = View.VISIBLE
    }

    private fun hideEmpty() {
        tvEmpty.visibility = View.GONE
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
        io.shutdownNow()
        super.onDestroy()
    }
}
