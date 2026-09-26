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

        // Prefer stock DocumentsUI browse (not folder-picker / OPEN_DOCUMENT_TREE).
        val docId = "primary:Android/data/$packageName/files/workspace"
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId
        )
        val encodedUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/" +
                Uri.encode(docId)
        )

        val candidates = mutableListOf<Intent>()
        for (uri in listOf(docUri, encodedUri)) {
            candidates += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
                candidates += Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        // FileProvider fallback — some OEMs browse file:// via Files app
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                dir
            )
            candidates += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) { }

        for (intent in candidates) {
            try {
                startActivity(intent)
                Toast.makeText(this, dir.absolutePath, Toast.LENGTH_SHORT).show()
                return
            } catch (_: Exception) { }
        }

        Toast.makeText(
            this,
            "Open Files → Android/data/$packageName/files/workspace",
            Toast.LENGTH_LONG
        ).show()
    }
}

        val larger = findViewById<MaterialSwitch>(R.id.switchLargerText)
        larger.isChecked = prefs.getInt("text_zoom", 100) >= 110
        larger.setOnCheckedChangeListener { _, on ->
            prefs.edit().putInt("text_zoom", if (on) 110 else 100).apply()
        }
