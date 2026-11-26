package com.gdk.biliapp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** 全局固定浅色主题，不跟随系统深色 */
class BiliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
