package com.endroid.dharness

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted at-rest store for secrets (GitHub PAT, custom keys).
 * Uses Android Keystore via EncryptedSharedPreferences.
 */
object SecureStore {
    private const val TAG = "SecureStore"
    private const val ENC_FILE = "dharness_keys_enc"
    private const val LEGACY_FILE = "dharness_keys"
    private const val SETTINGS_FILE = "dharness_settings"
    private const val MIGRATE_FLAG = "keys_migrated_v1"

    @Volatile
    private var cached: SharedPreferences? = null

    fun keys(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            migrateKeysIfNeeded(app)
            val prefs = createEncrypted(app)
            cached = prefs
            return prefs
        }
    }

    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENC_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * One-time copy from plaintext `dharness_keys` (+ stray settings github_pat)
     * into encrypted store, then wipe plaintext.
     */
    fun migrateKeysIfNeeded(context: Context) {
        val settings = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (settings.getBoolean(MIGRATE_FLAG, false)) return

        try {
            val enc = createEncrypted(context)
            val editor = enc.edit()
            var migrated = 0

            val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            for ((k, v) in legacy.all) {
                if (v is String && v.isNotEmpty()) {
                    editor.putString(k, v)
                    migrated++
                }
            }

            // Stray plaintext PAT in settings
            val stray = settings.getString("github_pat", null)
            if (!stray.isNullOrBlank()) {
                if (!enc.contains("github") && !enc.contains("github_pat")) {
                    editor.putString("github", stray)
                    editor.putString("github_pat", stray)
                    migrated++
                }
            }

            editor.apply()

            if (legacy.all.isNotEmpty()) {
                legacy.edit().clear().apply()
                try {
                    context.deleteSharedPreferences(LEGACY_FILE)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteSharedPreferences legacy: ${e.message}")
                }
            }
            if (!stray.isNullOrBlank()) {
                settings.edit().remove("github_pat").apply()
            }

            settings.edit().putBoolean(MIGRATE_FLAG, true).apply()
            Log.i(TAG, "keys migration done, entries≈$migrated")
        } catch (e: Exception) {
            Log.e(TAG, "keys migration failed", e)
            // Do not set flag — retry next launch
        }
    }
}
