package com.endroid.dharness

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app workspace browser: open, share, delete, rename, copy path. */
class WorkspaceBrowserActivity : AppCompatActivity() {

    private lateinit var root: File
    private lateinit var current: File
    private lateinit var pathText: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private val adapter = FileAdapter()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace_browser)

        root = run {
            val ext = getExternalFilesDir(null)
            if (ext != null) File(ext, "workspace") else File(filesDir, "workspace")
        }.also { it.mkdirs() }
        current = root

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        pathText = findViewById(R.id.pathText)
        emptyText = findViewById(R.id.emptyText)
        list = findViewById(R.id.fileList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnUp).setOnClickListener { goUp() }
        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<MaterialButton>(R.id.btnNewFile).setOnClickListener { promptNewFile() }
        findViewById<MaterialButton>(R.id.btnCopyPath).setOnClickListener {
            copyText(current.absolutePath)
            Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    private fun goUp() {
        if (current.canonicalPath == root.canonicalPath) {
            Toast.makeText(this, "Already at workspace root", Toast.LENGTH_SHORT).show()
            return
        }
        val parent = current.parentFile ?: return
        if (!parent.canonicalPath.startsWith(root.canonicalPath)) return
        current = parent
        refresh()
    }

    private fun refresh() {
        pathText.text = current.absolutePath
        val entries = (current.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
        adapter.submit(entries)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openEntry(f: File) {
        if (f.isDirectory) {
            current = f
            refresh()
            return
        }
        showFileActions(f)
    }

    private fun showFileActions(f: File) {
        val options = arrayOf(
            "Open / Share",
            "View text",
            "Copy path",
            "Copy contents",
            "Rename",
            "Delete"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(f.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareOrOpen(f)
                    1 -> viewText(f)
                    2 -> {
                        copyText(f.absolutePath)
                        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        try {
                            copyText(f.readText())
                            Toast.makeText(this, "Contents copied", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Read failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    4 -> promptRename(f)
                    5 -> confirmDelete(f)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareOrOpen(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = guessMime(f.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                startActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        type = guessMime(f.name)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Open failed: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun viewText(f: File) {
        try {
            val text = f.readText().take(50_000)
            MaterialAlertDialogBuilder(this)
                .setTitle(f.name)
                .setMessage(text.ifBlank { "(empty)" })
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ ->
                    copyText(text)
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Not text or unreadable: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptRename(f: File) {
        val input = EditText(this).apply {
            setText(f.name)
            setSelection(f.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    Toast.makeText(this, "Invalid name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val dest = File(f.parentFile, name)
                if (dest.exists()) {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (f.renameTo(dest)) refresh()
                else Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(f: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${f.name}?")
            .setMessage(if (f.isDirectory) "Directory and contents will be removed." else "This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    refresh()
                } else Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewFile() {
        val input = EditText(this).apply { hint = "notes.txt" }
        MaterialAlertDialogBuilder(this)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    Toast.makeText(this, "Invalid name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val f = File(current, name)
                if (f.exists()) {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    f.writeText("")
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(this, "Create failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("workspace", s))
    }

    private fun guessMime(name: String): String {
        val n = name.lowercase(Locale.US)
        return when {
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") -> "text/plain"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        private var items: List<File> = emptyList()

        fun submit(files: List<File>) {
            items = files
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_workspace_file, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.name.text = f.name
            holder.icon.text = if (f.isDirectory) "📁" else "📄"
            holder.meta.text = if (f.isDirectory) {
                "Folder · ${dateFmt.format(Date(f.lastModified()))}"
            } else {
                "${formatSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
            }
            holder.itemView.setOnClickListener { openEntry(f) }
            holder.more.setOnClickListener {
                if (f.isDirectory) {
                    MaterialAlertDialogBuilder(this@WorkspaceBrowserActivity)
                        .setTitle(f.name)
                        .setItems(arrayOf("Open", "Copy path", "Delete")) { _, w ->
                            when (w) {
                                0 -> openEntry(f)
                                1 -> {
                                    copyText(f.absolutePath)
                                    Toast.makeText(this@WorkspaceBrowserActivity, "Path copied", Toast.LENGTH_SHORT).show()
                                }
                                2 -> confirmDelete(f)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    showFileActions(f)
                }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameText)
            val meta: TextView = v.findViewById(R.id.metaText)
            val icon: TextView = v.findViewById(R.id.iconText)
            val more: TextView = v.findViewById(R.id.moreText)
        }
    }
}
