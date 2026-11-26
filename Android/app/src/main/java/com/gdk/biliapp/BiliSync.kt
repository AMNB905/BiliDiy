package com.gdk.biliapp

import android.content.Context
import org.json.JSONObject

/**
 * 把用户自己 B 站账号的观看历史、追番/追剧列表拉到本机。
 *
 * 只读取当前账号可见的数据；接口失败时保留本机已有记录，不做删除。
 */
object BiliSync {

    data class Result(
        val history: Int,
        val bangumi: Int,
        val historyError: String = "",
        val bangumiError: String = ""
    ) {
        fun summary(): String {
            val sb = StringBuilder()
            if (history > 0) sb.append("B站历史 ").append(history).append(" 条（独立保存）")
            else if (historyError.isBlank()) sb.append("B站历史没有记录")
            if (bangumi > 0) {
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("追番/追剧 ").append(bangumi).append(" 部")
            } else if (bangumiError.isBlank()) {
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("追番列表为空")
            }
            if (historyError.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("历史同步失败：").append(historyError)
            }
            if (bangumiError.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("追番同步失败：").append(bangumiError)
            }
            return sb.toString().ifBlank { "没有可同步的数据" }
        }
    }

    fun syncAll(ctx: Context, cb: (Result) -> Unit) {
        val app = ctx.applicationContext
        val info = BiliUser.info(app)
        if (info == null) {
            Bili.main { cb(Result(0, 0, "未绑定 B 站账号", "")) }
            return
        }
        Bili.io {
            var history = 0
            var bangumi = 0
            var historyError = ""
            var bangumiError = ""

            try {
                val items = fetchHistory()
                history = BiliHistory.replace(app, info.mid, items)
                BiliUser.markHistory(app, history)
            } catch (e: Exception) {
                historyError = e.message ?: "接口请求失败"
            }

            try {
                val list = fetchBangumi(info.mid)
                BiliUser.markBangumi(app, list)
                bangumi = list.size
            } catch (e: Exception) {
                bangumiError = e.message ?: "接口请求失败"
            }

            val result = Result(history, bangumi, historyError, bangumiError)
            Bili.main { cb(result) }
        }
    }

    private fun fetchHistory(): List<History.Item> {
        val out = LinkedHashMap<String, History.Item>()
        var max = 0L
        var business = ""
        var viewAt = 0L
        for (page in 0 until 10) {
            val json = try {
                Bili.history("all", max, business, viewAt)
            } catch (_: Exception) {
                break
            }
            val data = json.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            if (list.length() == 0) break

            for (i in 0 until list.length()) {
                val item = convert(list.optJSONObject(i)) ?: continue
                // 同一视频可能重复出现，保留 view_at 较新的那条
                val old = out[item.key]
                if (old == null || item.at > old.at) out[item.key] = item
            }

            val cursor = data.optJSONObject("cursor") ?: break
            max = cursor.optLong("max", 0L)
            business = cursor.optString("business", "")
            viewAt = cursor.optLong("view_at", 0L)
            if (max <= 0) break
        }
        return out.values.toList()
    }

    private fun convert(o: JSONObject?): History.Item? {
        if (o == null) return null
        val h = o.optJSONObject("history") ?: return null
        val business = h.optString("business", "")
        val title = o.optString("title", "").trim()
        if (title.isBlank()) return null

        val at = o.optLong("view_at", 0L).let { if (it > 0) it * 1000L else System.currentTimeMillis() }
        val pos = o.optLong("progress", 0L).coerceAtLeast(0L) * 1000L
        val dur = o.optLong("duration", 0L).coerceAtLeast(0L) * 1000L
        val cover = BiliUser.httpsify(o.optString("cover", ""))

        if (business == "pgc") {
            val ep = h.optLong("epid", 0L).takeIf { it > 0 } ?: h.optLong("oid", 0L)
            if (ep <= 0) return null
            return History.Item(
                key = "p:$ep",
                kind = "pgc",
                bvid = h.optString("bvid", ""),
                epId = ep,
                title = title,
                cover = cover,
                pos = pos,
                dur = dur,
                at = at,
                cat = "anime"
            )
        }

        if (business != "archive") return null
        val bvid = h.optString("bvid", "").trim()
        if (bvid.isBlank()) return null
        val cid = h.optLong("cid", 0L)
        return History.Item(
            key = "v:$bvid:$cid",
            kind = "video",
            bvid = bvid,
            epId = 0L,
            title = title,
            cover = cover,
            pos = pos,
            dur = dur,
            at = at,
            cat = "video"
        )
    }

    private fun fetchBangumi(mid: Long): List<BiliUser.Bangumi> {
        val out = LinkedHashMap<Long, BiliUser.Bangumi>()
        for (type in listOf(1, 2)) {
            var total = Int.MAX_VALUE
            for (pn in 1..5) {
                val data = Bili.bangumiFollow(mid, type, pn).optJSONObject("data") ?: break
                total = data.optInt("total", total)
                val list = data.optJSONArray("list") ?: break
                if (list.length() == 0) break

                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val sid = o.optLong("season_id", 0L)
                    if (sid <= 0) continue
                    val title = o.optString("title", "").trim()
                    if (title.isBlank()) continue
                    val cover = BiliUser.httpsify(
                        o.optString("cover", "").ifBlank { o.optString("square_cover", "") }
                    )
                    val progress = o.optJSONObject("new_ep")?.optString("index_show", "")
                        ?.takeIf { it.isNotBlank() }
                        ?: o.optString("progress", "")
                    val url = BiliUser.httpsify(o.optString("url", ""))
                        .ifBlank { "https://www.bilibili.com/bangumi/play/ss$sid" }
                    out[sid] = BiliUser.Bangumi(
                        seasonId = sid,
                        title = title,
                        cover = cover,
                        progress = progress,
                        url = url,
                        typeName = o.optString("season_type_name", "番剧")
                    )
                }

                if (pn * 20 >= total) break
            }
        }
        return out.values.toList()
    }
}
