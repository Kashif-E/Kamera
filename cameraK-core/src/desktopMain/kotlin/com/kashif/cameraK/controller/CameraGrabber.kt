package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.state.removedIn2
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.AtomicLong
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.bytedeco.javacv.FrameGrabber
import java.awt.image.BufferedImage
import java.io.File
import java.util.WeakHashMap

/**
 * The single producer of desktop camera frames. One grab loop, on its own thread, is the only code
 * that calls `grab()` or the frame converter. It converts each frame into a leased slot of the
 * converter's pool (§8 desktop) and holds the two latest for the preview; preview frames go to the
 * engine's listener, which also hands them to analysis, the recording reads the same leased frames
 * through [openFeed], and a capture copies one through [awaitSnapshot], so none of them grabs from
 * the camera or steals frames from the preview.
 *
 * Public only so a 1.x call of [grabCurrentFrame] still resolves to its hint; the desktop engine
 * builds and runs it. [nanoClock] reads the capture time of each frame, which the recording
 * timestamps frames with.
 */
public class CameraGrabber internal constructor(private val nanoClock: () -> Long = System::nanoTime) {
    private val job = atomic<Job?>(null)

    // Serialises startRun() and stop(), so a run and the stop of the run before it never interleave.
    private val startLock = Any()

    @Volatile private var isHorizontallyFlipped = false

    /** How many analysis frames the grab loops converted to YUV_420. */
    internal val yuvConversions: AtomicLong = atomic(0L)

    @Volatile private var converterInUse: FrameConverter? = null

    /** The slots the running grab loop's pool has allocated; 0 without one. */
    internal val slotsAllocated: Int get() = converterInUse?.slotCount ?: 0

    /** The width and height of the frames the grab loop last produced, or null before the first. */
    @Volatile internal var frameSize: Pair<Int, Int>? = null
        private set

    // The consumers' feeds of the loop's leased frames. A run owns them from its launch; when any
    // run ends, every feed is closed, as a camera cut ends a recording.
    private val feedLock = Any()
    private var feedOwner: Any? = null
    private val feeds = mutableListOf<FrameFeed>()

    internal fun setHorizontalFlip(flip: Boolean) {
        this.isHorizontallyFlipped = flip
    }

    /** Removed in 2.0. */
    @Deprecated(
        "Removed in CameraK 2.0: capture a still with the holder's suspending capture(), which writes " +
            "the next frame to a file.",
        ReplaceWith("holder.capture()"),
        level = DeprecationLevel.ERROR,
    )
    public fun grabCurrentFrame(): BufferedImage? = throw removedIn2()

    /** Whether a grab loop is running. */
    internal val isRunning: Boolean get() = job.value?.isActive == true

    /** A copy of the grab loop's next frame, or null if the loop is not running or none arrives in time. */
    internal suspend fun awaitSnapshot(): BufferedImage? {
        val feed = openFeed()
        try {
            val frame = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { feed.next() } ?: return null
            return try {
                frame.image.copy()
            } finally {
                frame.release()
            }
        } finally {
            feed.cancel()
        }
    }

    /**
     * A feed of the grab loop's leased frames from now until the loop stops; closed at once if no
     * loop is running, so a consumer never waits for frames that will not come. The consumer
     * releases each frame it takes and cancels the feed when done.
     */
    internal fun openFeed(): FrameFeed = synchronized(feedLock) {
        FrameFeed().also { if (feedOwner == null) it.close() else feeds += it }
    }

    /**
     * Starts one run of the grab loop for the desktop engine, on a dedicated thread, after the
     * previous run has released the camera: the engine cancels a run it replaces before it starts
     * the next. The loop owns the grabber and the converter for its whole life: it opens the grabber,
     * grabs and converts, and stops and releases both in its own `finally`, so the native handle is
     * never freed while a `grab()` or a conversion of the grabber's frame is still running. A loop of
     * any grabber waits for every other loop on the same camera to release it. The run reports
     * to [listener] on [newWorker]'s thread, one worker per run (§2.4 desktop). Each frame is
     * blitted with [blit]'s crop and mirror, and mirrored as well while [setHorizontalFlip] is on.
     * The run's frame pool holds at most [poolSize] slots, read again at each frame.
     */
    internal fun startRun(
        scope: CoroutineScope,
        source: GrabberSource,
        device: LensInfo,
        newWorker: (String) -> WorkerDispatcher,
        listener: GrabLoopListener,
        blit: FrameBlit = FrameBlit.NONE,
        poolSize: () -> Int = { framePoolSize(retainLimits = 0, analyzers = 0) },
    ): Job = synchronized(startLock) { launchRun(scope, source, device, newWorker, listener, blit, poolSize) }

    private fun launchRun(
        scope: CoroutineScope,
        source: GrabberSource,
        device: LensInfo,
        newWorker: (String) -> WorkerDispatcher,
        listener: GrabLoopListener,
        blit: FrameBlit,
        poolSize: () -> Int,
    ): Job {
        val previous = job.value
        val deviceLock = cameraDeviceLock(source.deviceKey(device))
        val producer = newWorker(PRODUCER_THREAD)
        val run = Any()
        synchronized(feedLock) { feedOwner = run }
        val loop = scope.launch(producer.dispatcher, start = CoroutineStart.ATOMIC) {
            runLoop(previous, deviceLock, source, device, listener, blit, run, poolSize)
        }
        loop.invokeOnCompletion { producer.close() }
        job.value = loop
        return loop
    }

    private suspend fun CoroutineScope.runLoop(
        previous: Job?,
        deviceLock: Mutex,
        source: GrabberSource,
        device: LensInfo,
        listener: GrabLoopListener,
        blit: FrameBlit,
        run: Any,
        poolSize: () -> Int,
    ) {
        var owned: FrameGrabber? = null
        var holdsDevice = false
        var opened = false
        val converter = FrameConverter(yuvConversions, poolSize)
        converterInUse = converter
        // The two latest frames, held for the preview: a frame is not written again while the next
        // two are converted and published.
        val held = ArrayDeque<LeasedFrame>(PREVIEW_HELD + 1)
        val mirrored = FrameBlit(blit.aspectRatio, mirrored = true)
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
                val leased = converter.convertLeased(frame, if (isHorizontallyFlipped) mirrored else blit, captureNanos)
                    ?: continue
                held.addLast(leased)
                val size = frameSize
                if (size == null || size.first != leased.width || size.second != leased.height) {
                    frameSize = leased.width to leased.height
                }
                guarded(listener, "frame") { listener.onFrame(leased) }
                synchronized(feedLock) { if (feeds.isNotEmpty()) feeds.removeAll { !it.offer(leased) } }
                if (held.size > PREVIEW_HELD) held.removeFirst().release()
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
            synchronized(feedLock) {
                feeds.forEach { it.close() }
                feeds.clear()
                if (feedOwner === run) feedOwner = null
            }
            held.forEach { it.release() }
            held.clear()
            if (converterInUse === converter) converterInUse = null
            releaseConverter(converter, listener)
            if (holdsDevice) deviceLock.unlock()
        }
    }

    /**
     * Stops the grab loop. Non-blocking and idempotent: the loop releases the grabber and converter
     * once it leaves the native call it is in, however many times this is called.
     */
    internal fun stop() {
        synchronized(startLock) { job.value }?.cancel()
    }

    // A listener is the engine's code: what it throws is logged and that event dropped.
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

/** Asks this grabber for [size] at CameraK's frame rate; null leaves the camera at its own mode. */
internal fun FrameGrabber.askFor(size: Pair<Int, Int>?): FrameGrabber = apply {
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
internal fun findBestVideoDevice(devDir: File, sysDir: File, udevDataDir: File): String =
    captureNodes(devDir, sysDir, udevDataDir).firstOrNull()?.path ?: File(devDir, "video0").path

/** Every readable `videoN` node in [devDir] that captures video, as [findBestVideoDevice] decides it, by number. */
internal fun captureNodes(devDir: File, sysDir: File, udevDataDir: File): List<File> = devDir
    .listFiles { file -> VIDEO_NODE.matches(file.name) && file.canRead() }
    .orEmpty()
    .filter { isCaptureNode(File(sysDir, it.name), udevDataDir) }
    .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }

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
// A device is keyed on its id (§2.4 desktop); a custom grabber is its own device. Weak keys let a
// custom grabber be collected once no loop uses it. Ids are held strongly: each enumeration builds
// new id strings, so a weak id could be collected while a loop holds its lock, and the next loop
// on that device would get a lock of its own.
private val cameraDeviceLocks = WeakHashMap<Any, Mutex>()
private val deviceIdLocks = HashMap<String, Mutex>()

private fun cameraDeviceLock(deviceKey: Any): Mutex = when (deviceKey) {
    is String -> synchronized(deviceIdLocks) { deviceIdLocks.getOrPut(deviceKey) { Mutex() } }
    else -> synchronized(cameraDeviceLocks) { cameraDeviceLocks.getOrPut(deviceKey) { Mutex() } }
}

private fun BufferedImage.copy(): BufferedImage = BufferedImage(colorModel, copyData(null), isAlphaPremultiplied, null)

// The frame rate the default camera is asked for.
private const val DEFAULT_CAMERA_FRAME_RATE = 30.0

private const val TAG = "CameraK"
private const val PRODUCER_THREAD = "CameraK-grabber"

// The latest frame and the one before it, which a preview collector one frame behind still reads.
private const val PREVIEW_HELD = 2

// Long enough for a slow webcam's first frame; capture and recording fail rather than wait forever.
private const val SNAPSHOT_TIMEOUT_MS = 5_000L

// Frames without an image (audio, or a stream the demuxer is draining) are interleaved with video,
// a few at a time. This many in a row means the camera has stopped producing pictures.
private const val MAX_FRAMES_WITHOUT_IMAGE = 300

internal enum class OperatingSystem {
    WINDOWS,
    MAC,
    LINUX,
}
