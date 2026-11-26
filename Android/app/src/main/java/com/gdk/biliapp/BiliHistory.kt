package com.gdk.biliapp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * B站账号观看历史：与设备本机历史完全隔离。
 *
 * - 只保存当前绑定 B站 UID 的历史，换绑账号时清空旧账号缓存；
 * - 独立 SharedPreferences，不写入 [History]，也不会被首页「本机继续观看」读取；
 * - 最多保留 300 条，按观看时间倒序。
 */
object BiliHistory {

    private const val PREF = "bili_account_history"
    private const val K_MID = "mid"
    private const val K_ITEMS = "items"
    private const val K_AT = "at"
    private const val MAX = 300

    @Volatile
    private var cachedMid: Long = -1L

    @Volatile
    private var cached: List<History.Item>? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun currentMid(ctx: Context): Long = BiliUser.info(ctx)?.mid ?: 0L

    /** 绑定 B站账号。同一 UID 保留已有历史，换 UID 立即清空旧账号缓存。 */
    fun bindAccount(ctx: Context, mid: Long) {
        if (mid <= 0) return
        val app = ctx.applicationContext
        val p = prefs(app)
        if (p.getLong(K_MID, 0L) == mid) return
        p.edit()
            .putLong(K_MID, mid)
            .remove(K_ITEMS)
            .remove(K_AT)
            .apply()
        cachedMid = mid
        cached = emptyList()
    }

    /** 当前绑定账号的 B站历史。账号不匹配时返回空，不把旧账号记录串出来。 */
    fun snapshot(ctx: Context): List<History.Item> {
        val app = ctx.applicationContext
        val mid = currentMid(app)
        if (mid <= 0) {
            cachedMid = 0L
            cached = emptyList()
            return emptyList()
        }
        val p = prefs(app)
        val savedMid = p.getLong(K_MID, 0L)
        if (savedMid != mid) {
            // 会话还在，但独立历史尚未绑定：只建立归属，不搬旧数据。
            p.edit()
                .putLong(K_MID, mid)
                .remove(K_ITEMS)
                .remove(K_AT)
                .apply()
            cachedMid = mid
            cached = emptyList()
            return emptyList()
        }
        cached?.let { if (cachedMid == mid) return it }
        val loaded = parse(p.getString(K_ITEMS, null))
        cachedMid = mid
        cached = loaded
        return loaded
    }

    fun list(ctx: Context): List<History.Item> = snapshot(ctx)

    fun count(ctx: Context): Int = snapshot(ctx).size

    fun lastAt(ctx: Context): Long {
        val app = ctx.applicationContext
        val mid = currentMid(app)
        if (mid <= 0) return 0L
        val p = prefs(app)
        if (p.getLong(K_MID, 0L) != mid) return 0L
        return p.getLong(K_AT, 0L)
    }

    /** 用一次同步结果替换当前账号历史，返回保存后的条数。 */
    fun replace(ctx: Context, mid: Long, incoming: List<History.Item>): Int {
        if (mid <= 0) return 0
        val app = ctx.applicationContext
        bindAccount(app, mid)

        val unique = LinkedHashMap<String, History.Item>()
        for (item in incoming) {
            if (item.key.isBlank() || item.title.isBlank()) continue
            val old = unique[item.key]
            if (old == null || item.at > old.at) unique[item.key] = item
        }
        val sorted = unique.values
            .sortedByDescending { it.at }
            .take(MAX)

        val p = prefs(app)
        p.edit()
            .putLong(K_MID, mid)
            .putString(K_ITEMS, encode(sorted))
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
        cachedMid = mid
        cached = sorted
        return sorted.size
    }

    fun remove(ctx: Context, key: String): Boolean {
        val app = ctx.applicationContext
        val mid = currentMid(app)
        if (mid <= 0) return false
        val left = snapshot(app).filterNot { it.key == key }
        if (left.size == snapshot(app).size) return false
        prefs(app).edit()
            .putLong(K_MID, mid)
            .putString(K_ITEMS, encode(left))
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
        cachedMid = mid
        cached = left
        return true
    }

    /** 只清本机独立缓存，不影响 B站服务器上的观看记录。 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_MID).remove(K_ITEMS).remove(K_AT).apply()
        cachedMid = 0L
        cached = emptyList()
    }

    private fun parse(raw: String?): List<History.Item> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<History.Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("key").isBlank() || o.optString("title").isBlank()) continue
            val kind = o.optString("kind", "video")
            out.add(History.Item(
                key = o.optString("key"),
                kind = kind,
                bvid = o.optString("bvid"),
                epId = o.optLong("ep"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                pos = o.optLong("pos"),
                dur = o.optLong("dur"),
                at = o.optLong("at"),
                cat = o.optString("cat", if (kind == "pgc") "movie" else "video")
            ))
        }
        return out.sortedByDescending { it.at }.take(MAX)
    }

    private fun encode(items: List<History.Item>): String {
        val arr = JSONArray()
        for (it in items.take(MAX)) {
            arr.put(JSONObject().apply {
                put("key", it.key)
                put("kind", it.kind)
                put("bvid", it.bvid)
                put("ep", it.epId)
                put("title", it.title)
                put("cover", it.cover)
                put("pos", it.pos)
                put("dur", it.dur)
                put("at", it.at)
                put("cat", it.cat)
            })
        }
        return arr.toString()
    }
}
