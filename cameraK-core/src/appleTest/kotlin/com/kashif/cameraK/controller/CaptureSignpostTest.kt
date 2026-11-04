@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A capture is one `CameraK.capture` interval, from the command until its file is written. */
class CaptureSignpostTest {
    private val tracer = RecordingTracer()
    private val driver = AppleEngineDriver(tracer = tracer)
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun captureOpensAndClosesOneInterval() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(aspectRatio = AspectRatio.RATIO_4_3))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val (capture, request) = captureReachingThePhotoOutput(driver, engine)
        assertEquals(
            listOf("begin CameraK.capture"),
            tracer.events.filter {
                "capture" in it
            },
            "while AVFoundation works",
        )
        val result = deliverPhoto(driver, request, capture, orientedJpeg(width = 4, height = 3, orientation = 1))
        written += assertIs<CameraKResult.Success<CapturedImage>>(result).value.path

        assertEquals(
            listOf("begin CameraK.capture", "end CameraK.capture"),
            tracer.events.filter { "capture" in it },
            "the capture's intervals",
        )
        engine.close()
    }
}
