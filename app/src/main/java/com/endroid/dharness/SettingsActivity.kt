package com.endroid.dharness

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import android.provider.DocumentsContract
import java.io.File
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SecureStore.migrateKeysIfNeeded(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Theme: system / light / dark
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
        val keysList = findViewById<TextView>(R.id.keysList)
        val patLayout = findViewById<TextInputLayout>(R.id.patLayout)
        val patInput = findViewById<TextInputEditText>(R.id.patInput)

        fun refreshKeys(store: SharedPreferences = keys) {
            val names = store.all.keys.sorted()
            keysList.text = if (names.isEmpty()) "(no keys)" else names.joinToString("\n") { "• $it" }
        }

        fun updatePatHelper() {
            val saved = keys.contains("github") || keys.contains("github_pat")
            patLayout.helperText =
                if (saved) "Saved — enter a new token to replace"
                else "Stored as key github"
        }

        findViewById<TextView>(R.id.versionText).text =
            try {
                "D-Harness v${packageManager.getPackageInfo(packageName, 0).versionName}"
            } catch (_: Exception) {
                ""
            }

        findViewById<MaterialButton>(R.id.btnCheckUpdate).setOnClickListener {
            // MainActivity will check when we set flag and finish, or run inline
            Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
            Thread {
                val installed = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                } catch (_: Exception) { "0" }
                val info = UpdateChecker.fetchLatest()
                runOnUiThread {
                    if (info == null) {
                        Toast.makeText(this, "Update check failed", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    if (!UpdateChecker.isNewer(info.tag, installed)) {
                        Toast.makeText(this, "Up to date (v$installed)", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Update v${info.tag}")
                        .setMessage(info.body.take(600).ifBlank { info.name })
                        .setPositiveButton("Open") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }.start()
        }

        updatePatHelper()

        findViewById<MaterialButton>(R.id.btnSavePat).setOnClickListener {
            val v = patInput.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) {
                keys.edit().putString("github", v).putString("github_pat", v).apply()
                patInput.setText("")
                updatePatHelper()
                Toast.makeText(this, "GitHub PAT saved", Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
        }

        val swDesktop = findViewById<MaterialSwitch>(R.id.switchDesktop)
        val swInject = findViewById<MaterialSwitch>(R.id.switchInject)
        val swDedupe = findViewById<MaterialSwitch>(R.id.switchDedupe)
        val swExecShell = findViewById<MaterialSwitch>(R.id.switchExecShell)
        val swTaskBar = findViewById<MaterialSwitch>(R.id.switchTaskBar)
        swDesktop.isChecked = prefs.getBoolean("desktop", false)
        swInject.isChecked = prefs.getBoolean("auto_inject", true)
        swDedupe.isChecked = prefs.getBoolean("dedupe", true)
        swExecShell.isChecked = prefs.getBoolean("exec_shell", false)
        swTaskBar.isChecked = prefs.getBoolean("show_task_bar", false)
        fun persist() {
            prefs.edit()
                .putBoolean("desktop", swDesktop.isChecked)
                .putBoolean("auto_inject", swInject.isChecked)
                .putBoolean("dedupe", swDedupe.isChecked)
                .putBoolean("exec_shell", swExecShell.isChecked)
                .putBoolean("show_task_bar", swTaskBar.isChecked)
                .apply()
        }
        swDesktop.setOnCheckedChangeListener { _, _ -> persist() }
        swInject.setOnCheckedChangeListener { _, _ -> persist() }
        swExecShell.setOnCheckedChangeListener { _, _ -> persist() }
        swDedupe.setOnCheckedChangeListener { _, _ -> persist() }
        swDsml.setOnCheckedChangeListener { _, _ -> persist() }
        swTaskBar.setOnCheckedChangeListener { _, _ -> persist() }

        val larger = findViewById<MaterialSwitch>(R.id.switchLargerText)
        larger.isChecked = prefs.getInt("text_zoom", 100) >= 110
        larger.setOnCheckedChangeListener { _, on ->
            prefs.edit().putInt("text_zoom", if (on) 110 else 100).apply()
        }

        val keyName = findViewById<TextInputEditText>(R.id.keyName)
        val keyValue = findViewById<TextInputEditText>(R.id.keyValue)
        refreshKeys()

        findViewById<MaterialButton>(R.id.btnSaveKey).setOnClickListener {
            val n = keyName.text?.toString()?.trim().orEmpty()
            val v = keyValue.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty() && v.isNotEmpty()) {
                keys.edit().putString(n, v).apply()
                keyName.setText("")
                keyValue.setText("")
                Toast.makeText(this, "Saved key $n", Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
        }
        findViewById<MaterialButton>(R.id.btnDeleteKey).setOnClickListener {
            val n = keyName.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty()) {
                keys.edit().remove(n).apply()
                Toast.makeText(this, "Deleted $n", Toast.LENGTH_SHORT).show()
                refreshKeys()
            }
        }

        findViewById<MaterialButton>(R.id.btnSendInstructions).setOnClickListener {
            prefs.edit().putBoolean("pending_send_instructions", true).apply()
            Toast.makeText(this, "Sending tool instructions…", Toast.LENGTH_SHORT).show()
            finish()
        }
        // Long-press Explore already opens Files; add stop agent via clear-cache row reuse if needed
        try {
            findViewById<MaterialButton>(R.id.btnClearMemory).setOnLongClickListener {
                AgentService.stop(this)
                Toast.makeText(this, "Background agent stopped", Toast.LENGTH_SHORT).show()
                true
            }
        } catch (_: Exception) { }
        findViewById<MaterialButton>(R.id.btnReload).setOnClickListener {
            prefs.edit().putBoolean("pending_reload", true).apply()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnReinject).setOnClickListener {
            prefs.edit().putBoolean("pending_inject", true).apply()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener {
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnClearMemory).setOnClickListener {
            mem.edit().clear().apply()
            Toast.makeText(this, "Agent memory cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnClearFs).setOnClickListener {
            prefs.edit().putBoolean("pending_clear_fs", true).apply()
            Toast.makeText(this, "Native FS clear queued", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<TextView>(R.id.toolsList).text = """
            Quick map:
            list_tools · describe · skill.list · skill.read
            shell (primary) · term · exec.*
            workspace.* · paste_box · github.* · http_request
            research.web · device · clipboard · memory · keys · tasks
            Use skill.read before a domain. Only closed skill names exist.
        """.trimIndent()

        
        val workspaceRoot = run {
            val ext = getExternalFilesDir(null)
            if (ext != null) File(ext, "workspace") else File(filesDir, "workspace")
        }.also { it.mkdirs() }

        findViewById<TextView>(R.id.workspacePathText).text = workspaceRoot.absolutePath

        findViewById<MaterialButton>(R.id.btnExploreWorkspace).setOnClickListener {
            startActivity(Intent(this, WorkspaceBrowserActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }
    }}
