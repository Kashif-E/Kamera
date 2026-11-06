package com.kashif.cameraK.docs

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The iOS setup in the README and the installation guide names every Info.plist usage description
 * the library can make iOS ask for. iOS terminates an app the moment it requests a permission whose
 * usage description is missing, so an app built by following the docs must not lack one.
 *
 * - NSCameraUsageDescription: every camera session.
 * - NSMicrophoneUsageDescription: recordings with audio (VideoConfiguration's enableAudio).
 * - NSPhotoLibraryAddUsageDescription: MediaSaverPlugin's saves to Photos, which ask for add-only
 *   access.
 * - NSPhotoLibraryUsageDescription: MediaSaverPlugin.readBytes, which fetches the saved asset by its
 *   identifier; add-only access cannot read it back, so such an app asks for read access itself.
 *
 * Each of the last three is tied in the docs to the feature that needs it, so an app that uses none
 * of them can leave it out.
 */
class IosInstallDocsTest {
    /** The keys an app declares only for one feature, with the API that needs each (§13.2 item 10). */
    private val conditionalKeys = mapOf(
        "NSMicrophoneUsageDescription" to "enableAudio",
        "NSPhotoLibraryAddUsageDescription" to "MediaSaverPlugin",
        "NSPhotoLibraryUsageDescription" to "readBytes",
    )

    private val requiredKeys = listOf(
        "NSCameraUsageDescription",
        "NSMicrophoneUsageDescription",
        "NSPhotoLibraryAddUsageDescription",
        "NSPhotoLibraryUsageDescription",
    )

    private fun assertDeclaresEveryKey(doc: String, section: String) {
        val missing = requiredKeys.filterNot { key ->
            Regex("<key>$key</key>\\s*<string>[^<\\s][^<]*</string>").containsMatchIn(section)
        }
        assertTrue(missing.isEmpty(), "$doc's iOS setup lacks a usage description for $missing")
    }

    /**
     * Each key an app needs only for one feature is tied to that feature: every sentence, table row
     * or XML comment of the iOS setup that names the key names the feature, and at least one does.
     * Nothing there names a symbol 2.0 removed (Directory, RequestStoragePermission).
     */
    private fun assertTiesEachConditionalKeyToItsFeature(doc: String, section: String) {
        val statements = DocsSources.statements(section)
        conditionalKeys.forEach { (key, feature) ->
            val aboutKey = statements.filter { Regex("\\b$key\\b").containsMatchIn(it) }
            assertTrue(aboutKey.isNotEmpty(), "$doc's iOS setup never says when an app needs $key")
            val untied = aboutKey.filterNot { feature in it }
            assertTrue(untied.isEmpty(), "$doc names $key without naming $feature: $untied")
        }
        val removed = statements.filter { Regex("\\bDirectory\\b|RequestStoragePermission").containsMatchIn(it) }
        assertTrue(removed.isEmpty(), "$doc's iOS setup names a symbol 2.0 removed: $removed")
    }

    private val readme = DocsSources.README
    private val readmeIosSetup get() = DocsSources.section(
        readme,
        Regex("\\*\\*iOS\\*\\*.*"),
        Regex("(#+ |\\*\\*[A-Z]).*"),
    )
    private val guide = DocsSources.INSTALLATION_GUIDE
    private val guideIosSetup get() = DocsSources.section(guide, Regex("### iOS"), Regex("#{1,3} .*"))

    /** Every "iOS N.N+" minimum the README or the installation guide states is 16.0 or later. */
    @Test
    fun minimumIsIos16() {
        listOf(readme, guide).forEach { doc ->
            val minimums = Regex("""iOS\s+(\d+)(?:\.\d+)?\+""").findAll(DocsSources.lines(doc).joinToString("\n"))
                .map { it.value to it.groupValues[1].toInt() }
                .toList()
            assertTrue(minimums.isNotEmpty(), "$doc states no iOS minimum")
            val below = minimums.filter { (_, major) -> major < 16 }.map { it.first }
            assertTrue(below.isEmpty(), "$doc states an iOS minimum below 16: $below")
        }
    }

    @Test
    fun theReadmesIosSetupDeclaresEveryUsageDescription() = assertDeclaresEveryKey(readme, readmeIosSetup)

    @Test
    fun theInstallationGuidesIosSetupDeclaresEveryUsageDescription() = assertDeclaresEveryKey(guide, guideIosSetup)

    @Test
    fun theReadmeTiesEachConditionalKeyToItsFeature() = assertTiesEachConditionalKeyToItsFeature(readme, readmeIosSetup)

    @Test
    fun theInstallationGuideTiesEachConditionalKeyToItsFeature() =
        assertTiesEachConditionalKeyToItsFeature(guide, guideIosSetup)
}
