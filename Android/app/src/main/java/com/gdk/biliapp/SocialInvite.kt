package com.gdk.biliapp

import android.content.Context
import android.content.Intent

/** App 账号邀请：只分享账号与下载地址，不携带任何 B站 Cookie。 */
object SocialInvite {

    const val DOWNLOAD_URL = "https://xiaorou.top/BILILI/download/BiliDiy-3.9.39-release.apk"

    fun text(username: String, nickname: String): String {
        val handle = username.trim().removePrefix("@")
        val name = nickname.trim().ifBlank { handle }
        return if (handle.isBlank()) {
            "我在 BiliDiy 看视频。来和我一起追番、互相分享会员。下载：$DOWNLOAD_URL"
        } else {
            "我在 BiliDiy 看视频，App 账号 @$handle（$name）。来加我好友，一起追番、互相分享会员。下载：$DOWNLOAD_URL"
        }
    }

    fun share(ctx: Context, username: String, nickname: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "邀请你一起用 BiliDiy")
            putExtra(Intent.EXTRA_TEXT, text(username, nickname))
        }
        val chooser = Intent.createChooser(send, "邀请好友一起用 BiliDiy")
        if (ctx !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }
}
