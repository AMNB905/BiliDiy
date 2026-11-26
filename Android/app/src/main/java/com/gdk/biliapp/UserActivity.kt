package com.gdk.biliapp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 用户中心：
 * - 本机观看历史与 B站账号历史分来源展示，互不合并；
 * - 顶部资料卡 + 三项统计 + 社交中心入口；
 * - 历史点击直接续播，支持按来源删除。
 */
class UserActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var content: View
    private lateinit var tvAvatar: TextView
    private lateinit var ivAvatar: ImageView
    private lateinit var tvNickname: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var chipAll: TextView
    private lateinit var chipVideo: TextView
    private lateinit var chipAnime: TextView
    private lateinit var chipMovie: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnSourceLocal: TextView
    private lateinit var btnSourceBili: TextView
    private lateinit var tvStatLocal: TextView
    private lateinit var tvStatBili: TextView
    private lateinit var tvStatFriend: TextView
    private lateinit var tvSocialSub: TextView

    private var source = "local"
    private var filter = "all"
    private var biliSyncing = false
    private var socialSeq = 0

    private val adapter = HistoryListAdapter(
        onClick = { open(it) },
        onDelete = { confirmDelete(it) }
    )

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = Profile.setAvatar(this, uri)
        bindProfile()
        Ui.toast(this, if (ok) "头像已更新" else "头像设置失败，换张图片试试")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Cloud.init(this)
        Account.init(this)
        Member.init(this)
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_user)

        pageRoot = findViewById(R.id.pageRoot)
        content = findViewById(R.id.content)
        tvAvatar = findViewById(R.id.tvAvatar)
        ivAvatar = findViewById(R.id.ivAvatar)
        tvNickname = findViewById(R.id.tvNickname)
        tvDevice = findViewById(R.id.tvDevice)
        tvSync = findViewById(R.id.tvSync)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvHistory = findViewById(R.id.rvHistory)
        chipAll = findViewById(R.id.chipAll)
        chipVideo = findViewById(R.id.chipVideo)
        chipAnime = findViewById(R.id.chipAnime)
        chipMovie = findViewById(R.id.chipMovie)
        btnClear = findViewById(R.id.btnClear)
        btnSourceLocal = findViewById(R.id.btnSourceLocal)
        btnSourceBili = findViewById(R.id.btnSourceBili)
        tvStatLocal = findViewById(R.id.tvStatLocal)
        tvStatBili = findViewById(R.id.tvStatBili)
        tvStatFriend = findViewById(R.id.tvStatFriend)
        tvSocialSub = findViewById(R.id.tvSocialSub)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAvatar).setOnClickListener { pickAvatar.launch("image/*") }
        findViewById<View>(R.id.avatarBox).setOnClickListener { pickAvatar.launch("image/*") }
        findViewById<TextView>(R.id.btnName).setOnClickListener { editNickname() }
        tvNickname.setOnClickListener { editNickname() }
        findViewById<View>(R.id.tvSocialEntry).setOnClickListener { openSocial() }
        btnClear.setOnClickListener {
            if (source == "local") confirmClearLocal() else syncBiliHistory()
        }
        btnSourceLocal.setOnClickListener {
            if (source != "local") {
                source = "local"
                refreshHistory()
            }
        }
        btnSourceBili.setOnClickListener {
            if (source != "bili") {
                source = "bili"
                refreshHistory()
            }
        }

        setupFilters()

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        bindProfile()
        bindStats()
        applyInsets()
        Cloud.gate(this)
    }

    override fun onResume() {
        super.onResume()
        bindProfile()
        refreshHistory()
        bindSocialStats()
        Cloud.gate(this)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
            content.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 16), Ui.dp(this, 18) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun bindProfile() {
        val name = Profile.nickname(this)
        tvNickname.text = name
        tvAvatar.text = name.take(1).ifBlank { "用" }
        val bmp = if (Profile.hasAvatar(this)) {
            BitmapFactory.decodeFile(Profile.avatarFile(this).absolutePath)
        } else {
            null
        }
        if (bmp != null) {
            ivAvatar.setImageBitmap(bmp)
            ivAvatar.visibility = View.VISIBLE
            tvAvatar.visibility = View.GONE
        } else {
            ivAvatar.visibility = View.GONE
            tvAvatar.visibility = View.VISIBLE
        }
        tvDevice.text = "设备 ID · " + Profile.deviceId(this)
        bindSyncText(Profile.synced(this))
        Profile.sync(this) { ok ->
            if (isFinishing || isDestroyed) return@sync
            bindSyncText(ok)
        }
    }

    private fun bindSyncText(synced: Boolean) {
        tvSync.text = if (Account.isLoggedIn(this)) {
            val user = Account.username(this)
            "App @${user.ifBlank { "账号" }} · " + if (synced) "本机资料已同步" else "本地保存"
        } else {
            "App 账号未登录 · 仅本机资料"
        }
    }

    private fun bindStats() {
        tvStatLocal.text = History.list(this).size.toString()
        tvStatBili.text = BiliHistory.count(this).toString()
        if (!Account.isLoggedIn(this)) tvStatFriend.text = "—"
    }

    private fun bindSocialStats() {
        if (!Account.isLoggedIn(this)) {
            tvStatFriend.text = "—"
            tvSocialSub.text = "登录 App 账号后，加好友、互相分享会员 ›"
            return
        }
        val seq = ++socialSeq
        tvSocialSub.text = "正在读取好友与分享…"
        Friends.list(this) { ok, _, friends ->
            if (isFinishing || isDestroyed || seq != socialSeq) return@list
            if (!ok) {
                tvStatFriend.text = "—"
                tvSocialSub.text = "好友列表读取失败，点这里重试 ›"
                return@list
            }
            tvStatFriend.text = friends.size.toString()
            Friends.requests(this) { _, _, requests ->
                if (isFinishing || isDestroyed || seq != socialSeq) return@requests
                val pending = requests.count { it.incoming }
                MemberShare.refresh(this, force = false) {
                    if (isFinishing || isDestroyed || seq != socialSeq) return@refresh
                    val incoming = MemberShare.list(this).first
                    val active = incoming.count {
                        it.active && it.expireAt > System.currentTimeMillis()
                    }
                    tvSocialSub.text = if (pending > 0) {
                        "待处理 $pending 条好友申请 · 好友 ${friends.size} 位 ›"
                    } else {
                        "好友 ${friends.size} 位 · 可用分享 $active 个 ›"
                    }
                }
            }
        }
    }

    private fun setupFilters() {
        val chips = listOf(
            "all" to chipAll,
            "video" to chipVideo,
            "anime" to chipAnime,
            "movie" to chipMovie
        )
        chips.forEach { (key, view) ->
            view.setOnClickListener {
                filter = key
                refreshHistory()
            }
        }
    }

    private fun currentItems(): List<History.Item> =
        if (source == "local") History.list(this) else BiliHistory.list(this)

    private fun refreshHistory() {
        bindStats()
        btnSourceLocal.setBackgroundResource(
            if (source == "local") R.drawable.bg_chip_on else R.drawable.bg_chip
        )
        btnSourceLocal.setTextColor(ContextCompat.getColor(
            this, if (source == "local") R.color.pinkDeep else R.color.textSecondary
        ))
        btnSourceBili.setBackgroundResource(
            if (source == "bili") R.drawable.bg_chip_on else R.drawable.bg_chip
        )
        btnSourceBili.setTextColor(ContextCompat.getColor(
            this, if (source == "bili") R.color.pinkDeep else R.color.textSecondary
        ))

        val all = currentItems()
        val items = if (filter == "all") all else all.filter { it.cat == filter }
        adapter.submit(items)

        val counts = mapOf(
            "all" to all.size,
            "video" to all.count { it.cat == "video" },
            "anime" to all.count { it.cat == "anime" },
            "movie" to all.count { it.cat == "movie" }
        )
        val labels = listOf(
            "all" to chipAll,
            "video" to chipVideo,
            "anime" to chipAnime,
            "movie" to chipMovie
        )
        labels.forEach { (key, view) ->
            val name = when (key) {
                "all" -> "全部"
                "video" -> "视频"
                "anime" -> "番剧"
                else -> "电影"
            }
            view.text = "$name ${counts[key] ?: 0}"
            val on = filter == key
            view.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            view.setTextColor(ContextCompat.getColor(this, if (on) R.color.pink else R.color.textSecondary))
        }

        val prefix = if (source == "local") "本机历史" else "B站独立历史"
        tvCount.text = if (filter == "all") {
            "共 ${all.size} 条 · $prefix"
        } else {
            "筛选 ${items.size} 条 · 总计 ${all.size} 条 · $prefix"
        }
        btnClear.text = if (source == "local") "清空" else if (biliSyncing) "同步中…" else "同步"
        btnClear.isEnabled = !(source == "bili" && biliSyncing)

        val emptyText = when {
            source == "bili" && !BiliUser.isBound(this) ->
                "还没有绑定 B站账号\n绑定并同步后，B站历史会独立保存在这里"
            source == "bili" && filter == "video" -> "B站历史里还没有视频记录"
            source == "bili" && filter == "anime" -> "B站历史里还没有番剧记录"
            source == "bili" && filter == "movie" -> "B站历史里还没有电影记录"
            source == "bili" -> "B站历史还没有记录\n点右上角「同步」拉取账号历史"
            filter == "video" -> "还没有视频记录"
            filter == "anime" -> "还没有番剧记录"
            filter == "movie" -> "还没有电影记录"
            else -> "还没有本机播放记录\n随便看一个视频就会出现在这里"
        }
        tvEmpty.text = emptyText
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvHistory.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun syncBiliHistory() {
        if (biliSyncing) return
        if (!BiliUser.isBound(this)) {
            startActivity(Intent(this, BiliLoginActivity::class.java))
            return
        }
        biliSyncing = true
        refreshHistory()
        BiliSync.syncAll(this) { r ->
            biliSyncing = false
            if (isFinishing || isDestroyed) return@syncAll
            refreshHistory()
            AlertDialog.Builder(this)
                .setTitle("B站同步完成")
                .setMessage(r.summary())
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun editNickname() {
        val input = EditText(this).apply {
            setText(Profile.nickname(this@UserActivity))
            setSelection(text.length)
            hint = "最多 16 个字"
            maxLines = 1
            setPadding(
                Ui.dp(this@UserActivity, 4), Ui.dp(this@UserActivity, 8),
                Ui.dp(this@UserActivity, 4), Ui.dp(this@UserActivity, 8)
            )
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(this@UserActivity, 20), 0, Ui.dp(this@UserActivity, 20), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle("修改昵称")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Profile.setNickname(this, input.text.toString())
                bindProfile()
                Ui.toast(this, "昵称已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearLocal() {
        if (History.list(this).isEmpty()) {
            Ui.toast(this, "本机还没有播放记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空本机观看记录")
            .setMessage("只清除这台设备的观看记录与进度，不影响 B站账号历史。")
            .setPositiveButton("清空") { _, _ ->
                History.clear(this)
                refreshHistory()
                Ui.toast(this, "已清空本机记录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(item: History.Item) {
        AlertDialog.Builder(this)
            .setTitle("移除这条记录")
            .setMessage(item.title)
            .setPositiveButton("移除") { _, _ ->
                if (source == "local") {
                    History.remove(this, item.key)
                } else {
                    BiliHistory.remove(this, item.key)
                }
                refreshHistory()
                Ui.toast(this, "已移除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSocial() {
        if (!Account.isLoggedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java).putExtra("gate", true))
            return
        }
        startActivity(Intent(this, FriendsActivity::class.java))
    }

    private fun open(item: History.Item) {
        val intent = Intent(this, VideoActivity::class.java)
            .putExtra("title", item.title)
            .putExtra("start_ms", item.pos)
        if (item.kind == "pgc" && item.epId > 0) {
            intent.putExtra("ep_id", item.epId)
        } else {
            intent.putExtra("bvid", item.bvid)
        }
        startActivity(intent)
    }
}
