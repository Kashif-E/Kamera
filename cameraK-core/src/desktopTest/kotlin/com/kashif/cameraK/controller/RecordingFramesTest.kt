@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The recorder reads the producer's leased frames, not a copy of each (§8 desktop, D4'; closes
 * desktop-recording-pipeline's per-frame copy): a recording allocates no image per frame, so the
 * frames it is handed wrap the producer's few slots, each given back once written (W12: with no
 * conversion of the recording's own, the encoder is handed the slot's bytes).
 */
class RecordingFramesTest {
    @Test
    fun recorderUsesLeasedFramesWithoutCopies() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            repeat(FRAMES) { steps.frameAt(1_040L + it * 40) }
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            val recorder = rig.recorders.single()
            assertEquals(FRAMES, recorder.recordCount.get(), "frames recorded")
            // Each recorded frame wraps a slot's bytes; a frame over a buffer of its own has no array.
            val handed = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
            val written = recorder.buffers.count { buffer ->
                (buffer as? ByteBuffer)?.takeIf { it.hasArray() }?.let { handed += it.array() } != null
            }
            assertEquals(FRAMES, written, "frames recorded over a slot's bytes")
            // The producer holds the two latest frames for the preview and converts into a third
            // slot; a copy per frame would be ten images.
            assertTrue(handed.size <= 3, "images allocated for $FRAMES recorded frames: ${handed.size}")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private companion object {
        const val FRAMES = 10
    }
}
