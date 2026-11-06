package com.kashif.cameraK.docs

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android setup in the README and the installation guide declares the permissions an app adds
 * itself. CameraK's own manifest declares CAMERA, and the media saver's declares WRITE_EXTERNAL_STORAGE
 * up to API 28; RECORD_AUDIO is the app's, needed only for a recording with audio.
 */
class AndroidInstallDocsTest {
    private val readme = DocsSources.README
    private val readmeAndroidSetup get() = DocsSources.section(
        readme,
        Regex("\\*\\*Android\\*\\*.*"),
        Regex("(#+ |\\*\\*[A-Z]).*"),
    )
    private val guide = DocsSources.INSTALLATION_GUIDE
    private val guideAndroidSetup get() = DocsSources.section(guide, Regex("### Android"), Regex("#{1,3} .*"))

    /**
     * RECORD_AUDIO is declared and tied to `enableAudio`, the one feature that needs it; every
     * WRITE_EXTERNAL_STORAGE is capped with maxSdkVersion 28, above which MediaStore needs no
     * permission for the app's own media and the permission grants nothing.
     */
    @Test
    fun recordAudioListedForAudioOnlyAndStorageCappedAt28() {
        val problems = listOf(readme to readmeAndroidSetup, guide to guideAndroidSetup).flatMap { (doc, section) ->
            val permissions = Regex("<uses-permission[^>]*>").findAll(section).map { it.value }.toList()
            val audio = DocsSources.statements(section).filter { "RECORD_AUDIO" in it }
            val uncapped = permissions.filter {
                "WRITE_EXTERNAL_STORAGE" in it && !Regex("""android:maxSdkVersion\s*=\s*"28"""").containsMatchIn(it)
            }
            listOfNotNull(
                "$doc does not declare RECORD_AUDIO"
                    .takeIf { permissions.none { "android.permission.RECORD_AUDIO" in it } },
                "$doc does not say RECORD_AUDIO is for enableAudio: $audio"
                    .takeIf { audio.none { "enableAudio" in it } },
                "$doc declares WRITE_EXTERNAL_STORAGE without maxSdkVersion 28: $uncapped"
                    .takeIf { uncapped.isNotEmpty() },
            )
        }
        assertTrue(problems.isEmpty(), "The Android setup is wrong:\n${problems.joinToString("\n")}")
    }
}
