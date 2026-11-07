@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The recording adds no conversion or copy of its own (W12 Y-desktop-rec): the encoder is handed a
 * `Frame` over the leased slot's own BGR24 bytes, the pixels the producer's blit wrote and the
 * preview shows. The producer's one blit stays, shared by every consumer.
 */
class RecordedFrameWrapTest {
    @Test
    fun theRecorderIsHandedTheLeasedSlotsOwnPixels() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
        try {
            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            val recorder = rig.recorders.single()
            repeat(FRAMES) { index ->
                steps.frameAt(1_040L + index * 40)
                // The frame the producer just published is the one the recorder took.
                val slotPixels = slotBytes(assertNotNull(sut.preview.frames.value, "no preview frame").image)
                assertEquals(index + 1, recorder.buffers.size, "frames recorded after frame $index")
                val handed = recorder.buffers.last()
                assertTrue(
                    handed is ByteBuffer && handed.hasArray(),
                    "frame $index: the recorder was handed a ${handed.javaClass.name}, not the leased slot's bytes",
                )
                assertSame(slotPixels, handed.array(), "frame $index: the recorder's buffer is not the slot's")
                assertEquals(0, handed.arrayOffset(), "frame $index: offset into the slot's bytes")
            }
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut), "stop")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    private fun slotBytes(image: BufferedImage): ByteArray = (image.raster.dataBuffer as DataBufferByte).data

    private companion object {
        const val FRAMES = 6
    }
}
