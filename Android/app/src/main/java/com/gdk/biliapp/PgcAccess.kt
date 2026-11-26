package com.gdk.biliapp

import org.json.JSONObject

/** 判断某个 PGC 剧集是否被 B 站标记为「会员专享」 */
object PgcAccess {

    fun requiresVip(season: JSONObject, ep: JSONObject): Boolean {
        val badge = ep.optString("badge", "")
        if (badge.contains("会员")) return true
        return false
    }
}
