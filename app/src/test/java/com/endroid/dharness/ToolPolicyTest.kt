package com.endroid.dharness

import com.endroid.dharness.bridge.ToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {
    @Test
    fun timeFormatIsNotMutating() {
        // The old substring check ("rm" in "format") wrongly blocked this in strict mode.
        assertFalse(ToolPolicy.isMutating("time.format"))
        assertFalse(ToolPolicy.isMutating("workspace.read"))
        assertFalse(ToolPolicy.isMutating("github.repos"))
    }

    @Test
    fun stateChangingToolsAreMutating() {
        for (t in listOf("fs.append", "github.push_file", "github.pr_merge", "keys.set")) {
            assertTrue(t, ToolPolicy.isMutating(t))
        }
        assertTrue(ToolPolicy.isMutating("workspace.write"))
        assertTrue(ToolPolicy.isMutating("exec.lang"))
        assertTrue(ToolPolicy.isMutating("shell"))
    }

    @Test
    fun ruleMatchesExactGroupAndWildcardNotSubstring() {
        assertTrue(ToolPolicy.ruleMatches("shell", "shell"))
        assertTrue(ToolPolicy.ruleMatches("github.pr_merge", "github"))
        assertTrue(ToolPolicy.ruleMatches("github.pr_merge", "github.*"))
        assertTrue(ToolPolicy.ruleMatches("anything", "*"))
        assertFalse(ToolPolicy.ruleMatches("time.format", "rm"))
        assertFalse(ToolPolicy.ruleMatches("workspace.rm", "rm"))
        assertFalse(ToolPolicy.ruleMatches("shell", ""))
    }

    @Test
    fun cleanRelativeKeepsNamesAndRejectsTraversal() {
        assertEquals("a/b.txt", ToolPolicy.cleanRelative("/a/./b.txt"))
        assertEquals("my..file.txt", ToolPolicy.cleanRelative("my..file.txt"))
        assertEquals("", ToolPolicy.cleanRelative("."))
        for (bad in listOf("../x", "a/../b", "a/..", "..\\x")) {
            try {
                ToolPolicy.cleanRelative(bad)
                throw AssertionError("should reject $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun isInsideRequiresASeparator() {
        assertTrue(ToolPolicy.isInside("/data/ws", "/data/ws"))
        assertTrue(ToolPolicy.isInside("/data/ws", "/data/ws/a/b"))
        assertFalse(ToolPolicy.isInside("/data/ws", "/data/ws-evil/a"))
        assertFalse(ToolPolicy.isInside("/data/ws", "/data"))
    }

    @Test
    fun githubTokenOnlyGoesToApiGithub() {
        assertEquals("https://api.github.com/user", ToolPolicy.githubUrl("/user"))
        assertEquals("https://api.github.com/user", ToolPolicy.githubUrl("user"))
        assertEquals(
            "https://api.github.com/repos/a/b",
            ToolPolicy.githubUrl("https://api.github.com/repos/a/b")
        )
        assertNull(ToolPolicy.githubUrl("https://evil.example/steal"))
        assertNull(ToolPolicy.githubUrl("https://api.github.com.evil.example/x"))
        assertNull(ToolPolicy.githubUrl("http://api.github.com/user"))
        assertNull(ToolPolicy.githubUrl("https://api.github.com@evil.example/x"))
        assertNull(ToolPolicy.githubUrl("file:///etc/passwd"))
    }

    @Test
    fun readCappedBoundsMemoryButDrainsInput() {
        val big = ByteArray(200_000) { 'a'.code.toByte() }
        val s = ToolPolicy.readCapped(big.inputStream(), 1000)
        assertEquals(1000, s.length)
        assertEquals("", ToolPolicy.readCapped(ByteArray(0).inputStream(), 10))
    }
}
