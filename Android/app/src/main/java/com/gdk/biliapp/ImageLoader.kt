package com.gdk.biliapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, rawUrl: String) {
        val url = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http://") -> rawUrl.replace("http://", "https://")
            else -> rawUrl
        }
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        pool.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("User-Agent", Bili.UA)
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                if (bmp != null) cache.put(url, bmp)
                if (view.tag == url) main.post { if (bmp != null) view.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }
}
