package com.endroid.dharness

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        val keys = getSharedPreferences("dharness_keys", MODE_PRIVATE)
        val mem = getSharedPreferences("dharness_mem", MODE_PRIVATE)
        val keysList = findViewById<TextView>(R.id.keysList)

        fun refreshKeys(store: SharedPreferences = keys) {
            val names = store.all.keys.sorted()
            keysList.text = if (names.isEmpty()) "(no keys)" else names.joinToString("\n") { "• $it" }
        }

        findViewById<TextView>(R.id.versionText).text =
            try {
                "v${packageManager.getPackageInfo(packageName, 0).versionName}"
            } catch (_: Exception) {
                ""
            }

        val patInput = findViewById<EditText>(R.id.patInput)
        if (keys.contains("github") || keys.contains("github_pat")) {
            patInput.hint = "PAT saved (enter new to replace)"
        }
        findViewById<Button>(R.id.btnSavePat).setOnClickListener {
            val v = patInput.text.toString().trim()
            if (v.isNotEmpty()) {
                keys.edit().putString("github", v).putString("github_pat", v).apply()
                patInput.setText("")
                patInput.hint = "PAT saved"
                Toast.makeText(this, "GitHub PAT saved", Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
        }

        val swDesktop = findViewById<SwitchCompat>(R.id.switchDesktop)
        val swInject = findViewById<SwitchCompat>(R.id.switchInject)
        val swDedupe = findViewById<SwitchCompat>(R.id.switchDedupe)
        swDesktop.isChecked = prefs.getBoolean("desktop", false)
        swInject.isChecked = prefs.getBoolean("auto_inject", true)
        swDedupe.isChecked = prefs.getBoolean("dedupe", true)
        fun persist() {
            prefs.edit()
                .putBoolean("desktop", swDesktop.isChecked)
                .putBoolean("auto_inject", swInject.isChecked)
                .putBoolean("dedupe", swDedupe.isChecked)
                .apply()
        }
        swDesktop.setOnCheckedChangeListener { _, _ -> persist() }
        swInject.setOnCheckedChangeListener { _, _ -> persist() }
        swDedupe.setOnCheckedChangeListener { _, _ -> persist() }

        val keyName = findViewById<EditText>(R.id.keyName)
        val keyValue = findViewById<EditText>(R.id.keyValue)
        refreshKeys()

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val n = keyName.text.toString().trim()
            val v = keyValue.text.toString()
            if (n.isNotEmpty() && v.isNotEmpty()) {
                keys.edit().putString(n, v).apply()
                keyValue.setText("")
                refreshKeys()
            }
        }
        findViewById<Button>(R.id.btnDeleteKey).setOnClickListener {
            val n = keyName.text.toString().trim()
            if (n.isNotEmpty()) {
                keys.edit().remove(n).apply()
                refreshKeys()
            }
        }

        findViewById<Button>(R.id.btnSendInstructions).setOnClickListener {
            prefs.edit().putBoolean("pending_send_instructions", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            prefs.edit().putBoolean("pending_reload", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnReinject).setOnClickListener {
            prefs.edit().putBoolean("pending_inject", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            mem.edit().clear().apply()
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearFs).setOnClickListener {
            val root = java.io.File(filesDir, "harness_fs")
            root.deleteRecursively()
            root.mkdirs()
            Toast.makeText(this, "FS cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.toolsList).text = """
            memory.* = agent scratchpad
            keys.* = secrets (PAT) — never print values
            http_request / fetch_url = headers supported
            github.* = needs PAT key github
            No shell/exec on device (safety)
        """.trimIndent()

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
    }
}
