package com.gdk.biliapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地观看历史：不设条数上限，记录进度（毫秒）与时长，用于首页「继续观看」、用户中心与断点续播。
 * 只存在本机 SharedPreferences，不上传。内存里缓存一份，避免每次保存都重新解析整段 JSON。
 */
object History {

    data class Item(
        val key: String,
        val kind: String,      // video / pgc
        val bvid: String,
        val epId: Long,
        val title: String,
        val cover: String,
        val pos: Long,
        val dur: Long,
        val at: Long,
        val cat: String = "video"   // video / anime / movie
    )

    private const val PREF = "bili_history"

    @Volatile
    private var cached: List<Item>? = null

    fun list(ctx: Context): List<Item> {
        cached?.let { return it }
        val loaded = parse(ctx)
        cached = loaded
        return loaded
    }

    fun get(ctx: Context, key: String): Item? = list(ctx).firstOrNull { it.key == key }

    fun save(ctx: Context, item: Item) {
        if (item.key.isBlank() || item.title.isBlank()) return
        val now = System.currentTimeMillis()
        val items = list(ctx).filterNot { it.key == item.key }.toMutableList()
        items.add(0, item.copy(at = now))
        cached = items
        persist(ctx, items)
        CloudHistory.schedule(ctx)
    }

    fun remove(ctx: Context, key: String) {
        val left = list(ctx).filterNot { it.key == key }
        cached = left
        persist(ctx, left)
        CloudHistory.schedule(ctx)
    }

    fun clear(ctx: Context) {
        cached = emptyList()
        prefs(ctx).edit().remove("items").apply()
        CloudHistory.clear(ctx)
    }

    /** 只写本机历史，不触发云端上传（本机历史与B站账号历史互不合并） */
    fun replaceLocal(ctx: Context, items: List<Item>) {
        cached = items
        persist(ctx, items)
    }

    private fun parse(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString("items", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("kind", "video")
            out.add(
                Item(
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
                )
            )
        }
        return out.sortedByDescending { it.at }
    }

    private fun persist(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
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
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
