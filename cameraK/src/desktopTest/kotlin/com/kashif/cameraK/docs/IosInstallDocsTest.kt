package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The iOS setup in the README and the installation guide names every Info.plist usage description
 * the library can make iOS ask for. iOS terminates an app the moment it requests a permission whose
 * usage description is missing, so an app built by following the docs must not lack one.
 *
 * - NSCameraUsageDescription: every camera session.
 * - NSMicrophoneUsageDescription: recordings with audio, which VideoConfiguration.enableAudio turns on
 *   by default.
 * - NSPhotoLibraryAddUsageDescription: saving captures and recordings to Photos (Directory.PICTURES,
 *   the default, and DCIM), and the image saver plugin.
 * - NSPhotoLibraryUsageDescription: RequestStoragePermission, which asks for read-write access, and
 *   reading a saved ph:// photo back.
 */
class IosInstallDocsTest {
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("no settings.gradle.kts above ${File("").absolutePath}")

    private val requiredKeys = listOf(
        "NSCameraUsageDescription",
        "NSMicrophoneUsageDescription",
        "NSPhotoLibraryAddUsageDescription",
        "NSPhotoLibraryUsageDescription",
    )

    /** The lines of [doc] from the first line matching [start] up to the next line matching [end]. */
    private fun section(doc: String, start: Regex, end: Regex): String {
        val lines = File(repoRoot, doc).readLines()
        val from = lines.indexOfFirst { start.matches(it) }
        if (from < 0) fail("$doc has no iOS setup section matching $start")
        val to = lines.drop(from + 1).indexOfFirst { end.matches(it) }.let { if (it < 0) lines.size else from + 1 + it }
        return lines.subList(from, to).joinToString("\n")
    }

    private fun assertDeclaresEveryKey(doc: String, section: String) {
        val missing = requiredKeys.filterNot { key ->
            Regex("<key>$key</key>\\s*<string>[^<\\s][^<]*</string>").containsMatchIn(section)
        }
        assertTrue(missing.isEmpty(), "$doc's iOS setup lacks a usage description for $missing")
    }

    @Test
    fun theReadmesIosSetupDeclaresEveryUsageDescription() {
        val doc = "README.MD"
        assertDeclaresEveryKey(doc, section(doc, Regex("\\*\\*iOS\\*\\*.*"), Regex("(#+ |\\*\\*[A-Z]).*")))
    }

    @Test
    fun theInstallationGuidesIosSetupDeclaresEveryUsageDescription() {
        val doc = "docs/getting-started/installation.md"
        assertDeclaresEveryKey(doc, section(doc, Regex("### iOS"), Regex("#{1,3} .*")))
    }
}
