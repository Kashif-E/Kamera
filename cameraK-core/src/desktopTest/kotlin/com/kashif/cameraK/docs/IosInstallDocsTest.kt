package com.kashif.cameraK.docs

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The iOS setup in the README and the installation guide names every Info.plist usage description
 * the library can make iOS ask for. iOS terminates an app the moment it requests a permission whose
 * usage description is missing, so an app built by following the docs must not lack one.
 *
 * - NSCameraUsageDescription: every camera session.
 * - NSMicrophoneUsageDescription: recordings with audio, which VideoConfiguration.enableAudio turns on
 *   by default.
 * - NSPhotoLibraryAddUsageDescription: saving captures and recordings to Photos (Directory.PICTURES
 *   and DCIM; captures default to DOCUMENTS, the app's own storage), and the image saver plugin.
 * - NSPhotoLibraryUsageDescription: RequestStoragePermission, which asks for read-write access, and
 *   reading a saved ph:// photo back.
 *
 * Saving only to Directory.DOCUMENTS removes the need for the add-only key, not for the read-write
 * one: an app that calls RequestStoragePermission still needs NSPhotoLibraryUsageDescription.
 */
class IosInstallDocsTest {
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
     * Every sentence that lets a DOCUMENTS-only app drop a Photos key keeps NSPhotoLibraryUsageDescription
     * for an app that calls RequestStoragePermission.
     */
    private fun assertDocumentsOnlyKeepsTheReadWriteKey(doc: String, section: String) {
        val claims = section.lines()
            .flatMap { it.split(Regex("(?<=\\.)\\s+")) }
            .filter { "Directory.DOCUMENTS" in it && "need" in it }
        assertTrue(claims.isNotEmpty(), "$doc's iOS setup no longer says which keys a DOCUMENTS-only app needs")
        claims.forEach { claim ->
            assertTrue(
                "RequestStoragePermission" in claim && "NSPhotoLibraryUsageDescription" in claim && "neither" !in claim,
                "$doc tells a DOCUMENTS-only app it can drop a Photos key without keeping " +
                    "NSPhotoLibraryUsageDescription for RequestStoragePermission: '$claim'",
            )
        }
    }

    private val readme = DocsSources.README
    private val readmeIosSetup get() = DocsSources.section(
        readme,
        Regex("\\*\\*iOS\\*\\*.*"),
        Regex("(#+ |\\*\\*[A-Z]).*"),
    )
    private val guide = DocsSources.INSTALLATION_GUIDE
    private val guideIosSetup get() = DocsSources.section(guide, Regex("### iOS"), Regex("#{1,3} .*"))

    @Test
    fun theReadmesIosSetupDeclaresEveryUsageDescription() = assertDeclaresEveryKey(readme, readmeIosSetup)

    @Test
    fun theInstallationGuidesIosSetupDeclaresEveryUsageDescription() = assertDeclaresEveryKey(guide, guideIosSetup)

    @Test
    fun theReadmeKeepsTheReadWriteKeyForADocumentsOnlyAppThatRequestsStoragePermission() =
        assertDocumentsOnlyKeepsTheReadWriteKey(readme, readmeIosSetup)

    @Test
    fun theInstallationGuideKeepsTheReadWriteKeyForADocumentsOnlyAppThatRequestsStoragePermission() =
        assertDocumentsOnlyKeepsTheReadWriteKey(guide, guideIosSetup)
}
