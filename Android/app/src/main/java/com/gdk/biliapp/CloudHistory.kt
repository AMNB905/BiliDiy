package com.gdk.biliapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 本机观看记录云端备份：本地保存后延迟批量上传。只备份，不反向合并。 */
object CloudHistory {

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pending: Runnable? = null

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        pending?.let { main.removeCallbacks(it) }
        val task = Runnable { flush(app) }
        pending = task
        main.postDelayed(task, 12_000)
    }

    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        pending?.let { main.removeCallbacks(it); pending = null }
        val items = History.list(app)
        if (items.isEmpty()) return
        io.execute {
            runCatching {
                val arr = JSONArray()
                for (it in items) {
                    arr.put(JSONObject().apply {
                        put("key", it.key)
                        put("kind", it.kind)
                        put("cat", it.cat)
                        put("bvid", it.bvid)
                        put("ep", it.epId)
                        put("title", it.title)
                        put("cover", it.cover)
                        put("pos", it.pos)
                        put("dur", it.dur)
                        put("at", it.at)
                    })
                }
                post("history_save", "items=" + enc(arr.toString()))
            }
        }
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            runCatching { post("history_clear", "") }
        }
    }

    private fun post(action: String, extra: String): String {
        val c = URL("${Cloud.API}?action=$action").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.setRequestProperty("User-Agent", Bili.UA)
        val body = "key=${enc(Cloud.key())}&device_id=${enc(Profile.deviceId(AppCtx.app))}" +
                if (extra.isBlank()) "" else "&$extra"
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = (try { c.inputStream } catch (e: Exception) { c.errorStream })
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return text
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
