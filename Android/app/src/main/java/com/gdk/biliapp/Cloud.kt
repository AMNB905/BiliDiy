package com.gdk.biliapp

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 云端控制：公告 / 更新 / Cookie / 远程锁定。
 * 服务端：https://xiaorou.top/BILILI/api.php
 * 所有内容都可由管理后台上改，App 只在本地做缓存与展示。
 */
object Cloud {

    const val API = "https://xiaorou.top/BILILI/api.php"
    private const val KEY = "bili_15ee31b34c54"

    /** 账户上报等接口与配置接口共用同一个 client key */
    fun key(): String = KEY
    private const val PREF = "bili_cloud"

    data class Ann(val enabled: Boolean, val id: String, val title: String, val content: String)
    data class Upd(
        val enabled: Boolean, val code: Int, val name: String,
        val url: String, val log: String, val force: Boolean
    )
    data class Lck(val locked: Boolean, val title: String, val message: String)

    @Volatile var ann = Ann(false, "", "", "")
    @Volatile var upd = Upd(false, 0, "", "", "", false)
    @Volatile var lck = Lck(false, "服务已暂停", "")

    /** 配置更新后的回调（刷新首页公告条 / 账号文案） */
    @Volatile var onChanged: (() -> Unit)? = null

    @Volatile private var lastSync = 0L
    @Volatile private var syncing = false
    private var lockDialog: AlertDialog? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        val p = prefs(ctx)
        runCatching {
            val a = p.getString("ann", null)
            if (a != null) {
                val o = JSONObject(a)
                ann = Ann(o.optBoolean("enabled"), o.optString("id"), o.optString("title"), o.optString("content"))
            }
            val u = p.getString("upd", null)
            if (u != null) {
                val o = JSONObject(u)
                upd = Upd(
                    o.optBoolean("enabled"), o.optInt("code"), o.optString("name"),
                    o.optString("url"), o.optString("log"), o.optBoolean("force")
                )
            }
            val l = p.getString("lock", null)
            if (l != null) {
                val o = JSONObject(l)
                lck = Lck(o.optBoolean("locked"), o.optString("title"), o.optString("message"))
            }
            lastSync = p.getLong("last_sync", 0L)
        }
    }

    /** 拉取云端配置；throttle 内不重复请求 */
    fun refresh(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 30_000L) {
            done?.invoke()
            return
        }
        if (syncing) {
            done?.invoke()
            return
        }
        syncing = true
        val app = ctx.applicationContext
        io.execute {
            var ok = false
            try {
                val c = URL("$API?action=config&key=$KEY").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.setRequestProperty("User-Agent", Bili.UA)
                c.setRequestProperty("Accept", "application/json")
                val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                c.disconnect()
                val o = JSONObject(body)
                if (o.optInt("code", -1) == 0) {
                    apply(app, o)
                    // Cookie 不再随公开配置下发，改为用设备令牌单独换取
                    runCatching { fetchCookie(app) }
                    ok = true
                }
            } catch (_: Exception) {
                // 离线时保留上一次缓存
            } finally {
                syncing = false
            }
            main.post {
                if (ok) onChanged?.invoke()
                done?.invoke()
            }
        }
    }

    private fun apply(ctx: Context, o: JSONObject) {
        val p = prefs(ctx)

        val cookie = o.optString("cookie", "")
        if (cookie.isNotBlank()) {
            CookieStore.put(cookie)
            Bili.setCookie(cookie)
        }

        val a = o.optJSONObject("announcement")
        if (a != null) {
            ann = Ann(
                a.optBoolean("enabled"), a.optString("id"),
                a.optString("title"), a.optString("content")
            )
            p.edit().putString("ann", JSONObject().apply {
                put("enabled", ann.enabled); put("id", ann.id)
                put("title", ann.title); put("content", ann.content)
            }.toString()).apply()
        }

        val u = o.optJSONObject("update")
        if (u != null) {
            upd = Upd(
                u.optBoolean("enabled"), u.optInt("version_code"), u.optString("version_name"),
                u.optString("url"), u.optString("changelog"), u.optBoolean("force")
            )
            p.edit().putString("upd", JSONObject().apply {
                put("enabled", upd.enabled); put("code", upd.code); put("name", upd.name)
                put("url", upd.url); put("log", upd.log); put("force", upd.force)
            }.toString()).apply()
        }

        val l = o.optJSONObject("lock")
        if (l != null) {
            lck = Lck(l.optBoolean("locked"), l.optString("title"), l.optString("message"))
            p.edit().putString("lock", JSONObject().apply {
                put("locked", lck.locked); put("title", lck.title); put("message", lck.message)
            }.toString()).apply()
        }

        lastSync = System.currentTimeMillis()
        p.edit().putLong("last_sync", lastSync).apply()
    }

    /** 播放前刷新播放专用 Cookie（不再经过云端 playurl） */
    fun ensureCookie(ctx: Context) {
        runCatching { fetchCookie(ctx) }
    }

    private fun fetchCookie(ctx: Context) {
        val app = ctx.applicationContext
        if (!Account.isLoggedIn(app)) return
        val device = Profile.deviceId(app)
        val token = Profile.ensureToken(app)
        if (token.isBlank()) return
        val ts = System.currentTimeMillis() / 1000L
        val sig = hmac(token, "$device|$ts|cookie")
        val body = buildString {
            append("key=").append(enc(key()))
            append("&device_id=").append(enc(device))
            append("&token=").append(enc(token))
            append("&account_token=").append(enc(Account.token(app)))
            append("&ts=").append(ts)
            append("&sig=").append(enc(sig))
        }
        val c = URL("$API?action=cookie").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.setRequestProperty("User-Agent", Bili.UA)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) return
        val o = JSONObject(text)
        if (o.optInt("code", -1) != 0) return
        val cookie = o.optString("cookie", "")
        if (cookie.isNotBlank()) {
            CookieStore.put(cookie)
            Bili.setCookie(cookie)
        }
    }

    private fun hmac(secret: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- 远程锁定 ----------

    /** 已锁定时弹出不可关闭的提示并返回 true；调用方应停止后续交互 */
    fun gate(activity: Activity): Boolean {
        if (!lck.locked) return false
        showLock(activity)
        return true
    }

    private fun showLock(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (lockDialog?.isShowing == true) return

        val title = lck.title.ifBlank { "服务已暂停" }
        val msg = lck.message.ifBlank { "该应用已被远程停用，请联系管理员。" }
        val d = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("退出应用") { _, _ -> activity.finishAffinity() }
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        d.setOnDismissListener { lockDialog = null }
        d.show()
        lockDialog = d
    }

    // ---------- 云公告 ----------

    fun bannerTitle(ctx: Context): String? {
        if (!ann.enabled || ann.title.isBlank()) return null
        return if (prefs(ctx).getString("seen_ann", "") == ann.id) null else ann.title
    }

    fun markAnnSeen(ctx: Context) {
        prefs(ctx).edit().putString("seen_ann", ann.id).apply()
        onChanged?.invoke()
    }

    /** 启动时弹一次；force=true 用于用户主动点公告条 */
    fun showAnnouncement(activity: Activity, force: Boolean = false) {
        if (!ann.enabled) return
        if (ann.title.isBlank() && ann.content.isBlank()) return
        val seen = prefs(activity).getString("seen_ann", "")
        if (!force && seen == ann.id) return

        AlertDialog.Builder(activity)
            .setTitle(ann.title.ifBlank { "公告" })
            .setMessage(ann.content)
            .setPositiveButton("知道了") { _, _ ->
                if (ann.id.isNotBlank()) markAnnSeen(activity)
            }
            .setCancelable(true)
            .show()
    }

    // ---------- 云更新 ----------

    fun versionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (_: Exception) {
        0
    }

    /** 启动时静默提示新版本 */
    fun maybeUpdate(activity: Activity) {
        if (!upd.enabled || upd.code <= versionCode(activity)) return
        if (!upd.force && prefs(activity).getInt("skip_ver", 0) == upd.code) return
        showUpdate(activity)
    }

    /** 设置页“检查更新”：强制拉一次云端配置，并把结果回传给页面 */
    fun checkUpdate(activity: Activity, done: (String) -> Unit) {
        refresh(activity, force = true) {
            val vc = versionCode(activity)
            val current = versionName(activity)
            when {
                !upd.enabled -> done("云端暂未开启更新检查")
                upd.code <= vc -> done("已是最新版本 v$current")
                else -> {
                    val target = upd.name.ifBlank { upd.code.toString() }
                    done("发现新版本 v$target · 当前 v$current")
                    showUpdate(activity)
                }
            }
        }
    }

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** 更新弹窗；默认用云端配置里的 upd，GitHub 检查会传入自己的结果 */
    fun showUpdate(activity: Activity, u: Upd = upd) {
        val msg = buildString {
            if (u.name.isNotBlank()) append("新版本 v${u.name}\n\n")
            if (u.log.isNotBlank()) append(u.log)
            if (u.url.isBlank()) append("\n\n（下载地址尚未配置）")
            else append("\n\n将转入后台下载并安装，退出 App 也会继续；下载完成后点通知即可安装。")
        }.trim()

        val b = AlertDialog.Builder(activity)
            .setTitle(if (u.force) "必须更新" else "发现新版本")
            .setMessage(msg)
            .setCancelable(!u.force)
            .setPositiveButton("立即更新") { _, _ ->
                AppUpdater.request(activity, u)
            }
        if (!u.force) {
            b.setNegativeButton("稍后") { _, _ ->
                prefs(activity).edit().putInt("skip_ver", u.code).apply()
            }
        }
        b.show()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
