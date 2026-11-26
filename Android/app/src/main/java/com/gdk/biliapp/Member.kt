package com.gdk.biliapp

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 自己的会员激活体系。
 * 只用于「B 站标记为会员专享」的片段：没有有效激活时不允许播放；普通视频不受影响。
 */
object Member {

    private const val PREF = "bili_member"
    private const val KEY_EXPIRE = "expire_at"
    private const val KEY_PLAN = "plan"
    private const val KEY_SOURCE = "source"
    private const val KEY_SYNC = "last_sync"

    @Volatile private var expireAt: Long = 0
    @Volatile private var plan: String = ""
    @Volatile private var source: String = ""
    @Volatile private var lastSync: Long = 0

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var prompting = false

    data class Info(val active: Boolean, val expireAt: Long, val plan: String, val source: String)

    data class Entitlement(
        val active: Boolean,
        val kind: String,      // bili / app / share / none
        val expireAt: Long,
        val label: String
    )

    fun init(ctx: Context) {
        val p = prefs(ctx)
        expireAt = normalizeExpire(p.getLong(KEY_EXPIRE, 0))
        plan = p.getString(KEY_PLAN, "") ?: ""
        source = p.getString(KEY_SOURCE, "") ?: ""
        lastSync = p.getLong(KEY_SYNC, 0)
        // 兼容旧缓存：旧版把“秒”直接当成毫秒存过，读出来会变成 1970 年
        if (expireAt > 0) p.edit().putLong(KEY_EXPIRE, expireAt).apply()
    }

    /** 兼容服务端返回的秒级时间戳，统一转成毫秒 */
    private fun normalizeExpire(v: Long): Long {
        return if (v in 1L..9_999_999_999L) v * 1000L else v
    }

    fun isActive(ctx: Context): Boolean = entitlement(ctx).active

    fun entitlement(ctx: Context): Entitlement {
        initIfNeeded(ctx)
        if (!Security.isMemberEnvironmentTrusted(ctx)) {
            return Entitlement(false, "none", 0, "未激活")
        }
        val now = System.currentTimeMillis()
        val biliVip = BiliUser.isVip(ctx)
        val biliExpire = BiliUser.vipExpire(ctx)
        if (biliVip && (biliExpire <= 0L || biliExpire > now)) {
            return Entitlement(true, "bili", biliExpire, "B站大会员")
        }
        val appActive = expireAt > now
        if (appActive) {
            return Entitlement(true, "app", expireAt, planLabel(plan))
        }
        val share = MemberShare.active(ctx)
        if (share != null) {
            return Entitlement(true, "share", share.expireAt, "好友分享")
        }
        return Entitlement(false, "none", 0, "未激活")
    }

    fun info(ctx: Context): Info {
        initIfNeeded(ctx)
        val e = entitlement(ctx)
        return Info(e.active, e.expireAt, if (e.kind == "app") planLabel(plan) else e.label, e.kind)
    }

    private fun initIfNeeded(ctx: Context) {
        if (lastSync == 0L) init(ctx)
    }

    fun planLabel(p: String): String = when (p) {
        "month" -> "月卡"
        "season" -> "季卡"
        "year" -> "年卡"
        "custom" -> "自定义会员"
        "admin" -> "管理员开通"
        else -> "会员"
    }

    fun statusText(ctx: Context): String {
        val e = entitlement(ctx)
        if (!e.active) return "未激活"
        return when (e.kind) {
            "bili" -> if (e.expireAt > 0) {
                "B站大会员 · 有效期至 ${formatDate(e.expireAt)}"
            } else {
                "B站大会员 · 已解锁会员内容"
            }
            "share" -> "好友分享 · 至 ${formatDate(e.expireAt)}"
            else -> "已激活 · 有效期至 ${formatDate(e.expireAt)}"
        }
    }

    fun refresh(ctx: Context, done: ((Info) -> Unit)? = null) {
        initIfNeeded(ctx)
        // 30 秒内不重复请求，避免每次 onResume 都打一次接口
        if (lastSync > 0 && System.currentTimeMillis() - lastSync < 30_000L) {
            done?.invoke(info(ctx))
            return
        }
        val app = ctx.applicationContext
        Thread {
            var result: Info? = null
            try {
                val body = "key=${enc(Cloud.key())}&device_id=${enc(Profile.deviceId(app))}"
                val json = post("status", body)
                if (json.optInt("code", -1) == 0) {
                    result = saveMemberJson(app, json.optJSONObject("member") ?: JSONObject())
                }
            } catch (_: Exception) {
            }
            main.post { done?.invoke(result ?: info(app)) }
        }.start()
    }

    fun activate(ctx: Context, rawCode: String, done: (Boolean, String, Info?) -> Unit) {
        val code = rawCode.trim().uppercase(Locale.ROOT)
        if (code.isEmpty()) {
            done(false, "请输入激活码", null)
            return
        }
        val app = ctx.applicationContext
        Thread {
            try {
                val body = "key=${enc(Cloud.key())}&device_id=${enc(Profile.deviceId(app))}&code=${enc(code)}"
                val json = post("activate", body)
                val ok = json.optInt("code", -1) == 0
                val info = if (ok) saveMemberJson(app, json.optJSONObject("member") ?: JSONObject()) else null
                val msg = json.optString("message", if (ok) "激活成功" else "激活失败")
                main.post { done(ok, msg, info) }
            } catch (e: Exception) {
                main.post { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    /** 会员内容播放前的校验；B站大会员/好友分享/App 激活任一有效即可 */
    fun require(activity: Activity, onReady: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!Security.isMemberEnvironmentTrusted(activity)) {
            Security.blockMember(activity)
            return
        }
        if (isActive(activity)) {
            onReady()
            return
        }
        // 本地缓存可能过期：先校验 B站大会员，再拉一次好友分享
        BiliUser.refreshVip(activity, force = true) {
            if (activity.isFinishing || activity.isDestroyed) return@refreshVip
            MemberShare.refresh(activity, force = true) {
                if (activity.isFinishing || activity.isDestroyed) return@refresh
                if (isActive(activity)) onReady() else showActivation(activity, onReady = onReady)
            }
        }
    }

    fun showActivation(activity: Activity, onReady: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed || prompting) return
        prompting = true

        val et = EditText(activity).apply {
            hint = "输入激活码，例如 BD-XXXX-XXXX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(24))
            setSingleLine(true)
            setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), Ui.dp(activity, 8))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(et, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("会员激活")
            .setMessage("该内容需激活会员。绑定 B站大会员账号，或接受好友的会员分享后也可继续播放。")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("激活", null)
            .create()
        dlg.setOnDismissListener {
            prompting = false
        }
        dlg.setOnShowListener {
            val btn = dlg.getButton(DialogInterface.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val code = et.text.toString().trim()
                if (code.isEmpty()) {
                    et.error = "请输入激活码"
                    return@setOnClickListener
                }
                btn.isEnabled = false
                btn.text = "激活中…"
                activate(activity, code) { ok, msg, info ->
                    if (activity.isFinishing || activity.isDestroyed) return@activate
                    if (ok && info != null) {
                        dlg.dismiss()
                        Ui.toast(activity, "激活成功 · 有效期至 ${formatDate(info.expireAt)}")
                        onReady()
                    } else {
                        btn.isEnabled = true
                        btn.text = "激活"
                        et.error = msg
                    }
                }
            }
        }
        dlg.show()
    }

    private fun saveMemberJson(ctx: Context, m: JSONObject): Info {
        val exp = normalizeExpire(m.optLong("expire_at", 0))
        val pl = m.optString("plan", "")
        val src = m.optString("source", "")
        expireAt = exp
        plan = pl
        source = src
        lastSync = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong(KEY_EXPIRE, exp)
            .putString(KEY_PLAN, pl)
            .putString(KEY_SOURCE, src)
            .putLong(KEY_SYNC, lastSync)
            .apply()
        return Info(exp > System.currentTimeMillis(), exp, pl, src)
    }

    private fun post(action: String, body: String): JSONObject {
        val c = URL("${Cloud.API}?action=$action").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.setRequestProperty("User-Agent", Bili.UA)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = (try { c.inputStream } catch (e: Exception) { c.errorStream })
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return JSONObject(text)
    }

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
