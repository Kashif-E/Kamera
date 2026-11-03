package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.engine.newDedicatedWorker
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.VideoInputFrameGrabber
import java.awt.image.BufferedImage
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * The single producer of desktop camera frames. One grab loop, on its own thread, is the only code
 * that calls `grab()` or the frame converter. Preview frames go to `frameFlow`; capture and
 * recording read copies of the same frames through [awaitSnapshot] and [frames], so they never
 * grab from the camera themselves or steal frames from the preview.
 */
class CameraGrabber(
    private val frameFlow: MutableSharedFlow<BufferedImage>,
    private val errorHandler: (Throwable) -> Unit,
    private val targetResolution: Pair<Int, Int>? = null,
) {
    // Reads the capture time of each frame; the recording timestamps frames with it.
    private var nanoClock: () -> Long = System::nanoTime

    internal constructor(
        frameFlow: MutableSharedFlow<BufferedImage>,
        errorHandler: (Throwable) -> Unit,
        targetResolution: Pair<Int, Int>?,
        nanoClock: () -> Long,
    ) : this(frameFlow, errorHandler, targetResolution) {
        this.nanoClock = nanoClock
    }

    private val job = atomic<Job?>(null)
    private val ownerScope = atomic<CoroutineScope?>(null)

    // Serialises start() and stop(), so two starts racing from different threads launch one loop.
    private val startLock = Any()

    @Volatile private var isHorizontallyFlipped = false

    /** The width and height of the frames the grab loop last produced, or null before the first. */
    @Volatile internal var frameSize: Pair<Int, Int>? = null
        private set

    // Copies of the loop's frames, made only while someone collects. Each copy is new and never
    // written again, so a collector may keep it. null marks the end of a run.
    private val snapshots = MutableSharedFlow<CapturedFrame?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun setHorizontalFlip(flip: Boolean) {
        this.isHorizontallyFlipped = flip
    }

    /**
     * Returns a copy of the grab loop's next frame, waiting up to [SNAPSHOT_TIMEOUT_MS], or null if
     * the loop is not running. This blocks the calling thread.
     */
    @Deprecated(
        "This used to call grab() on the caller's thread, racing the grab loop for the same native " +
            "handle. It now blocks until the grab loop's next frame. Use CameraController.takePictureToFile(); " +
            "this method becomes internal in CameraK 2.0.",
    )
    fun grabCurrentFrame(): BufferedImage? {
        val scope = ownerScope.value ?: return null
        return try {
            scope
                .future(Dispatchers.Default) { awaitSnapshot() }
                .get(SNAPSHOT_TIMEOUT_MS + FUTURE_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CameraKLogger.e(TAG, "Interrupted while waiting for a camera frame", e)
            null
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Waiting for a camera frame failed", e)
            null
        }
    }

    /** A copy of the grab loop's next frame, or null if the loop is not running or none arrives in time. */
    internal suspend fun awaitSnapshot(): BufferedImage? {
        if (job.value?.isActive != true) return null
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshots.first()?.image }
    }

    /**
     * Copies of the grab loop's frames from now until the loop stops. Completes at once if no loop
     * is running, so a consumer never waits for frames that will not come.
     */
    internal fun frames(): Flow<CapturedFrame> = snapshots
        .onSubscription { if (job.value?.isActive != true) emit(null) }
        .takeWhile { it != null }
        .filterNotNull()

    /**
     * Starts the grab loop in [coroutineScope], on a dedicated thread. The loop owns the grabber and
     * the converter for its whole life: it opens the grabber, grabs and converts, and stops and
     * releases both in its own `finally`, so the native handle is never freed while a `grab()` or a
     * conversion of the grabber's frame is still running. A start that follows [stop] waits for the
     * previous loop to release its grabber first, and a loop of any grabber waits for every other
     * loop on the same camera to release it. A start while a loop runs does nothing.
     */
    fun start(coroutineScope: CoroutineScope, customGrabber: FrameGrabber? = null): Unit = synchronized(startLock) {
        if (job.value?.isActive == true) return
        val source = customGrabber?.let(::CustomGrabberSource) ?: DefaultDeviceSource(targetResolution)
        launchRun(coroutineScope, source, desktopLensInfo(), ::newDedicatedWorker, legacyListener)
    }

    /**
     * Starts one run of the grab loop for the desktop engine, after the previous run has released
     * the camera: the engine cancels a run it replaces before it starts the next. The run reports
     * to [listener] on [newWorker]'s thread, one worker per run (§2.4 desktop).
     */
    internal fun startRun(
        scope: CoroutineScope,
        source: GrabberSource,
        device: LensInfo,
        newWorker: (String) -> WorkerDispatcher,
        listener: GrabLoopListener,
    ): Job = synchronized(startLock) { launchRun(scope, source, device, newWorker, listener) }

    private fun launchRun(
        scope: CoroutineScope,
        source: GrabberSource,
        device: LensInfo,
        newWorker: (String) -> WorkerDispatcher,
        listener: GrabLoopListener,
    ): Job {
        val previous = job.value
        val deviceLock = cameraDeviceLock(source.deviceKey(device))
        val producer = newWorker(PRODUCER_THREAD)
        val loop = scope.launch(producer.dispatcher, start = CoroutineStart.ATOMIC) {
            runLoop(previous, deviceLock, source, device, listener)
        }
        loop.invokeOnCompletion { producer.close() }
        job.value = loop
        ownerScope.value = scope
        return loop
    }

    private suspend fun CoroutineScope.runLoop(
        previous: Job?,
        deviceLock: Mutex,
        source: GrabberSource,
        device: LensInfo,
        listener: GrabLoopListener,
    ) {
        var owned: FrameGrabber? = null
        var holdsDevice = false
        var opened = false
        val converter = FrameConverter()
        try {
            previous?.join()
            deviceLock.lock()
            holdsDevice = true
            // A loop cancelled before it opened the camera (a stop or cleanup right after the
            // start) leaves it closed.
            ensureActive()
            val grabber = source.open(device, own = { owned = it }, release = { release(it, listener) }).let {
                opened = true
                guarded(listener, "open") { listener.onOpened(it.relaxed) }
                it.grabber
            }

            // grab() blocks until the camera delivers the next frame, which paces the loop. It runs
            // interruptibly: a camera that stopped delivering leaves FFmpeg sleeping and retrying on
            // EAGAIN until the thread is interrupted, so cancelling the loop interrupts it. A grabber
            // that returns at once without an image would not pace the loop, so the run ends rather
            // than spin.
            var framesWithoutImage = 0
            while (isActive) {
                val frame = blockingCall { grabber.grab() }
                val captureNanos = nanoClock()
                if (frame == null) {
                    if (isActive) {
                        throw IllegalStateException(
                            "The camera stopped delivering frames",
                        )
                    } else {
                        break
                    }
                }
                if (frame.image == null) {
                    if (++framesWithoutImage >= MAX_FRAMES_WITHOUT_IMAGE) {
                        throw IllegalStateException(
                            "The camera delivered no image in $MAX_FRAMES_WITHOUT_IMAGE frames",
                        )
                    }
                    continue
                }
                framesWithoutImage = 0
                converter.setHorizontalFlip(isHorizontallyFlipped)
                val image = converter.convert(frame) ?: continue
                val size = frameSize
                if (size == null || size.first != image.width || size.second != image.height) {
                    frameSize = image.width to image.height
                }
                guarded(listener, "frame") { listener.onFrame(image, captureNanos) }
                if (snapshots.subscriptionCount.value > 0) {
                    snapshots.tryEmit(CapturedFrame(image.copy(), captureNanos))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            guarded(listener, "failure") { listener.onFailure(e, opened) }
        } catch (e: LinkageError) {
            guarded(listener, "failure") { listener.onFailure(missingNatives(e), opened) }
        } finally {
            // runInterruptible clears its own interrupt; a stray one must not reach the native release.
            Thread.interrupted()
            owned?.let { release(it, listener) }
            releaseConverter(converter, listener)
            if (holdsDevice) deviceLock.unlock()
            snapshots.tryEmit(null)
        }
    }

    // The 1.x callers' listener: frames to frameFlow, every failure to errorHandler.
    private val legacyListener = object : GrabLoopListener {
        override fun onFrame(image: BufferedImage, captureNanos: Long) {
            frameFlow.tryEmit(image)
        }

        override fun onFailure(failure: Throwable, opened: Boolean) = errorHandler(failure)

        override fun onReleaseFailure(failure: Throwable) = errorHandler(failure)
    }

    /**
     * Stops the grab loop. Non-blocking and idempotent: the loop releases the grabber and converter
     * once it leaves the native call it is in, however many times this is called.
     */
    fun stop() {
        synchronized(startLock) { job.value }?.cancel()
    }

    // A listener is the engine's or a 1.x caller's code: what it throws is logged and that event dropped.
    private inline fun guarded(listener: GrabLoopListener, event: String, report: () -> Unit) {
        try {
            report()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "A grab-loop listener failed on its $event event; the event is dropped", e)
        }
    }

    private fun release(owned: FrameGrabber, listener: GrabLoopListener) {
        try {
            owned.stop()
        } catch (e: Exception) {
            listener.onReleaseFailure(e)
        } catch (e: LinkageError) {
            listener.onReleaseFailure(missingNatives(e))
        }
        try {
            owned.release()
        } catch (e: Exception) {
            listener.onReleaseFailure(e)
        } catch (e: LinkageError) {
            listener.onReleaseFailure(missingNatives(e))
        }
    }

    private fun releaseConverter(converter: FrameConverter, listener: GrabLoopListener) {
        try {
            converter.release()
        } catch (e: Exception) {
            listener.onReleaseFailure(e)
        } catch (e: LinkageError) {
            listener.onReleaseFailure(missingNatives(e))
        }
    }

    // CameraK ships no natives on desktop. Without them JavaCV's first native call throws
    // NoClassDefFoundError (caused by an UnsatisfiedLinkError), an Error the catches above would miss.
    private fun missingNatives(e: LinkageError): Throwable = IllegalStateException(
        "CameraK could not load the bytedeco natives for ${System.getProperty("os.name")} " +
            "${System.getProperty("os.arch")}. Desktop apps supply them: add the javacpp and ffmpeg " +
            "classifier jars (and videoinput on Windows) for each platform you ship, or the -platform " +
            "artifacts. See docs/getting-started/installation.md.",
        e,
    )
}

/**
 * The system's default camera as a new grabber: device "0" through AVFoundation on macOS, device 0
 * through videoInput on Windows, the best V4L2 node on Linux. [size] asks for that size at
 * CameraK's frame rate; null leaves the camera at its own mode.
 */
internal fun createDefaultGrabber(os: OperatingSystem, size: Pair<Int, Int>?): FrameGrabber = when (os) {
    OperatingSystem.MAC -> {
        // On macOS, use AVFoundation with device index as string
        FFmpegFrameGrabber("0")
    }
    OperatingSystem.WINDOWS -> {
        VideoInputFrameGrabber(0)
    }
    OperatingSystem.LINUX -> {
        // The lowest-numbered node that captures video (#52, #53)
        val videoDevice =
            findBestVideoDevice(File("/dev"), File("/sys/class/video4linux"), File("/run/udev/data"))
        CameraKLogger.d("CameraK", "Using video device: $videoDevice")
        FFmpegFrameGrabber(videoDevice)
    }
}.apply {
    if (size != null) {
        frameRate = DEFAULT_CAMERA_FRAME_RATE
        imageWidth = size.first
        imageHeight = size.second
    }
}

/** The size the default camera is asked for when the configuration names none; 720p since #52. */
internal val DEFAULT_CAMERA_RESOLUTION: Pair<Int, Int> = 1280 to 720

internal fun currentOperatingSystem(): OperatingSystem {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") -> OperatingSystem.MAC
        os.contains("windows") -> OperatingSystem.WINDOWS
        else -> OperatingSystem.LINUX
    }
}

/**
 * The V4L2 device the default camera opens on Linux: the lowest-numbered readable `videoN` node in
 * [devDir] that captures video, or [devDir]/video0 if there is none. Nodes sort by number, so
 * video2 comes before video10.
 *
 * Whether a node captures comes from its device capabilities. udev's v4l_id reads them with
 * VIDIOC_QUERYCAP and records them in udev's database ([udevDataDir], one `c<major>:<minor>` file
 * per character device, numbered as in the node's `dev` file in [sysDir], the video4linux class
 * folder) as `E:ID_V4L_CAPABILITIES=:capture:...`; a node is kept when that list has `capture`.
 * Reading the record opens no device. A node without a record (no udev, as in many containers)
 * falls back to its sysfs `index`: 0 for a camera's capture node and 1 for its UVC metadata node,
 * which streams no pictures, so a node whose index is not 0 is skipped. A node with neither is kept.
 */
internal fun findBestVideoDevice(devDir: File, sysDir: File, udevDataDir: File): String {
    val candidates =
        devDir
            .listFiles { file -> VIDEO_NODE.matches(file.name) && file.canRead() }
            .orEmpty()
            .filter { isCaptureNode(File(sysDir, it.name), udevDataDir) }
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }

    return candidates.firstOrNull()?.path ?: File(devDir, "video0").path
}

private fun isCaptureNode(sysNode: File, udevDataDir: File): Boolean {
    udevCapabilities(sysNode, udevDataDir)?.let { return "capture" in it }
    val index = File(sysNode, "index")
    if (!index.canRead()) return true
    return try {
        index.readText().trim() == "0"
    } catch (e: java.io.IOException) {
        CameraKLogger.w(TAG, "Could not read a video node's index; treating it as a capture node", e)
        true
    }
}

// The capabilities udev recorded for a video node, or null if there is no readable record.
private fun udevCapabilities(sysNode: File, udevDataDir: File): Set<String>? {
    val devFile = File(sysNode, "dev")
    if (!devFile.canRead()) return null
    return try {
        val record = File(udevDataDir, "c" + devFile.readText().trim())
        if (!record.canRead()) return null
        record
            .readLines()
            .firstOrNull { it.startsWith(UDEV_CAPABILITIES) }
            ?.removePrefix(UDEV_CAPABILITIES)
            ?.split(':')
            ?.filter { it.isNotEmpty() }
            ?.toSet()
    } catch (e: java.io.IOException) {
        CameraKLogger.w(TAG, "Could not read udev's record of a video node; using its sysfs index", e)
        null
    }
}

private const val UDEV_CAPABILITIES = "E:ID_V4L_CAPABILITIES="

private val VIDEO_NODE = Regex("video\\d+")

/**
 * Replaces how a grab loop with no custom grabber opens the system's default camera. Null, the
 * default, opens the real camera. Tests set it so that a test which reaches the default camera
 * fails instead of turning on the host's webcam.
 */
@Volatile internal var defaultCameraOverride: (() -> FrameGrabber)? = null

// One lock per camera, held by a grab loop from before it opens the camera until it has released
// it, so a new controller's loop waits for an old controller's loop that is still inside grab().
// Every default grabber opens the system's first camera; a custom grabber is its own device. Weak
// keys let a custom grabber be collected once no loop uses it.
private val cameraDeviceLocks = WeakHashMap<Any, Mutex>()

/** The device key every grabber of the system's default camera shares. */
internal val defaultCamera = Any()

private fun cameraDeviceLock(deviceKey: Any): Mutex =
    synchronized(cameraDeviceLocks) { cameraDeviceLocks.getOrPut(deviceKey) { Mutex() } }

/** A copy of one of the grab loop's frames, which its receiver may keep, and when it was grabbed. */
internal class CapturedFrame(val image: BufferedImage, val captureNanos: Long)

private fun BufferedImage.copy(): BufferedImage = BufferedImage(colorModel, copyData(null), isAlphaPremultiplied, null)

// The frame rate the default camera is asked for.
private const val DEFAULT_CAMERA_FRAME_RATE = 30.0

private const val TAG = "CameraK"
private const val PRODUCER_THREAD = "CameraK-grabber"

// Long enough for a slow webcam's first frame; capture and recording fail rather than wait forever.
private const val SNAPSHOT_TIMEOUT_MS = 5_000L
private const val FUTURE_GRACE_MS = 1_000L

// Frames without an image (audio, or a stream the demuxer is draining) are interleaved with video,
// a few at a time. This many in a row means the camera has stopped producing pictures.
private const val MAX_FRAMES_WITHOUT_IMAGE = 300

enum class OperatingSystem {
    WINDOWS,
    MAC,
    LINUX,
}
