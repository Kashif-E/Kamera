package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Windows cameras are listed and opened through one internal seam, [WindowsCaptureBackend]
 * (Decision 17). The check that videoinput loads against javacpp 1.5.12 cannot run on this host,
 * so FFmpeg dshow is the default: its ids are dshow's "alternative names" from
 * `avdevice_list_input_sources`, which dshow accepts in place of the friendly name in `video=`.
 * These run on any host, over fakes of the native listing.
 */
class WindowsBackendTest {
    @Test
    fun enumeratesThroughTheChosenBackendSeam() {
        val camera = NoCamera()
        val backend = object : WindowsCaptureBackend {
            val opened = mutableListOf<Pair<String, Pair<Int, Int>?>>()

            override fun list(): List<LensInfo> = listOf(webcam("first"), webcam("second"))

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber {
                opened += id to size
                return camera
            }
        }
        val windows = platformDevices(OperatingSystem.WINDOWS, windows = backend)

        assertEquals(listOf("first", "second"), DeviceEnumerator(windows).lenses().map { it.id })
        assertSame<FrameGrabber>(camera, windows.open("second", 1280 to 720))
        assertEquals(listOf<Pair<String, Pair<Int, Int>?>>("second" to (1280 to 720)), backend.opened)
    }

    @Test
    fun dshowIsTheDefaultUntilTheWindowsCheck() {
        assertIs<DshowCaptureBackend>(defaultWindowsCaptureBackend())
        assertIs<DshowCaptureBackend>(platformDevices(OperatingSystem.WINDOWS))
    }

    @Test
    fun dshowIdIsTheAlternativeNameAndOpensAsVideoEquals() {
        val webcam = PNP + """usb#vid_046d&pid_082d&mi_00#6&2d6b1f5e&0&0000""" + VIDEO_CLASS
        val builtIn = PNP + """usb#vid_04f2&pid_b6d9&mi_00#6&1a2b3c4d&0&0000""" + VIDEO_CLASS
        val microphone = """@device_cm_{33D9A762-90C8-11D0-BD43-00A0C911CE86}""" +
            """\wave_{0A1B2C3D-0000-0000-0000-000000000000}"""
        val requests = mutableListOf<Pair<DeviceInput, Pair<Int, Int>?>>()
        val camera = NoCamera()
        val dshow = DshowCaptureBackend(
            sources = {
                listOf(
                    DshowSource(webcam, "HD Pro Webcam C920", isVideo = true),
                    DshowSource(microphone, "Microphone (HD Pro Webcam C920)", isVideo = false),
                    DshowSource(builtIn, "Integrated Camera", isVideo = true),
                )
            },
            openInput = { input, size ->
                requests += input to size
                camera
            },
        )

        assertEquals(listOf(webcam(webcam), webcam(builtIn)), dshow.list(), "the video sources, in dshow's order")
        assertEquals(DeviceInput("video=$webcam", "dshow"), dshow.input(webcam))
        assertSame<FrameGrabber>(camera, dshow.open(builtIn, null))
        assertEquals(
            listOf<Pair<DeviceInput, Pair<Int, Int>?>>(DeviceInput("video=$builtIn", "dshow") to null),
            requests,
        )
    }

    private fun webcam(id: String) = LensInfo(
        id = id,
        deviceType = CameraDeviceType.DEFAULT,
        lens = CameraLens.BACK,
        minZoom = 1f,
        maxZoom = 1f,
        hasFlash = false,
        isLogical = false,
    )

    private companion object {
        // dshow's alternative name for a USB camera: the PnP device path behind "@device_pnp_".
        const val PNP = """@device_pnp_\\?\"""
        const val VIDEO_CLASS = """#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\global"""
    }

    private class NoCamera : FrameGrabber() {
        override fun start() = Unit

        override fun stop() = Unit

        override fun trigger() = Unit

        override fun grab(): Frame? = null

        override fun release() = Unit
    }
}
