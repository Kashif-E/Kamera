@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The out-of-range zoom rule (design §6.2). On a camera with a zoom range, setZoom clamps into it on
 * the session queue before the device sees the value, because AVFoundation raises an exception
 * nothing can catch for a factor outside it; the command returns the clamped ratio without waiting
 * for the report. A camera with a fixed zoom takes that one ratio and refuses any other.
 */
class ZoomTest {
    @Test
    fun outOfRangeZoomIsClampedBeforeTheDeviceSeesIt() {
        val driver = AppleEngineDriver(adjustableBack = true)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val before = driver.back.callsOf("setVideoZoomFactor").size

            assertEquals(CameraKResult.Success(ZoomState(4f, 1f, 4f)), engine.setZoom(9f), "a ratio above the range")
            assertEquals(CameraKResult.Success(ZoomState(1f, 1f, 4f)), engine.setZoom(0.2f), "a ratio below the range")
            assertEquals(
                CameraKResult.Success(ZoomState(2.5f, 1f, 4f)),
                engine.setZoom(2.5f),
                "a ratio inside the range",
            )

            val sets = driver.back.callsOf("setVideoZoomFactor").drop(before)
            assertEquals(listOf<Any?>(4.0, 1.0, 2.5), sets.map { it.value }, "the factors the device was given")
            assertEquals(emptyList(), driver.back.outOfRangeZoomSets, "the device was given a factor outside its range")
            assertEquals(emptyList(), sets.filter { !it.locked }, "zoom set without the configuration lock")
            assertEquals(setOf(SESSION_QUEUE), sets.map { it.queue }.toSet(), "zoom set off the session queue")
            engine.close()
        }
    }

    @Test
    fun fixedZoomCameraRefusesAnyOtherRatio() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            assertEquals(CameraKResult.Success(ZoomState(1f, 1f, 1f)), engine.setZoom(1f), "the camera's one ratio")
            val refused = assertIs<CameraKResult.Failure>(engine.setZoom(2f), "another ratio on a fixed zoom")
            assertEquals(CameraFeature.ZOOM, assertIs<CameraKError.Unsupported>(refused.error).feature)
            assertEquals(
                emptyList(),
                driver.back.callsOf("setVideoZoomFactor").filter { it.value != 1.0 },
                "a fixed-zoom camera was given another factor",
            )
            engine.close()
        }
    }

    private companion object {
        const val SESSION_QUEUE = "com.kashif.cameraK.session"
    }
}
