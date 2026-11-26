package com.gdk.biliapp

import android.media.MediaCodecList
import org.json.JSONObject
import java.util.Locale

/**
 * B 站 DASH 音轨类型。
 *
 * B 站普通音轨在 dash.audio，杜比音轨在 dash.dolby.audio，无损音轨在
 * dash.flac.audio；三种轨道的 codecs 分别是 mp4a、ec-3、fLaC。
 */
enum class AudioKind(val key: String, val title: String, val order: Int) {
    AAC("aac", "AAC", 0),
    FLAC("flac", "FLAC", 1),
    DOLBY("dolby", "杜比", 2)
}

/** 当前视频实际可用的一条音轨。json 保留原始 playurl 条目，用于生成 MPD。 */
data class AudioTrackInfo(
    val kind: AudioKind,
    val codecs: String,
    val representationId: String,
    val label: String,
    val detail: String,
    val bandwidth: Long,
    val channels: Int,
    val sampleRate: Int,
    val supported: Boolean,
    val json: JSONObject
) {
    /** 按钮上尽量短，避免横屏 chip 被截断。 */
    val shortLabel: String
        get() = when (kind) {
            AudioKind.DOLBY -> "杜比"
            AudioKind.FLAC -> "FLAC"
            else -> "AAC"
        }
}

/**
 * 只判断本机是否存在对应的音频解码器，不尝试申请杜比授权。
 *
 * - ec-3 / ac-3 需要设备自带杜比解码器；
 * - fLaC 需要系统 FLAC 解码器（绝大多数 Android 设备都有）；
 * - AAC 始终可用。
 *
 * 这里不做“没有解码器就伪造杜比”的软件下混，避免出现能选中但无声。
 */
object AudioSupport {

    data class Probe(
        val eac3: Boolean,
        val eac3Joc: Boolean,
        val ac3: Boolean,
        val flac: Boolean
    )

    val probe: Probe by lazy { probeCodecs() }

    fun supports(kind: AudioKind, codecs: String): Boolean = when (kind) {
        AudioKind.AAC -> true
        AudioKind.FLAC -> probe.flac
        AudioKind.DOLBY -> probe.eac3 || probe.eac3Joc || probe.ac3
    }

    fun detail(
        kind: AudioKind,
        codecs: String,
        channels: Int,
        sampleRate: Int,
        bandwidth: Long
    ): String {
        val codec = when {
            codecs.startsWith("ec-3", ignoreCase = true) -> "E-AC-3"
            codecs.startsWith("ac-3", ignoreCase = true) -> "AC-3"
            codecs.startsWith("fLaC", ignoreCase = true) ||
                codecs.startsWith("flac", ignoreCase = true) -> "FLAC"
            codecs.startsWith("mp4a", ignoreCase = true) -> "AAC"
            else -> codecs.ifBlank { "未知编码" }
        }
        val channel = when (channels) {
            6 -> "5.1"
            8 -> "7.1"
            2 -> "2 声道"
            in 1..8 -> "${channels} 声道"
            else -> "多声道"
        }
        val sample = if (sampleRate > 0) " · ${sampleRate / 1000} kHz" else ""
        val bitrate = if (bandwidth > 0) " · ${formatBitrate(bandwidth)}" else ""
        return "$codec · $channel$sample$bitrate"
    }

    private fun formatBitrate(bw: Long): String =
        if (bw >= 1_000_000L) {
            String.format(Locale.US, "%.2f Mbps", bw / 1_000_000.0)
        } else {
            "${(bw / 1000L).coerceAtLeast(1)} kbps"
        }

    private fun probeCodecs(): Probe {
        val mimes = HashSet<String>()
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    mimes.add(type.lowercase(Locale.US))
                }
            }
        } catch (_: Throwable) {
            // 某些精简 ROM 的 MediaCodecList 会抛异常，按不支持处理。
        }
        return Probe(
            eac3 = "audio/eac3" in mimes,
            eac3Joc = "audio/eac3-joc" in mimes,
            ac3 = "audio/ac3" in mimes,
            flac = "audio/flac" in mimes || "audio/x-flac" in mimes
        )
    }
}
