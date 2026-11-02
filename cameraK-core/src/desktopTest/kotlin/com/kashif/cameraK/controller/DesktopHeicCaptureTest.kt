package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
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
 * Until D3' reports HEIC unsupported at start, a capture configured as HEIC fails rather than writing
 * a JPEG: nothing is written under the user's home, and the refusal is logged once.
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
    fun heicCapture_failsWithoutWritingAFile() {
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
            val result = runBlocking { withTimeout(10_000) { sut.takePictureToFile() } }

            val error = assertIs<ImageCaptureResult.Error>(result, "capture result: $result")
            assertEquals("HEIC capture is not supported yet", error.exception.message)
            assertEquals(emptyList(), home.walk().filter { it.isFile }.map { it.name }.toList(), "files written")
            assertEquals(1, logged.size, "the refusal is logged once: $logged")
        } finally {
            sut.cleanup()
        }
    }
}
