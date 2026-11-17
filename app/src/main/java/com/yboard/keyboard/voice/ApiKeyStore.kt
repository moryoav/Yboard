package com.yboard.keyboard.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's OpenAI API key. Encrypted with a key that lives in the phone's Android Keystore
 * (it can't be copied off the phone), kept out of backups, and never logged.
 */
object ApiKeyStore {
    private const val FILE = "yboard_secret"
    private const val PREF = "openai_key"
    private const val ALIAS = "yboard_api_key"

    @Volatile private var cached: String? = null

    fun get(context: Context): String? {
        cached?.let { return it }
        val blob = prefs(context).getString(PREF, null) ?: return null
        return try {
            decrypt(blob).also { cached = it }
        } catch (e: Exception) {
            null  // e.g. restored on another phone: the Keystore key isn't there
        }
    }

    fun set(context: Context, key: String) {
        prefs(context).edit().putString(PREF, encrypt(key)).apply()
        cached = key
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF).apply()
        cached = null
    }

    /** Cleans up a pasted key: spaces, line breaks and quotes around it. */
    fun clean(raw: String): String = raw.trim().trim('"', '\'', '`').filterNot { it.isWhitespace() }

    /** "sk-pr…a1b2": enough to recognize the key, not enough to use it. */
    fun masked(key: String): String = if (key.length <= 12) "••••" else key.take(5) + "…" + key.takeLast(4)

    /** Text that looks like an API key or token (used to keep keys out of clipboard history). */
    fun looksLikeSecret(text: String): Boolean {
        val t = text.trim()
        if (t.length < 24 || t.any { it.isWhitespace() }) return false
        if (t.startsWith("sk-") || t.startsWith("sk_") || t.startsWith("AIza") || t.startsWith("gsk_")) return true
        // Long random-looking tokens: letters and digits mixed, no spaces.
        return t.length >= 32 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
            t.any { it.isDigit() } && t.any { it.isUpperCase() } && t.any { it.isLowerCase() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
