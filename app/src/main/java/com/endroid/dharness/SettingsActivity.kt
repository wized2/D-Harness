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

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        val keys = getSharedPreferences("dharness_keys", MODE_PRIVATE)
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
        val swTheme = findViewById<MaterialSwitch>(R.id.switchTheme)
        swDesktop.isChecked = prefs.getBoolean("desktop", false)
        swInject.isChecked = prefs.getBoolean("auto_inject", true)
        swDedupe.isChecked = prefs.getBoolean("dedupe", true)
        swTheme.isChecked = prefs.getBoolean("auto_theme", false)
        fun persist() {
            prefs.edit()
                .putBoolean("desktop", swDesktop.isChecked)
                .putBoolean("auto_inject", swInject.isChecked)
                .putBoolean("dedupe", swDedupe.isChecked)
                .putBoolean("auto_theme", swTheme.isChecked)
                .apply()
        }
        swDesktop.setOnCheckedChangeListener { _, _ -> persist() }
        swInject.setOnCheckedChangeListener { _, _ -> persist() }
        swDedupe.setOnCheckedChangeListener { _, _ -> persist() }
        swTheme.setOnCheckedChangeListener { _, _ ->
            persist()
            prefs.edit().putBoolean("pending_inject", true).apply()
        }

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
            list_tools / describe = discover tools
            workspace.* = sandbox files
            paste_box = paste UI → workspace file
            memory.* = scratchpad
            keys.* = secrets (PAT) — never print values
            http_request / fetch_url = headers supported
            github.* = needs PAT key github
            No shell/exec on device (safety)
        """.trimIndent()

        
        val workspaceRoot = run {
            val ext = getExternalFilesDir(null)
            if (ext != null) File(ext, "workspace") else File(filesDir, "workspace")
        }.also { it.mkdirs() }

        findViewById<TextView>(R.id.workspacePathText).text = workspaceRoot.absolutePath

        findViewById<MaterialButton>(R.id.btnExploreWorkspace).setOnClickListener {
            openWorkspaceExplorer(workspaceRoot)
        }

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }
    }


    /**
     * Open system Files / DocumentsUI browser on the app workspace under Android/data.
     * Uses document URI scheme that stock DocumentsUI can resolve on Android 11–12+.
     */

    /**
     * Open stock DocumentsUI / Files in **browse** mode on the workspace folder.
     * Never uses ACTION_OPEN_DOCUMENT_TREE (that is "select folder" mode).
     */

    private fun openWorkspaceExplorer(dir: File) {
        if (!dir.exists()) dir.mkdirs()

        // Always copy path for the user
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("workspace", dir.absolutePath))
        } catch (_: Exception) { }

        // In-app browser — works even when Android/data is not visible to Files app
        val files = try {
            dir.walkTopDown().maxDepth(3).filter { it.isFile }.take(80).map {
                it.relativeTo(dir).path + " (" + it.length() + " B)"
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
        val summary = buildString {
            append(dir.absolutePath)
            append("\n\n")
            if (files.isEmpty()) append("(empty workspace)")
            else append(files.joinToString("\n"))
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Workspace")
            .setMessage(summary.take(3500))
            .setPositiveButton("Open Files app") { _, _ ->
                tryOpenSystemFiles(dir)
            }
            .setNeutralButton("Copy path") { _, _ ->
                Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun tryOpenSystemFiles(dir: File) {
        val docId = "primary:Android/data/$packageName/files/workspace"
        val uris = listOf(
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId),
            Uri.parse("content://com.android.externalstorage.documents/document/" + Uri.encode(docId)),
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        )
        for (uri in uris) {
            for (pkg in listOf(null, "com.google.android.documentsui", "com.android.documentsui")) {
                try {
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        if (pkg != null) setPackage(pkg)
                        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addCategory(Intent.CATEGORY_DEFAULT)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(i)
                    return
                } catch (_: Exception) { }
            }
        }
        // Last resort: app details (user can clear storage / see path tips)
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            Toast.makeText(this, "Files app cannot open Android/data on this ROM. Path is on clipboard.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Path on clipboard:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
