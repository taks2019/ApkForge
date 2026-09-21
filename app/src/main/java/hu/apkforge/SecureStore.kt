package hu.apkforge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore-backed encrypted store for the short-lived GitHub
 * access token and refresh token. No Personal Access Token is stored here.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("apkforge_secure", Context.MODE_PRIVATE)
    private val alias = "apkforge.github.auth.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    var accessToken: String
        get() = decrypt(prefs.getString("access_token", "") ?: "")
        set(value) = prefs.edit().putString("access_token", encrypt(value)).apply()

    var refreshToken: String
        get() = decrypt(prefs.getString("refresh_token", "") ?: "")
        set(value) = prefs.edit().putString("refresh_token", encrypt(value)).apply()

    var expiresAtMs: Long
        get() = prefs.getLong("expires_at_ms", 0L)
        set(value) = prefs.edit().putLong("expires_at_ms", value).apply()

    var login: String
        get() = prefs.getString("login", "") ?: ""
        set(value) = prefs.edit().putString("login", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
