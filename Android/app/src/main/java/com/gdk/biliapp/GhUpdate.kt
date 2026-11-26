package com.gdk.biliapp

import android.app.Activity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * App 内置更新：直接读 GitHub Releases，不再依赖云端配置里的下载地址。
 *
 * 约定（与发版脚本一致）：
 * - 仓库：AMNB905/BiliDiy（公开），读取 releases/latest；
 * - 版本号取 release 正文中的 `versionCode: NN` 标记（tag 只用来显示版本名）；
 * - 下载地址取 assets 里第一个 .apk 的 API 地址，DownloadManager 需要 Accept 头；
 * - `forceBelow: NN` 表示低于该版本必须更新。
 *
 * GitHub 不可达时只在设置页给出失败提示，不影响启动与使用。
 */
object GhUpdate {

    private const val REPO = "AMNB905/BiliDiy"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "bili_cloud"

    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var cached: Cloud.Upd? = null
    @Volatile private var forceBelow = 0
    @Volatile private var fetchedAt = 0L
    @Volatile private var lastError = ""

    /** 启动时静默检查：有新版本才弹窗 */
    fun maybePrompt(activity: Activity) {
        val c = cached
        if (c != null && System.currentTimeMillis() - fetchedAt < 30 * 60 * 1000L) {
            promptIfNewer(activity, c)
            return
        }
        io.execute {
            val upd = runCatching { fetch() }.getOrElse {
                lastError = friendly(it)
                null
            }
            if (upd != null) {
                cached = upd
                fetchedAt = System.currentTimeMillis()
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                upd?.let { promptIfNewer(activity, it) }
            }
        }
    }

    /** 设置页「检查更新」：强制拉一次并把结果文案回传 */
    fun check(activity: Activity, done: (String) -> Unit) {
        io.execute {
            val upd = runCatching { fetch() }.getOrElse {
                lastError = friendly(it)
                null
            }
            if (upd != null) {
                cached = upd
                fetchedAt = System.currentTimeMillis()
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val cur = Cloud.versionCode(activity)
                val curName = versionName(activity)
                when {
                    upd == null ->
                        done(if (lastError.isBlank()) "暂时读不到 GitHub 更新信息" else lastError)
                    upd.code <= cur -> done("已是最新版本 v$curName")
                    else -> {
                        done("发现新版本 v${upd.name.ifBlank { upd.code.toString() }} · 当前 v$curName")
                        Cloud.showUpdate(activity, forced(activity, upd))
                    }
                }
            }
        }
    }

    private fun promptIfNewer(activity: Activity, upd: Cloud.Upd) {
        if (upd.code <= Cloud.versionCode(activity)) return
        if (upd.force.not() && skipped(activity, upd.code)) return
        Cloud.showUpdate(activity, forced(activity, upd))
    }

    /** 低于 forceBelow 的版本强制更新 */
    private fun forced(activity: Activity, upd: Cloud.Upd): Cloud.Upd {
        val must = forceBelow > 0 && Cloud.versionCode(activity) < forceBelow
        return if (must && !upd.force) upd.copy(force = true) else upd
    }

    private fun skipped(activity: Activity, code: Int): Boolean =
        activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE).getInt("skip_ver", 0) == code

    private fun versionName(ctx: android.content.Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun fetch(): Cloud.Upd {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "BiliDiy")
        val http = c.responseCode
        val text = (if (http in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (http != 200) error("GitHub 返回 $http，稍后再试")

        val o = JSONObject(text)
        val tag = o.optString("tag_name")
        val name = tag.removePrefix("v").ifBlank { o.optString("name") }
        val notes = o.optString("body")
        val code = Regex("(?m)^\\s*versionCode\\s*[:=]\\s*(\\d+)")
            .find(notes)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Release 正文缺少 versionCode 标记")
        forceBelow = Regex("(?m)^\\s*forceBelow\\s*[:=]\\s*(\\d+)")
            .find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val assets = o.optJSONArray("assets") ?: error("Release 里没有资产")
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", true)) {
                apk = a
                break
            }
        }
        val asset = apk ?: error("Release 里没有 APK 资产")

        val log = notes.lineSequence()
            .filterNot {
                val t = it.trim()
                t.startsWith("versionCode") || t.startsWith("forceBelow")
            }
            .joinToString("\n")
            .trim()

        return Cloud.Upd(
            enabled = true,
            code = code,
            name = name,
            url = asset.optString("url"),
            log = log,
            force = false
        )
    }

    private fun friendly(t: Throwable): String = when {
        t.message?.contains("403") == true -> "GitHub 访问受限（403），请稍后再试"
        t.message?.contains("404") == true -> "GitHub 上还没有可用的 Release"
        else -> "检查更新失败：${t.message ?: t.javaClass.simpleName}"
    }
}
