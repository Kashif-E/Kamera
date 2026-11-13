@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import org.bytedeco.ffmpeg.avdevice.AVDeviceInfo
import org.bytedeco.ffmpeg.avdevice.AVDeviceInfoList
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avdevice
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.VideoInputFrameGrabber
import org.bytedeco.videoinput.videoInput
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** One operating system's cameras: listed without opening any, and opened by `LensInfo.id`. */
internal interface DesktopDevices {
    /** The cameras, the system default first. Opens nothing. */
    fun list(): List<LensInfo>

    /**
     * A new grabber for the camera [id], asking for [size] at CameraK's frame rate, or at the
     * camera's own mode when null. Throws [DeviceGoneException] when no camera has that id now.
     * The id of [desktopLensInfo], the one default entry, opens the system's default camera.
     */
    fun open(id: String, size: Pair<Int, Int>?): FrameGrabber
}

/** No camera has the selected id any more: it was unplugged after enumeration. */
internal class DeviceGoneException(message: String) : Exception(message)

/** What FFmpeg opens for a camera: the input name and, where FFmpeg needs one, the input format. */
internal data class DeviceInput(val name: String, val format: String?)

/**
 * The cameras an engine offers (§2.4 desktop): [devices]' list, or only the one default entry when
 * the list cannot be read (JNA absent on macOS, missing natives on Windows), so the open still runs
 * and reports what is wrong.
 */
internal class DeviceEnumerator(private val devices: DesktopDevices) {
    fun lenses(): List<LensInfo> = try {
        devices.list()
    } catch (e: Exception) {
        CameraKLogger.w(TAG, "Enumerating the cameras failed; only the default camera is offered", e)
        listOf(desktopLensInfo())
    } catch (e: LinkageError) {
        CameraKLogger.w(
            TAG,
            "Enumerating the cameras needs a library that is missing; only the default camera is offered",
            e,
        )
        listOf(desktopLensInfo())
    }
}

/** The cameras of [os]: sysfs on Linux, AVFoundation over JNA on macOS, [windows] on Windows. */
internal fun platformDevices(
    os: OperatingSystem,
    windows: WindowsCaptureBackend = defaultWindowsCaptureBackend(),
): DesktopDevices = when (os) {
    OperatingSystem.LINUX -> LinuxDevices()
    OperatingSystem.MAC -> MacDevices()
    OperatingSystem.WINDOWS -> windows
}

/**
 * Linux cameras: the V4L2 nodes that capture video, found as [findBestVideoDevice] finds them
 * (sysfs and udev's records, no device opened), lowest number first, so the default is first. A
 * node's id is the name of its `/dev/v4l/by-id` link when udev made one, which survives replugging
 * into another port, else the node's path.
 */
internal class LinuxDevices(
    private val devDir: File = File("/dev"),
    private val sysDir: File = File("/sys/class/video4linux"),
    private val udevDataDir: File = File("/run/udev/data"),
    private val byIdDir: File = File("/dev/v4l/by-id"),
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : DesktopDevices {
    override fun list(): List<LensInfo> {
        val links = byIdLinks()
        return captureNodes(devDir, sysDir, udevDataDir).map { node -> webcam(links[canonical(node)] ?: node.path) }
    }

    /** The FFmpeg input for [id]: the by-id link or the node; the default entry is the best node. */
    fun input(id: String): DeviceInput = when {
        id == desktopLensInfo().id -> DeviceInput(findBestVideoDevice(devDir, sysDir, udevDataDir), null)
        id.startsWith("/") -> DeviceInput(id, null)
        else -> DeviceInput(File(byIdDir, id).path, null)
    }

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber {
        val input = input(id)
        if (id != desktopLensInfo().id && !File(input.name).exists()) {
            throw DeviceGoneException("The selected camera is no longer connected")
        }
        return openInput(input, size)
    }

    // The node each by-id link points to, by canonical path; the first link by name wins.
    private fun byIdLinks(): Map<String, String> {
        val links = byIdDir.listFiles().orEmpty().filter { Files.isSymbolicLink(it.toPath()) }.sortedBy { it.name }
        val byNode = mutableMapOf<String, String>()
        for (link in links) {
            val node = canonical(link) ?: continue
            byNode.putIfAbsent(node, link.name)
        }
        return byNode
    }

    private fun canonical(file: File): String? = try {
        file.canonicalPath
    } catch (e: IOException) {
        CameraKLogger.w(TAG, "Could not resolve a video device link; the node keeps its path as its id", e)
        null
    }
}

/**
 * macOS cameras: `+[AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo]` read through the
 * Objective-C runtime over JNA (Decision 15), the `defaultDeviceWithMediaType:` device first. Each
 * id is the device's `uniqueID`. FFmpeg's avfoundation input opens by localized name or by index,
 * so every open reads the devices again and opens the id's current name, or its index in
 * `devicesWithMediaType:` order when two cameras share the name (B1 checks that order on a Mac
 * with two identical cameras). The default entry opens as `"0"`, as 1.x did.
 */
internal class MacDevices(
    // A lambda, not a constructor reference, so nothing resolves JnaObjCRuntime before the read.
    private val runtime: () -> ObjCRuntime = { JnaObjCRuntime() },
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : DesktopDevices {
    override fun list(): List<LensInfo> {
        val objc = runtime()
        val cameras = cameras(objc)
        val defaultId = defaultId(objc)
        val (default, others) = cameras.partition { it.uniqueId == defaultId }
        return (default + others).map { camera ->
            webcam(
                camera.uniqueId,
                if (camera.position ==
                    FRONT
                ) {
                    CameraLens.FRONT
                } else {
                    CameraLens.BACK
                },
                camera.isExternal,
            )
        }
    }

    /** The FFmpeg input for [id] now, or null when no camera has that `uniqueID`. */
    fun input(id: String): DeviceInput? {
        if (id == desktopLensInfo().id) return DeviceInput("0", AVFOUNDATION)
        val cameras = cameras(runtime())
        val index = cameras.indexOfFirst { it.uniqueId == id }
        if (index < 0) return null
        val name = cameras[index].name
        // avfoundation reads "a:b" as video and audio device, and a number as an index.
        val byName = name.isNotEmpty() &&
            ':' !in name &&
            name.toIntOrNull() == null &&
            cameras.count { it.name == name } == 1
        return DeviceInput(if (byName) name else index.toString(), AVFOUNDATION)
    }

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber =
        openInput(input(id) ?: throw DeviceGoneException("The selected camera is no longer connected"), size)

    private class MacCamera(val uniqueId: String, val name: String, val position: Long, val isExternal: Boolean)

    private fun cameras(objc: ObjCRuntime): List<MacCamera> {
        // Reading the constant loads AVFoundation, which registers AVCaptureDevice with the runtime.
        val video = objc.pointerConstant("AVFoundation", "AVMediaTypeVideo")
        val deviceClass = objc.getClass("AVCaptureDevice")
        check(video != 0L && deviceClass != 0L) { "AVFoundation is not loaded" }
        val array = objc.sendLong(deviceClass, objc.selector("devicesWithMediaType:"), video)
        if (array == 0L) return emptyList()
        val count = objc.send(array, objc.selector("count"))
        return (0 until count).mapNotNull { i ->
            val device = objc.sendLong(array, objc.selector("objectAtIndex:"), i)
            val uniqueId = objc.string(objc.send(device, objc.selector("uniqueID"))) ?: return@mapNotNull null
            MacCamera(
                uniqueId = uniqueId,
                name = objc.string(objc.send(device, objc.selector("localizedName"))).orEmpty(),
                position = objc.send(device, objc.selector("position")),
                isExternal = deviceType(objc, device) in EXTERNAL_TYPES,
            )
        }
    }

    // deviceType exists from macOS 10.15; a selector a device does not know would raise an
    // Objective-C exception, which no JVM catch reaches.
    private fun deviceType(objc: ObjCRuntime, device: Long): String? {
        val selector = objc.selector("deviceType")
        val responds = objc.sendLong(device, objc.selector("respondsToSelector:"), selector) and BOOL_MASK
        return if (responds != 0L) objc.string(objc.send(device, selector)) else null
    }

    private fun defaultId(objc: ObjCRuntime): String? {
        val video = objc.pointerConstant("AVFoundation", "AVMediaTypeVideo")
        val deviceClass = objc.getClass("AVCaptureDevice")
        val device = objc.sendLong(deviceClass, objc.selector("defaultDeviceWithMediaType:"), video)
        return if (device == 0L) null else objc.string(objc.send(device, objc.selector("uniqueID")))
    }

    private companion object {
        const val AVFOUNDATION = "avfoundation"

        // AVCaptureDevicePosition.front
        const val FRONT = 2L

        // A BOOL return fills only the low byte of the register.
        const val BOOL_MASK = 0xFFL
        val EXTERNAL_TYPES = setOf("AVCaptureDeviceTypeExternal", "AVCaptureDeviceTypeExternalUnknown")
    }
}

/**
 * How CameraK lists and opens Windows cameras (Decision 17). Decision 17 picks videoinput only once
 * a check on Windows shows it loads against javacpp 1.5.12; that check is B1's, so FFmpeg dshow is
 * the default ([defaultWindowsCaptureBackend]) and videoinput waits behind the same seam.
 */
internal interface WindowsCaptureBackend : DesktopDevices

/** FFmpeg dshow, until B1's Windows check shows videoinput loading (§2.4 desktop). */
internal fun defaultWindowsCaptureBackend(): WindowsCaptureBackend = DshowCaptureBackend()

/** One `avdevice_list_input_sources` entry of FFmpeg's dshow input. */
internal class DshowSource(val name: String, val description: String, val isVideo: Boolean)

/**
 * Windows cameras through FFmpeg's dshow input: its video sources from `avdevice_list_input_sources`
 * (implemented by dshow from FFmpeg 5.0), in dshow's order, so the first is the default. Each id is
 * the source's "alternative name" (the `@device_pnp_…` moniker), which dshow accepts in place of the
 * friendly name in `video=<name>` and which two cameras of one model do not share.
 */
internal class DshowCaptureBackend(
    private val sources: () -> List<DshowSource> = ::dshowSources,
    private val openInput: (DeviceInput, Pair<Int, Int>?) -> FrameGrabber = ::ffmpegGrabber,
) : WindowsCaptureBackend {
    override fun list(): List<LensInfo> = sources().filter { it.isVideo }.map { webcam(it.name) }

    fun input(id: String): DeviceInput = DeviceInput("video=$id", DSHOW)

    // Every open lists the sources again, so a camera unplugged after enumeration is NoCamera, not
    // an FFmpeg open error.
    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber {
        val video = sources().filter { it.isVideo }
        val name = if (id == desktopLensInfo().id) {
            video.firstOrNull()?.name ?: throw DeviceGoneException("No camera is connected")
        } else {
            id.takeIf { video.any { it.name == id } }
                ?: throw DeviceGoneException("The selected camera is no longer connected")
        }
        return openInput(input(name), size)
    }

    private companion object {
        const val DSHOW = "dshow"
    }
}

/**
 * Windows cameras through videoinput, ids by index (so not stable across plugging). Not selected:
 * its native is built for javacpp 1.5.9, and whether it loads against 1.5.12 is B1's Windows check.
 */
internal class VideoInputCaptureBackend : WindowsCaptureBackend {
    override fun list(): List<LensInfo> = (0 until videoInput.listDevices(true)).map { webcam(it.toString()) }

    override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber {
        val index = if (id == desktopLensInfo().id) 0 else id.toIntOrNull()
        if (index == null || index >= videoInput.listDevices(true)) {
            throw DeviceGoneException("The selected camera is no longer connected")
        }
        return VideoInputFrameGrabber(index).askFor(size)
    }
}

// FFmpeg's dshow sources. Throws when FFmpeg cannot list them, so the enumerator offers the default.
private fun dshowSources(): List<DshowSource> {
    avdevice.avdevice_register_all()
    val dshow = avformat.av_find_input_format("dshow") ?: error("This FFmpeg build has no dshow input")
    // Once the list has failed for want of an audio device, it fails every time: the process lists
    // through the log from then on.
    if (dshowListFails) return dshowSourcesFromLog(dshow)
    // JavaCPP does not zero the slot, and FFmpeg leaves it unwritten on some early errors; freed unwritten,
    // it corrupted the heap (windows-2025 runner).
    val listOut = PointerPointer<AVDeviceInfoList>(1L).put(0L, null as Pointer?)
    try {
        val result = avdevice.avdevice_list_input_sources(dshow, null as BytePointer?, null, listOut)
        // dshow returns the audio category's result: with no audio capture device the list fails, and is
        // freed, whatever cameras there are.
        if (result == AVERROR_EIO) {
            dshowListFails = true
            return dshowSourcesFromLog(dshow)
        }
        check(result >= 0) { "avdevice_list_input_sources failed with $result" }
        val list = listOut.get(AVDeviceInfoList::class.java, 0L)
        if (list == null || list.isNull) return emptyList()
        return (0 until list.nb_devices()).map { i ->
            val source = list.devices(i)
            DshowSource(
                name = source.device_name().string,
                description = source.device_description()?.string.orEmpty(),
                isVideo = source.isVideo(),
            )
        }
    } finally {
        avdevice.avdevice_free_list_devices(listOut)
        listOut.close()
    }
}

/**
 * The dshow sources in the log of FFmpeg's list_devices query: a `"<friendly name>" (<media types>)`
 * line for each device, then its `  Alternative name "<moniker>"`, each line prefixed by its context.
 * A device that names no media type counts as video, as in [isVideo].
 */
internal fun parseDshowListing(log: String): List<DshowSource> {
    val lines = log.lineSequence().map { it.replaceFirst(LOG_PREFIX, "") }.toList()
    return lines.zipWithNext().mapNotNull { (device, next) ->
        val (friendly, types) = DEVICE_LINE.matchEntire(device)?.destructured ?: return@mapNotNull null
        val moniker = ALTERNATIVE_NAME_LINE.matchEntire(next)?.groupValues?.get(1) ?: return@mapNotNull null
        val kinds = types.split(", ")
        DshowSource(moniker, friendly, isVideo = "video" in kinds || "none" in kinds)
    }
}

private val LOG_PREFIX = Regex("""^\[[^\]]*] """)
private val DEVICE_LINE = Regex(""""(.*)" \(([^()]*)\)""")
private val ALTERNATIVE_NAME_LINE = Regex("""  Alternative name "(.*)"""")

/**
 * The dshow sources from FFmpeg's list_devices query, which logs every device, video first, whatever
 * audio has. The query's log is read through [DshowLogTap], at INFO at least while it runs.
 */
private fun dshowSourcesFromLog(dshow: AVInputFormat): List<DshowSource> = synchronized(DshowLogTap) {
    val log = StringBuffer()
    val level = avutil.av_log_get_level()
    if (level < avutil.AV_LOG_INFO) avutil.av_log_set_level(avutil.AV_LOG_INFO)
    DshowLogTap.install()
    DshowLogTap.recording = log
    val context = AVFormatContext(null)
    val options = AVDictionary(null)
    try {
        avutil.av_dict_set(options, "list_devices", "true", 0)
        // Always AVERROR_EXIT once the devices are logged; FFmpeg frees the context.
        avformat.avformat_open_input(context, "dummy", dshow, options)
    } finally {
        DshowLogTap.recording = null
        avutil.av_dict_free(options)
        avutil.av_log_set_level(level)
    }
    parseDshowListing(log.toString()).also {
        CameraKLogger.d(TAG, "FFmpeg's list_devices query found ${it.count { source -> source.isVideo }} video sources")
    }
}

/**
 * FFmpeg's process-wide log callback once a list_devices query has run: installed once and never
 * closed or replaced, it passes each line to the callback the app had set, or to standard error as
 * FFmpeg's default does, and records the lines while a query runs. A callback set back after the query
 * and the query's own closed one both left FFmpeg calling address 0 on its next line (windows-2025).
 */
private object DshowLogTap {
    @Volatile
    var recording: StringBuffer? = null

    @Volatile
    private var app: LogCallback? = null
    private var tap: LogCallback? = null

    /** Makes the tap FFmpeg's callback, keeping the app's to pass lines to, unless it already is. */
    fun install() {
        val current = avutil.logCallback()
        val installed = tap
        if (installed != null && current != null && current.address() == installed.address()) return
        app = current?.takeUnless { it.isNull }
        val callback = installed ?: object : LogCallback() {
            override fun call(level: Int, msg: BytePointer) {
                recording?.append(msg.string)
                val forward = app
                if (forward != null) forward.call(level, msg) else System.err.print(msg.string)
            }
        }
        tap = callback
        avutil.setLogCallback(callback)
    }
}

/** Whether dshow's device list failed with EIO in this process; it then fails on every call. */
@Volatile
private var dshowListFails = false

/** AVERROR(EIO), which dshow's device list returns whenever either category is empty. */
private const val AVERROR_EIO = -5

// A source that names no media type is kept: only one that names others is left out.
private fun AVDeviceInfo.isVideo(): Boolean {
    val count = nb_media_types()
    if (count <= 0) return true
    val types = media_types()
    return (0 until count).any { types.get(it.toLong()) == avutil.AVMEDIA_TYPE_VIDEO }
}

/** An FFmpeg grabber for [input] at [size] and CameraK's frame rate, or at the camera's own mode. */
internal fun ffmpegGrabber(input: DeviceInput, size: Pair<Int, Int>?): FrameGrabber =
    FFmpegFrameGrabber(input.name).apply { input.format?.let { format = it } }.askFor(size)

// A desktop camera: no facing on Linux and Windows, no flash, no torch, no zoom (§2.4 desktop).
private fun webcam(id: String, lens: CameraLens = CameraLens.BACK, isExternal: Boolean = false) = LensInfo(
    id = id,
    deviceType = CameraDeviceType.DEFAULT,
    lens = lens,
    minZoom = 1f,
    maxZoom = 1f,
    hasFlash = false,
    isLogical = false,
    isExternal = isExternal,
)

private const val TAG = "CameraK"
