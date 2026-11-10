package com.endroid.dharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the skill files the model reads via skill.read. The shim only executes ```invoke fences,
 * so any example in another fence would silently do nothing.
 */
class SkillsTest {
    private val closedSet = setOf(
        "shell", "workspace", "github", "http", "device", "protocol", "exec",
        "memory", "tasks", "research", "skill", "time", "safety"
    )

    private fun skillsDir(): File {
        val candidates = listOf("src/main/assets/skills", "app/src/main/assets/skills")
        return candidates.map(::File).first { it.isDirectory }
    }

    private fun skills(): List<File> =
        skillsDir().listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    @Test
    fun skillFilesMatchTheClosedSet() {
        assertEquals(closedSet, skills().map { it.nameWithoutExtension }.toSet())
    }

    @Test
    fun frontMatterNameMatchesFileName() {
        for (f in skills()) {
            val lines = f.readLines()
            assertEquals("${f.name}: must start with front matter", "---", lines.firstOrNull())
            val name = lines.firstOrNull { it.startsWith("name:") }?.removePrefix("name:")?.trim()
            assertEquals("${f.name}: front matter name", f.nameWithoutExtension, name)
            assertTrue("${f.name}: needs a description", lines.any { it.startsWith("description:") && it.length > 20 })
        }
    }

    @Test
    fun noNonExecutableXmlFences() {
        for (f in skills()) {
            assertFalse("${f.name}: ```xml fences are never executed; use ```invoke", f.readText().contains("```xml"))
        }
    }

    @Test
    fun everyInvokeBlockIsWellFormed() {
        val fence = Regex("```invoke\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        var total = 0
        for (f in skills()) {
            val text = f.readText()
            assertEquals("${f.name}: unbalanced code fences", 0, Regex("(?m)^```").findAll(text).count() % 2)
            for (m in fence.findAll(text)) {
                total++
                val body = m.groupValues[1]
                assertTrue("${f.name}: missing <function_calls>", body.contains("<function_calls>") && body.contains("</function_calls>"))
                assertEquals(
                    "${f.name}: <invoke> open/close mismatch",
                    Regex("<invoke name=").findAll(body).count(),
                    Regex("</invoke>").findAll(body).count()
                )
                assertEquals(
                    "${f.name}: <parameter> open/close mismatch",
                    Regex("<parameter name=").findAll(body).count(),
                    Regex("</parameter>").findAll(body).count()
                )
            }
        }
        assertTrue("expected examples across skills", total >= 20)
    }

    @Test
    fun skillSkillListsExactlyTheClosedSet() {
        val text = File(skillsDir(), "skill.md").readText()
        for (n in closedSet) assertTrue("skill.md must name $n", text.contains("`$n`"))
    }
}
