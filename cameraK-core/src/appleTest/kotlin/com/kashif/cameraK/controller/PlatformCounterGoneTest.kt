@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iOS controller no longer counts captures (pending-captures-double-decrement): the holder holds the
 * one counter and returns Busy(CAPTURE) for a fourth capture in flight. Every capture the facade or the
 * engine is asked for reaches the photo output.
 */
class PlatformCounterGoneTest {
    private val written = mutableListOf<String>()
    private var rig: CaptureRig? = null

    @AfterTest
    fun tearDown() {
        rig?.tearDown()
        removeFiles(written)
    }

    @Test
    fun fourCapturesReachTheEngineAndTheHolderAloneRejectsTheFourth() {
        val facadeRig = CaptureRig().also { rig = it }
        facadeRig.start()
        val facadeCaptures = List(4) { facadeRig.capture() }
        assertEquals(4, facadeRig.capturer.requests.size, "facade captures that reached the photo output")
        facadeCaptures.forEach { (_, request) ->
            request.callbacks.photoProcessed(orientedJpeg(width = 4, height = 3, orientation = 1), null)
            request.callbacks.captureFinished(null)
        }
        facadeCaptures.forEach { (capture, _) -> capture.awaitResult() }

        val driver = AppleEngineDriver()
        driver.runCase {
            val holder = CameraKStateHolder(
                CameraConfiguration(aspectRatio = AspectRatio.RATIO_4_3),
                CameraEngineFactory { config, _ -> driver.createEngine(config) },
                environment = cameraKTestEnvironment(),
            )
            holder.start()
            repeat(20) { if (holder.cameraState.value !is CameraKState.Ready) driver.settle() }
            assertIs<CameraKState.Ready>(holder.cameraState.value, "the holder's camera")

            val captures = List(4) { async { holder.capture() } }
            repeat(20) { if (driver.photoCapturer.requests.size < 3) driver.settle() }
            assertEquals(3, driver.photoCapturer.requests.size, "holder captures that reached the photo output")
            val fourth = captures[3].await()
            assertEquals(
                CameraOperation.CAPTURE,
                assertIs<CameraKError.Busy>(assertIs<CameraKResult.Failure>(fourth).error).operation,
                "the fourth capture in flight",
            )
            driver.photoCapturer.requests.forEachIndexed { i, request ->
                val image =
                    deliverPhoto(driver, request, captures[i], orientedJpeg(width = 4, height = 3, orientation = 1))
                written += assertIs<CameraKResult.Success<CapturedImage>>(image, "holder capture $i").value.path
            }
            holder.closeAndJoin()
        }
    }
}
