package com.gdk.biliapp

import android.graphics.BitmapFactory
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简单设置页：检查更新 + 关于（作者 AM / 头像 / 版本）。
 * 云更新未开启时明确提示“云端暂未开启更新检查”，不假装成功。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var content: View
    private lateinit var btnCheck: TextView
    private lateinit var tvCheckResult: TextView
    private lateinit var tvNickname: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvAvatar: TextView
    private lateinit var ivUserAvatar: ImageView
    private lateinit var tvMember: TextView
    private lateinit var tvMemberExpire: TextView
    private lateinit var tvAccount: TextView
    private lateinit var tvAccountSub: TextView
    private lateinit var btnAccount: TextView
    private lateinit var cardBili: View
    private lateinit var ivBiliAvatar: ImageView
    private lateinit var tvBiliAccount: TextView
    private lateinit var tvBiliSub: TextView
    private lateinit var btnBiliAccount: TextView
    private lateinit var cardInvite: View
    private lateinit var tvInviteSub: TextView
    private lateinit var btnInvite: TextView
    private lateinit var cardFriends: View
    private lateinit var tvFriendsSub: TextView
    private lateinit var btnFriends: TextView
    private lateinit var cardInviteHistory: View
    private lateinit var tvInviteHistorySub: TextView
    private lateinit var btnInviteHistory: TextView
    private lateinit var tvBackgroundSub: TextView
    private lateinit var btnBackground: TextView

    private val updatePoll = Handler(Looper.getMainLooper())
    private val updatePollTask = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            UpdateDownload.sync(this@SettingsActivity)
            if (UpdateBus.state?.running == true) updatePoll.postDelayed(this, 1000)
        }
    }

    private val avatarUrl = "http://q2.qlogo.cn/headimg_dl?dst_uin=1958832195&spec=5"

    private var biliSyncing = false

    private val biliLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            bindBili()
            syncBili()
        }
    }

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
        Member.init(this)
        Account.init(this)
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_settings)

        pageRoot = findViewById(R.id.pageRoot)
        content = findViewById(R.id.content)
        btnCheck = findViewById(R.id.btnCheck)
        tvCheckResult = findViewById(R.id.tvCheckResult)
        tvNickname = findViewById(R.id.tvNickname)
        tvDevice = findViewById(R.id.tvDevice)
        tvAvatar = findViewById(R.id.tvAvatar)
        ivUserAvatar = findViewById(R.id.ivUserAvatar)
        tvMember = findViewById(R.id.tvMember)
        tvMemberExpire = findViewById(R.id.tvMemberExpire)
        tvAccount = findViewById(R.id.tvAccount)
        tvAccountSub = findViewById(R.id.tvAccountSub)
        btnAccount = findViewById(R.id.btnAccount)
        cardBili = findViewById(R.id.cardBili)
        ivBiliAvatar = findViewById(R.id.ivBiliAvatar)
        tvBiliAccount = findViewById(R.id.tvBiliAccount)
        tvBiliSub = findViewById(R.id.tvBiliSub)
        btnBiliAccount = findViewById(R.id.btnBiliAccount)
        cardFriends = findViewById(R.id.cardFriends)
        tvFriendsSub = findViewById(R.id.tvFriendsSub)
        btnFriends = findViewById(R.id.btnFriends)
        cardInvite = findViewById(R.id.cardInvite)
        tvInviteSub = findViewById(R.id.tvInviteSub)
        btnInvite = findViewById(R.id.btnInvite)
        cardInviteHistory = findViewById(R.id.cardInviteHistory)
        tvInviteHistorySub = findViewById(R.id.tvInviteHistorySub)
        btnInviteHistory = findViewById(R.id.btnInviteHistory)
        tvBackgroundSub = findViewById(R.id.tvBackgroundSub)
        btnBackground = findViewById(R.id.btnBackground)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnEditName).setOnClickListener { editNickname() }
        tvNickname.setOnClickListener { editNickname() }
        findViewById<View>(R.id.avatarBox).setOnClickListener { pickAvatar.launch("image/*") }
        findViewById<View>(R.id.cardProfile).setOnClickListener {
            startActivity(Intent(this, UserActivity::class.java))
        }
        findViewById<View>(R.id.cardAccount).setOnClickListener { accountAction() }
        cardBili.setOnClickListener { biliAccountAction() }
        btnBiliAccount.setOnClickListener {
            if (BiliUser.isBound(this)) syncBili() else openBiliLogin()
        }
        cardFriends.setOnClickListener { openFriends() }
        btnFriends.setOnClickListener { openFriends() }
        findViewById<View>(R.id.cardInvite).setOnClickListener { makeInvite() }
        findViewById<View>(R.id.cardInviteHistory).setOnClickListener { showInviteHistory() }
        btnBackground.setOnClickListener {
            BackgroundGuard.showGuide(this)
        }

        val version = versionLabel()
        findViewById<TextView>(R.id.tvVer).text = "当前版本 $version"
        findViewById<TextView>(R.id.tvAboutVer).text = "v$version"
        ImageLoader.load(findViewById<ImageView>(R.id.ivAvatar), avatarUrl)
        bindProfile()
        bindMember()
        bindAccount()
        bindBili()
        bindFriends()
        bindInvite()
        bindBackground()

        btnCheck.setOnClickListener { checkUpdate() }
        findViewById<TextView>(R.id.btnActivate).setOnClickListener {
            Member.showActivation(this) { bindMember() }
        }

        applyInsets()
        scrollToRequestedEntry()
        Cloud.gate(this)
    }

    /** 从首页「功能」面板进入时，直接定位到对应设置分区 */
    private fun scrollToRequestedEntry() {
        val targetId = when (intent.getStringExtra("scroll_to")) {
            "member" -> R.id.cardMember
            "bili" -> R.id.cardBili
            "account" -> R.id.cardAccount
            "about" -> R.id.cardAbout
            else -> return
        }
        val target = findViewById<View>(targetId) ?: return
        val scroll = findViewById<ScrollView>(R.id.settingsScroll)
        scroll.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            scroll.smoothScrollTo(0, maxOf(0, target.top - Ui.dp(this, 10)))
        }, 120)
    }

    override fun onResume() {
        super.onResume()
        bindProfile()
        bindMember()
        bindAccount()
        bindBili()
        bindFriends()
        bindInvite()
        bindBackground()
        Cloud.gate(this)
        AppUpdater.onResume(this)
        Member.refresh(this) { bindMember() }
        BiliUser.refreshVip(this) {
            if (isFinishing || isDestroyed) return@refreshVip
            bindBili()
            bindMember()
        }
        MemberShare.refresh(this) { bindMember() }
    }

    override fun onStart() {
        super.onStart()
        UpdateDownload.sync(this)
        UpdateBus.register(updateStateListener)
        if (UpdateBus.state?.running == true) {
            updatePoll.removeCallbacks(updatePollTask)
            updatePoll.postDelayed(updatePollTask, 1000)
        }
    }

    override fun onStop() {
        updatePoll.removeCallbacks(updatePollTask)
        UpdateBus.unregister(updateStateListener)
        super.onStop()
    }

    private val updateStateListener: (UpdateBus.State) -> Unit = { s ->
        if (::btnCheck.isInitialized && !isFinishing && !isDestroyed) renderUpdateState(s)
    }

    @SuppressLint("SetTextI18n")
    private fun renderUpdateState(s: UpdateBus.State) {
        if (s.running) {
            btnCheck.isEnabled = false
            btnCheck.text = if (s.progress > 0) "下载中 ${s.progress}%" else "下载中…"
            tvCheckResult.visibility = View.VISIBLE
            tvCheckResult.setTextColor(getColor(R.color.textSecondary))
            tvCheckResult.text = if (s.progress > 0) {
                getString(R.string.update_running_progress, s.progress)
            } else {
                getString(R.string.update_running)
            }
            return
        }

        btnCheck.isEnabled = true
        btnCheck.text = "检查更新"
        tvCheckResult.visibility = View.VISIBLE
        if (s.error.isNotBlank()) {
            tvCheckResult.setTextColor(getColor(R.color.textSecondary))
            tvCheckResult.text = getString(R.string.update_failed, s.error)
        } else if (s.progress >= 100) {
            tvCheckResult.setTextColor(getColor(R.color.pink))
            tvCheckResult.text = getString(R.string.update_done)
        }
    }

    private fun bindInvite() {
        if (!Account.isLoggedIn(this) || !Account.canInvite(this)) {
            cardInvite.visibility = View.GONE
            cardInviteHistory.visibility = View.GONE
            return
        }
        cardInvite.visibility = View.VISIBLE
        cardInviteHistory.visibility = View.VISIBLE
        val q = Account.inviteQuota(this)
        tvInviteSub.text = if (q > 0) {
            "剩余 $q 个邀请名额，被邀请人无邀请资格"
        } else {
            "5 个邀请名额已用完"
        }
        tvInviteHistorySub.text = "查看已生成邀请码的使用情况"
        btnInvite.isEnabled = q > 0
        btnInvite.text = if (q > 0) "生成邀请码" else "已用完"
        btnInvite.alpha = if (q > 0) 1f else 0.5f
    }

    private fun makeInvite() {
        if (Account.inviteQuota(this) <= 0) {
            Ui.toast(this, "邀请名额已用完")
            return
        }
        val input = EditText(this)
        input.setText("INV")
        input.hint = "前缀，例如 INV / AM / FRIEND"
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        input.setSingleLine(true)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("生成邀请码")
            .setMessage("可自定义邀请码前缀（字母数字，最多 8 位）")
            .setView(input)
            .setPositiveButton("生成") { _, _ -> doGenerateInvite(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doGenerateInvite(prefix: String) {
        btnInvite.isEnabled = false
        btnInvite.text = "生成中…"
        Account.generateInvite(this, prefix) { ok, msg, result ->
            bindInvite()
            if (isFinishing || isDestroyed) return@generateInvite
            if (!ok || result == null) {
                Ui.toast(this, msg)
                return@generateInvite
            }
            AlertDialog.Builder(this)
                .setTitle("邀请码")
                .setMessage(
                    "${result.code}\n\n" +
                        "有效期 30 天，仅可使用 1 次；对方注册后无邀请资格。\n" +
                        "剩余名额：${result.quota}"
                )
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("BiliDiy 邀请码", result.code))
                    Ui.toast(this, "已复制")
                }
                .show()
        }
    }

    private fun showInviteHistory() {
        btnInviteHistory.isEnabled = false
        btnInviteHistory.text = "加载中…"
        Account.listInvites(this) { ok, msg, list ->
            bindInvite()
            btnInviteHistory.isEnabled = true
            btnInviteHistory.text = "查看"
            if (isFinishing || isDestroyed) return@listInvites
            if (!ok || list == null) {
                Ui.toast(this, msg)
                return@listInvites
            }
            val sb = StringBuilder()
            if (list.isEmpty()) {
                sb.append("还没有生成过邀请码。")
            } else {
                for ((i, item) in list.withIndex()) {
                    if (i > 0) sb.append("\n\n")
                    val state = when {
                        !item.enabled -> "已停用"
                        item.expireAt <= System.currentTimeMillis() -> "已过期"
                        item.uses >= item.maxUses -> "已使用"
                        else -> "可用"
                    }
                    sb.append(item.code).append(" · ").append(state)
                    sb.append("\n使用 ").append(item.uses).append(" / ").append(item.maxUses)
                    sb.append(" · 有效期至 ").append(fmtDate(item.expireAt))
                    if (item.usedUsers.isNotEmpty()) {
                        sb.append("\n被邀请：").append(item.usedUsers.joinToString("、"))
                    }
                }
            }
            AlertDialog.Builder(this)
                .setTitle("邀请记录")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun bindBackground() {
        tvBackgroundSub.text = BackgroundGuard.statusText(this)
        btnBackground.text = if (BackgroundGuard.isIgnoringBatteryOptimizations(this)) "查看" else "设置"
    }

    private fun bindAccount() {
        val logged = Account.isLoggedIn(this)
        if (logged) {
            tvAccount.text = "账号 · " + Account.username(this)
            tvAccountSub.text = "已登录 · 有效期至 " + fmtDate(Account.expireAt(this))
            btnAccount.text = "退出登录"
        } else {
            tvAccount.text = "未登录"
            tvAccountSub.text = "登录后使用播放与云端记录"
            btnAccount.text = "登录"
        }
    }

    private fun accountAction() {
        if (!Account.isLoggedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java).putExtra("gate", true))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("账户")
            .setMessage(
                "账号：${Account.username(this)}\n" +
                    "昵称：${Profile.nickname(this)}\n" +
                    "有效期至：${fmtDate(Account.expireAt(this))}\n" +
                    if (Account.canInvite(this)) {
                        "邀请名额：${Account.inviteQuota(this)} / 5"
                    } else {
                        "邀请资格：无（由其他用户邀请注册）"
                    }
            )
            .setPositiveButton("退出登录") { _, _ ->
                Account.logout(this)
                startActivity(Intent(this, AuthActivity::class.java).putExtra("gate", true))
                finishAffinity()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindFriends() {
        if (!Account.isLoggedIn(this)) {
            cardFriends.visibility = View.GONE
            return
        }
        cardFriends.visibility = View.VISIBLE
        val (incoming, outgoing) = MemberShare.list(this)
        val activeOut = outgoing.count { it.active && it.expireAt > System.currentTimeMillis() }
        val activeIn = incoming.count { it.active && it.expireAt > System.currentTimeMillis() }
        tvFriendsSub.text = "好友 — 位 · 可用分享 $activeIn 个 · 分享中 $activeOut 个"
        Friends.list(this) { ok, _, list ->
            if (isFinishing || isDestroyed) return@list
            if (ok) {
                Friends.requests(this) { _, _, requests ->
                    if (isFinishing || isDestroyed) return@requests
                    val pending = requests.count { it.incoming }
                    val (incNow, outNow) = MemberShare.list(this)
                    val inCount = incNow.count { it.active && it.expireAt > System.currentTimeMillis() }
                    val outCount = outNow.count { it.active && it.expireAt > System.currentTimeMillis() }
                    tvFriendsSub.text = buildString {
                        append("好友 ").append(list.size).append(" 位")
                        append(" · 可用分享 ").append(inCount).append(" 个")
                        append(" · 分享中 ").append(outCount).append(" 个")
                        if (pending > 0) append(" · 待处理 ").append(pending).append(" 条")
                    }
                }
            }
        }
    }

    private fun openFriends() {
        if (!Account.isLoggedIn(this)) {
            Ui.toast(this, "请先登录账号")
            return
        }
        startActivity(Intent(this, FriendsActivity::class.java))
    }

    private fun bindBili() {        val i = BiliUser.info(this)
        if (i == null) {
            ivBiliAvatar.visibility = View.GONE
            tvBiliAccount.text = "B站账号"
            tvBiliSub.text = "绑定后同步历史与追番"
            btnBiliAccount.text = "绑定"
            btnBiliAccount.isEnabled = true
            return
        }
        ivBiliAvatar.visibility = View.VISIBLE
        if (i.face.isNotBlank()) ImageLoader.load(ivBiliAvatar, i.face)
        tvBiliAccount.text = "B站 · ${i.name}"
        val hc = BiliHistory.count(this)
        val bc = BiliUser.bangumiCount(this)
        val last = maxOf(BiliHistory.lastAt(this), BiliUser.lastBangumiAt(this))
        tvBiliSub.text = buildString {
            append("UID ").append(i.mid).append(" · ")
            append(if (BiliUser.isVip(this@SettingsActivity)) "大会员" else "普通用户")
            if (BiliUser.isVip(this@SettingsActivity) && i.vipExpire > 0) {
                append(" · 至 ").append(fmtDate(i.vipExpire))
            }
            if (hc > 0 || bc > 0) {
                append(" · B站历史 ").append(hc).append(" 条（独立） · 追番 ").append(bc).append(" 部")
            }
            if (last > 0) append("\n上次同步 ").append(fmtTime(last))
        }
        btnBiliAccount.text = if (biliSyncing) "同步中…" else "同步"
        btnBiliAccount.isEnabled = !biliSyncing
    }

    private fun openBiliLogin() {
        biliLogin.launch(Intent(this, BiliLoginActivity::class.java))
    }

    private fun biliAccountAction() {
        if (!BiliUser.isBound(this)) {
            openBiliLogin()
            return
        }
        val items = arrayOf("同步历史与追番", "查看我的追番", "解除绑定")
        AlertDialog.Builder(this)
            .setTitle("B站账号")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> syncBili()
                    1 -> showBangumi()
                    2 -> confirmClearBili()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun syncBili() {
        if (biliSyncing) return
        if (!BiliUser.isBound(this)) {
            openBiliLogin()
            return
        }
        biliSyncing = true
        btnBiliAccount.isEnabled = false
        btnBiliAccount.text = "同步中…"
        BiliSync.syncAll(this) { r ->
            biliSyncing = false
            if (isFinishing || isDestroyed) return@syncAll
            bindBili()
            AlertDialog.Builder(this)
                .setTitle("B站同步完成")
                .setMessage(r.summary())
                .setPositiveButton("知道了", null)
                .setNeutralButton("查看记录") { _, _ ->
                    startActivity(Intent(this, UserActivity::class.java))
                }
                .show()
        }
    }

    private fun showBangumi() {
        val list = BiliUser.bangumi(this)
        if (list.isEmpty()) {
            Ui.toast(this, "还没有同步追番，先点「同步」")
            return
        }
        val labels = list.map {
            buildString {
                append(it.title)
                if (it.progress.isNotBlank()) append("\n").append(it.progress)
                if (it.typeName.isNotBlank()) append(" · ").append(it.typeName)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("我的追番（${list.size}）")
            .setItems(labels) { _, which ->
                val b = list.getOrNull(which) ?: return@setItems
                startActivity(Intent(this, PgcDetailActivity::class.java)
                    .putExtra("season_id", b.seasonId))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmClearBili() {
        AlertDialog.Builder(this)
            .setTitle("解除 B站账号绑定")
            .setMessage("解绑后会删除本机保存的 Cookie、追番和独立 B站历史缓存；本机观看历史与 B站服务器记录不受影响。")
            .setPositiveButton("解除绑定") { _, _ ->
                BiliUser.clear(this)
                bindBili()
                Ui.toast(this, "已解除 B站账号绑定")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))

    private fun fmtDate(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))

    private fun bindMember() {
        val e = Member.entitlement(this)
        val btn = findViewById<TextView>(R.id.btnActivate)
        tvMember.text = when (e.kind) {
            "bili" -> "B站大会员"
            "share" -> "好友分享"
            else -> "会员"
        }
        tvMemberExpire.text = Member.statusText(this)
        when (e.kind) {
            "bili" -> {
                btn.text = "B站大会员已解锁"
                btn.isEnabled = false
                btn.alpha = 0.6f
            }
            "share" -> {
                btn.text = "好友分享中"
                btn.isEnabled = false
                btn.alpha = 0.6f
            }
            "app" -> {
                btn.text = "续期"
                btn.isEnabled = true
                btn.alpha = 1f
            }
            else -> {
                btn.text = "输入激活码"
                btn.isEnabled = true
                btn.alpha = 1f
            }
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
            content.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), Ui.dp(this, 24) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }

    private fun bindProfile() {
        val name = Profile.nickname(this)
        tvNickname.text = name
        tvAvatar.text = name.take(1).ifBlank { "用" }
        val bmp = if (Profile.hasAvatar(this)) BitmapFactory.decodeFile(Profile.avatarFile(this).absolutePath) else null
        if (bmp != null) {
            ivUserAvatar.setImageBitmap(bmp)
            ivUserAvatar.visibility = View.VISIBLE
            tvAvatar.visibility = View.GONE
        } else {
            ivUserAvatar.visibility = View.GONE
            tvAvatar.visibility = View.VISIBLE
        }
        val dev = Profile.deviceId(this)
        tvDevice.text = "设备 ID · $dev"
        Profile.sync(this) { ok ->
            if (isFinishing || isDestroyed) return@sync
            tvDevice.text = "设备 ID · $dev · " + if (ok) "已同步到云端" else "本地保存"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun editNickname() {
        val input = EditText(this).apply {
            setText(Profile.nickname(this@SettingsActivity))
            setSelection(text.length)
            hint = "最多 16 个字"
            maxLines = 1
            setPadding(Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 8),
                Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 8))
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(this@SettingsActivity, 20), 0, Ui.dp(this@SettingsActivity, 20), 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
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

    @SuppressLint("SetTextI18n")
    private fun checkUpdate() {
        if (UpdateBus.state?.running == true) {
            Ui.toast(this, "更新包正在后台下载，请稍候")
            return
        }
        btnCheck.isEnabled = false
        btnCheck.text = "检查中…"
        tvCheckResult.visibility = View.VISIBLE
        tvCheckResult.text = getString(R.string.update_checking)
        tvCheckResult.setTextColor(getColor(R.color.textSecondary))

        Cloud.checkUpdate(this) { msg ->
            if (isFinishing || isDestroyed) return@checkUpdate
            btnCheck.isEnabled = true
            btnCheck.text = "检查更新"
            tvCheckResult.text = msg
            tvCheckResult.setTextColor(
                getColor(if (msg.startsWith("发现")) R.color.pink else R.color.textSecondary)
            )
        }
    }

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$name（$code）"
    } catch (_: Exception) {
        "?"
    }
}
