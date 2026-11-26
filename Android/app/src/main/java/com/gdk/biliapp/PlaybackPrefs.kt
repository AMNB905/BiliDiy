package com.gdk.biliapp

import android.content.Context

/**
 * 播放偏好：记住用户上次选择的画质，避免每次进入视频都回到 80P。
 * 只保存在本机，不上传；换账号也不会把别人的画质偏好带过去。
 */
object PlaybackPrefs {
    private const val PREF = "bilidiy_playback"
    private const val KEY_QN = "quality_id"
    private const val KEY_LABEL = "quality_label"
    private const val KEY_FIT = "video_fit"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_DANMAKU = "danmaku_enabled"
    private const val KEY_DM_SCALE = "danmaku_scale"
    private const val KEY_DM_AREA = "danmaku_area"
    private const val KEY_DM_COLOR = "danmaku_color"
    const val DEFAULT_QN = 80
    const val DEFAULT_DM_SCALE = 1.0f
    const val AUDIO_AUTO = "auto"
    const val AUDIO_DOLBY = "dolby"
    const val AUDIO_FLAC = "flac"
    const val AUDIO_AAC = "aac"
    private val AUDIO_MODES = setOf(AUDIO_AUTO, AUDIO_DOLBY, AUDIO_FLAC, AUDIO_AAC)

    fun quality(ctx: Context): Int {
        val qn = prefs(ctx).getInt(KEY_QN, DEFAULT_QN)
        return if (qn > 0) qn else DEFAULT_QN
    }

    fun label(ctx: Context): String =
        prefs(ctx).getString(KEY_LABEL, "")?.trim().orEmpty()

    fun save(ctx: Context, qn: Int, label: String) {
        if (qn <= 0) return
        prefs(ctx).edit()
            .putInt(KEY_QN, qn)
            .putString(KEY_LABEL, label.trim())
            .apply()
    }

    /** 音轨偏好：auto / dolby / flac / aac；默认自动优先杜比。 */
    fun audioMode(ctx: Context): String {
        val mode = prefs(ctx).getString(KEY_AUDIO_MODE, AUDIO_AUTO) ?: AUDIO_AUTO
        return if (mode in AUDIO_MODES) mode else AUDIO_AUTO
    }

    fun saveAudioMode(ctx: Context, mode: String) {
        if (mode !in AUDIO_MODES) return
        prefs(ctx).edit().putString(KEY_AUDIO_MODE, mode).apply()
    }

    /** 画面比例：original / 16:9 / 4:3 / cover */
    fun videoFit(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_FIT, VideoFit.ORIGINAL) ?: VideoFit.ORIGINAL
        return if (v in VideoFit.modes) v else VideoFit.ORIGINAL
    }

    fun saveVideoFit(ctx: Context, fit: String) {
        if (fit !in VideoFit.modes) return
        prefs(ctx).edit().putString(KEY_FIT, fit).apply()
    }

    fun danmakuEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DANMAKU, true)

    fun saveDanmakuEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DANMAKU, enabled).apply()
    }

    fun danmakuScale(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_DM_SCALE, DEFAULT_DM_SCALE).coerceIn(0.7f, 1.6f)

    fun saveDanmakuScale(ctx: Context, scale: Float) {
        prefs(ctx).edit().putFloat(KEY_DM_SCALE, scale.coerceIn(0.7f, 1.6f)).apply()
    }

    fun danmakuArea(ctx: Context): String =
        DanmakuStyle.areaOrFull(prefs(ctx).getString(KEY_DM_AREA, DanmakuStyle.AREA_FULL))

    fun saveDanmakuArea(ctx: Context, area: String) {
        prefs(ctx).edit().putString(KEY_DM_AREA, DanmakuStyle.areaOrFull(area)).apply()
    }

    fun danmakuColor(ctx: Context): String =
        DanmakuStyle.colorOrDefault(prefs(ctx).getString(KEY_DM_COLOR, DanmakuStyle.COLOR_ORIGINAL))

    fun saveDanmakuColor(ctx: Context, color: String) {
        prefs(ctx).edit().putString(KEY_DM_COLOR, DanmakuStyle.colorOrDefault(color)).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_QN)
            .remove(KEY_LABEL)
            .remove(KEY_FIT)
            .remove(KEY_AUDIO_MODE)
            .remove(KEY_DANMAKU)
            .remove(KEY_DM_SCALE)
            .remove(KEY_DM_AREA)
            .remove(KEY_DM_COLOR)
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
