package com.gdk.biliapp

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账户令牌、设备令牌等短文本的加密存储。
 * 密文写入 SharedPreferences，明文键立即删除；密钥由 Android Keystore 持有，不落盘。
 */
object SecretPrefs {
    private const val ALIAS = "bilidiy_secret_key_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val old = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (old != null) return old.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun get(prefs: SharedPreferences, field: String): String? {
        val encrypted = prefs.getString(field + "_enc", null)
        if (!encrypted.isNullOrBlank()) {
            try {
                val raw = Base64.decode(encrypted, Base64.NO_WRAP)
                if (raw.size > IV_BYTES) {
                    val iv = raw.copyOfRange(0, IV_BYTES)
                    val body = raw.copyOfRange(IV_BYTES, raw.size)
                    val cipher = Cipher.getInstance(TRANSFORM)
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                    return String(cipher.doFinal(body), Charsets.UTF_8)
                        .trim().takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                prefs.edit().remove(field + "_enc").apply()
            }
        }
        val legacy = prefs.getString(field, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (legacy != null) {
            put(prefs, field, legacy)
            return legacy
        }
        return null
    }

    fun put(prefs: SharedPreferences, field: String, value: String) {
        val clean = value.trim()
        prefs.edit().remove(field).apply()
        if (clean.isEmpty()) {
            prefs.edit().remove(field + "_enc").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val all = cipher.iv + body
            prefs.edit()
                .putString(field + "_enc", Base64.encodeToString(all, Base64.NO_WRAP))
                .remove(field)
                .apply()
        } catch (_: Exception) {
            // 加密不可用时不落明文，宁可让用户重新登录/同步。
            prefs.edit().remove(field).remove(field + "_enc").apply()
        }
    }

    fun remove(prefs: SharedPreferences, field: String) {
        prefs.edit().remove(field).remove(field + "_enc").apply()
    }
}
