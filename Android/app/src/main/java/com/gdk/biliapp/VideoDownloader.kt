package com.gdk.biliapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * 视频下载工具：
 * - 普通投稿 / 番剧优先下载 B 站下发的 MP4（自带音轨）；
 * - 课程只提供 DASH 时，分别下载视频轨和音频轨，再用 MediaMuxer 合成一个 MP4。
 * 下载目录为 App 外部私有目录：Android/data/<包名>/files/Movies/BiliDiy。
 */
object VideoDownloader {

    data class Streams(val video: JSONObject, val audio: JSONObject?)

    /** App 专属下载目录；无需存储权限，卸载 App 时会一并删除 */
    fun outputDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.getExternalFilesDir(null)
            ?: File(context.filesDir, "downloads")
        return File(base, "BiliDiy")
    }

    /** 保存到 App 外部私有目录；同名文件直接覆盖 */
    fun outputFile(context: Context, title: String): File {
        val dir = outputDir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建下载目录")
        val name = safeName(title)
        return File(dir, "$name.mp4")
    }

    /** 本地下载列表：最新的排前面，只统计非空的 mp4 */
    fun listDownloads(context: Context): List<File> {
        val dir = outputDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4", true) && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun firstDurlUrl(data: JSONObject?): String? {
        val durl = data?.optJSONArray("durl") ?: return null
        for (i in 0 until durl.length()) {
            val d = durl.optJSONObject(i) ?: continue
            val url = d.optString("url", "")
            if (url.isNotEmpty()) return url
            val backups = d.optJSONArray("backup_url") ?: continue
            for (j in 0 until backups.length()) {
                val u = backups.optString(j, "")
                if (u.isNotEmpty()) return u
            }
        }
        return null
    }

    /** 从 playurl 的 dash 中挑一条 AVC 视频轨和一条 AAC 音频轨 */
    fun pick(dash: JSONObject, preferQuality: Int): Streams? {
        val videos = dash.optJSONArray("video") ?: return null
        val candidates = ArrayList<Pair<JSONObject, Int>>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (baseUrlOf(v).isEmpty()) continue
            candidates.add(v to codecRank(v.optString("codecs", "")))
        }
        if (candidates.isEmpty()) return null
        val usable = candidates.filter { it.second < 3 }.ifEmpty { candidates }
        val ids = usable.map { it.first.optInt("id", 0) }.distinct().sorted()
        val targetId = when {
            ids.contains(preferQuality) -> preferQuality
            ids.any { it <= preferQuality } -> ids.last { it <= preferQuality }
            else -> ids.first()
        }
        val video = usable.filter { it.first.optInt("id", 0) == targetId }
            .sortedWith(compareBy<Pair<JSONObject, Int>> { it.second }
                .thenByDescending { it.first.optLong("bandwidth", 0) })
            .firstOrNull()?.first ?: return null

        val audios = dash.optJSONArray("audio") ?: JSONArray()
        var audio: JSONObject? = null
        for (i in 0 until audios.length()) {
            val a = audios.optJSONObject(i) ?: continue
            if (baseUrlOf(a).isEmpty()) continue
            if (!a.optString("codecs", "").startsWith("mp4a")) continue
            if (audio == null || a.optLong("bandwidth", 0) > audio.optLong("bandwidth", 0)) audio = a
        }
        if (audio == null) {
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                if (baseUrlOf(a).isEmpty()) continue
                if (a.optString("codecs", "").startsWith("ec-3")) continue
                if (a.optString("codecs", "").startsWith("fLaC")) continue
                if (audio == null || a.optLong("bandwidth", 0) > audio.optLong("bandwidth", 0)) audio = a
            }
        }
        return Streams(video, audio)
    }

    fun baseUrlOf(o: JSONObject): String =
        o.optString("baseUrl", o.optString("base_url", ""))

    fun download(url: String, out: File, referer: String, onProgress: (Long, Long) -> Unit) {
        if (url.isEmpty()) throw IOException("下载地址为空")
        out.parentFile?.mkdirs()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Bili.UA)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败 HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (done <= 0) throw IOException("下载内容为空")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** 把视频轨、音频轨合成标准 MP4；audio 为空时只封装视频轨 */
    fun mux(video: File, audio: File?, out: File) {
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
            val vTrack = findTrack(vEx, "video/")
                ?: throw IOException("视频轨读取失败")
            val vFmt = vEx.getTrackFormat(vTrack)
            vEx.selectTrack(vTrack)

            var aTrack = -1
            var aFmt: MediaFormat? = null
            if (audio != null && audio.exists() && audio.length() > 0) {
                aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                aTrack = findTrack(aEx, "audio/") ?: -1
                if (aTrack >= 0) {
                    aFmt = aEx.getTrackFormat(aTrack)
                    aEx.selectTrack(aTrack)
                }
            }

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vFmt)
            val aOut = if (aFmt != null) muxer.addTrack(aFmt) else -1
            muxer.start()
            started = true
            copyInterleaved(vEx, vFmt, vOut, aEx, aFmt, aOut, muxer)
            muxer.stop()
            started = false
        } catch (e: Exception) {
            if (started) runCatching { muxer?.stop() }
            out.delete()
            throw e
        } finally {
            runCatching { vEx?.release() }
            runCatching { aEx?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun copyInterleaved(
        vEx: MediaExtractor,
        vFmt: MediaFormat,
        vOut: Int,
        aEx: MediaExtractor?,
        aFmt: MediaFormat?,
        aOut: Int,
        muxer: MediaMuxer
    ) {
        val vBuf = ByteBuffer.allocate(bufferSize(vFmt, 4 * 1024 * 1024))
        var vSize = vEx.readSampleData(vBuf, 0)
        var vTime = if (vSize >= 0) vEx.sampleTime else -1L

        val aBuf = if (aEx != null && aFmt != null) {
            ByteBuffer.allocate(bufferSize(aFmt, 2 * 1024 * 1024))
        } else null
        var aSize = if (aEx != null && aBuf != null) aEx.readSampleData(aBuf, 0) else -1
        var aTime = if (aSize >= 0) aEx?.sampleTime ?: -1L else -1L

        val info = MediaCodec.BufferInfo()
        while (vSize >= 0 || aSize >= 0) {
            val takeVideo = vSize >= 0 && (aSize < 0 || vTime <= aTime)
            if (takeVideo) {
                info.set(0, vSize, vTime, vEx.sampleFlags)
                muxer.writeSampleData(vOut, vBuf, info)
                vEx.advance()
                vSize = vEx.readSampleData(vBuf, 0)
                vTime = if (vSize >= 0) vEx.sampleTime else -1L
            } else if (aEx != null && aBuf != null && aOut >= 0) {
                info.set(0, aSize, aTime, aEx.sampleFlags)
                muxer.writeSampleData(aOut, aBuf, info)
                aEx.advance()
                aSize = aEx.readSampleData(aBuf, 0)
                aTime = if (aSize >= 0) aEx.sampleTime else -1L
            } else {
                break
            }
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = runCatching { fmt.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: ""
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun bufferSize(fmt: MediaFormat, fallback: Int): Int {
        val max = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else 0
        return maxOf(max, fallback).coerceAtMost(32 * 1024 * 1024)
    }

    private fun codecRank(codecs: String): Int = when {
        codecs.startsWith("avc1") -> 0
        codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> 1
        codecs.startsWith("av01") -> 2
        else -> 3
    }

    private fun safeName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
        return cleaned.take(80).ifBlank { "BiliDiy视频" }
    }
}
