package com.gdk.biliapp

import android.graphics.Color

/**
 * 弹幕显示样式：字号倍率、垂直显示区域、颜色策略。
 * 只影响本机显示，不上传，也不改变 B 站原始弹幕。
 */
object DanmakuStyle {
    const val AREA_FULL = "full"
    const val AREA_TOP = "top"
    const val AREA_MIDDLE = "middle"
    const val AREA_BOTTOM = "bottom"

    const val COLOR_ORIGINAL = "original"
    const val COLOR_WHITE = "white"
    const val COLOR_YELLOW = "yellow"
    const val COLOR_BLUE = "blue"
    const val COLOR_PINK = "pink"
    const val COLOR_GREEN = "green"

    data class AreaOption(val key: String, val label: String)
    data class ColorOption(val key: String, val label: String, val argb: Int)

    val areas = listOf(
        AreaOption(AREA_FULL, "全屏"),
        AreaOption(AREA_TOP, "上半屏"),
        AreaOption(AREA_MIDDLE, "中间半屏"),
        AreaOption(AREA_BOTTOM, "下半屏")
    )

    val colors = listOf(
        ColorOption(COLOR_ORIGINAL, "原色", 0),
        ColorOption(COLOR_WHITE, "纯白", Color.WHITE),
        ColorOption(COLOR_YELLOW, "暖黄", 0xFFFFE066.toInt()),
        ColorOption(COLOR_BLUE, "浅蓝", 0xFF66CCFF.toInt()),
        ColorOption(COLOR_PINK, "粉色", 0xFFFF9EC8.toInt()),
        ColorOption(COLOR_GREEN, "绿色", 0xFF7CFC98.toInt())
    )

    fun areaOrFull(key: String?): String =
        areas.firstOrNull { it.key == key }?.key ?: AREA_FULL

    fun colorOrDefault(key: String?): String =
        colors.firstOrNull { it.key == key }?.key ?: COLOR_ORIGINAL

    /** 原色弹幕里的 0 表示白色；统一色直接返回调色板颜色 */
    fun resolve(itemColor: Int, mode: String): Int {
        if (mode == COLOR_ORIGINAL) return if (itemColor == 0) Color.WHITE else itemColor
        val option = colors.firstOrNull { it.key == mode } ?: return if (itemColor == 0) Color.WHITE else itemColor
        return option.argb
    }
}
