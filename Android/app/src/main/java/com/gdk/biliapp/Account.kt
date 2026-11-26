package com.gdk.biliapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 登录账户：账号 + 密码 + 邀请码注册。
 * 设备 ID 仍然作为设备身份，服务端把账号会话绑定到设备；
 * 播放解析接口同时要求设备 token + 账号 token，未登录不能解析。
 */
object Account {

    private const val PREF = "bili_account"
    private const val K_TOKEN = "token"
    private const val K_USER = "username"
    private const val K_NICK = "nickname"
    private const val K_EXPIRE = "expire_at"
    private const val K_QUOTA = "invite_quota"
    private const val K_CAN = "can_invite"
    private const val K_INVITED_BY = "invited_by"

    class AccountError(
        val serverCode: Int,
        val httpStatus: Int,
        message: String
    ) : Exception(message)

    data class Info(
        val username: String,
        val nickname: String,
        val expireAt: Long,
        val inviteQuota: Int = 0,
        val canInvite: Boolean = false,
        val invitedBy: String = ""
    )

    data class InviteResult(val code: String, val quota: Int)

    data class InviteItem(
        val code: String,
        val uses: Int,
        val maxUses: Int,
        val expireAt: Long,
        val enabled: Boolean,
        val usedUsers: List<String>
    )

    fun init(ctx: Context) {
        val p = prefs(ctx)
        val exp = normalizeExpire(p.getLong(K_EXPIRE, 0))
        if (exp > 0 && exp != p.getLong(K_EXPIRE, 0)) p.edit().putLong(K_EXPIRE, exp).apply()
    }

    private fun normalizeExpire(v: Long): Long =
        if (v in 1L..9_999_999_999L) v * 1000L else v

    fun token(ctx: Context): String = SecretPrefs.get(prefs(ctx), K_TOKEN).orEmpty()

    fun username(ctx: Context): String = prefs(ctx).getString(K_USER, "") ?: ""

    fun nickname(ctx: Context): String = prefs(ctx).getString(K_NICK, "") ?: ""

    fun expireAt(ctx: Context): Long = normalizeExpire(prefs(ctx).getLong(K_EXPIRE, 0))

    fun inviteQuota(ctx: Context): Int = prefs(ctx).getInt(K_QUOTA, 0).coerceIn(0, 5)

    fun canInvite(ctx: Context): Boolean = prefs(ctx).getBoolean(K_CAN, false)

    fun invitedBy(ctx: Context): String = prefs(ctx).getString(K_INVITED_BY, "") ?: ""

    fun isLoggedIn(ctx: Context): Boolean =
        token(ctx).isNotBlank() && expireAt(ctx) > System.currentTimeMillis()

    fun info(ctx: Context): Info =
        Info(username(ctx), nickname(ctx), expireAt(ctx), inviteQuota(ctx), canInvite(ctx), invitedBy(ctx))

    fun login(ctx: Context, user: String, password: String, done: (Boolean, String, Info?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val json = post(
                    "account_login",
                    mapOf(
                        "username" to user.trim(),
                        "password" to password,
                        "device_id" to Profile.deviceId(app)
                    )
                )
                save(app, json)
                main { done(true, json.optString("message", "登录成功"), info(app)) }
            } catch (e: AccountError) {
                main { done(false, e.message ?: "登录失败", null) }
            } catch (e: Exception) {
                main { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    fun register(
        ctx: Context,
        invite: String,
        user: String,
        password: String,
        nickname: String,
        done: (Boolean, String, Info?) -> Unit
    ) {
        val app = ctx.applicationContext
        Thread {
            try {
                val json = post(
                    "account_register",
                    mapOf(
                        "invite" to invite.trim().uppercase(),
                        "username" to user.trim(),
                        "password" to password,
                        "nickname" to nickname.trim(),
                        "device_id" to Profile.deviceId(app)
                    )
                )
                save(app, json)
                main { done(true, json.optString("message", "注册成功"), info(app)) }
            } catch (e: AccountError) {
                main { done(false, e.message ?: "注册失败", null) }
            } catch (e: Exception) {
                main { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    /** 生成一个邀请码；prefix 可自定义，每个账户最多 5 个名额 */
    fun generateInvite(ctx: Context, prefix: String, done: (Boolean, String, InviteResult?) -> Unit) {
        val app = ctx.applicationContext
        val t = token(app)
        if (t.isBlank() || !isLoggedIn(app)) {
            done(false, "请先登录", null)
            return
        }
        if (!canInvite(app)) {
            done(false, "该账号没有邀请资格", null)
            return
        }
        if (inviteQuota(app) <= 0) {
            done(false, "邀请名额已用完", null)
            return
        }
        val p = prefix.trim().uppercase().replace(Regex("[^A-Z0-9]"), "").take(8).ifBlank { "INV" }
        Thread {
            try {
                val json = post(
                    "account_invite_gen",
                    mapOf(
                        "account_token" to t,
                        "device_id" to Profile.deviceId(app),
                        "prefix" to p
                    )
                )
                val code = json.optString("invite", "")
                val quota = json.optInt("invite_quota", inviteQuota(app) - 1).coerceIn(0, 5)
                if (code.isBlank()) throw AccountError(500, 200, "服务端未返回邀请码")
                prefs(app).edit()
                    .putInt(K_QUOTA, quota)
                    .putBoolean(K_CAN, json.optBoolean("can_invite", true))
                    .apply()
                main { done(true, "邀请码已生成", InviteResult(code, quota)) }
            } catch (e: AccountError) {
                main { done(false, e.message ?: "生成失败", null) }
            } catch (e: Exception) {
                main { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    /** 查看当前账户生成的邀请码及使用情况 */
    fun listInvites(ctx: Context, done: (Boolean, String, List<InviteItem>?) -> Unit) {
        val app = ctx.applicationContext
        val t = token(app)
        if (t.isBlank() || !isLoggedIn(app)) {
            done(false, "请先登录", null)
            return
        }
        Thread {
            try {
                val json = post(
                    "account_invites",
                    mapOf("account_token" to t, "device_id" to Profile.deviceId(app))
                )
                val arr = json.optJSONArray("invites")
                val list = ArrayList<InviteItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val users = ArrayList<String>()
                        val ua = o.optJSONArray("used_users")
                        if (ua != null) for (j in 0 until ua.length()) users.add(ua.optString(j, ""))
                        list.add(
                            InviteItem(
                                code = o.optString("code", ""),
                                uses = o.optInt("uses", 0),
                                maxUses = o.optInt("max_uses", 1),
                                expireAt = o.optLong("expire_at", 0) * 1000L,
                                enabled = o.optBoolean("enabled", false),
                                usedUsers = users
                            )
                        )
                    }
                }
                val quota = json.optInt("invite_quota", inviteQuota(app)).coerceIn(0, 5)
                prefs(app).edit().putInt(K_QUOTA, quota).apply()
                main { done(true, "已加载", list) }
            } catch (e: AccountError) {
                main { done(false, e.message ?: "加载失败", null) }
            } catch (e: Exception) {
                main { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    /** 校验服务端会话；失效时清掉本地账号 */
    fun refresh(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val t = token(app)
        if (t.isBlank()) {
            done?.invoke(false)
            return
        }
        Thread {
            var ok = false
            try {
                val json = post(
                    "account_me",
                    mapOf("account_token" to t, "device_id" to Profile.deviceId(app))
                )
                val exp = normalizeExpire(json.optLong("account_expires_at", 0))
                val acc = json.optJSONObject("account")
                if (exp > 0) {
                    prefs(app).edit().putLong(K_EXPIRE, exp).apply()
                }
                if (acc != null) {
                    prefs(app).edit()
                        .putString(K_USER, acc.optString("username", username(app)))
                        .putString(K_NICK, acc.optString("nickname", nickname(app)))
                        .putInt(K_QUOTA, acc.optInt("invite_quota", inviteQuota(app)).coerceIn(0, 5))
                        .putBoolean(K_CAN, acc.optBoolean("can_invite", canInvite(app)))
                        .putString(K_INVITED_BY, acc.optString("invited_by", invitedBy(app)))
                        .apply()
                }
                ok = true
            } catch (e: AccountError) {
                if (e.httpStatus == 401 || e.serverCode == 401 || e.serverCode == 403) {
                    clearLocal(app)
                    CookieStore.clear()
                    Bili.clearCookie()
                }
            } catch (_: Exception) {
                // 网络异常保留本地会话，避免离线时被登出
            }
            main { done?.invoke(ok) }
        }.start()
    }

    /** 通用账号接口（后台线程 + 主线程回调）；自动带 account_token 与 device_id */
    fun api(ctx: Context, action: String, fields: Map<String, String>,
            done: (Boolean, String, JSONObject?) -> Unit) {
        val app = ctx.applicationContext
        val t = token(app)
        if (t.isBlank() || !isLoggedIn(app)) {
            done(false, "请先登录账号", null)
            return
        }
        Thread {
            try {
                val json = post(action, fields + mapOf(
                    "account_token" to t,
                    "device_id" to Profile.deviceId(app)
                ))
                main { done(true, json.optString("message", ""), json) }
            } catch (e: AccountError) {
                main { done(false, e.message ?: "请求失败", null) }
            } catch (e: Exception) {
                main { done(false, e.message ?: "网络异常", null) }
            }
        }.start()
    }

    /** 同步版，供已经在后台线程的播放链路调用 */
    fun apiSync(ctx: Context, action: String, fields: Map<String, String>): JSONObject {
        val app = ctx.applicationContext
        val t = token(app)
        if (t.isBlank() || !isLoggedIn(app)) throw AccountError(401, 401, "请先登录账号")
        return post(action, fields + mapOf(
            "account_token" to t,
            "device_id" to Profile.deviceId(app)
        ))
    }

    fun logout(ctx: Context) {
        val app = ctx.applicationContext
        val t = token(app)
        MemberShare.revokeAll(app)
        clearLocal(app)
        MemberShare.resetLocal(app)
        CookieStore.clear()
        Bili.clearCookie()
        BiliUser.clear(app)
        if (t.isNotBlank()) {
            Thread {
                runCatching { post("account_logout", mapOf("account_token" to t, "device_id" to Profile.deviceId(app))) }
            }.start()
        }
    }

    private fun save(ctx: Context, json: JSONObject) {
        val token = json.optString("account_token", "")
        val acc = json.optJSONObject("account") ?: JSONObject()
        val exp = normalizeExpire(json.optLong("expires_at", 0))
        if (token.isBlank() || exp <= 0) throw AccountError(500, 200, "服务端返回的登录信息不完整")
        SecretPrefs.put(prefs(ctx), K_TOKEN, token)
        prefs(ctx).edit()
            .putString(K_USER, acc.optString("username", ""))
            .putString(K_NICK, acc.optString("nickname", ""))
            .putLong(K_EXPIRE, exp)
            .putInt(K_QUOTA, acc.optInt("invite_quota", 0).coerceIn(0, 5))
            .putBoolean(K_CAN, acc.optBoolean("can_invite", false))
            .putString(K_INVITED_BY, acc.optString("invited_by", ""))
            .apply()
        val nick = acc.optString("nickname", "")
        if (nick.isNotBlank()) Profile.setNickname(ctx, nick)
        // 换账号后旧的好友分享缓存立即作废，服务端会重新按当前账号下发
        MemberShare.resetLocal(ctx)
    }

    private fun clearLocal(ctx: Context) {
        SecretPrefs.remove(prefs(ctx), K_TOKEN)
        prefs(ctx).edit()
            .remove(K_TOKEN).remove(K_USER).remove(K_NICK).remove(K_EXPIRE)
            .remove(K_QUOTA).remove(K_CAN).remove(K_INVITED_BY)
            .apply()
    }

    private fun post(action: String, fields: Map<String, String>): JSONObject {
        val body = buildString {
            append("key=").append(enc(Cloud.key()))
            for ((k, v) in fields) append("&").append(enc(k)).append("=").append(enc(v))
        }
        val c = URL("${Cloud.API}?action=$action").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.setRequestProperty("User-Agent", Bili.UA)
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val http = c.responseCode
        val text = (if (http in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw AccountError(0, http, "服务端返回异常（HTTP $http）")
        }
        val code = json.optInt("code", -1)
        if (code != 0 || http !in 200..299) {
            throw AccountError(code, http, json.optString("message", "请求失败（HTTP $http）"))
        }
        return json
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun main(block: () -> Unit) = handler.post(block)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
