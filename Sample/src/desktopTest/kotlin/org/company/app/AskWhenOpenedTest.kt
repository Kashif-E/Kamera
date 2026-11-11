package org.company.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.kashif.cameraK.permissions.CameraKPermission
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Sample asks for its permissions when it opens. A rotation recreates the screen from its saved
 * state; asking again there, for a permission just refused, shows the dialog on every rotation until
 * Android stops showing it.
 */
class AskWhenOpenedTest {
    private val withoutGallery = samplePermissions(autoSave = false)

    @Test
    fun aRecreatedScreenDoesNotAskAgainForWhatWasRefused() = runTest {
        var requests = 0
        val saved = open(this, restored = null, asked = withoutGallery) { requests++ }
        assertEquals(1, requests, "requests when the app opened")

        open(this, restored = saved, asked = withoutGallery) { requests++ }

        assertEquals(1, requests, "requests once a rotation recreated the screen with the microphone refused")
    }

    @Test
    fun turningSaveToGalleryOnAsksForTheGallery() = runTest {
        var requests = 0
        val saved = open(this, restored = null, asked = withoutGallery) { requests++ }

        open(this, restored = saved, asked = samplePermissions(autoSave = true)) { requests++ }

        assertEquals(2, requests, "requests once the gallery was added to what is asked")
    }

    @Test
    fun nothingIsAskedWhenEverythingIsGranted() = runTest {
        var requests = 0
        open(this, restored = null, asked = withoutGallery, allGranted = true) { requests++ }

        assertEquals(0, requests)
    }

    // Composes the ask as a screen restored from [restored], and returns what that screen saves.
    private fun open(
        scope: TestScope,
        restored: Map<String, List<Any?>>?,
        asked: List<CameraKPermission>,
        allGranted: Boolean = false,
        launchRequest: () -> Unit,
    ): Map<String, List<Any?>> {
        val registry = SaveableStateRegistry(restored) { true }
        SampleScene(scope) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                AskWhenOpened(asked = asked, allGranted = allGranted, launchRequest = launchRequest)
            }
        }.use { return registry.performSave() }
    }
}
