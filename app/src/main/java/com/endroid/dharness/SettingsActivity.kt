package com.endroid.dharness

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDeepEase = BuildConfig.PRODUCT == "deepease"
        setContentView(if (isDeepEase) R.layout.activity_settings_deepease else R.layout.activity_settings)
        ThemeHelper.applySansToTree(this, findViewById(android.R.id.content))
        SecureStore.migrateKeysIfNeeded(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val themeToggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.themeToggle)
        when (ThemeHelper.current(this)) {
            ThemeHelper.MODE_LIGHT -> themeToggle.check(R.id.themeLight)
            ThemeHelper.MODE_DARK -> themeToggle.check(R.id.themeDark)
            else -> themeToggle.check(R.id.themeSystem)
        }
        themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> ThemeHelper.MODE_LIGHT
                R.id.themeDark -> ThemeHelper.MODE_DARK
                else -> ThemeHelper.MODE_SYSTEM
            }
            ThemeHelper.save(this, mode)
        }

        val prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        val keys = SecureStore.keys(this)
        val mem = getSharedPreferences("dharness_mem", MODE_PRIVATE)

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        findViewById<TextView>(R.id.versionText)?.text =
            if (isDeepEase) "DeepEase v$ver" else "D-Harness v$ver"

        findViewById<MaterialButton>(R.id.btnCheckUpdate)?.setOnClickListener {
            Thread {
                val info = UpdateChecker.fetchLatest(this)
                runOnUiThread {
                    if (info == null) {
                        Toast.makeText(this, "No update info", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Update ${info.tag}")
                        .setMessage(info.body.take(600).ifBlank { info.name })
                        .setPositiveButton("Open") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }.start()
        }

        val patInput = findViewById<TextInputEditText>(R.id.patInput)
        fun refreshKeys(store: SharedPreferences = keys) {
            val names = store.all.keys.sorted().joinToString(", ").ifBlank { "(none)" }
            findViewById<TextView>(R.id.keysList)?.text = "Saved keys: $names"
        }
        if (!isDeepEase) refreshKeys()

        findViewById<MaterialButton>(R.id.btnSavePat)?.setOnClickListener {
            val v = patInput?.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) {
                keys.edit().putString("github", v).putString("github_pat", v).apply()
                patInput?.setText("")
                refreshKeys()
                Toast.makeText(this, "GitHub PAT saved", Toast.LENGTH_SHORT).show()
            }
        }

        val swDesktop = findViewById<MaterialSwitch>(R.id.switchDesktop)
        val swInject = findViewById<MaterialSwitch>(R.id.switchInject)
        val swDedupe = findViewById<MaterialSwitch>(R.id.switchDedupe)
        val swExecShell = findViewById<MaterialSwitch>(R.id.switchExecShell)
        val swTaskBar = findViewById<MaterialSwitch>(R.id.switchTaskBar)
        val swLarger = findViewById<MaterialSwitch>(R.id.switchLargerText)

        swDesktop?.isChecked = prefs.getBoolean("desktop", false)
        swInject?.isChecked = prefs.getBoolean("auto_inject", true)
        swDedupe?.isChecked = prefs.getBoolean("dedupe", true)
        swExecShell?.isChecked = prefs.getBoolean("exec_shell", false)
        swTaskBar?.isChecked = prefs.getBoolean("show_task_bar", false)
        swLarger?.isChecked = prefs.getInt("text_zoom", 100) >= 110

        fun persist() {
            val ed = prefs.edit()
                .putBoolean("desktop", swDesktop?.isChecked == true)
                .putBoolean("auto_inject", swInject?.isChecked != false) // default on if missing
                .putBoolean("dedupe", swDedupe?.isChecked != false)
                .putBoolean("exec_shell", if (isDeepEase) false else swExecShell?.isChecked == true)
                .putBoolean("show_task_bar", if (isDeepEase) false else swTaskBar?.isChecked == true)
            if (swLarger != null) {
                ed.putInt("text_zoom", if (swLarger.isChecked) 110 else 100)
            }
            ed.apply()
        }
        swDesktop?.setOnCheckedChangeListener { _, _ -> persist() }
        swInject?.setOnCheckedChangeListener { _, _ -> persist() }
        swDedupe?.setOnCheckedChangeListener { _, _ -> persist() }
        swExecShell?.setOnCheckedChangeListener { _, _ -> persist() }
        swTaskBar?.setOnCheckedChangeListener { _, _ -> persist() }
        swLarger?.setOnCheckedChangeListener { _, _ -> persist() }

        // DeepEase: force tools on, shell off
        if (isDeepEase) {
            prefs.edit()
                .putBoolean("auto_inject", swInject?.isChecked != false)
                .putBoolean("exec_shell", false)
                .putBoolean("show_task_bar", false)
                .apply()
        }

        findViewById<MaterialButton>(R.id.btnSaveKey)?.setOnClickListener {
            val name = findViewById<TextInputEditText>(R.id.keyName)?.text?.toString()?.trim().orEmpty()
            val value = findViewById<TextInputEditText>(R.id.keyValue)?.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty() && value.isNotEmpty()) {
                keys.edit().putString(name, value).apply()
                refreshKeys()
                Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnDeleteKey)?.setOnClickListener {
            val name = findViewById<TextInputEditText>(R.id.keyName)?.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) {
                keys.edit().remove(name).apply()
                refreshKeys()
            }
        }

        findViewById<MaterialButton>(R.id.btnSendInstructions)?.setOnClickListener {
            prefs.edit().putBoolean("pending_send_instructions", true).apply()
            Toast.makeText(this, "Sending tool instructions…", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.btnReload)?.setOnClickListener {
            prefs.edit().putBoolean("pending_reload", true).apply()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnReinject)?.setOnClickListener {
            prefs.edit().putBoolean("pending_inject", true).putBoolean("auto_inject", true).apply()
            Toast.makeText(this, "Assistant will refresh", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnClearCache)?.setOnClickListener {
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnClearMemory)?.setOnClickListener {
            mem.edit().clear().apply()
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnClearFs)?.setOnClickListener {
            prefs.edit().putBoolean("pending_clear_fs", true).apply()
            finish()
        }

        findViewById<TextView>(R.id.toolsList)?.text = if (isDeepEase) {
            "Everyday tools: flashlight, Wi‑Fi/Bluetooth/data panels, volume, brightness, timer, battery, notify, share."
        } else {
            "list_tools · describe · shell · workspace · github · device · memory"
        }

        val workspaceRoot = run {
            val ext = getExternalFilesDir(null)
            if (ext != null) File(ext, "workspace") else File(filesDir, "workspace")
        }.also { it.mkdirs() }
        findViewById<TextView>(R.id.workspacePathText)?.text = workspaceRoot.absolutePath
        findViewById<MaterialButton>(R.id.btnExploreWorkspace)?.setOnClickListener {
            startActivity(Intent(this, WorkspaceBrowserActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnClose)?.setOnClickListener { finish() }
    }
}
