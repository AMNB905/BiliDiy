package com.gdk.biliapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 系统下载完成广播：校验更新包并发出安装通知 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
        if (id <= 0L) return
        UpdateDownload.complete(context.applicationContext, id)
    }
}
