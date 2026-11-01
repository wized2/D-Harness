package com.endroid.dharness.bridge

import java.io.File

/** Sandboxed paths under the app workspace root. */
object WorkspacePaths {
    fun resolve(root: File, path: String): File {
        val cleaned = path.trim().removePrefix("/").replace("..", "")
        val f = File(root, cleaned)
        val rootCanon = root.canonicalPath
        val fileCanon = f.canonicalPath
        require(fileCanon.startsWith(rootCanon)) { "path escapes workspace" }
        return f
    }

    fun safeName(name: String, max: Int = 80): String {
        return name.replace(Regex("""[^A-Za-z0-9._\-]"""), "_").take(max).ifBlank { "file" }
    }
}
