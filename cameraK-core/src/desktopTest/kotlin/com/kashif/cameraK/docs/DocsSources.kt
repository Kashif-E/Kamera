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
}
