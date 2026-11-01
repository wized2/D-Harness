package com.endroid.dharness

import com.endroid.dharness.bridge.CatalogCache
import com.endroid.dharness.bridge.WorkspacePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspacePathsTest {
    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "dh-ws-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun resolvesInsideRoot() {
        val root = tempRoot()
        val f = WorkspacePaths.resolve(root, "a/b.txt")
        assertTrue(f.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun safeNameStripsJunk() {
        assertEquals("a_b.txt", WorkspacePaths.safeName("a/b.txt"))
    }

    @Test
    fun catalogCacheStable() {
        CatalogCache.invalidate()
        var builds = 0
        val a = CatalogCache.getListOrBuild {
            builds++
            """{"tools":[]}"""
        }
        val b = CatalogCache.getListOrBuild {
            builds++
            """{"tools":[1]}"""
        }
        assertEquals(a, b)
        assertEquals(1, builds)
        CatalogCache.invalidate()
        CatalogCache.getListOrBuild {
            builds++
            """{"tools":[]}"""
        }
        assertEquals(2, builds)
    }

    @Test
    fun truncatePayload() {
        val o = CatalogCache.truncatePayload("x".repeat(10), 4)
        assertTrue(o.getBoolean("truncated"))
        assertTrue(o.getString("text").contains("truncated"))
    }
}
