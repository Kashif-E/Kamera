package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.fail

/** The repository's documentation and sources as text, for the docs tests. */
internal object DocsSources {
    val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("no settings.gradle.kts above ${File("").absolutePath}")

    const val README = "README.MD"
    const val INSTALLATION_GUIDE = "docs/getting-started/installation.md"

    fun lines(path: String): List<String> = File(repoRoot, path).readLines()

    /** The lines of [doc] from the first line matching [start] up to the next line matching [end]. */
    fun section(doc: String, start: Regex, end: Regex): String {
        val lines = lines(doc)
        val from = lines.indexOfFirst { start.matches(it) }
        if (from < 0) fail("$doc has no section matching $start")
        val to = lines.drop(from + 1).indexOfFirst { end.matches(it) }.let { if (it < 0) lines.size else from + 1 + it }
        return lines.subList(from, to).joinToString("\n")
    }

    /**
     * What [section] says, one statement at a time: each table row, each XML comment in a fenced
     * block, and each sentence of its prose. Code outside comments is left out.
     */
    fun statements(section: String): List<String> {
        val statements = mutableListOf<String>()
        val prose = StringBuilder()
        fun endParagraph() {
            prose.split(Regex("(?<=\\.)\\s+")).map(String::trim).filter(String::isNotEmpty).forEach(statements::add)
            prose.clear()
        }
        var fenced = false
        section.lines().map(String::trim).forEach { line ->
            when {
                line.startsWith("```") -> {
                    endParagraph()
                    fenced = !fenced
                }
                fenced -> Regex("<!--(.*?)-->").findAll(line).forEach { statements += it.groupValues[1].trim() }
                line.startsWith("|") -> {
                    endParagraph()
                    if (!Regex("[|\\s:-]+").matches(line)) statements += line
                }
                line.isEmpty() -> endParagraph()
                else -> prose.append(line).append(' ')
            }
        }
        endParagraph()
        return statements
    }

    /** The first fenced block after the line [heading] in [doc], without its fences. */
    fun firstFenceAfter(doc: String, heading: String): String {
        val lines = lines(doc)
        val at = lines.indexOf(heading)
        if (at < 0) fail("$doc has no heading '$heading'")
        val open = (at + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") }
            ?: fail("$doc has no fenced block after '$heading'")
        val close = (open + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") }
            ?: fail("$doc's block after '$heading' is not closed")
        return lines.subList(open + 1, close).joinToString("\n")
    }
}
