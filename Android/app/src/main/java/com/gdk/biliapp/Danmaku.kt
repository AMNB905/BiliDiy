package com.gdk.biliapp

import android.graphics.Color
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.regex.Pattern

/** 一条弹幕：时间轴、显示方式（1-3 滚动 / 4 底部 / 5 顶部）、颜色和正文 */
data class DanmakuItem(
    val timeMs: Long,
    val mode: Int,
    val color: Int,
    val text: String
)

/**
 * B 站弹幕源。优先使用分段 protobuf 接口，失败或为空时回退 XML 列表。
 * 只做只读展示，不发送弹幕，也不上传任何内容。
 */
object DanmakuRepository {
    private const val SEG_HOST = "https://api.bilibili.com/x/v2/dm/web/seg.so"
    private const val XML_HOST = "https://api.bilibili.com/x/v1/dm/list.so"
    private const val CACHE_TTL = 10 * 60 * 1000L

    private data class CacheEntry(val at: Long, val items: List<DanmakuItem>)

    private val cache = Collections.synchronizedMap(HashMap<Long, CacheEntry>())

    fun load(cid: Long, durationSec: Int, referer: String): List<DanmakuItem> {
        if (cid <= 0L) return emptyList()
        cache[cid]?.let {
            if (System.currentTimeMillis() - it.at <= CACHE_TTL) return it.items
        }

        val segments = if (durationSec > 0) {
            ((durationSec + 359) / 360).coerceIn(1, 30)
        } else {
            6
        }
        val list = ArrayList<DanmakuItem>()
        for (i in 1..segments) {
            val bytes = try {
                getBytes("$SEG_HOST?type=1&oid=$cid&pid=$cid&segment_index=$i", referer)
            } catch (e: Exception) {
                null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                try {
                    list.addAll(parseSegment(bytes))
                } catch (e: Exception) {
                    // 单个分段解析失败不影响其它分段
                }
            }
        }

        if (list.isEmpty()) {
            val body = try {
                getText("$XML_HOST?oid=$cid", referer)
            } catch (e: Exception) {
                ""
            }
            if (body.isNotEmpty()) list.addAll(parseXml(body))
        }

        val result = list
            .filter { it.text.isNotBlank() && it.timeMs >= 0 }
            .sortedBy { it.timeMs }
        if (result.isNotEmpty()) {
            if (cache.size > 24) cache.clear()
            cache[cid] = CacheEntry(System.currentTimeMillis(), result)
        }
        return result
    }

    private fun getBytes(url: String, referer: String): ByteArray {
        val c = open(url, referer)
        try {
            if (c.responseCode !in 200..299) return ByteArray(0)
            return readAll(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    private fun getText(url: String, referer: String): String {
        val bytes = getBytes(url, referer)
        return if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
    }

    private fun open(url: String, referer: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", Bili.UA)
        c.setRequestProperty("Referer", referer.ifBlank { "https://www.bilibili.com/" })
        c.setRequestProperty("Cookie", Bili.cookie)
        // 不让服务端按 UA 返回压缩流，protobuf/XML 都按原始字节解析
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------- protobuf ----------

    private fun parseSegment(bytes: ByteArray): List<DanmakuItem> {
        val out = ArrayList<DanmakuItem>()
        val pos = intArrayOf(0)
        while (pos[0] < bytes.size) {
            val key = varint(bytes, pos, bytes.size)
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when (wire) {
                0 -> varint(bytes, pos, bytes.size)
                1 -> pos[0] += 8
                2 -> {
                    val len = varint(bytes, pos, bytes.size).toInt()
                    if (len < 0 || pos[0] + len > bytes.size) return out
                    if (field == 1) {
                        val item = parseElement(bytes, pos[0], pos[0] + len)
                        if (item != null) out.add(item)
                    }
                    pos[0] += len
                }
                5 -> pos[0] += 4
                else -> return out
            }
        }
        return out
    }

    private fun parseElement(bytes: ByteArray, start: Int, end: Int): DanmakuItem? {
        val p = intArrayOf(start)
        var time = 0L
        var mode = 1
        var color = Color.WHITE
        var text = ""
        while (p[0] < end) {
            val key = varint(bytes, p, end)
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when (wire) {
                0 -> {
                    val v = varint(bytes, p, end)
                    when (field) {
                        2 -> time = v
                        3 -> mode = v.toInt()
                        5 -> color = v.toInt() and 0xFFFFFF
                    }
                }
                2 -> {
                    val len = varint(bytes, p, end).toInt()
                    if (len < 0 || p[0] + len > end) return null
                    if (field == 7) text = String(bytes, p[0], len, Charsets.UTF_8)
                    p[0] += len
                }
                5 -> p[0] += 4
                1 -> p[0] += 8
                else -> return null
            }
        }
        if (text.isBlank()) return null
        return DanmakuItem(time.coerceAtLeast(0L), mode, color, text)
    }

    private fun varint(bytes: ByteArray, pos: IntArray, end: Int): Long {
        var value = 0L
        var shift = 0
        while (pos[0] < end) {
            val b = bytes[pos[0]++].toInt()
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift > 63) return value
        }
        return value
    }

    // ---------- XML 回退 ----------

    private val dmPattern = Pattern.compile("<d\\s+p=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</d>")

    private fun parseXml(body: String): List<DanmakuItem> {
        val out = ArrayList<DanmakuItem>()
        val m = dmPattern.matcher(body)
        while (m.find()) {
            val p = m.group(1)?.split(",") ?: continue
            if (p.size < 4) continue
            val time = (p[0].toDoubleOrNull() ?: continue) * 1000.0
            val mode = p[1].toIntOrNull() ?: 1
            val color = p[3].toLongOrNull()?.toInt() ?: Color.WHITE
            val text = unescape(m.group(2).orEmpty())
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .trim()
            if (text.isBlank()) continue
            out.add(DanmakuItem(time.toLong().coerceAtLeast(0L), mode, color and 0xFFFFFF, text))
        }
        return out
    }

    private fun unescape(s: String): String {
        var text = s
        text = Regex("&#(\\d+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        text = Regex("&#x([0-9a-fA-F]+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /** 供调试/设置页显示条数使用 */
    fun parseXmlForTest(body: String): JSONArray {
        val arr = JSONArray()
        parseXml(body).forEach {
            arr.put(
                org.json.JSONObject()
                    .put("time", it.timeMs)
                    .put("mode", it.mode)
                    .put("color", it.color)
                    .put("text", it.text)
            )
        }
        return arr
    }
}
