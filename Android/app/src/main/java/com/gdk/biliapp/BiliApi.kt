package com.gdk.biliapp

import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiException(val code: Int, message: String) : Exception(message)

/** 搜索的三种内容形态 */
enum class SearchKind { VIDEO, BANGUMI, MOVIE }

object Bili {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val BASE = "https://api.bilibili.com"

    /** 播放器 Referer（媒体流防盗链校验） */
    @Volatile
    var currentReferer: String = "https://www.bilibili.com/"

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cookieCache: String? = null

    /** 用户自己绑定的 B 站 Cookie；优先于云端下发的播放 Cookie */
    @Volatile
    private var userCookieCache: String? = null

    val cookie: String
        get() {
            val user = userCookieCache
            if (!user.isNullOrEmpty()) return user
            val cached = cookieCache
            if (!cached.isNullOrEmpty()) return cached
            val stored = CookieStore.get().orEmpty()
            if (stored.isNotEmpty()) cookieCache = stored
            return stored
        }

    fun setCookie(c: String) {
        cookieCache = c.trim()
        CookieStore.put(cookieCache.orEmpty())
    }

    fun setUserCookie(c: String) {
        userCookieCache = c.trim().takeIf { it.isNotEmpty() }
    }

    fun clearUserCookie() {
        userCookieCache = null
    }

    fun clearCookie() {
        cookieCache = ""
        CookieStore.clear()
    }

    fun io(block: () -> Unit) = executor.execute(block)
    fun <T> main(block: () -> T) = mainHandler.post { block() }

    // ---------- HTTP ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(url: String, method: String, form: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.requestMethod = method
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://www.bilibili.com/")
        c.setRequestProperty("Cookie", cookie)
        if (form != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream = try { c.inputStream } catch (e: Exception) { c.errorStream }
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return body
    }

    private fun request(path: String, params: Map<String, String>, wbi: Boolean = false,
                        host: String = BASE): JSONObject {
        val p = params.toMutableMap()
        if (wbi) signWbi(p)
        val qs = p.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val json = JSONObject(readBody(open("$host$path?$qs", "GET", null)))
        val code = json.optInt("code", -1)
        if (code != 0) throw ApiException(code, json.optString("message", "接口错误 code=$code"))
        return json
    }

    // ---------- WBI 签名 ----------

    private val MIXIN = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    @Volatile private var wbiImg: String? = null
    @Volatile private var wbiSub: String? = null
    @Volatile private var wbiAt: Long = 0

    private fun wbiKeys(): Pair<String, String> {
        if (wbiImg == null || wbiSub == null || System.currentTimeMillis() - wbiAt > 3_600_000) {
            synchronized(this) {
                val nav = request("/x/web-interface/nav", emptyMap())
                val img = nav.getJSONObject("data").getJSONObject("wbi_img")
                wbiImg = img.getString("img_url").substringAfterLast('/').substringBefore('.')
                wbiSub = img.getString("sub_url").substringAfterLast('/').substringBefore('.')
                wbiAt = System.currentTimeMillis()
            }
        }
        return wbiImg!! to wbiSub!!
    }

    private fun signWbi(p: MutableMap<String, String>) {
        val (img, sub) = wbiKeys()
        val all = img + sub
        val mixin = MIXIN.map { all[it] }.joinToString("")
        p["wts"] = (System.currentTimeMillis() / 1000).toString()
        val filtered = p.mapValues { v -> v.value.filter { it !in "!'()*" } }
        val qs = filtered.toSortedMap().entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        p["w_rid"] = md5(qs + mixin)
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun ticket(): String {
        for (part in cookie.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "bili_ticket") return kv[1]
        }
        return ""
    }

    // ---------- 账号 / 首页 ----------

    fun nav(): JSONObject = request("/x/web-interface/nav", emptyMap())

    fun popular(pn: Int): JSONArray =
        request("/x/web-interface/popular", mapOf("ps" to "20", "pn" to pn.toString()))
            .getJSONObject("data").optJSONArray("list") ?: JSONArray()

    // ---------- 搜索发现：热搜 / 排行榜 ----------

    /** B 站实时热搜；优先搜索广场，失败后降级到旧热词接口 */
    fun hotSearch(): JSONArray {
        return try {
            request("/x/web-interface/search/square", mapOf("limit" to "20"))
                .optJSONObject("data")?.optJSONObject("trending")?.optJSONArray("list")
                ?: JSONArray()
        } catch (first: Exception) {
            try {
                request(
                    "/main/hotword",
                    mapOf("limit" to "20"),
                    host = "https://s.search.bilibili.com"
                ).optJSONArray("list") ?: JSONArray()
            } catch (_: Exception) {
                throw first
            }
        }
    }

    data class RankFeed(val items: JSONArray, val fallback: Boolean)

    /** 全站排行榜；风控时降级为按播放量排序的热门列表 */
    fun ranking(): RankFeed {
        val params = linkedMapOf("rid" to "0", "type" to "all")
        val t = ticket()
        if (t.isNotEmpty()) {
            params["bili_ticket"] = t
            params["web_location"] = "333.934"
        }
        try {
            val list = request("/x/web-interface/ranking/v2", params)
                .getJSONObject("data").optJSONArray("list")
            if (list != null && list.length() > 0) return RankFeed(list, false)
        } catch (_: Exception) {
        }
        try {
            val list = request(
                "/x/web-interface/ranking",
                mapOf("rid" to "0", "type" to "all")
            ).getJSONObject("data").optJSONArray("list")
            if (list != null && list.length() > 0) return RankFeed(list, false)
        } catch (_: Exception) {
        }

        val popular = popular(1)
        val sorted = ArrayList<JSONObject>()
        for (i in 0 until popular.length()) popular.optJSONObject(i)?.let { sorted.add(it) }
        sorted.sortByDescending {
            it.optJSONObject("stat")?.optLong("view", 0) ?: it.optLong("play", 0)
        }
        val out = JSONArray()
        for (item in sorted) out.put(item)
        return RankFeed(out, true)
    }

    // ---------- 搜索（视频 / 番剧 / 影视）----------

    private const val SEARCH_TYPE_VIDEO = "video"
    private const val SEARCH_TYPE_BANGUMI = "media_bangumi"
    private const val SEARCH_TYPE_MOVIE = "media_ft"

    fun search(kind: SearchKind, keyword: String, page: Int): JSONArray {
        val type = when (kind) {
            SearchKind.VIDEO -> SEARCH_TYPE_VIDEO
            SearchKind.BANGUMI -> SEARCH_TYPE_BANGUMI
            SearchKind.MOVIE -> SEARCH_TYPE_MOVIE
        }
        val base = linkedMapOf("search_type" to type, "keyword" to keyword, "page" to page.toString())
        val params = LinkedHashMap(base)
        // 视频搜索风控最严，带上 bili_ticket 提高通过率；其余类型同样适用
        val t = ticket()
        if (t.isNotEmpty()) {
            params["bili_ticket"] = t
            params["web_location"] = "1435856662"
        }
        return try {
            pickResults(request("/x/web-interface/wbi/search/type", params, wbi = true))
        } catch (first: Exception) {
            try {
                // 未签名接口风控规则不同，作为降级
                pickResults(request("/x/web-interface/search/type", base))
            } catch (_: Exception) {
                throw first
            }
        }
    }

    private fun pickResults(json: JSONObject): JSONArray {
        val data = json.optJSONObject("data") ?: return JSONArray()
        return data.optJSONArray("result") ?: JSONArray()
    }

    // ---------- 视频（UGC）----------

    /** ref: "BV..." 或 "av123" */
    fun view(ref: String): JSONObject =
        request("/x/web-interface/view",
            if (ref.startsWith("av")) mapOf("aid" to ref.substring(2)) else mapOf("bvid" to ref)
        ).getJSONObject("data")

    fun playurl(bvid: String, cid: Long, qn: Int): JSONObject {
        val data = request("/x/player/wbi/playurl", mapOf(
            "bvid" to bvid, "cid" to cid.toString(), "qn" to qn.toString(),
            "fnval" to "4048", "fnver" to "0", "fourk" to "1", "voice_balance" to "1"
        ), wbi = true).getJSONObject("data")
        if (data.optInt("code", 0) == -404) throw ApiException(-404, "视频不存在或需要登录")
        return data
    }

    fun playurlHtml5(bvid: String, cid: Long, qn: Int): JSONObject =
        request("/x/player/playurl", mapOf(
            "bvid" to bvid, "cid" to cid.toString(), "qn" to qn.toString(),
            "platform" to "html5", "high_quality" to "1", "fnval" to "0", "fnver" to "0"
        )).getJSONObject("data")

    // ---------- 番剧 / 影视（PGC）----------

    fun pgcSeason(seasonId: Long): JSONObject =
        request("/pgc/view/web/season", mapOf("season_id" to seasonId.toString()))
            .getJSONObject("result")

    fun pgcSeasonByEp(epId: Long): JSONObject =
        request("/pgc/view/web/season", mapOf("ep_id" to epId.toString()))
            .getJSONObject("result")

    fun pgcPlayurl(epId: Long, qn: Int): JSONObject =
        request("/pgc/player/web/playurl",
            mapOf("ep_id" to epId.toString(), "qn" to qn.toString(), "fnval" to "4048",
                "fnver" to "0", "fourk" to "1"))
            .getJSONObject("result")

    /** PGC 的 MP4 直链（下载用，带音轨）；付费内容可能只返回试看片段 */
    fun pgcDownloadData(epId: Long, qn: Int): JSONObject =
        request("/pgc/player/web/playurl",
            mapOf("ep_id" to epId.toString(), "qn" to qn.toString(), "fnval" to "0",
                "fnver" to "0", "fourk" to "1"))
            .getJSONObject("result")

    // ---------- 课堂 / 付费课程（cheese）----------

    /** 课程季信息；用于识别免费试看课时和付费课时 */
    fun courseSeason(seasonId: Long): JSONObject =
        request("/pugv/view/web/season", mapOf("season_id" to seasonId.toString()))
            .getJSONObject("data")

    /** 课程课时播放地址；免费课时可直接解析，付费课时由 B 站返回限制 */
    fun coursePlayurl(epId: Long, qn: Int): JSONObject =
        request("/pugv/player/web/playurl", mapOf(
            "ep_id" to epId.toString(), "qn" to qn.toString(),
            "fnval" to "4048", "fnver" to "0", "fourk" to "1"
        )).getJSONObject("data")

    // ---------- 当前用户 / 历史 / 追番 ----------

    /** type: all / archive / live / article；分页用上一页 cursor 的 max/business/view_at */
    fun history(type: String, max: Long, business: String, viewAt: Long, ps: Int = 20): JSONObject {
        val p = linkedMapOf("ps" to ps.toString(), "type" to type)
        if (max > 0) {
            p["max"] = max.toString()
            if (business.isNotBlank()) p["business"] = business
            if (viewAt > 0) p["view_at"] = viewAt.toString()
        }
        return request("/x/web-interface/history/cursor", p)
    }

    /** 追番/追剧列表；type=1 番剧，type=2 影视 */
    fun bangumiFollow(mid: Long, type: Int, pn: Int, ps: Int = 20): JSONObject =
        request("/x/space/bangumi/follow/list", linkedMapOf(
            "type" to type.toString(),
            "follow_status" to "0",
            "pn" to pn.toString(),
            "ps" to ps.toString(),
            "vmid" to mid.toString(),
            "order" to "0"
        ))

    // ---------- 杂项 ----------

    fun myMid(): Long {
        for (part in cookie.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "DedeUserID") return kv[1].toLongOrNull() ?: 0
        }
        return 0
    }
}

/** 云端下发的播放 Cookie，落盘前用 Android Keystore AES-GCM 加密 */
object CookieStore {
    private const val PREF = "bili_local"
    private const val KEY = "cookie"
    private const val KEY_ENC = "cookie_enc"
    private const val ALIAS = "bilidiy_cookie_key_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val prefs
        get() = AppCtx.app.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val old = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (old != null) return old.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun get(): String? {
        val encrypted = prefs.getString(KEY_ENC, null)
        if (!encrypted.isNullOrBlank()) {
            try {
                val raw = Base64.decode(encrypted, Base64.NO_WRAP)
                if (raw.size <= 12) return null
                val iv = raw.copyOfRange(0, 12)
                val body = raw.copyOfRange(12, raw.size)
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                return String(cipher.doFinal(body), Charsets.UTF_8)
                    .trim().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                clear()
                return null
            }
        }
        val plain = prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (plain != null) {
            put(plain)
            return plain
        }
        return null
    }

    fun put(c: String) {
        val value = c.trim()
        if (value.isEmpty()) return clear()
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val all = cipher.iv + body
            prefs.edit()
                .putString(KEY_ENC, Base64.encodeToString(all, Base64.NO_WRAP))
                .remove(KEY)
                .apply()
        } catch (_: Exception) {
            // 加密不可用时宁可不落盘，也不保存明文
            prefs.edit().remove(KEY).remove(KEY_ENC).apply()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(KEY_ENC).apply()
    }
}

