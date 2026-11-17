package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.controller.FakeMacCameras.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sizes a macOS camera has a mode for, read from its AVCaptureDeviceFormats the way FFmpeg's
 * avfoundation input picks one: a frame rate range whose maximum is the rate asked for (within 0.01),
 * as configure_video_device matches it; a 1-60 range does not open at 30. Unknown (null) when the
 * camera or the runtime cannot be read, or for the default entry, which FFmpeg opens as its own device 0.
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
            Format(1920, 1080, listOf(1.0 to 30.0)),
        ),
    )
    private val mac = MacDevices({ FakeMacCameras(listOf(faceTime)) })

    @Test
    fun theSizesAreThoseWithARangeToppingOutAtTheRate() {
        assertEquals(listOf(1920 to 1080, 1280 to 720, 1552 to 1552), mac.sizes("built-in", 30.0))
        assertEquals(listOf(640 to 480), mac.sizes("built-in", 25.0))
        assertEquals(listOf(3840 to 2160), mac.sizes("built-in", 60.0))
    }

    // A camera whose modes top out elsewhere (29.97) is known to have none at the rate: unknown would
    // send the open through a failed avfoundation open and a quick reopen, the sequence that crashed.
    @Test
    fun aCameraWithNoModeAtTheRateHasNoSizes() {
        assertEquals(emptyList(), mac.sizes("built-in", 29.97))
    }

    @Test
    fun theDefaultEntryIsUnknownSinceFfmpegOpensItsOwnDeviceZero() {
        assertNull(mac.sizes(desktopLensInfo().id, 30.0))
    }

    @Test
    fun aCameraThatIsGoneOrARuntimeThatCannotBeReadIsUnknown() {
        assertNull(mac.sizes("unplugged", 30.0))
        assertNull(MacDevices({ throw UnsatisfiedLinkError("no JNA") }).sizes("built-in", 30.0))
        val noFormats = FakeMacCameras.Device("usb", "USB Camera", 0, FakeMacCameras.EXTERNAL)
        assertNull(MacDevices({ FakeMacCameras(listOf(noFormats)) }).sizes("usb", 30.0))
    }
}
