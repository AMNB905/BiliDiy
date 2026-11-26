package com.gdk.biliapp

import android.content.Context
import org.json.JSONObject

/**
 * App 账号好友：按用户名添加，双方确认后生效。
 * 好友只用于会员分享，不会把 B站 Cookie 交给对方。
 */
object Friends {

    data class Friend(
        val username: String,
        val nickname: String,
        val since: Long = 0,
        val online: Boolean = false,
        val lastAt: Long = 0
    )

    data class Request(
        val id: String,
        val username: String,
        val nickname: String,
        val createdAt: Long,
        val incoming: Boolean
    )

    data class SearchResult(
        val username: String,
        val nickname: String,
        val friend: Boolean,
        val requested: Boolean
    )

    fun list(ctx: Context, done: (Boolean, String, List<Friend>) -> Unit) {
        Account.api(ctx, "friend_list", emptyMap()) { ok, msg, json ->
            if (!ok || json == null) {
                done(false, msg, emptyList())
                return@api
            }
            val arr = json.optJSONArray("friends")
            val out = ArrayList<Friend>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val username = o.optString("username", "")
                    if (username.isBlank()) continue
                    out.add(Friend(
                        username,
                        o.optString("nickname", username),
                        o.optLong("since", 0L) * 1000L,
                        o.optBoolean("online", false),
                        o.optLong("last_at", 0L) * 1000L
                    ))
                }
            }
            done(true, "", out)
        }
    }

    fun requests(ctx: Context, done: (Boolean, String, List<Request>) -> Unit) {
        Account.api(ctx, "friend_requests", emptyMap()) { ok, msg, json ->
            if (!ok || json == null) {
                done(false, msg, emptyList())
                return@api
            }
            val out = ArrayList<Request>()
            val incoming = json.optJSONArray("incoming")
            if (incoming != null) {
                for (i in 0 until incoming.length()) {
                    val o = incoming.optJSONObject(i) ?: continue
                    out.add(Request(
                        o.optString("id", ""),
                        o.optString("from", ""),
                        o.optString("from_nickname", ""),
                        o.optLong("created_at", 0L) * 1000L,
                        true
                    ))
                }
            }
            val outgoing = json.optJSONArray("outgoing")
            if (outgoing != null) {
                for (i in 0 until outgoing.length()) {
                    val o = outgoing.optJSONObject(i) ?: continue
                    out.add(Request(
                        o.optString("id", ""),
                        o.optString("to", ""),
                        o.optString("to_nickname", ""),
                        o.optLong("created_at", 0L) * 1000L,
                        false
                    ))
                }
            }
            done(true, "", out)
        }
    }

    fun search(ctx: Context, username: String, done: (Boolean, String, SearchResult?) -> Unit) {
        Account.api(ctx, "friend_search", mapOf("username" to username.trim())) { ok, msg, json ->
            val u = json?.optJSONObject("user")
            if (!ok || u == null) {
                done(false, msg, null)
                return@api
            }
            done(true, "", SearchResult(
                u.optString("username", username),
                u.optString("nickname", username),
                u.optBoolean("friend", false),
                u.optBoolean("requested", false)
            ))
        }
    }

    fun add(ctx: Context, username: String, done: (Boolean, String) -> Unit) {
        Account.api(ctx, "friend_request", mapOf("username" to username.trim())) { ok, msg, _ ->
            done(ok, msg.ifBlank { if (ok) "好友申请已发送" else "添加失败" })
        }
    }

    fun accept(ctx: Context, id: String, done: (Boolean, String) -> Unit) {
        Account.api(ctx, "friend_accept", mapOf("id" to id)) { ok, msg, _ ->
            done(ok, msg.ifBlank { if (ok) "已添加好友" else "处理失败" })
        }
    }

    fun reject(ctx: Context, id: String, done: (Boolean, String) -> Unit) {
        Account.api(ctx, "friend_reject", mapOf("id" to id)) { ok, msg, _ ->
            done(ok, msg.ifBlank { if (ok) "已拒绝申请" else "处理失败" })
        }
    }

    fun remove(ctx: Context, username: String, done: (Boolean, String) -> Unit) {
        Account.api(ctx, "friend_remove", mapOf("username" to username)) { ok, msg, _ ->
            done(ok, msg.ifBlank { if (ok) "已删除好友" else "删除失败" })
        }
    }
}
