package com.gdk.biliapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * 轻量账户：不要密码、不要注册，设备 ID 就是身份，昵称可改。
 * 本地保存一份，能连网时同步到云端（后台可看到设备列表）。
 */
object Profile {

    private const val PREF = "bili_profile"

    /**
     * 设备 ID 尽量稳定：优先用 ANDROID_ID 做哈希（同签名、同设备、同用户下重装/清数据都不变），
     * 不再用一次性随机 UUID。极少数取不到 ANDROID_ID 的机器才退回本地缓存/随机值。
     */
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val stable = stableDeviceId(ctx)
        if (stable != null) {
            if (p.getString("device_id", null) != stable) {
                p.edit().putString("device_id", stable).apply()
                runCatching {
                    File(ctx.filesDir, "device_id").writeText(stable)
                }
            }
            return stable
        }
        val saved = p.getString("device_id", null)
        if (!saved.isNullOrBlank()) return saved
        val id = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        p.edit().putString("device_id", id).apply()
        return id
    }

    private fun stableDeviceId(ctx: Context): String? = runCatching {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$androidId|${ctx.packageName}".toByteArray(Charsets.UTF_8))
        "dev_" + digest.joinToString("") { "%02x".format(it) }.take(20)
    }.getOrNull()

    fun nickname(ctx: Context): String {
        val p = prefs(ctx)
        val saved = p.getString("nickname", null)
        if (!saved.isNullOrBlank()) return saved
        val def = "用户" + deviceId(ctx).takeLast(4)
        p.edit().putString("nickname", def).apply()
        return def
    }

    fun setNickname(ctx: Context, name: String) {
        val clean = name.trim().take(16).ifBlank { nickname(ctx) }
        prefs(ctx).edit().putString("nickname", clean).apply()
        sync(ctx)
    }

    /** 自定义头像存成固定文件，退出/重启后仍在 */
    fun avatarFile(ctx: Context): File = File(ctx.filesDir, "avatar.jpg")

    fun hasAvatar(ctx: Context): Boolean = avatarFile(ctx).let { it.exists() && it.length() > 0 }

    fun clearAvatar(ctx: Context) {
        runCatching { avatarFile(ctx).delete() }
    }

    /** 从相册选图：居中裁方、缩到 256px、压成 JPEG */
    fun setAvatar(ctx: Context, uri: Uri): Boolean = try {
        val bmp = decodeBitmap(ctx, uri) ?: return false
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(
            bmp,
            (bmp.width - side) / 2,
            (bmp.height - side) / 2,
            side,
            side
        )
        val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
        FileOutputStream(avatarFile(ctx)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (square != bmp) square.recycle()
        if (scaled != square) scaled.recycle()
        bmp.recycle()
        true
    } catch (_: Exception) {
        false
    }

    private fun decodeBitmap(ctx: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
    }

    /** 服务端签发的设备令牌，用于受保护的播放解析接口；随账户同步下发 */
    fun deviceToken(ctx: Context): String =
        SecretPrefs.get(prefs(ctx), "device_token").orEmpty()

    /** 播放解析前调用：本机还没拿到令牌时，同步注册/同步一次账户 */
    fun ensureToken(ctx: Context): String {
        val cached = deviceToken(ctx)
        if (cached.isNotBlank()) return cached
        syncNow(ctx)
        return deviceToken(ctx)
    }

    /** 尽力同步，失败不影响本地使用 */
    fun sync(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val ok = syncNow(app)
            Handler(Looper.getMainLooper()).post { done?.invoke(ok) }
        }.start()
    }

    /** 同步账户并保存服务端下发的设备令牌（同步执行，供后台线程调用） */
    private fun syncNow(ctx: Context): Boolean {
        val app = ctx.applicationContext
        var ok = false
        try {
            val c = URL(Cloud.API + "?action=profile").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", Bili.UA)
            val body = buildString {
                append("key=").append(Cloud.key())
                append("&device_id=").append(enc(deviceId(app)))
                append("&nickname=").append(enc(nickname(app)))
                append("&version=").append(enc(appVersion(app)))
                append("&model=").append(enc(Build.MANUFACTURER + " " + Build.MODEL))
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..299) {
                val json = JSONObject(text)
                val token = json.optString("device_token", "")
                if (token.isNotBlank()) {
                    SecretPrefs.put(prefs(app), "device_token", token)
                }
                ok = json.optInt("code", -1) == 0
            }
        } catch (_: Exception) {
            ok = false
        }
        prefs(app).edit().putBoolean("synced", ok).apply()
        return ok
    }

    fun synced(ctx: Context): Boolean = prefs(ctx).getBoolean("synced", false)

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
