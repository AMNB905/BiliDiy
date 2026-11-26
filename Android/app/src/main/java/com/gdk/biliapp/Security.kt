package com.gdk.biliapp

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.security.MessageDigest

/**
 * 运行时完整性校验。
 * 目标不是“绝对防破解”，而是让改包 / 调试器 / 注入框架下的激活墙更难直接复用：
 * 1. 校验安装包签名是否仍是官方证书；
 * 2. release 包不允许 debuggable；
 * 3. 检测常见注入框架与 root 环境，会员门禁在不可信环境直接拒绝。
 */
object Security {

    private const val EXPECTED_CERT_SHA256 =
        "33a8bef95a2c25ef003e49ba27a63fd79e136671d58bb405a80c917db301df9c"

    @Volatile private var checked = false
    @Volatile private var signatureOk = false
    @Volatile private var rooted = false
    @Volatile private var hooked = false

    private fun inspect(ctx: Context) {
        if (checked) return
        synchronized(this) {
            if (checked) return
            signatureOk = runCatching { verifySignature(ctx) }.getOrDefault(false)
            rooted = runCatching { detectRoot() }.getOrDefault(false)
            hooked = runCatching { detectHook() }.getOrDefault(false)
            checked = true
        }
    }

    /** 官方签名 + 非 debuggable + 无常见注入框架 */
    fun isTrusted(ctx: Context): Boolean {
        inspect(ctx)
        val debuggable = runCatching {
            (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(true)
        // 开发用的 debug 包证书不同，直接放行；release 包必须命中官方证书
        if (debuggable) return true
        return signatureOk && !hooked
    }

    /** 会员门禁额外拒绝 root 环境；普通视频不受影响 */
    fun isMemberEnvironmentTrusted(ctx: Context): Boolean {
        inspect(ctx)
        return isTrusted(ctx) && !rooted
    }

    /** 启动页调用；不可信时弹不可关闭提示并结束进程 */
    fun ensure(activity: Activity): Boolean {
        if (isTrusted(activity)) return true
        showBlocked(activity)
        return false
    }

    fun blockMember(activity: Activity) {
        showBlocked(activity)
    }

    private fun showBlocked(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("应用完整性校验失败")
            .setMessage("当前安装包签名或运行环境异常，已停止运行。请从官方渠道重新安装。")
            .setCancelable(false)
            .setPositiveButton("退出应用") { _, _ ->
                activity.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .show()
    }

    private fun verifySignature(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val expected = EXPECTED_CERT_SHA256.lowercase()
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        if (certs.isEmpty()) return false
        for (cert in certs) {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (hex == expected) return true
        }
        return false
    }

    private fun detectRoot(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu",
            "/data/adb/apd"
        )
        if (paths.any { File(it).exists() }) return true
        return Build.TAGS?.contains("test-keys") == true
    }

    private fun detectHook(): Boolean {
        return runCatching {
            val names = arrayOf(
                "de.robv.android.xposed.XposedBridge",
                "org.lsposed.lspd.core.Main",
                "com.saurik.substrate.MS"
            )
            names.any { name ->
                runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            }
        }.getOrDefault(false) || runCatching {
            val maps = File("/proc/self/maps")
            if (!maps.canRead()) return@runCatching false
            val text = maps.readText()
            listOf("frida", "xposed", "lsposed", "substrate").any { text.contains(it, ignoreCase = true) }
        }.getOrDefault(false)
    }
}
