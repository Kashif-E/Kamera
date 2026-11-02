package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import platform.Foundation.NSLock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Until I3' implements HEIC, a capture configured as HEIC fails rather than writing other bytes to a
 * `.heic` file: the photo output is never asked for a photo, nothing is written, and the refusal is
 * logged once.
 */
class HeicCaptureTest {
    private val rig = CaptureRig(imageFormat = ImageFormat.HEIC)
    private val lock = NSLock()
    private val logged = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        rig.tearDown()
    }

    @Test
    fun heicCaptureFailsWithoutAskingThePhotoOutput() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ ->
            if ("HEIC" in message) {
                lock.lock()
                logged += message
                lock.unlock()
            }
        }
        rig.start()

        val error = assertIs<ImageCaptureResult.Error>(rig.Capture().awaitResult())

        assertEquals("HEIC capture is not supported yet", error.exception.message)
        assertEquals(0, rig.capturer.requests.size, "a HEIC capture reached the photo output")
        lock.lock()
        val lines = logged.toList()
        lock.unlock()
        assertEquals(1, lines.size, "the refusal is logged once: $lines")
    }
}
