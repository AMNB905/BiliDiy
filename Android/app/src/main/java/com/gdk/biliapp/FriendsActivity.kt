package com.gdk.biliapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 社交中心 2.0：
 * - 顶部账号名片、好友/可用分享/待处理统计；
 * - 好友在线状态、彩色头像、分享/复制/删除操作；
 * - 好友申请、会员分享统一为同一套社交行。
 */
class FriendsActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var content: View
    private lateinit var tvSocialSubtitle: TextView
    private lateinit var tvMyAvatar: TextView
    private lateinit var tvMyName: TextView
    private lateinit var tvMyHandle: TextView
    private lateinit var tvStatFriends: TextView
    private lateinit var tvStatShares: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var llFriends: LinearLayout
    private lateinit var llIncoming: LinearLayout
    private lateinit var llOutgoing: LinearLayout
    private lateinit var llRequests: LinearLayout
    private lateinit var tvFriendCount: TextView
    private lateinit var tvIncomingCount: TextView
    private lateinit var tvOutgoingCount: TextView
    private lateinit var tvRequestCount: TextView
    private lateinit var tvFriendEmpty: TextView
    private lateinit var tvIncomingEmpty: TextView
    private lateinit var tvOutgoingEmpty: TextView
    private lateinit var tvRequestEmpty: TextView

    private var friendCount = 0
    private var pendingCount = 0
    private var activeShareCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Cloud.init(this)
        Account.init(this)
        Member.init(this)
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_friends)

        pageRoot = findViewById(R.id.pageRoot)
        content = findViewById(R.id.content)
        tvSocialSubtitle = findViewById(R.id.tvSocialSubtitle)
        tvMyAvatar = findViewById(R.id.tvMyAvatar)
        tvMyName = findViewById(R.id.tvMyName)
        tvMyHandle = findViewById(R.id.tvMyHandle)
        tvStatFriends = findViewById(R.id.tvStatFriends)
        tvStatShares = findViewById(R.id.tvStatShares)
        tvStatPending = findViewById(R.id.tvStatPending)
        llFriends = findViewById(R.id.llFriends)
        llIncoming = findViewById(R.id.llIncoming)
        llOutgoing = findViewById(R.id.llOutgoing)
        llRequests = findViewById(R.id.llRequests)
        tvFriendCount = findViewById(R.id.tvFriendCount)
        tvIncomingCount = findViewById(R.id.tvIncomingCount)
        tvOutgoingCount = findViewById(R.id.tvOutgoingCount)
        tvRequestCount = findViewById(R.id.tvRequestCount)
        tvFriendEmpty = findViewById(R.id.tvFriendEmpty)
        tvIncomingEmpty = findViewById(R.id.tvIncomingEmpty)
        tvOutgoingEmpty = findViewById(R.id.tvOutgoingEmpty)
        tvRequestEmpty = findViewById(R.id.tvRequestEmpty)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddFriend).setOnClickListener { showAddFriend() }
        findViewById<TextView>(R.id.btnInvite).setOnClickListener {
            val username = Account.username(this)
            if (username.isBlank()) {
                Ui.toast(this, "请先登录 App 账号")
                return@setOnClickListener
            }
            SocialInvite.share(this, username, Account.nickname(this))
        }
        tvSocialSubtitle.setOnClickListener {
            refreshAll()
            Ui.toast(this, "已刷新社交中心")
        }

        bindIdentity()
        applyInsets()
        Cloud.gate(this)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        Cloud.gate(this)
        bindIdentity()
        BiliUser.refreshVip(this) {
            if (!isFinishing && !isDestroyed) refreshShares()
        }
        refreshAll()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            content.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), Ui.dp(this, 24) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun bindIdentity() {
        val username = Account.username(this)
        val nickname = Account.nickname(this).ifBlank { username.ifBlank { "BiliDiy 用户" } }
        tvMyName.text = nickname
        tvMyHandle.text = if (username.isBlank()) "@未登录" else "@$username"
        tvMyAvatar.text = nickname.take(1).ifBlank { "友" }
        updateSubtitle()
    }

    private fun updateSubtitle() {
        tvSocialSubtitle.text = if (pendingCount > 0) {
            "待处理 $pendingCount 条好友申请 · 点击刷新"
        } else {
            "好友、会员分享与好友申请 · 点击刷新"
        }
    }

    private fun bindHeroStats() {
        tvStatFriends.text = friendCount.toString()
        tvStatShares.text = activeShareCount.toString()
        tvStatPending.text = pendingCount.toString()
        updateSubtitle()
    }

    private fun refreshAll() {
        if (!Account.isLoggedIn(this)) {
            Ui.toast(this, "请先登录账号")
            finish()
            return
        }
        refreshFriends()
        refreshRequests()
        refreshShares()
    }

    private fun refreshFriends() {
        Friends.list(this) { ok, msg, list ->
            if (isFinishing || isDestroyed) return@list
            if (!ok) {
                Ui.toast(this, msg)
                return@list
            }
            renderFriends(list)
        }
    }

    private fun refreshRequests() {
        Friends.requests(this) { ok, msg, list ->
            if (isFinishing || isDestroyed) return@requests
            if (!ok) {
                Ui.toast(this, msg)
                return@requests
            }
            renderRequests(list)
        }
    }

    private fun refreshShares() {
        MemberShare.refresh(this, force = true) {
            if (isFinishing || isDestroyed) return@refresh
            renderShares()
        }
    }

    private fun renderFriends(list: List<Friends.Friend>) {
        val sorted = list.sortedWith(
            compareByDescending<Friends.Friend> { it.online }
                .thenByDescending { it.lastAt }
                .thenBy { it.nickname.lowercase(Locale.CHINA) }
        )
        friendCount = sorted.size
        llFriends.removeAllViews()
        tvFriendCount.text = if (sorted.isEmpty()) {
            "共 0 位"
        } else {
            "共 ${sorted.size} 位 · 在线 ${sorted.count { it.online }} 位"
        }
        tvFriendEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        for (f in sorted) {
            val ref = socialRow(
                initial = f.nickname.take(1).ifBlank { f.username.take(1) },
                title = f.nickname.ifBlank { f.username },
                sub = "@${f.username} · ${activityText(f)}",
                accent = accentFor(f.username),
                online = f.online
            )
            if (BiliUser.isVip(this)) {
                ref.actions.addView(primaryButton("分享") { showShareDialog(f) })
            }
            val menu = secondaryButton("⋯") { showFriendMenu(f, it) }
            menu.textSize = 14f
            ref.actions.addView(menu)
            llFriends.addView(ref.row)
        }
        bindHeroStats()
    }

    private fun renderRequests(list: List<Friends.Request>) {
        val incoming = list.count { it.incoming }
        pendingCount = incoming
        llRequests.removeAllViews()
        tvRequestCount.text = when {
            incoming > 0 -> "收到 $incoming 条申请"
            list.isNotEmpty() -> "暂无新申请"
            else -> "暂无申请"
        }
        tvRequestEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (r in list) {
            val name = r.nickname.ifBlank { r.username }
            val sub = if (r.incoming) {
                "@${r.username} 想加你为好友"
            } else {
                "等待 @${r.username} 处理"
            }
            val ref = socialRow(
                initial = name.take(1),
                title = name,
                sub = sub,
                accent = accentFor(r.username),
                online = false
            )
            if (r.incoming) {
                ref.actions.addView(primaryButton("接受") {
                    Friends.accept(this, r.id) { ok, msg ->
                        if (isFinishing || isDestroyed) return@accept
                        Ui.toast(this, msg)
                        if (ok) {
                            refreshFriends()
                            refreshRequests()
                        }
                    }
                })
                ref.actions.addView(secondaryButton("拒绝") {
                    Friends.reject(this, r.id) { ok, msg ->
                        if (isFinishing || isDestroyed) return@reject
                        Ui.toast(this, msg)
                        if (ok) refreshRequests()
                    }
                })
            } else {
                ref.actions.addView(statusBadge("等待", R.color.warn))
            }
            llRequests.addView(ref.row)
        }
        bindHeroStats()
    }

    private fun renderShares() {
        val (incoming, outgoing) = MemberShare.list(this)
        val now = System.currentTimeMillis()
        val activeIn = incoming.filter { it.active && it.expireAt > now }
        val activeOut = outgoing.filter { it.active && it.expireAt > now }
        activeShareCount = activeIn.size

        llIncoming.removeAllViews()
        tvIncomingCount.text = if (activeIn.isNotEmpty()) {
            "${activeIn.size} 个可用 · 共 ${incoming.size} 个"
        } else {
            "暂无可用分享"
        }
        tvIncomingEmpty.visibility = if (incoming.isEmpty()) View.VISIBLE else View.GONE
        for (s in incoming) {
            val state = if (s.active && s.expireAt > now) {
                "可用至 ${fmt(s.expireAt)}"
            } else {
                "已结束"
            }
            val sub = buildString {
                append("@").append(s.owner).append(" · ").append(state)
                if (s.biliName.isNotBlank()) append(" · ").append(s.biliName)
            }
            val ref = socialRow(
                initial = s.ownerNickname.ifBlank { s.owner }.take(1),
                title = s.ownerNickname.ifBlank { s.owner },
                sub = sub,
                accent = accentFor(s.owner),
                online = false
            )
            ref.actions.addView(
                if (s.active && s.expireAt > now) statusBadge("可用", R.color.online)
                else statusBadge("已结束", R.color.textSecondary)
            )
            llIncoming.addView(ref.row)
        }

        llOutgoing.removeAllViews()
        tvOutgoingCount.text = if (activeOut.isNotEmpty()) {
            "分享中 ${activeOut.size} 个 · 共 ${outgoing.size} 个"
        } else {
            "还没有分享"
        }
        tvOutgoingEmpty.visibility = if (outgoing.isEmpty()) View.VISIBLE else View.GONE
        for (s in outgoing) {
            val state = if (s.active && s.expireAt > now) "至 ${fmt(s.expireAt)}" else "已结束"
            val ref = socialRow(
                initial = s.targetNickname.ifBlank { s.target }.take(1),
                title = s.targetNickname.ifBlank { s.target },
                sub = "@${s.target} · $state" + if (s.lastError.isNotBlank()) " · ${s.lastError}" else "",
                accent = accentFor(s.target),
                online = false
            )
            if (s.active && s.expireAt > now) {
                ref.actions.addView(secondaryButton("撤回") { confirmRevoke(s) })
            } else {
                ref.actions.addView(statusBadge("已结束", R.color.textSecondary))
            }
            llOutgoing.addView(ref.row)
        }
        bindHeroStats()
    }

    private class RowRefs(val row: LinearLayout, val actions: LinearLayout)

    private fun socialRow(
        initial: String,
        title: String,
        sub: String,
        accent: Int,
        online: Boolean
    ): RowRefs {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@FriendsActivity, R.drawable.bg_social_row)
            setPadding(
                Ui.dp(this@FriendsActivity, 12), Ui.dp(this@FriendsActivity, 11),
                Ui.dp(this@FriendsActivity, 10), Ui.dp(this@FriendsActivity, 11)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(this@FriendsActivity, 7) }
        }

        val avatarBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@FriendsActivity, 42),
                Ui.dp(this@FriendsActivity, 42)
            )
        }
        val avatar = TextView(this).apply {
            text = initial.take(1).ifBlank { "友" }
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        avatarBox.addView(avatar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (online) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@FriendsActivity, R.color.online))
                }
            }
            avatarBox.addView(dot, FrameLayout.LayoutParams(
                Ui.dp(this@FriendsActivity, 9),
                Ui.dp(this@FriendsActivity, 9),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = Ui.dp(this@FriendsActivity, 1)
                marginEnd = Ui.dp(this@FriendsActivity, 1)
            })
        }
        outer.addView(avatarBox)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = Ui.dp(this@FriendsActivity, 10) }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@FriendsActivity, R.color.textMain))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subView = TextView(this).apply {
            text = sub
            textSize = 9f
            setTextColor(ContextCompat.getColor(this@FriendsActivity, R.color.textSecondary))
            setPadding(0, Ui.dp(this@FriendsActivity, 2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(titleView)
        texts.addView(subView)
        outer.addView(texts)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(actions)
        return RowRefs(outer, actions)
    }

    private fun primaryButton(text: String, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FriendsActivity, R.color.onPink))
            background = ContextCompat.getDrawable(this@FriendsActivity, R.drawable.bg_action_primary)
            setPadding(
                Ui.dp(this@FriendsActivity, 12), 0,
                Ui.dp(this@FriendsActivity, 12), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Ui.dp(this@FriendsActivity, 30)
            ).apply { marginStart = Ui.dp(this@FriendsActivity, 6) }
            setOnClickListener { click() }
        }
    }

    private fun secondaryButton(text: String, click: (View) -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FriendsActivity, R.color.pinkDeep))
            background = ContextCompat.getDrawable(this@FriendsActivity, R.drawable.bg_chip)
            setPadding(
                Ui.dp(this@FriendsActivity, 10), 0,
                Ui.dp(this@FriendsActivity, 10), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Ui.dp(this@FriendsActivity, 30)
            ).apply { marginStart = Ui.dp(this@FriendsActivity, 6) }
            setOnClickListener { click(it) }
        }
    }

    private fun statusBadge(text: String, colorRes: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FriendsActivity, colorRes))
            background = ContextCompat.getDrawable(this@FriendsActivity, R.drawable.bg_chip)
            setPadding(
                Ui.dp(this@FriendsActivity, 9), 0,
                Ui.dp(this@FriendsActivity, 9), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Ui.dp(this@FriendsActivity, 26)
            ).apply { marginStart = Ui.dp(this@FriendsActivity, 6) }
        }
    }

    private fun accentFor(key: String): Int {
        val colors = intArrayOf(
            Color.parseColor("#C2185B"),
            Color.parseColor("#D84315"),
            Color.parseColor("#AD1457"),
            Color.parseColor("#5E35B1"),
            Color.parseColor("#00695C"),
            Color.parseColor("#8D6E00")
        )
        var hash = 0
        for (c in key) hash = hash * 31 + c.code
        return colors[Math.floorMod(hash, colors.size)]
    }

    private fun activityText(f: Friends.Friend): String {
        if (f.online) return "在线"
        if (f.lastAt <= 0) return "最近活跃未知"
        val diff = (System.currentTimeMillis() - f.lastAt).coerceAtLeast(0)
        return when {
            diff < 60_000L -> "刚刚活跃"
            diff < 60 * 60_000L -> "${diff / 60_000L} 分钟前活跃"
            diff < 24 * 60 * 60_000L -> "${diff / (60 * 60_000L)} 小时前活跃"
            diff < 7 * 24 * 60 * 60_000L -> "${diff / (24 * 60 * 60_000L)} 天前活跃"
            else -> "很久未上线"
        }
    }

    private fun showFriendMenu(f: Friends.Friend, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("分享会员")
        popup.menu.add("复制好友账号")
        popup.menu.add("删除好友")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "分享会员" -> {
                    showShareDialog(f)
                    true
                }
                "复制好友账号" -> {
                    copyUsername(f.username)
                    true
                }
                "删除好友" -> {
                    confirmRemove(f)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyUsername(username: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BiliDiy 好友账号", "@$username"))
        Ui.toast(this, "已复制 @$username")
    }

    private fun showAddFriend() {
        val input = EditText(this).apply {
            hint = "对方登录账号，例如 user_01"
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(24))
            setSingleLine(true)
            setPadding(
                Ui.dp(this@FriendsActivity, 20), Ui.dp(this@FriendsActivity, 8),
                Ui.dp(this@FriendsActivity, 20), Ui.dp(this@FriendsActivity, 8)
            )
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("添加好友")
            .setMessage("输入对方的 App 登录账号，发送申请后由对方确认。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("查找", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.length < 3) {
                    input.error = "至少 3 个字符"
                    return@setOnClickListener
                }
                Friends.search(this, name) { ok, msg, result ->
                    if (isFinishing || isDestroyed) return@search
                    if (!ok || result == null) {
                        input.error = msg
                        return@search
                    }
                    if (result.friend) {
                        Ui.toast(this, "已经是好友")
                        dlg.dismiss()
                        return@search
                    }
                    if (result.requested) {
                        Ui.toast(this, "已经发送过申请")
                        dlg.dismiss()
                        return@search
                    }
                    dlg.dismiss()
                    confirmAdd(result)
                }
            }
        }
        dlg.show()
    }

    private fun confirmAdd(result: Friends.SearchResult) {
        AlertDialog.Builder(this)
            .setTitle("添加 ${result.nickname}")
            .setMessage("账号：@${result.username}")
            .setPositiveButton("发送申请") { _, _ ->
                Friends.add(this, result.username) { ok, msg ->
                    if (isFinishing || isDestroyed) return@add
                    Ui.toast(this, msg)
                    if (ok) refreshRequests()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showShareDialog(f: Friends.Friend) {
        if (!BiliUser.isVip(this)) {
            BiliUser.refreshVip(this, force = true) {
                if (isFinishing || isDestroyed) return@refreshVip
                if (BiliUser.isVip(this)) showShareDialog(f) else Ui.toast(this, "当前 B站账号不是有效大会员")
            }
            return
        }
        val input = EditText(this).apply {
            setText("30")
            hint = "分享天数，1-365"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setSingleLine(true)
            setPadding(
                Ui.dp(this@FriendsActivity, 20), Ui.dp(this@FriendsActivity, 8),
                Ui.dp(this@FriendsActivity, 20), Ui.dp(this@FriendsActivity, 8)
            )
        }
        AlertDialog.Builder(this)
            .setTitle("分享会员给 ${f.nickname}")
            .setMessage("对方只能播放会员专享内容，不会拿到你的 B站 Cookie，也不能用你的账号做其他操作。")
            .setView(input)
            .setPositiveButton("分享") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: 30
                MemberShare.create(this, f.username, days) { ok, msg, _ ->
                    if (isFinishing || isDestroyed) return@create
                    Ui.toast(this, msg)
                    if (ok) {
                        refreshShares()
                        refreshFriends()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRevoke(s: MemberShare.Share) {
        AlertDialog.Builder(this)
            .setTitle("撤回分享")
            .setMessage("撤回后 ${s.targetNickname.ifBlank { s.target }} 将不能再使用你的会员播放。")
            .setPositiveButton("撤回") { _, _ ->
                MemberShare.revoke(this, s.id) { ok, msg ->
                    if (isFinishing || isDestroyed) return@revoke
                    Ui.toast(this, msg)
                    if (ok) refreshShares()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemove(f: Friends.Friend) {
        AlertDialog.Builder(this)
            .setTitle("删除好友")
            .setMessage("删除后，双方相关的会员分享会一起停止。")
            .setPositiveButton("删除") { _, _ ->
                Friends.remove(this, f.username) { ok, msg ->
                    if (isFinishing || isDestroyed) return@remove
                    Ui.toast(this, msg)
                    if (ok) {
                        refreshFriends()
                        refreshShares()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
}
