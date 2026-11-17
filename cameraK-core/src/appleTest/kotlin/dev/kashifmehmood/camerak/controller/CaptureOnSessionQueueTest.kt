@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.QualityPrioritization
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A capture is one session-queue block (design §2.4, review change 5): its settings are built from the
 * photo output as it is when the block runs, after every piece of session work queued before it. A
 * lens switch queued just before the capture therefore decides the photo size, not the camera that
 * was in use when capture was called.
 */
class CaptureOnSessionQueueTest {
    private val backLargest = PhotoDimensions(8064, 6048)
    private val frontLargest = PhotoDimensions(4032, 3024)
    private val driver = AppleEngineDriver(
        backPhotoSizes = listOf(PhotoDimensions(4032, 3024), backLargest),
        frontPhotoSizes = listOf(PhotoDimensions(1920, 1440), frontLargest),
    )

    @Test
    fun queuedSwapToSmallerCameraThenCaptureUsesTheOutputAtThatMoment() = driver.runCase {
        val engine = driver.createEngine(
            CameraConfiguration {
                qualityPrioritization = QualityPrioritization.QUALITY
                aspectRatio = AspectRatio.RATIO_4_3
            },
        )
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.settle()

        val switch = async { engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)) }
        val (capture, request) = captureReachingThePhotoOutput(driver, engine)

        assertEquals(
            frontLargest,
            request.settings.maxPhotoDimensions.toPhotoDimensions(),
            "the capture asked for the camera in use when it was called, not the one behind the output",
        )
        switch.await()
        capture.cancel()
        engine.close()
    }
}
