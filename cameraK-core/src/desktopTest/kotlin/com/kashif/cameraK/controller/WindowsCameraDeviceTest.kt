package com.kashif.cameraK.controller

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real Windows camera, named by CAMERAK_DSHOW_CAMERA (its friendly name; CI's Unity Capture camera),
 * opened at CameraK's 1280x720 and 30 fps: once by JavaCV alone, once through CameraK's dshow backend,
 * which lists through FFmpeg's list_devices log on a machine with no audio device. Skipped elsewhere.
 * Run each on its own JVM: a native crash in one ends the run.
 */
class WindowsCameraDeviceTest {
    private val camera: String? = System.getenv("CAMERAK_DSHOW_CAMERA")

    @Test
    fun javacvAloneGrabsAFrame() {
        assumeTrue("no CAMERAK_DSHOW_CAMERA", camera != null)
        val grabber = FFmpegFrameGrabber("video=$camera").apply { format = "dshow" }.askFor(DEFAULT_CAMERA_RESOLUTION)
        grabber.start()
        try {
            val frame = assertNotNull<Frame>(grabber.grab(), "no frame from $camera")
            assertTrue(frame.imageWidth > 0, "an empty frame")
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    @Test
    fun cameraKsDshowBackendListsAndGrabsAFrame() {
        assumeTrue("no CAMERAK_DSHOW_CAMERA", camera != null)
        val backend = DshowCaptureBackend()
        val lens = backend.list().singleOrNull() ?: error("listed ${backend.list().map { it.id }}")
        val grabber = backend.open(lens.id, DEFAULT_CAMERA_RESOLUTION)
        grabber.start()
        try {
            val frame = assertNotNull<Frame>(grabber.grab(), "no frame from ${lens.id}")
            assertTrue(frame.imageWidth > 0, "an empty frame")
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    @Test
    fun cameraKsDshowBackendListsTwiceWithoutOpening() {
        assumeTrue("no CAMERAK_DSHOW_CAMERA", camera != null)
        val backend = DshowCaptureBackend()
        val first = backend.list().map { it.id }
        val second = backend.list().map { it.id }
        println("WindowsCameraDeviceTest listed $first then $second")
        assertTrue(first.isNotEmpty() && first == second, "listed $first then $second")
    }

    @Test
    fun javacvAloneGrabsByAlternativeName() {
        val alternative = System.getenv("CAMERAK_DSHOW_ALT")
        assumeTrue("no CAMERAK_DSHOW_ALT", alternative != null)
        val grabber = FFmpegFrameGrabber("video=$alternative").apply {
            format = "dshow"
        }.askFor(DEFAULT_CAMERA_RESOLUTION)
        grabber.start()
        try {
            val frame = assertNotNull<Frame>(grabber.grab(), "no frame from $alternative")
            assertTrue(frame.imageWidth > 0, "an empty frame")
        } finally {
            grabber.stop()
            grabber.release()
        }
    }
}
