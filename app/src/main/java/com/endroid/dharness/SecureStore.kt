package com.endroid.dharness

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

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
            val prefs = openEncrypted(app)
            migrateInto(app, prefs)
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
     * Opens the encrypted store. If the file and the Keystore master key are out of sync
     * (restored backup, Keystore reset by the OS) the stored secrets are unreadable and
     * every launch would crash, so reset the store once and start empty.
     */
    private fun openEncrypted(context: Context): SharedPreferences =
        try {
            createEncrypted(context)
        } catch (e: Exception) {
            Log.e(TAG, "encrypted store unreadable, resetting it", e)
            resetEncrypted(context)
            createEncrypted(context)
        }

    private fun resetEncrypted(context: Context) {
        try {
            context.deleteSharedPreferences(ENC_FILE)
        } catch (e: Exception) {
            Log.w(TAG, "delete encrypted prefs: ${e.message}")
        }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "delete master key: ${e.message}")
        }
    }

    /** Store a secret key. Rejects blank keys to prevent accidental overwrites. */
    fun putKey(context: Context, name: String, value: String): Boolean {
        if (name.isBlank() || value.isBlank()) {
            Log.w(TAG, "Refusing to store blank key name or value")
            return false
        }
        keys(context).edit().putString(name.trim(), value.trim()).apply()
        return true
    }

    /** Retrieve a secret key by name. */
    fun getKey(context: Context, name: String): String? {
        if (name.isBlank()) return null
        return keys(context).getString(name.trim(), null)
    }

    /** Remove a secret key. */
    fun removeKey(context: Context, name: String): Boolean {
        if (name.isBlank()) return false
        return keys(context).edit().remove(name.trim()).commit()
    }

    /** List all stored key names (not values, for security). */
    fun listKeyNames(context: Context): Set<String> {
        return keys(context).all.keys
    }

    /** Opens the store and runs the one-time migration if it has not run yet. */
    fun migrateKeysIfNeeded(context: Context) {
        keys(context)
    }

    /**
     * One-time copy from plaintext `dharness_keys` (+ stray settings github_pat)
     * into the encrypted store, then wipe the plaintext. The plaintext is only wiped
     * after the encrypted copy has been written to disk.
     */
    private fun migrateInto(context: Context, enc: SharedPreferences) {
        val settings = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (settings.getBoolean(MIGRATE_FLAG, false)) return

        try {
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

            if (!editor.commit()) {
                Log.e(TAG, "keys migration: could not write encrypted store; keeping plaintext")
                return // flag not set, retry next launch
            }

            if (legacy.all.isNotEmpty()) {
                legacy.edit().clear().commit()
                try {
                    context.deleteSharedPreferences(LEGACY_FILE)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteSharedPreferences legacy: ${e.message}")
                }
            }
            if (!stray.isNullOrBlank()) {
                settings.edit().remove("github_pat").commit()
            }

            settings.edit().putBoolean(MIGRATE_FLAG, true).apply()
            Log.i(TAG, "keys migration done, entries~$migrated")
        } catch (e: Exception) {
            Log.e(TAG, "keys migration failed", e)
            // Do not set flag; retry next launch
        }
    }
}
