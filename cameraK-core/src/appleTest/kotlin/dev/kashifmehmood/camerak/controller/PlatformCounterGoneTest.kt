@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.CapturedImage
import dev.kashifmehmood.camerak.engine.CameraEngineFactory
import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.CameraOperation
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKState
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iOS controller no longer counts captures (pending-captures-double-decrement): the holder holds the
 * one counter and returns Busy(CAPTURE) for a fourth capture in flight. Every capture the engine is
 * asked for reaches the photo output.
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
        val engineRig = CaptureRig().also { rig = it }
        engineRig.start()
        val engineCaptures = List(4) { engineRig.capture() }
        assertEquals(4, engineRig.capturer.requests.size, "engine captures that reached the photo output")
        engineCaptures.forEach { (_, request) ->
            request.callbacks.photoProcessed(orientedJpeg(width = 4, height = 3, orientation = 1), null)
            request.callbacks.captureFinished(null)
        }
        engineCaptures.forEach { (capture, _) -> capture.awaitResult() }

        val driver = AppleEngineDriver()
        driver.runCase {
            val holder = CameraKStateHolder(
                CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 },
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
