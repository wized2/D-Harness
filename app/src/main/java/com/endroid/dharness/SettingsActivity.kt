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


    /**
     * Open system Files / DocumentsUI browser on the app workspace under Android/data.
     * Uses document URI scheme that stock DocumentsUI can resolve on Android 11–12+.
     */

    /**
     * Open stock DocumentsUI / Files in **browse** mode on the workspace folder.
     * Never uses ACTION_OPEN_DOCUMENT_TREE (that is "select folder" mode).
     */
    private fun openWorkspaceExplorer(dir: File) {
        dir.mkdirs()
        val rel = "Android/data/$packageName/files/workspace"
        val authority = "com.android.externalstorage.documents"
        val docId = "primary:$rel"
        val docUri = DocumentsContract.buildDocumentUri(authority, docId)
        // Encoded form used by many OEM Files apps
        val encodedUri = android.net.Uri.parse(
            "content://$authority/document/" + android.net.Uri.encode(docId)
        )

        val targets = listOf(
            // Class names for Files browser (not the SAF picker)
            Triple("com.android.documentsui", "com.android.documentsui.files.FilesActivity", docUri),
            Triple("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", docUri),
            Triple("com.android.documentsui", "com.android.documentsui.FilesActivity", docUri),
            Triple("com.google.android.documentsui", "com.android.documentsui.FilesActivity", docUri),
            Triple("com.android.documentsui", "com.android.documentsui.files.FilesActivity", encodedUri),
            Triple("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", encodedUri),
        )

        val mimes = listOf(
            "vnd.android.document/directory",
            "vnd.android.document/root",
            DocumentsContract.Document.MIME_TYPE_DIR
        )

        for ((pkg, cls, uri) in targets) {
            for (mime in mimes) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setClassName(pkg, cls)
                        setDataAndType(uri, mime)
                        addCategory(Intent.CATEGORY_DEFAULT)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Workspace:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
                    return
                } catch (_: Exception) {
                }
            }
        }

        // Package-only VIEW (still browse, not tree picker)
        for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
            for (uri in listOf(docUri, encodedUri)) {
                for (mime in mimes) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setPackage(pkg)
                            setDataAndType(uri, mime)
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                        Toast.makeText(this, "Workspace:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
                        return
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Generic VIEW without package
        for (uri in listOf(docUri, encodedUri)) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                Toast.makeText(this, "Workspace:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
            }
        }

        // Do NOT use ACTION_OPEN_DOCUMENT_TREE — that is select-folder mode.
        Toast.makeText(
            this,
            "Open system Files → Internal storage → Android/data/…/workspace\n${dir.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    }
}
