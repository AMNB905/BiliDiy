package com.gdk.biliapp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自己绑定的 B 站网页账号。
 *
 * Cookie 只用于以用户本人身份请求 B 站接口，和云端下发的“播放用 Cookie”分开保存：
 * - 绑定后 [Bili.cookie] 优先返回这里的 Cookie；
 * - 解绑后自动回落到云端播放 Cookie。
 *
 * Cookie 与账号信息使用 Android Keystore 加密后写入本机，不上传服务器，也不写入日志。
 */
object BiliUser {

    data class Info(
        val mid: Long,
        val name: String,
        val face: String,
        val cookie: String,
        val vip: String,
        val savedAt: Long,
        val vipExpire: Long = 0L,
        val vipActive: Boolean = false
    )

    data class Bangumi(
        val seasonId: Long,
        val title: String,
        val cover: String,
        val progress: String,
        val url: String,
        val typeName: String
    )

    private const val PREF = "bili_web_account"
    private const val SESSION = "session"
    private const val BANGUMI = "bangumi"
    private const val HISTORY_COUNT = "history_count"
    private const val BANGUMI_COUNT = "bangumi_count"
    private const val HISTORY_AT = "history_at"
    private const val BANGUMI_AT = "bangumi_at"

    @Volatile private var initialized = false
    @Volatile private var cached: Info? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val i = load(ctx)
            cached = i
            if (i != null) Bili.setUserCookie(i.cookie)
            initialized = true
        }
    }

    fun info(ctx: Context): Info? {
        init(ctx)
        return cached
    }

    fun isBound(ctx: Context): Boolean = info(ctx) != null

    /** 当前绑定账号是否是有效 B站大会员（到期时间未知时先按已保存状态） */
    fun isVip(ctx: Context): Boolean {
        val i = info(ctx) ?: return false
        return i.vipActive && (i.vipExpire <= 0L || i.vipExpire > System.currentTimeMillis())
    }

    fun vipExpire(ctx: Context): Long = info(ctx)?.vipExpire ?: 0L

    /** 重新调用 nav 校验大会员状态；6 小时内默认不重复请求 */
    fun refreshVip(ctx: Context, force: Boolean = false, done: ((Info?) -> Unit)? = null) {
        val app = ctx.applicationContext
        val current = info(app)
        if (current == null) {
            done?.invoke(null)
            return
        }
        val last = prefs(app).getLong("vip_at", 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) {
            done?.invoke(current)
            return
        }
        Bili.io {
            val updated = try {
                val data = Bili.nav().optJSONObject("data")
                    ?: throw IllegalStateException("B站未返回账号信息")
                if (!data.optBoolean("isLogin", false)) throw IllegalStateException("B站登录已失效")
                val due = data.optLong("vipDueDate", 0L)
                val active = data.optInt("vipStatus", 0) == 1 && due > System.currentTimeMillis()
                val i = current.copy(
                    vip = if (active) "大会员" else "普通用户",
                    vipExpire = due,
                    vipActive = active
                )
                saveSession(app, i)
                cached = i
                i
            } catch (e: Exception) {
                if (e is ApiException && (e.code == -101 || e.code == -111)) {
                    val i = current.copy(vip = "Cookie已失效", vipActive = false)
                    saveSession(app, i)
                    cached = i
                    i
                } else {
                    current
                }
            }
            prefs(app).edit().putLong("vip_at", System.currentTimeMillis()).apply()
            Bili.main { done?.invoke(updated) }
        }
    }

    /** 分享给好友时只上传播放需要的 Cookie，不上传 bili_jct 等可写字段 */
    fun playbackCookie(ctx: Context): String {
        val raw = info(ctx)?.cookie ?: return ""
        val allowed = setOf(
            "SESSDATA", "buvid3", "buvid4", "b_nut", "bili_ticket",
            "buvid_fp", "buvid_fp_plain", "_uuid"
        )
        val pairs = LinkedHashMap<String, String>()
        for (part in normalize(raw).split(";")) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key in allowed && value.isNotEmpty()) pairs[key] = value
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 用网页登录拿到的 Cookie 校验并保存；回调在主线程 */
    fun bind(ctx: Context, rawCookie: String, cb: (Boolean, String, Info?) -> Unit) {
        val app = ctx.applicationContext
        val previous = info(app)
        Bili.io {
            try {
                val cookie = normalize(rawCookie)
                if (!cookie.contains("SESSDATA=")) {
                    throw IllegalStateException("没有拿到 SESSDATA，请重新登录")
                }
                Bili.setUserCookie(cookie)
                val nav = Bili.nav()
                val data = nav.optJSONObject("data")
                    ?: throw IllegalStateException("B 站返回数据为空")
                if (!data.optBoolean("isLogin", false)) {
                    throw IllegalStateException("登录状态未生效，请重新登录")
                }
                val mid = data.optLong("mid", 0L)
                if (mid <= 0) throw IllegalStateException("没有读取到 UID")
                val name = data.optString("uname", "").ifBlank { "B站用户$mid" }
                val face = httpsify(data.optString("face", ""))
                val vipDue = data.optLong("vipDueDate", 0L)
                val vipActive = data.optInt("vipStatus", 0) == 1 && vipDue > System.currentTimeMillis()
                val vip = if (vipActive) "大会员" else "普通用户"
                val i = Info(
                    mid, name, face, cookie, vip, System.currentTimeMillis(),
                    vipDue, vipActive
                )
                saveSession(app, i)
                BiliHistory.bindAccount(app, mid)
                cached = i
                prefs(app).edit().putLong("vip_at", System.currentTimeMillis()).apply()
                if (Account.isLoggedIn(app)) MemberShare.refreshCookie(app)
                Bili.main { cb(true, "已绑定 B 站账号：$name", i) }
            } catch (e: Exception) {
                if (previous != null) Bili.setUserCookie(previous.cookie) else Bili.clearUserCookie()
                val msg = when {
                    e.message?.contains("SESSDATA") == true -> "没有拿到登录 Cookie，请重新登录"
                    else -> e.message ?: "绑定失败，请检查网络后重试"
                }
                Bili.main { cb(false, msg, null) }
            }
        }
    }

    fun clear(ctx: Context) {
        val p = prefs(ctx)
        SecretPrefs.remove(p, SESSION)
        SecretPrefs.remove(p, BANGUMI)
        p.edit()
            .remove(HISTORY_COUNT)
            .remove(BANGUMI_COUNT)
            .remove(HISTORY_AT)
            .remove(BANGUMI_AT)
            .remove("vip_at")
            .apply()
        cached = null
        Bili.clearUserCookie()
        BiliHistory.clear(ctx)
        MemberShare.revokeAll(ctx)
    }

    fun lastHistoryAt(ctx: Context): Long = prefs(ctx).getLong(HISTORY_AT, 0L)
    fun lastBangumiAt(ctx: Context): Long = prefs(ctx).getLong(BANGUMI_AT, 0L)
    fun historyCount(ctx: Context): Int = prefs(ctx).getInt(HISTORY_COUNT, 0)
    fun bangumiCount(ctx: Context): Int = prefs(ctx).getInt(BANGUMI_COUNT, 0)

    fun markHistory(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt(HISTORY_COUNT, count)
            .putLong(HISTORY_AT, System.currentTimeMillis()).apply()
    }

    fun markBangumi(ctx: Context, items: List<Bangumi>) {
        prefs(ctx).edit().putInt(BANGUMI_COUNT, items.size)
            .putLong(BANGUMI_AT, System.currentTimeMillis()).apply()
        val arr = JSONArray()
        for (b in items) {
            arr.put(JSONObject().apply {
                put("season", b.seasonId)
                put("title", b.title)
                put("cover", b.cover)
                put("progress", b.progress)
                put("url", b.url)
                put("type", b.typeName)
            })
        }
        SecretPrefs.put(prefs(ctx), BANGUMI, arr.toString())
    }

    fun bangumi(ctx: Context): List<Bangumi> {
        val raw = SecretPrefs.get(prefs(ctx), BANGUMI) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Bangumi>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Bangumi(
                    o.optLong("season"),
                    o.optString("title"),
                    o.optString("cover"),
                    o.optString("progress"),
                    o.optString("url"),
                    o.optString("type")
                )
            )
        }
        return out
    }

    private fun saveSession(ctx: Context, i: Info) {
        val o = JSONObject().apply {
            put("mid", i.mid)
            put("name", i.name)
            put("face", i.face)
            put("cookie", i.cookie)
            put("vip", i.vip)
            put("saved_at", i.savedAt)
            put("vip_expire", i.vipExpire)
            put("vip_active", i.vipActive)
        }
        SecretPrefs.put(prefs(ctx), SESSION, o.toString())
    }

    private fun load(ctx: Context): Info? {
        val raw = SecretPrefs.get(prefs(ctx), SESSION) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val cookie = o.optString("cookie", "")
        if (!cookie.contains("SESSDATA=")) {
            SecretPrefs.remove(prefs(ctx), SESSION)
            return null
        }
        return Info(
            o.optLong("mid", 0L),
            o.optString("name", "B站用户"),
            o.optString("face", ""),
            cookie,
            o.optString("vip", "普通用户"),
            o.optLong("saved_at", 0L),
            o.optLong("vip_expire", 0L),
            o.optBoolean("vip_active", o.optString("vip", "").contains("大会员"))
        )
    }

    fun normalize(raw: String): String {
        val pairs = LinkedHashMap<String, String>()
        for (part in raw.split(";")) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) pairs[key] = value
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun httpsify(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://" + u.substring(7)
            else -> u
        }
    }
}
