package dev.kashifmehmood.camerak.controller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The retry at the camera's own mode (§4.1 row 3) drops CameraK's size. FFmpeg's avfoundation input
 * then still asks for a frame rate, its own default of 29.97 (ntsc), and opens only a format whose
 * top rate matches it: no FaceTime camera mode does (each tops out at exactly 30), so the retry failed
 * as Hardware(-5) where it should have opened. On macOS the retry keeps CameraK's 30; v4l2 and dshow
 * have no such default, so their retry asks for nothing.
 */
class OwnModeGrabberTest {
    @Test
    fun anAvfoundationGrabberAtTheCamerasOwnModeKeepsCameraKsFrameRate() {
        val grabber = ffmpegGrabber(DeviceInput("FaceTime HD Camera", "avfoundation"), size = null)

        assertEquals(30.0, grabber.frameRate)
        assertEquals(0, grabber.imageWidth)
        assertEquals(0, grabber.imageHeight)
    }

    @Test
    fun v4l2AndDshowGrabbersAtTheCamerasOwnModeAskForNothing() {
        for (input in listOf(DeviceInput("/dev/video0", null), DeviceInput("video=USB Camera", "dshow"))) {
            val grabber = ffmpegGrabber(input, size = null)

            assertEquals(0.0, grabber.frameRate, "$input")
            assertEquals(0, grabber.imageWidth, "$input")
        }
    }
}
