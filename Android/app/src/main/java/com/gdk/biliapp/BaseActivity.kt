package com.gdk.biliapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 全局沉浸式页面基类。
 *
 * 所有 Activity 都继承它：内容铺满全屏、隐藏状态栏与导航栏；
 * 从系统权限页、安装器、对话框或后台返回时，自动重新进入沉浸。
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this, Ui.isLight(this))
    }

    override fun onResume() {
        super.onResume()
        Ui.rehideSystemBars(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.rehideSystemBars(this)
    }
}
