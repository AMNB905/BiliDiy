package com.gdk.biliapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * 后台下载保活引导：
 * - 电池优化白名单：Android 原生接口，最通用；
 * - 自启动 / 后台运行：尽量跳到各厂商的管家页面，找不到就落到应用详情；
 * - 只在首次下载时提示一次，之后可从设置页手动进入。
 */
object BackgroundGuard {

    private const val PREF = "bilidiy_background"
    private const val KEY_LAST_PROMPT = "last_prompt_at"
    private const val PROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun statusText(context: Context): String =
        if (isIgnoringBatteryOptimizations(context)) {
            "已加入电池白名单，后台下载更稳定"
        } else {
            "系统可能限制后台运行，建议加入白名单并允许自启动"
        }

    /** 首次下载引导；用户明确处理过或近期提示过就不再打扰 */
    fun shouldPrompt(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return false
        val last = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_PROMPT, 0L)
        return System.currentTimeMillis() - last >= PROMPT_INTERVAL_MS
    }

    private fun markPrompted(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PROMPT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 后台下载保活说明。点“电池优化”或“自启动管理”都会继续走 onContinue，
     * 下载不会被这个弹窗挡住。
     */
    fun showGuide(activity: Activity, onContinue: () -> Unit = {}) {
        markPrompted(activity)
        AlertDialog.Builder(activity)
            .setTitle("防止后台下载被系统清理")
            .setMessage(
                "下载已经使用前台服务，但部分系统仍会在锁屏或切后台后限制网络。\n\n" +
                    "建议做这三步：\n" +
                    "1. 电池优化中选择“不限制 / 允许后台运行”\n" +
                    "2. 自启动管理中允许 BiliDiy 自启动\n" +
                    "3. 最近任务里给 BiliDiy 加锁，避免一键清理"
            )
            .setPositiveButton("电池优化") { _, _ ->
                openBatteryOptimization(activity)
                onContinue()
            }
            .setNeutralButton("自启动管理") { _, _ ->
                openAutoStartSettings(activity)
                onContinue()
            }
            .setNegativeButton("仍然下载") { _, _ -> onContinue() }
            .show()
    }

    /** 原生“忽略电池优化”授权页；个别系统没有该页面时退回电池优化列表 */
    fun openBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            if (request.resolveActivity(activity.packageManager) != null) {
                runCatching { activity.startActivity(request) }
                    .onSuccess { return }
            }
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (list.resolveActivity(activity.packageManager) != null) {
                runCatching { activity.startActivity(list) }
                    .onSuccess { return }
            }
        }
        openAppDetails(activity)
    }

    /** 各厂商自启动管理入口；没有匹配项时打开应用详情，用户仍可手动设置 */
    fun openAutoStartSettings(activity: Activity) {
        val candidates = listOf(
            // Xiaomi / Redmi
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Huawei / Honor
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            // OPPO / OnePlus / realme
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity",
            // Meizu
            "com.meizu.safe/.permission.SmartBGActivity",
            // Samsung
            "com.samsung.android.lool/.activity.SleepAppsActivity"
        )
        for (flat in candidates) {
            val parts = flat.split('/', limit = 2)
            if (parts.size != 2) continue
            val intent = Intent().setComponent(ComponentName(parts[0], parts[1]))
            if (intent.resolveActivity(activity.packageManager) == null) continue
            if (runCatching { activity.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(activity)
    }

    fun openAppDetails(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { Ui.toast(activity, "无法打开应用设置，请手动到系统设置中允许后台运行") }
    }
}
