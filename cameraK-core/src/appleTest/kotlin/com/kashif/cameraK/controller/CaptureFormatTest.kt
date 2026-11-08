@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Captures overlap, as on Android: three in flight each get their own photo whatever order
 * AVFoundation finishes them in (the holder alone limits how many are in flight; PlatformCounterGoneTest).
 * QUALITY asks for the camera's largest photo through maxPhotoDimensions, not the high-resolution
 * flags deprecated since iOS 16.
 */
class CaptureFormatTest {
    private val photoSizes =
        listOf(PhotoDimensions(4032, 3024), PhotoDimensions(8064, 6048), PhotoDimensions(1920, 1080))
    private var rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    private fun photo(seed: Int) = ByteArray(48) { (it + seed).toByte() }

    @Test
    fun threeCapturesInFlightEachGetTheirOwnPhoto() {
        rig.start()
        val captures = List(3) { rig.capture() }

        // AVFoundation may finish them in any order.
        captures.withIndex().reversed().forEach { (i, pair) ->
            pair.second.callbacks.photoProcessed(photo(i).toNSData(), null)
            pair.second.callbacks.captureFinished(null)
        }
        captures.forEachIndexed { i, (capture, _) ->
            val result = assertIs<CameraKResult.Success<CapturedImage>>(
                capture.awaitResult(),
                "capture $i failed",
            ).value
            assertContentEquals(photo(i), fileBytes(result.path), "capture $i got another capture's photo")
        }
        awaitTrue("every capture delegate to be released") { rig.custom.outstandingPhotoDelegates == 0 }
    }

    @Test
    fun aCancelledCapturesLatePhotoDoesNotReachTheNextCapture() {
        rig.start()
        val (cancelled, cancelledRequest) = rig.capture()
        cancelled.job.cancel()
        awaitTrue("the cancelled capture to return") { cancelled.job.isCompleted }

        val (next, nextRequest) = rig.capture()
        cancelledRequest.callbacks.photoProcessed(photo(1).toNSData(), null)
        cancelledRequest.callbacks.captureFinished(null)
        nextRequest.callbacks.photoProcessed(photo(2).toNSData(), null)
        nextRequest.callbacks.captureFinished(null)

        val result = assertIs<CameraKResult.Success<CapturedImage>>(next.awaitResult()).value
        assertContentEquals(photo(2), fileBytes(result.path), "the cancelled capture's photo reached the next one")
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun qualityRequestsTheCamerasLargestPhotoThroughMaxPhotoDimensions() {
        rig = CaptureRig(
            device = FakeDevice(supportedMaxPhotoDimensions = photoSizes),
            qualityPrioritization = QualityPrioritization.QUALITY,
        )
        rig.start()
        drainSessionThenMain(rig.custom)
        val largest = PhotoDimensions(8064, 6048)

        assertEquals(
            listOf(largest),
            rig.capturer.maxPhotoDimensionsSet,
            "the photo output was not opened up to the largest size",
        )

        val (_, request) = rig.capture()
        assertEquals(
            largest,
            request.settings.maxPhotoDimensions.toPhotoDimensions(),
            "the capture asked for another size",
        )
        @Suppress("DEPRECATION")
        assertFalse(
            request.settings.isHighResolutionPhotoEnabled(),
            "the capture used the deprecated high-resolution flag",
        )
    }

    // On the iPhone 14 Pro a 16:9 QUALITY capture with a target of 1280x720 was 4224x2376 while BALANCED,
    // SPEED and NONE gave 1280x720 (device matrix): QUALITY opened the output to the camera's largest
    // photo whatever the target. The target is the size to aim for (CameraConfiguration.targetResolution).
    @Test
    fun qualityWithATargetThePresetReachesKeepsThePresetsSize() {
        rig = CaptureRig(
            device = FakeDevice(supportedMaxPhotoDimensions = sixteenNineSizes),
            qualityPrioritization = QualityPrioritization.QUALITY,
            aspectRatio = AspectRatio.RATIO_16_9,
            targetResolution = 1280 to 720,
        )
        rig.start()
        drainSessionThenMain(rig.custom)

        assertEquals(
            emptyList(),
            rig.capturer.maxPhotoDimensionsSet,
            "a 1280x720 target, which the 1280x720 preset reaches, opened the output to a larger photo",
        )
    }

    @Test
    fun qualityWithATargetAbovePresetsAsksForTheSmallestPhotoThatReachesIt() {
        rig = CaptureRig(
            device = FakeDevice(supportedMaxPhotoDimensions = sixteenNineSizes),
            qualityPrioritization = QualityPrioritization.QUALITY,
            aspectRatio = AspectRatio.RATIO_16_9,
            targetResolution = 4000 to 2250,
        )
        rig.start()
        drainSessionThenMain(rig.custom)

        assertEquals(
            listOf(PhotoDimensions(4224, 2376)),
            rig.capturer.maxPhotoDimensionsSet,
            "a target above the largest 16:9 preset asks for the smallest photo that reaches it",
        )
    }

    private val sixteenNineSizes =
        listOf(PhotoDimensions(8448, 4752), PhotoDimensions(4224, 2376), PhotoDimensions(3840, 2160))
}
