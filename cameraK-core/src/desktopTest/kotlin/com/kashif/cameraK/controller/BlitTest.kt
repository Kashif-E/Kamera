@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.capabilitiesOf
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The aspect ratio's crop and the front camera's mirror happen once per frame, in the producer's
 * blit, so they reach every consumer: the preview, stills, recordings and analysis frames (§2.4,
 * §6.1 desktop, D4'). Desktop has no separate preview mirror: `mirrorFrontCamera` mirrors all four
 * for a FRONT camera and never for a BACK one.
 *
 * The camera's 64 x 48 frames encode each pixel's place: blue is 4 x, green is 5 y and red 128, so
 * a consumer's top-left pixel says which source pixel it shows.
 */
class BlitTest {
    private val back = webcam("usb-back", CameraLens.BACK)
    private val front = webcam("usb-front", CameraLens.FRONT)

    @Suppress("DEPRECATION")
    @Test
    fun aspectRatioCropAppliedToPreviewStillsRecordingsAndAnalysis() = runTest {
        // 64 x 48 is 4:3; each ratio is the centred crop with even sides. RATIO_9_16 is the 16:9 crop,
        // as on Android and iOS (W11).
        val expected = mapOf(
            AspectRatio.RATIO_16_9 to Seen(64, 36, rgb(0, 6)),
            AspectRatio.RATIO_9_16 to Seen(64, 36, rgb(0, 6)),
            AspectRatio.RATIO_1_1 to Seen(48, 48, rgb(8, 0)),
            AspectRatio.RATIO_4_3 to Seen(64, 48, rgb(0, 0)),
        )
        for ((ratio, frame) in expected) {
            val consumers = consumersOf(CameraConfiguration { aspectRatio = ratio }, back)
            assertEquals(Consumers(frame, frame, frame, frame), consumers, "$ratio")
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun ratio9By16GivesTheSameGeometryAs16By9() = runTest {
        // One meaning on every platform: the 16:9 sensor ratio, with orientation deciding portrait or
        // landscape. A landscape webcam frame is cropped to 16:9, never to a 9:16 strip.
        val nineBySixteen = consumersOf(CameraConfiguration { aspectRatio = AspectRatio.RATIO_9_16 }, back)

        assertEquals(consumersOf(CameraConfiguration { aspectRatio = AspectRatio.RATIO_16_9 }, back), nineBySixteen)
    }

    @Test
    fun frontMirrorAppliedToAllFourConsumers() = runTest {
        val config = CameraConfiguration {
            aspectRatio = AspectRatio.RATIO_4_3
            mirrorFrontCamera = true
        }
        val mirrored = Seen(64, 48, rgb(63, 0))

        assertEquals(Consumers(mirrored, mirrored, mirrored, mirrored), consumersOf(config, front))
    }

    @Test
    fun backCameraNeverMirroredByMirrorFrontCamera() = runTest {
        val config = CameraConfiguration {
            aspectRatio = AspectRatio.RATIO_4_3
            mirrorFrontCamera = true
        }
        val unmirrored = Seen(64, 48, rgb(0, 0))

        assertEquals(Consumers(unmirrored, unmirrored, unmirrored, unmirrored), consumersOf(config, back))
    }

    /** One frame through an engine on [camera], and what each of the four consumers received. */
    private suspend fun TestScope.consumersOf(config: CameraConfiguration, camera: LensInfo): Consumers {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.fill { x, y -> rgb(x, y) }
        val stills = CopyOnWriteArrayList<Seen>()
        rig.writeCapture = { image, format, file ->
            stills += seen(image)
            writeImage(image, format, file)
        }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(
            config.copy { analysisFormat = FrameFormat.RGBA_8888 },
            enumerate = { capabilitiesOf(listOf(camera)) },
        )
        val analyzed = CopyOnWriteArrayList<Seen>()
        val registration = sut.frames.register(FrameSpec()) { frame ->
            val pixel = ByteArray(4)
            frame.planes[0].copyTo(pixel, 0, 0, 4)
            val color = (pixel[0].toInt() and 0xFF shl 16) or (pixel[1].toInt() and 0xFF shl 8) or
                (pixel[2].toInt() and 0xFF)
            analyzed += Seen(frame.width, frame.height, color)
        }
        try {
            steps.open(sut, DesktopRecordingSteps.desiredOn(CameraSelection.ById(camera.id)))
            val preview = seen(assertNotNull(sut.preview.frames.value, "no preview frame").image)

            val pending = async { sut.capture() }
            rig.settle()
            steps.frameAt(1_000)
            assertIs<CameraKResult.Success<CapturedImage>>(pending.await(), "capture")

            steps.startRecording(sut)
            steps.frameAt(1_040)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
            val recorder = rig.recorders.single()
            // The frame the encoder was handed: the slot's own bytes, read at the frame's stride.
            val recorded = recorder.pixels.map { Seen(it.width, it.height, it.rgb(0, 0)) }
            val recorderSize = recorder.sizeAtStart

            return Consumers(
                preview = preview,
                still = stills.single(),
                recording = recorded.first().also {
                    assertEquals(it.width to it.height, recorderSize, "the size the recorder started at")
                },
                analysis = analyzed.firstOrNull(),
            )
        } finally {
            registration.close()
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    private data class Seen(val width: Int, val height: Int, val topLeft: Int)

    private data class Consumers(val preview: Seen, val still: Seen, val recording: Seen, val analysis: Seen?)

    private fun seen(image: BufferedImage) = Seen(image.width, image.height, image.getRGB(0, 0) and 0xFFFFFF)

    private fun rgb(x: Int, y: Int): Int = (128 shl 16) or (y * 5 shl 8) or (x * 4)

    private fun webcam(id: String, lens: CameraLens) =
        LensInfo(id, CameraDeviceType.DEFAULT, lens, 1f, 1f, hasFlash = false, isLogical = false)
}
