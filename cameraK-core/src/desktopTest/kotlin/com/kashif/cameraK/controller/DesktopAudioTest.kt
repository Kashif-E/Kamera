@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Desktop records video only (Decision 16): a recording that asks for audio fails fast with
 * `Unsupported(AUDIO)`, before any recorder is opened, and the engine can still record without it.
 */
class DesktopAudioTest {
    @Test
    fun enableAudioIsUnsupportedAudio() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)

            val start = async { sut.startRecording(VideoConfiguration { enableAudio = true }) }
            rig.settle()

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertEquals(CameraFeature.AUDIO, error.feature)
            assertEquals(emptyList(), rig.recorders.toList(), "a recorder was opened for an audio recording")
            assertEquals(emptyList(), events.toList(), "recording events of a start that was refused")

            steps.at(1_000)
            steps.startRecording(sut)
            assertEquals(1, rig.recorders.size, "a recording without audio after the refusal")
        } finally {
            sut.close()
            rig.settle()
        }
    }
}
