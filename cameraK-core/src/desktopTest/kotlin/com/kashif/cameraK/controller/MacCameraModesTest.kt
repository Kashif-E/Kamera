package com.kashif.cameraK.controller

import com.kashif.cameraK.controller.FakeMacCameras.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether a macOS camera has a mode, read from its AVCaptureDeviceFormats the way FFmpeg's
 * avfoundation input picks one: the same dimensions, and a frame rate range whose maximum is the rate
 * asked for (within 0.01), as configure_video_device matches it; a 1-60 range does not open at 30.
 * Unknown (null) when the camera or the runtime cannot be read, or for the default entry, which FFmpeg
 * opens as its own device 0, so the open goes ahead.
 */
class MacCameraModesTest {
    private val faceTime = FakeMacCameras.Device(
        "built-in",
        "FaceTime HD Camera",
        0,
        FakeMacCameras.BUILT_IN,
        formats = listOf(
            Format(1920, 1080, listOf(1.0 to 30.0)),
            Format(1280, 720, listOf(1.0 to 15.0, 15.0 to 30.0)),
            Format(1552, 1552, listOf(1.0 to 30.0)),
            Format(640, 480, listOf(1.0 to 25.0)),
            Format(3840, 2160, listOf(1.0 to 60.0)),
        ),
    )
    private val mac = MacDevices({ FakeMacCameras(listOf(faceTime)) })

    @Test
    fun aSizeTheCameraHasAtTheRateAskedIsAMode() {
        assertEquals(true, mac.hasMode("built-in", 1280 to 720, 30.0))
        assertEquals(true, mac.hasMode("built-in", 1920 to 1080, 30.0))
    }

    @Test
    fun aSizeTheCameraLacksIsNotAMode() {
        assertEquals(false, mac.hasMode("built-in", 2560 to 1440, 30.0))
        assertEquals(false, mac.hasMode("built-in", 720 to 1280, 30.0))
    }

    @Test
    fun aSizeWhoseRatesStopBelowTheRateAskedIsNotAMode() {
        assertEquals(false, mac.hasMode("built-in", 640 to 480, 30.0))
    }

    @Test
    fun aSizeWhoseRangeOnlyPassesTheRateAskedIsNotAMode() {
        assertEquals(false, mac.hasMode("built-in", 3840 to 2160, 30.0))
    }

    @Test
    fun theDefaultEntryIsUnknownSinceFfmpegOpensItsOwnDeviceZero() {
        assertNull(mac.hasMode(desktopLensInfo().id, 1552 to 1552, 30.0))
    }

    @Test
    fun aCameraThatIsGoneOrARuntimeThatCannotBeReadIsUnknown() {
        assertNull(mac.hasMode("unplugged", 1280 to 720, 30.0))
        assertNull(MacDevices({ throw UnsatisfiedLinkError("no JNA") }).hasMode("built-in", 1280 to 720, 30.0))
        val noFormats = FakeMacCameras.Device("usb", "USB Camera", 0, FakeMacCameras.EXTERNAL)
        assertNull(MacDevices({ FakeMacCameras(listOf(noFormats)) }).hasMode("usb", 1280 to 720, 30.0))
    }
}
