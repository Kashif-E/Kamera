@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import platform.AVFoundation.AVCapturePhotoOutput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * From iOS 26 AVFoundation may start an output late (deferred start). The engine turns it off on the
 * photo output, so captures issued right after the camera reports Ready all run.
 */
class DeferredStartTest {
    private val driver = AppleEngineDriver(osVersion = 26)
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun threeCapturesRightAfterReadyAllComplete() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(aspectRatio = AspectRatio.RATIO_4_3))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val captures = List(3) { async { engine.capture() } }
        repeat(20) { if (driver.photoCapturer.requests.size < 3) driver.settle() }
        assertEquals(3, driver.photoCapturer.requests.size, "captures right after Ready that reached the photo output")
        assertTrue(
            driver.photoCapturer.deferredStartDisabled.any { it is AVCapturePhotoOutput },
            "the photo output's deferred start was left on",
        )

        driver.photoCapturer.requests.forEachIndexed { i, request ->
            val image = deliverPhoto(driver, request, captures[i], orientedJpeg(width = 4, height = 3, orientation = 1))
            written += assertIs<CameraKResult.Success<CapturedImage>>(image, "capture $i").value.path
        }
        engine.close()
    }
}
