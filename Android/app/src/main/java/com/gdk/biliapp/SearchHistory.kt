package com.gdk.biliapp

import android.content.Context
import org.json.JSONArray

/**
 * 本机搜索历史。只保留最近 20 条，重复关键词会移到最前。
 * 不上传云端，清空只影响当前设备。
 */
object SearchHistory {
    private const val PREF = "bili_search_history"
    private const val KEY = "items"
    private const val MAX = 20

    fun list(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty() && !out.contains(s)) out.add(s)
            }
            out
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, keyword: String): List<String> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return list(context)
        val items = ArrayList(list(context))
        items.removeAll { it.equals(kw, ignoreCase = true) }
        items.add(0, kw)
        while (items.size > MAX) items.removeAt(items.size - 1)
        save(context, items)
        return items
    }

    fun remove(context: Context, keyword: String): List<String> {
        val items = ArrayList(list(context))
        items.removeAll { it.equals(keyword.trim(), ignoreCase = true) }
        save(context, items)
        return items
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<String>) {
        val arr = JSONArray()
        for (item in items) arr.put(item)
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
