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
            prefs.edit().putBoolean("pending_instructions", true).apply()
            finish()
        }
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

    private fun openWorkspaceExplorer(dir: File) {
        dir.mkdirs()
        // 1) Try system Documents UI rooted at app external workspace (not Google Files app specifically)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val docId = "primary:Android/data/$packageName/files/workspace"
                val treeUri = DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                Toast.makeText(this, "Workspace:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {
        }
        // 2) FileProvider folder view
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {
        }
        // 3) Generic open document (system picker)
        try {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
            Toast.makeText(this, "Workspace path:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open files UI: ${e.message}\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
