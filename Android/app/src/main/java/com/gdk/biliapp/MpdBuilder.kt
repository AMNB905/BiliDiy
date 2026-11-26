package com.gdk.biliapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/**
 * 把 playurl 的 DASH JSON 生成为 MPD 清单，供 ExoPlayer 的 DashMediaSource 使用。
 *
 * 选流策略（实测要点）：
 * - 请求 127 时 B 站可能回 126（杜比视界 / dvh1），这类流在多数手机解不了，优先 avc1 > hev1/hvc1 > av01 > dvh1
 * - 画质以服务端实际返回的 quality 为准（可能与请求档位不一致）
 * - 音频不再只取 AAC：普通音轨、杜比音轨、FLAC 无损音轨都写入同一个音频
 *   AdaptationSet，由播放页按设备解码能力选择；AAC 始终作为兜底。
 */
object MpdBuilder {

    data class Result(val audioTracks: List<AudioTrackInfo>)

    private fun codecRank(codecs: String): Int = when {
        codecs.startsWith("avc1") -> 0
        codecs.startsWith("hev1") || codecs.startsWith("hvc1") -> 1
        codecs.startsWith("av01") -> 2
        else -> 3   // dvhe / dvh1 等
    }

    private fun baseUrlOf(o: JSONObject): String =
        o.optString("baseUrl", o.optString("base_url", ""))

    private fun intOf(o: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            val value = o.optInt(key, 0)
            if (value > 0) return value
        }
        return 0
    }

    private fun audioKindOf(codecs: String, forced: AudioKind?): AudioKind {
        if (forced != null) return forced
        return when {
            codecs.startsWith("ec-3", ignoreCase = true) ||
                codecs.startsWith("ac-3", ignoreCase = true) -> AudioKind.DOLBY
            codecs.startsWith("fLaC", ignoreCase = true) ||
                codecs.startsWith("flac", ignoreCase = true) -> AudioKind.FLAC
            else -> AudioKind.AAC
        }
    }

    private fun appendAudioObjects(target: MutableList<JSONObject>, value: Any?) {
        when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    value.optJSONObject(i)?.let { target.add(it) }
                }
            }
            is JSONObject -> target.add(value)
        }
    }

    /**
     * 解析 dash.audio、dash.dolby.audio、dash.flac.audio。
     *
     * B 站不同接口版本里，dash.flac.audio 可能是对象而不是数组，
     * 所以这里同时兼容 JSONObject / JSONArray。
     */
    private fun collectAudioTracks(dash: JSONObject): List<AudioTrackInfo> {
        val raw = ArrayList<Pair<JSONObject, AudioKind?>>()

        val normal = ArrayList<JSONObject>()
        appendAudioObjects(normal, dash.opt("audio"))
        normal.forEach { raw.add(it to null) }

        val dolby = ArrayList<JSONObject>()
        appendAudioObjects(dolby, dash.optJSONObject("dolby")?.opt("audio"))
        dolby.forEach { raw.add(it to AudioKind.DOLBY) }

        val flac = ArrayList<JSONObject>()
        appendAudioObjects(flac, dash.optJSONObject("flac")?.opt("audio"))
        flac.forEach { raw.add(it to AudioKind.FLAC) }

        val best = LinkedHashMap<AudioKind, AudioTrackInfo>()
        for ((json, forced) in raw) {
            if (baseUrlOf(json).isEmpty()) continue
            val codecs = json.optString("codecs", "")
            val kind = audioKindOf(codecs, forced)
            val bandwidth = json.optLong("bandwidth", 0L)
            val channels = intOf(json, "channels", "audioChannels", "channelCount").let {
                if (it > 0) it else when (kind) {
                    AudioKind.DOLBY -> 6
                    else -> 2
                }
            }
            val sampleRate = intOf(json, "sampleRate", "audioSamplingRate", "sample_rate").let {
                if (it > 0) it else when (kind) {
                    AudioKind.DOLBY, AudioKind.FLAC -> 48000
                    else -> 44100
                }
            }
            val supported = AudioSupport.supports(kind, codecs)
            val detail = AudioSupport.detail(kind, codecs, channels, sampleRate, bandwidth)
            val info = AudioTrackInfo(
                kind = kind,
                codecs = codecs,
                representationId = kind.key,
                label = kind.title,
                detail = detail,
                bandwidth = bandwidth,
                channels = channels,
                sampleRate = sampleRate,
                supported = supported,
                json = json
            )
            val old = best[kind]
            if (old == null || betterAudio(info, old)) best[kind] = info
        }

        return best.values.sortedBy { it.kind.order }
    }

    /** 同类型音轨有多条时：优先设备可解码，其次码率更高、声道更多。 */
    private fun betterAudio(candidate: AudioTrackInfo, current: AudioTrackInfo): Boolean {
        if (candidate.supported != current.supported) return candidate.supported
        if (candidate.bandwidth != current.bandwidth) return candidate.bandwidth > current.bandwidth
        return candidate.channels > current.channels
    }

    /**
     * @param preferQuality 期望画质 id（一般传 playurl 返回的 quality）
     * @return 非 null 表示已写出 MPD；无可用视频流时返回 null，由调用方走 MP4 兜底
     */
    fun build(dash: JSONObject, preferQuality: Int, timelengthMs: Long, out: File): Result? {
        val videos = dash.optJSONArray("video") ?: return null
        if (videos.length() == 0) return null

        val candidates = ArrayList<Pair<JSONObject, Int>>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (baseUrlOf(v).isEmpty()) continue
            candidates.add(v to codecRank(v.optString("codecs", "")))
        }
        if (candidates.isEmpty()) return null

        // 有非杜比流时先排除杜比视界，避免设备解不了黑屏
        val usable = candidates.filter { it.second < 3 }.ifEmpty { candidates }

        val ids = usable.map { it.first.optInt("id", 0) }.distinct().sorted()
        val targetId = when {
            ids.contains(preferQuality) -> preferQuality
            ids.any { it <= preferQuality } -> ids.last { it <= preferQuality }
            else -> ids.first()
        }
        val group = usable.filter { it.first.optInt("id", 0) == targetId }
        val chosen = group.sortedWith(
            compareBy<Pair<JSONObject, Int>> { it.second }
                .thenByDescending { it.first.optLong("bandwidth", 0) }
        ).first().first

        val audioTracks = collectAudioTracks(dash)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
        sb.append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
        if (timelengthMs > 0) sb.append("mediaPresentationDuration=\"PT").append(timelengthMs / 1000.0).append("S\" ")
        sb.append("minBufferTime=\"2.0\">\n<Period id=\"0\">\n")

        sb.append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\" segmentAlignment=\"true\">\n")
        sb.append(videoRepresentation(chosen, "v"))
        sb.append("</AdaptationSet>\n")

        if (audioTracks.isNotEmpty()) {
            sb.append("<AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"audio/mp4\" segmentAlignment=\"true\" lang=\"und\">\n")
            for (track in audioTracks) {
                sb.append(audioRepresentation(track))
            }
            sb.append("</AdaptationSet>\n")
        }

        sb.append("</Period>\n</MPD>\n")
        out.writeText(sb.toString())
        return Result(audioTracks)
    }

    private fun xmlEscapeUrl(u: String): String =
        u.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun videoRepresentation(r: JSONObject, id: String): String {
        val sb = StringBuilder()
        val segBase = r.optJSONObject("segment_base") ?: r.optJSONObject("SegmentBase")
        val init = segBase?.optString("initialization", "") ?: ""
        val index = segBase?.optString("index_range", "") ?: ""
        val codec = r.optString("codecs", "")
        val bw = r.optLong("bandwidth", 0)

        sb.append("<Representation id=\"").append(id).append("\" bandwidth=\"").append(bw)
            .append("\" codecs=\"").append(codec).append("\"")
        sb.append(" width=\"").append(r.optInt("width", 0))
            .append("\" height=\"").append(r.optInt("height", 0)).append("\"")
        val fr = r.optString("frameRate", r.optString("frame_rate", ""))
        if (fr.isNotEmpty()) sb.append(" frameRate=\"").append(fr).append("\"")
        sb.append(">\n")
        sb.append("<BaseURL>").append(xmlEscapeUrl(baseUrlOf(r))).append("</BaseURL>\n")
        if (init.isNotEmpty() && index.isNotEmpty()) {
            sb.append("<SegmentBase indexRange=\"").append(index).append("\">")
            sb.append("<Initialization range=\"").append(init).append("\"/>")
            sb.append("</SegmentBase>\n")
        }
        sb.append("</Representation>\n")
        return sb.toString()
    }

    private fun audioRepresentation(track: AudioTrackInfo): String {
        val r = track.json
        val segBase = r.optJSONObject("segment_base") ?: r.optJSONObject("SegmentBase")
        val init = segBase?.optString("initialization", "") ?: ""
        val index = segBase?.optString("index_range", "") ?: ""
        val codec = track.codecs
        val bw = track.bandwidth

        val sb = StringBuilder()
        sb.append("<Representation id=\"").append(track.representationId).append("\" bandwidth=\"").append(bw)
            .append("\" codecs=\"").append(codec).append("\" audioSamplingRate=\"")
            .append(track.sampleRate).append("\">\n")
        sb.append("<AudioChannelConfiguration ")
            .append("schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"")
            .append(track.channels).append("\"/>\n")
        sb.append("<BaseURL>").append(xmlEscapeUrl(baseUrlOf(r))).append("</BaseURL>\n")
        if (init.isNotEmpty() && index.isNotEmpty()) {
            sb.append("<SegmentBase indexRange=\"").append(index).append("\">")
            sb.append("<Initialization range=\"").append(init).append("\"/>")
            sb.append("</SegmentBase>\n")
        }
        sb.append("</Representation>\n")
        return sb.toString()
    }
}
