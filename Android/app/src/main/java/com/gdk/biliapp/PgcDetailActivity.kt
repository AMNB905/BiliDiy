package com.gdk.biliapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/** 番剧 / 影视详情页：封面、评分、简介、剧集（含版本）选择 */
class PgcDetailActivity : BaseActivity() {

    private var seasonId = 0L
    private var epId = 0L
    private var loadedTitle = ""

    private lateinit var epAdapter: EpAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvOrg: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvEpTitle: TextView
    private lateinit var tvEpCount: TextView
    private lateinit var btnJump: TextView
    private lateinit var btnPlay: TextView
    private lateinit var banner: ImageView
    private lateinit var rvEps: RecyclerView

    private var episodes: List<Ep> = emptyList()

    private lateinit var pageRoot: View
    private lateinit var bannerBox: View
    private lateinit var content: View
    private lateinit var btnBack: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Cloud.init(this)
        // 顶部是海报 + 深色渐变 → 状态栏白图标；下方是白底 → 导航栏深图标
        Ui.edgeToEdge(this, false)
        Ui.barIcons(this, false, Ui.isLight(this))
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_pgc)

        seasonId = intent.getLongExtra("season_id", 0L)
        epId = intent.getLongExtra("ep_id", 0L)
        if (seasonId == 0L && epId == 0L) { finish(); return }

        pageRoot = findViewById(R.id.pageRoot)
        bannerBox = findViewById(R.id.bannerBox)
        content = findViewById(R.id.content)
        btnBack = findViewById(R.id.btnBack)
        banner = findViewById(R.id.ivBanner)
        tvTitle = findViewById(R.id.tvTitle)
        tvOrg = findViewById(R.id.tvOrg)
        tvScore = findViewById(R.id.tvScore)
        tvMeta = findViewById(R.id.tvMeta)
        tvStats = findViewById(R.id.tvStats)
        tvDesc = findViewById(R.id.tvDesc)
        tvEpTitle = findViewById(R.id.tvEpTitle)
        tvEpCount = findViewById(R.id.tvEpCount)
        btnJump = findViewById(R.id.btnJump)
        btnPlay = findViewById(R.id.btnPlay)

        btnBack.setOnClickListener { finish() }
        applyInsets()

        rvEps = findViewById(R.id.rvEps)
        rvEps.layoutManager = GridLayoutManager(this, 4)
        epAdapter = EpAdapter { label -> openVideo(label) }
        rvEps.adapter = epAdapter
        btnJump.setOnClickListener { showJumpDialog() }

        load()
        Cloud.gate(this)
    }

    override fun onResume() {
        super.onResume()
        Cloud.gate(this)
    }

    /** banner 铺到状态栏下，返回键与内容按 insets 让位 */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val blp = bannerBox.layoutParams
            blp.height = Ui.dp(this, 200) + b.top
            bannerBox.layoutParams = blp

            val backLp = btnBack.layoutParams as android.view.ViewGroup.MarginLayoutParams
            backLp.topMargin = Ui.dp(this, 12) + b.top
            btnBack.layoutParams = backLp

            content.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 28) + b.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun load() {
        val sid = seasonId
        val eid = epId
        Bili.io {
            try {
                val season = if (sid > 0) Bili.pgcSeason(sid) else Bili.pgcSeasonByEp(eid)
                Bili.main { bind(season) }
            } catch (e: Exception) {
                Bili.main {
                    tvTitle.text = "加载失败"
                    tvMeta.text = e.message ?: ""
                    Ui.toast(this@PgcDetailActivity, "详情加载失败: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bind(season: JSONObject) {
        loadedTitle = Ui.stripEm(season.optString("title", ""))
        tvTitle.text = loadedTitle

        val org = season.optString("org_title", "")
        tvOrg.text = org
        tvOrg.visibility = if (org.isNotEmpty()) View.VISIBLE else View.GONE

        val rating = season.optJSONObject("rating")
        val score = rating?.optDouble("score", 0.0) ?: 0.0
        val count = rating?.optLong("count", 0) ?: 0
        val hasScore = score > 0
        tvScore.text = if (hasScore) "★ %.1f".format(score) else ""
        tvScore.visibility = if (hasScore) View.VISIBLE else View.GONE

        val areas = ArrayList<String>()
        season.optJSONArray("areas")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("name", "")?.let { areas.add(it) }
        }
        val styles = ArrayList<String>()
        season.optJSONArray("styles")?.let { arr ->
            for (i in 0 until arr.length()) styles.add(arr.optString(i, ""))
        }
        val total = season.optInt("total", 0)
        val kind = if (season.optInt("type", 2) == 1) "番剧" else "影视"
        val parts = ArrayList<String>()
        parts.add(kind)
        if (areas.isNotEmpty()) parts.add(areas.joinToString("/"))
        if (styles.isNotEmpty()) parts.add(styles.joinToString("/"))
        if (total > 0) parts.add("共 $total 集")
        tvMeta.text = parts.joinToString(" · ")

        val stats = season.optString("subtitle", "")
        val scoreCount = if (hasScore && count > 0) " · ${Ui.fmtCount(count)}人评分" else ""
        tvStats.text = stats + scoreCount
        tvStats.visibility = if (tvStats.text.isNotEmpty()) View.VISIBLE else View.GONE

        val desc = season.optString("evaluate", "")
        tvDesc.text = desc
        tvDesc.visibility = if (desc.isNotEmpty()) View.VISIBLE else View.GONE

        val cover = season.optString("bkg_cover", "")
            .ifEmpty { season.optString("cover", "") }
        ImageLoader.load(banner, cover)

        val eps = season.optJSONArray("episodes") ?: JSONArray()
        val items = ArrayList<Ep>()
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            val index = e.optString("title", "")       // 番剧是集号，电影是"正片/原版"
            val sub = e.optString("long_title", "")
            val badge = e.optString("badge", "")
            items.add(Ep(
                id = e.optLong("id", 0),
                index = index,
                sub = sub,
                badge = badge,
                duration = e.optLong("duration", 0)
            ))
        }
        epAdapter.submit(items)
        episodes = items
        tvEpTitle.text = when {
            eps.length() == 0 -> "暂无可播放剧集"
            season.optInt("type", 2) == 1 -> "选集"
            else -> "版本"
        }
        tvEpCount.text = if (items.isEmpty()) "" else
            if (season.optInt("type", 2) == 1) "共 ${items.size} 集 · 上下滑动选择" else "共 ${items.size} 个版本"
        btnJump.visibility = if (items.size > 8) View.VISIBLE else View.GONE
        rvEps.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (epId > 0) {
            val pos = items.indexOfFirst { it.id == epId }
            if (pos >= 0) rvEps.post {
                (rvEps.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            }
        }

        if (items.isNotEmpty()) {
            btnPlay.visibility = View.VISIBLE
            btnPlay.text = if (season.optInt("type", 2) == 1) "播放第 1 集" else "播放正片"
            val target = items.first()
            btnPlay.setOnClickListener { openVideo(target) }
        } else {
            btnPlay.visibility = View.GONE
        }
    }

    private fun showJumpDialog() {
        if (episodes.isEmpty()) return
        val et = android.widget.EditText(this)
        et.hint = "输入集数，例如 520"
        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        et.setSingleLine(true)
        et.setPadding(dp(20), dp(8), dp(20), dp(8))
        AlertDialog.Builder(this)
            .setTitle("跳转到指定集")
            .setView(et, 0, dp(4), 0, 0)
            .setPositiveButton("跳转") { _, _ ->
                val n = et.text.toString().trim().toIntOrNull()
                if (n == null) {
                    Ui.toast(this, "请输入集数")
                    return@setPositiveButton
                }
                val exact = episodes.indexOfFirst { it.index.filter { c -> c.isDigit() } == n.toString() }
                val fuzzy = if (exact >= 0) exact else episodes.indexOfFirst {
                    it.index.contains(n.toString())
                }
                if (fuzzy < 0) {
                    Ui.toast(this, "没有找到第 $n 集")
                    return@setPositiveButton
                }
                (rvEps.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(fuzzy, 0)
                Ui.toast(this, "已定位到第 $n 集")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun openVideo(ep: Ep) {
        if (ep.id == 0L) return
        val label = if (ep.sub.isNotEmpty()) "${ep.index} ${ep.sub}" else ep.index
        startActivity(Intent(this, VideoActivity::class.java)
            .putExtra("ep_id", ep.id)
            .putExtra("title", "$loadedTitle · $label"))
    }
}

data class Ep(val id: Long, val index: String, val sub: String, val badge: String, val duration: Long)

class EpAdapter(private val onClick: (Ep) -> Unit) :
    RecyclerView.Adapter<EpAdapter.VH>() {

    private val items = ArrayList<Ep>()
    private var selectedId = 0L

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Ep>, selected: Long = 0L) {
        items.clear()
        items.addAll(list)
        selectedId = selected
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.tvEpIndex)
        val sub: TextView = v.findViewById(R.id.tvEpSub)
        val badge: TextView = v.findViewById(R.id.tvEpBadge)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_ep, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ep = items[position]
        h.index.text = ep.index
        h.sub.text = ep.sub
        h.sub.visibility = if (ep.sub.isNotEmpty()) View.VISIBLE else View.GONE
        h.badge.visibility = View.GONE
        if (ep.badge.isNotEmpty()) {
            h.badge.text = ep.badge
            h.badge.visibility = View.VISIBLE
        }
        h.itemView.setBackgroundResource(
            if (ep.id == selectedId) R.drawable.bg_chip_on else R.drawable.bg_chip
        )
        h.itemView.setOnClickListener { onClick(ep) }
    }
}

