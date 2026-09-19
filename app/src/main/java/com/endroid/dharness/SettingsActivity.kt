package com.endroid.dharness

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        val keys = getSharedPreferences("dharness_keys", MODE_PRIVATE)
        val mem = getSharedPreferences("dharness_mem", MODE_PRIVATE)

        findViewById<TextView>(R.id.versionText).text =
            try {
                val p = packageManager.getPackageInfo(packageName, 0)
                "v${p.versionName} · ${packageName}"
            } catch (_: Exception) {
                packageName
            }

        val swDesktop = findViewById<SwitchCompat>(R.id.switchDesktop)
        val swInject = findViewById<SwitchCompat>(R.id.switchInject)
        val swDedupe = findViewById<SwitchCompat>(R.id.switchDedupe)
        swDesktop.isChecked = prefs.getBoolean("desktop", false)
        swInject.isChecked = prefs.getBoolean("auto_inject", true)
        swDedupe.isChecked = prefs.getBoolean("dedupe", true)

        fun persistToggles() {
            prefs.edit()
                .putBoolean("desktop", swDesktop.isChecked)
                .putBoolean("auto_inject", swInject.isChecked)
                .putBoolean("dedupe", swDedupe.isChecked)
                .apply()
        }
        swDesktop.setOnCheckedChangeListener { _, _ -> persistToggles() }
        swInject.setOnCheckedChangeListener { _, _ -> persistToggles() }
        swDedupe.setOnCheckedChangeListener { _, _ -> persistToggles() }

        val keyName = findViewById<EditText>(R.id.keyName)
        val keyValue = findViewById<EditText>(R.id.keyValue)
        val keysList = findViewById<TextView>(R.id.keysList)

        fun refreshKeys() {
            val names = keys.all.keys.sorted()
            keysList.text = if (names.isEmpty()) "(no keys)" else names.joinToString("\n") { "• $it" }
        }
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

        findViewById<Button>(R.id.btnReload).setOnClickListener {
            prefs.edit().putBoolean("pending_reload", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnReinject).setOnClickListener {
            prefs.edit().putBoolean("pending_inject", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            prefs.edit().putBoolean("pending_reload", true).apply()
            // cache clear happens after reload path; flag for main
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            finish()
        }
        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            mem.edit().clear().apply()
            keysList.append("\n(memory cleared)")
        }
        findViewById<Button>(R.id.btnClearFs).setOnClickListener {
            val root = java.io.File(filesDir, "harness_fs")
            root.deleteRecursively()
            root.mkdirs()
        }

        findViewById<TextView>(R.id.toolsList).text = TOOLS_HELP
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
    }

    companion object {
        val TOOLS_HELP = """
            |Native tools (via run_js):
            |  list_tools() · memory · fs · fetch_url
            |  clipboard · file.save · keys.get/set/list
            |  device.info · device.battery · device.network
            |  notify · toast · vibrate · share · appInfo
            |  geo (when permitted)
            """.trimMargin()
    }
}
