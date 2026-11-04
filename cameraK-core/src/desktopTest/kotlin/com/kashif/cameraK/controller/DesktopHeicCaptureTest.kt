@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * HEIC is not a desktop format (§6.1). From D3' the start of a facade configured as HEIC fails with
 * `Unsupported(IMAGE_FORMAT)`, which logs the refusal once; a capture after it returns
 * `ImageCaptureResult.Error` and nothing is written under the user's home.
 */
class DesktopHeicCaptureTest {
    private val home: File = Files.createTempDirectory("camerak-home").toFile()
    private val logged = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        home.deleteRecursively()
    }

    @Test
    fun heicConfiguredFacadeWritesNothing() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> if ("HEIC" in message) logged += message }
        val sut = CameraController(
            imageFormat = ImageFormat.HEIC,
            directory = Directory.PICTURES,
            customGrabber = FakeFrameGrabber().apply { openGate() },
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> FakeFrameRecorder().apply { finishFirstRecord() } },
            userHome = home,
        )
        try {
            sut.startSession()
            val failed = runBlocking {
                withTimeout(10_000) { sut.engine.state.first { it is EngineState.Failed } }
            }
            val error = assertIs<CameraKError.Unsupported>(assertIs<EngineState.Failed>(failed).error)
            assertEquals(CameraFeature.IMAGE_FORMAT, error.feature, "the start of a HEIC facade")

            val result = runBlocking { withTimeout(10_000) { sut.takePictureToFile() } }

            assertIs<ImageCaptureResult.Error>(result, "capture result: $result")
            assertEquals(emptyList(), home.walk().filter { it.isFile }.map { it.name }.toList(), "files written")
            assertEquals(1, logged.size, "the refusal is logged once: $logged")
        } finally {
            sut.cleanup()
        }
    }
}
