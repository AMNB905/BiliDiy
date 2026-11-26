package com.gdk.biliapp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 好友会员分享。
 *
 * 分享方只把「播放专用 Cookie」交给服务端加密保存；接收方拿到的始终是
 * share_playurl 返回的视频流地址，不会拿到对方的 SESSDATA / bili_jct。
 */
object MemberShare {

    private const val PREF = "bili_member_share"
    private const val K_IN = "incoming"
    private const val K_OUT = "outgoing"
    private const val K_AT = "last_sync"
    private const val K_USER = "cache_user"

    data class Share(
        val id: String,
        val owner: String,
        val ownerNickname: String,
        val target: String,
        val targetNickname: String,
        val biliName: String,
        val days: Int,
        val expireAt: Long,
        val active: Boolean,
        val lastError: String = ""
    )

    data class Grant(
        val id: String,
        val owner: String,
        val ownerNickname: String,
        val biliName: String,
        val expireAt: Long
    )

    @Volatile private var incoming: List<Share> = emptyList()
    @Volatile private var outgoing: List<Share> = emptyList()
    @Volatile private var lastSync = 0L
    @Volatile private var cachedUser = ""
    @Volatile private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val p = prefs(ctx)
            incoming = parse(p.getString(K_IN, null), false)
            outgoing = parse(p.getString(K_OUT, null), true)
            lastSync = p.getLong(K_AT, 0L)
            cachedUser = p.getString(K_USER, "") ?: ""
            if (cachedUser != Account.username(ctx)) {
                // 切换/退出 App 账号后，旧账号的好友分享缓存不能继续展示或使用。
                incoming = emptyList()
                outgoing = emptyList()
                lastSync = 0L
                cachedUser = Account.username(ctx)
                save(ctx)
            }
            initialized = true
        }
    }

    /** 在登录/退出账号时清空本地缓存，防止串号 */
    fun resetLocal(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            incoming = emptyList()
            outgoing = emptyList()
            lastSync = 0L
            cachedUser = Account.username(app)
            save(app)
        }
    }

    fun list(ctx: Context): Pair<List<Share>, List<Share>> {
        init(ctx)
        ensureAccount(ctx)
        return incoming to outgoing
    }

    fun active(ctx: Context): Grant? {
        init(ctx)
        ensureAccount(ctx)
        if (!Account.isLoggedIn(ctx)) return null
        val now = System.currentTimeMillis()
        val s = incoming.firstOrNull { it.active && it.expireAt > now } ?: return null
        return Grant(s.id, s.owner, s.ownerNickname, s.biliName, s.expireAt)
    }

    fun hasActive(ctx: Context): Boolean = active(ctx) != null

    fun refresh(ctx: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        init(ctx)
        val app = ctx.applicationContext
        if (!Account.isLoggedIn(app)) {
            incoming = emptyList()
            outgoing = emptyList()
            lastSync = 0L
            cachedUser = ""
            save(app)
            done?.invoke(true)
            return
        }
        val me = Account.username(app)
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 5 * 60_000L) {
            done?.invoke(true)
            return
        }
        Account.api(app, "share_list", emptyMap()) { ok, _, json ->
            // 请求期间切换了 App 账号，丢弃旧账号的返回
            if (Account.username(app) != me) {
                done?.invoke(false)
                return@api
            }
            if (ok && json != null) {
                incoming = parse(json.optJSONArray("incoming"), false)
                outgoing = parse(json.optJSONArray("outgoing"), true)
                lastSync = System.currentTimeMillis()
                cachedUser = me
                save(app)
            }
            done?.invoke(ok)
        }
    }

    fun create(ctx: Context, target: String, days: Int,
               done: (Boolean, String, Share?) -> Unit) {
        val app = ctx.applicationContext
        if (!BiliUser.isBound(app)) {
            done(false, "请先绑定 B站账号", null)
            return
        }
        if (!BiliUser.isVip(app)) {
            done(false, "需要有效的 B站大会员才能分享", null)
            return
        }
        val cookie = BiliUser.playbackCookie(app)
        if (cookie.isBlank() || !cookie.contains("SESSDATA=")) {
            done(false, "没有拿到可用的播放 Cookie，请重新绑定 B站账号", null)
            return
        }
        val d = days.coerceIn(1, 365)
        Account.api(app, "share_create", mapOf(
            "target" to target,
            "days" to d.toString(),
            "cookie" to cookie
        )) { ok, msg, json ->
            if (!ok || json == null) {
                done(false, msg, null)
                return@api
            }
            val s = parseShare(json.optJSONObject("share"), true)
            refresh(app, force = true)
            done(true, msg.ifBlank { "会员已分享" }, s)
        }
    }

    fun revoke(ctx: Context, id: String, done: (Boolean, String) -> Unit) {
        Account.api(ctx, "share_revoke", mapOf("id" to id)) { ok, msg, _ ->
            if (ok) refresh(ctx, force = true)
            done(ok, msg.ifBlank { if (ok) "已撤回分享" else "撤回失败" })
        }
    }

    fun revokeAll(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (Account.token(ctx).isBlank()) {
            outgoing = emptyList()
            save(ctx)
            done?.invoke(true)
            return
        }
        Account.api(ctx, "share_revoke_all", emptyMap()) { ok, _, _ ->
            if (ok) {
                outgoing = emptyList()
                save(ctx)
            }
            done?.invoke(ok)
        }
    }

    /** 重新绑定 B站账号后，把最新 Cookie 同步到已分享的记录 */
    fun refreshCookie(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!BiliUser.isBound(app) || !BiliUser.isVip(app)) {
            done?.invoke(false)
            return
        }
        val cookie = BiliUser.playbackCookie(app)
        if (cookie.isBlank() || !cookie.contains("SESSDATA=")) {
            done?.invoke(false)
            return
        }
        Account.api(app, "share_refresh", mapOf("cookie" to cookie)) { ok, _, _ ->
            done?.invoke(ok)
        }
    }

    /** 在后台线程同步调用；返回 share_playurl 的 result */
    fun playurl(ctx: Context, shareId: String, epId: Long, qn: Int): JSONObject {
        if (shareId.isBlank() || epId <= 0) throw IllegalStateException("共享播放参数不完整")
        val json = Account.apiSync(ctx, "share_playurl", mapOf(
            "share_id" to shareId,
            "ep_id" to epId.toString(),
            "qn" to qn.toString()
        ))
        return json.optJSONObject("result")
            ?: throw IllegalStateException("好友分享没有返回播放数据")
    }

    fun remainText(expireAt: Long): String {
        if (expireAt <= 0) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(expireAt))
    }

    private fun parse(raw: String?, outgoing: Boolean): List<Share> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Share>(arr.length())
        for (i in 0 until arr.length()) {
            val s = parseShare(arr.optJSONObject(i), outgoing) ?: continue
            out.add(s)
        }
        return out
    }

    private fun parse(arr: JSONArray?, outgoing: Boolean): List<Share> {
        if (arr == null) return emptyList()
        val out = ArrayList<Share>(arr.length())
        for (i in 0 until arr.length()) {
            val s = parseShare(arr.optJSONObject(i), outgoing) ?: continue
            out.add(s)
        }
        return out
    }

    private fun parseShare(o: JSONObject?, outgoing: Boolean): Share? {
        if (o == null) return null
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val expire = o.optLong("expire_at", 0L) * 1000L
        return Share(
            id = id,
            owner = o.optString("owner", ""),
            ownerNickname = o.optString("owner_nickname", ""),
            target = o.optString("target", ""),
            targetNickname = o.optString("target_nickname", ""),
            biliName = o.optString("bili_name", ""),
            days = o.optInt("days", 0),
            expireAt = expire,
            active = o.optBoolean("active", false) && expire > System.currentTimeMillis(),
            lastError = o.optString("last_error", "")
        )
    }

    private fun ensureAccount(ctx: Context) {
        val me = Account.username(ctx)
        if (cachedUser != me) {
            cachedUser = me
            incoming = emptyList()
            outgoing = emptyList()
            lastSync = 0L
            save(ctx)
        }
    }

    private fun save(ctx: Context) {
        val p = prefs(ctx)
        p.edit()
            .putString(K_IN, toJson(incoming))
            .putString(K_OUT, toJson(outgoing))
            .putLong(K_AT, lastSync)
            .putString(K_USER, cachedUser)
            .apply()
    }

    private fun toJson(list: List<Share>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("owner", s.owner)
                put("owner_nickname", s.ownerNickname)
                put("target", s.target)
                put("target_nickname", s.targetNickname)
                put("bili_name", s.biliName)
                put("days", s.days)
                put("expire_at", s.expireAt / 1000L)
                put("active", s.active)
                put("last_error", s.lastError)
            })
        }
        return arr.toString()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
