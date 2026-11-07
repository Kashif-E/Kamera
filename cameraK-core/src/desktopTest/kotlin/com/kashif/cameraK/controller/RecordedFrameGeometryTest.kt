@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.capabilitiesOf
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recorded frame is the blit's crop and mirror, pixel for pixel (W12 Y-desktop-rec): the
 * recording passes the slot's bytes on unchanged, as the 1.x conversion did. The camera's 64 x 48
 * frames encode each pixel's place (blue 4 x, green 5 y, red 128), so every recorded pixel says which
 * source pixel it shows. Crops are centred with even sides: 4:3 is the whole frame, 16:9 cuts six
 * rows top and bottom, 1:1 eight columns left and right.
 */
class RecordedFrameGeometryTest {
    private val back = webcam("usb-back", CameraLens.BACK)
    private val front = webcam("usb-front", CameraLens.FRONT)

    @Test
    fun ratio4By3RecordsTheWholeFrameMirroredAndNot() = runTest {
        assertRecorded(AspectRatio.RATIO_4_3, Crop(0, 0, 64, 48))
    }

    @Test
    fun ratio16By9RecordsTheCentredCropMirroredAndNot() = runTest {
        assertRecorded(AspectRatio.RATIO_16_9, Crop(0, 6, 64, 36))
    }

    @Test
    fun ratio1By1RecordsTheCentredCropMirroredAndNot() = runTest {
        assertRecorded(AspectRatio.RATIO_1_1, Crop(8, 0, 48, 48))
    }

    private suspend fun TestScope.assertRecorded(ratio: AspectRatio, crop: Crop) {
        for ((camera, mirrored) in listOf(back to false, front to true)) {
            val label = "$ratio, ${if (mirrored) "mirrored" else "not mirrored"}"
            val recorded = recordOneFrame(ratio, camera)
            assertEquals(crop.width to crop.height, recorded.width to recorded.height, "$label: recorded size")
            val wrong = mutableListOf<String>()
            for (y in 0 until crop.height) {
                for (x in 0 until crop.width) {
                    val sourceX = crop.x + if (mirrored) crop.width - 1 - x else x
                    val expected = rgb(sourceX, crop.y + y)
                    val actual = recorded.rgb(x, y)
                    if (actual != expected) wrong += "($x, $y) ${hex(actual)} not ${hex(expected)}"
                }
            }
            assertTrue(wrong.isEmpty(), "$label: ${wrong.size} pixels differ, first ${wrong.take(5)}")
        }
    }

    /** One frame recorded on [camera] with [ratio] and `mirrorFrontCamera`, as the encoder got it. */
    private suspend fun TestScope.recordOneFrame(ratio: AspectRatio, camera: LensInfo): RecordedPixels {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.fill { x, y -> rgb(x, y) }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(
            CameraConfiguration {
                aspectRatio = ratio
                mirrorFrontCamera = true
            },
            enumerate = { capabilitiesOf(listOf(camera)) },
        )
        try {
            steps.open(sut, DesktopRecordingSteps.desiredOn(CameraSelection.ById(camera.id)))
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
            val recorder = rig.recorders.single()
            val recorded = recorder.pixels.single()
            assertEquals(recorded.width to recorded.height, recorder.sizeAtStart, "the size the recorder started at")
            return recorded
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    private fun rgb(x: Int, y: Int): Int = (128 shl 16) or (y * 5 shl 8) or (x * 4)

    private fun hex(color: Int) = "%06x".format(color)

    private fun webcam(id: String, lens: CameraLens) =
        LensInfo(id, CameraDeviceType.DEFAULT, lens, 1f, 1f, hasFlash = false, isLogical = false)
}
