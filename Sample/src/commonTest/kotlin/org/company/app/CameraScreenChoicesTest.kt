package org.company.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.kashif.cameraK.enums.DeviceOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The camera screen's own choices survive a rotation (design §12.3 LC': the Sample is made
 * rotation-safe before configChanges goes). The retained holder keeps a recording through a
 * rotation, so the recreated screen must still offer the button that stops it.
 */
@OptIn(ExperimentalTestApi::class)
class CameraScreenChoicesTest {
    // What an Android Bundle can hold: the saver must reduce a choice to these.
    private val bundleScope = SaverScope { it is String || it is Boolean || it is List<*> }

    private fun roundTrip(choices: CameraScreenChoices): CameraScreenChoices {
        val saved = assertNotNull(with(CameraScreenChoicesSaver) { bundleScope.save(choices) }, "nothing was saved")
        return assertNotNull(CameraScreenChoicesSaver.restore(saved), "nothing was restored from $saved")
    }

    // A recreation, as an Activity's is: the saveable state is saved, the screen leaves the
    // composition, and a new screen composes at the same place with the saved state.
    @Test
    fun everyChoiceSurvivesARecreation() = runComposeUiTest {
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var shown by mutableStateOf(true)
        var choices: MutableState<CameraScreenChoices>? = null
        setContent {
            if (shown) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    choices = rememberCameraScreenChoices()
                }
            }
        }
        val changed = CameraScreenChoices(
            mode = CameraMode.Video,
            qrScanning = false,
            ocr = false,
            lockedOrientation = DeviceOrientation.LANDSCAPE_LEFT,
        )
        runOnIdle { assertNotNull(choices).value = changed }

        val saved = runOnIdle { registry.performSave() }
        runOnIdle {
            shown = false
            choices = null
        }
        runOnIdle {
            registry = SaveableStateRegistry(saved) { true }
            shown = true
        }

        runOnIdle { assertEquals(changed, assertNotNull(choices).value, "the recreated screen lost its choices") }
    }

    @Test
    fun restoresEveryChoice() {
        val defaults = CameraScreenChoices()
        assertEquals(defaults, roundTrip(defaults), "the defaults, with no orientation lock")
        // Each choice on its own, so one that is dropped cannot hide behind another.
        listOf(
            defaults.copy(mode = CameraMode.Video),
            defaults.copy(qrScanning = false),
            defaults.copy(ocr = false),
        ).forEach { assertEquals(it, roundTrip(it)) }
        DeviceOrientation.entries.forEach {
            val locked = defaults.copy(lockedOrientation = it)
            assertEquals(locked, roundTrip(locked))
        }
    }

    @Test
    fun aLiveRecordingShowsTheVideoModeWhateverWasSelected() {
        assertEquals(
            CameraMode.Video,
            CameraScreenChoices(mode = CameraMode.Photo).shownMode(recordingLive = true),
            "a live recording left the shutter taking photos, with no way to stop the recording",
        )
        assertEquals(CameraMode.Photo, CameraScreenChoices(mode = CameraMode.Photo).shownMode(recordingLive = false))
        assertEquals(CameraMode.Video, CameraScreenChoices(mode = CameraMode.Video).shownMode(recordingLive = false))
    }
}
