package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import org.bytedeco.javacv.FrameGrabber
import java.io.File

/** One operating system's cameras: listed without opening any, and opened by `LensInfo.id`. */
internal interface DesktopDevices {
    /** The cameras, the system default first. Opens nothing. */
    fun list(): List<LensInfo>

    /**
     * A new grabber for the camera [id], asking for [size] at CameraK's frame rate, or at the
     * camera's own mode when null. Throws [DeviceGoneException] when no camera has that id now.
     */
    fun open(id: String, size: Pair<Int, Int>?): FrameGrabber
}

/** No camera has the selected id any more: it was unplugged after enumeration. */
internal class DeviceGoneException(message: String) : Exception(message)

/** What FFmpeg opens for a camera: the input name and, where FFmpeg needs one, the input format. */
internal data class DeviceInput(val name: String, val format: String?)

/** The cameras an engine offers (§2.4 desktop). */
internal class DeviceEnumerator(private val devices: DesktopDevices) {
    fun lenses(): List<LensInfo> = TODO("D2'")
}

internal fun platformDevices(
    os: OperatingSystem,
    windows: WindowsCaptureBackend = defaultWindowsCaptureBackend(),
): DesktopDevices = TODO("D2'")

internal class LinuxDevices(
    private val devDir: File = File("/dev"),
    private val sysDir: File = File("/sys/class/video4linux"),
    private val udevDataDir: File = File("/run/udev/data"),
    private val byIdDir: File = File("/dev/v4l/by-id"),
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : DesktopDevices {
    override fun list(): List<LensInfo> = TODO("D2'")

    fun input(id: String): DeviceInput = TODO("D2'")

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = TODO("D2'")
}

internal class MacDevices(
    private val runtime: () -> ObjCRuntime = { JnaObjCRuntime() },
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : DesktopDevices {
    override fun list(): List<LensInfo> = TODO("D2'")

    fun input(id: String): DeviceInput? = TODO("D2'")

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = TODO("D2'")
}

/** How CameraK lists and opens Windows cameras (Decision 17). */
internal interface WindowsCaptureBackend : DesktopDevices

internal fun defaultWindowsCaptureBackend(): WindowsCaptureBackend = TODO("D2'")

/** One `avdevice_list_input_sources` entry of FFmpeg's dshow input. */
internal class DshowSource(val name: String, val description: String, val isVideo: Boolean)

internal class DshowCaptureBackend(
    private val sources: () -> List<DshowSource> = { TODO("D2'") },
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : WindowsCaptureBackend {
    override fun list(): List<LensInfo> = TODO("D2'")

    fun input(id: String): DeviceInput = TODO("D2'")

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = TODO("D2'")
}

internal fun ffmpegGrabber(input: DeviceInput, size: Pair<Int, Int>?): FrameGrabber = TODO("D2'")
