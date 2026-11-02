package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.WeakHashMap
import java.util.concurrent.Executors
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
        val previous = job.value
        if (previous?.isActive == true) return
        val device = cameraDeviceLock(customGrabber)

        val producer =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(runnable, PRODUCER_THREAD).apply { isDaemon = true } }
                .asCoroutineDispatcher()
        val loop =
            coroutineScope.launch(producer, start = CoroutineStart.ATOMIC) {
                var owned: FrameGrabber? = null
                var holdsDevice = false
                val converter = FrameConverter()
                try {
                    previous?.join()
                    device.lock()
                    holdsDevice = true
                    // A loop cancelled before it opened the camera (a stop or cleanup right after the
                    // start) leaves it closed.
                    ensureActive()
                    owned = customGrabber ?: createGrabber()
                    // Set format for macOS AVFoundation before starting
                    if (owned is FFmpegFrameGrabber && getOperatingSystem() == OperatingSystem.MAC) {
                        owned.format = "avfoundation"
                    }
                    owned.start()

                    // grab() blocks until the camera delivers the next frame, which paces the loop. A
                    // grabber that returns at once without an image would not pace it, so the run
                    // ends rather than spin.
                    var framesWithoutImage = 0
                    while (isActive) {
                        val frame = owned.grab()
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
                        frameFlow.tryEmit(image)
                        if (snapshots.subscriptionCount.value > 0) {
                            snapshots.tryEmit(CapturedFrame(image.copy(), captureNanos))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorHandler(e)
                } catch (e: LinkageError) {
                    errorHandler(missingNatives(e))
                } finally {
                    owned?.let { release(it, converter) }
                    if (holdsDevice) device.unlock()
                    snapshots.tryEmit(null)
                }
            }
        loop.invokeOnCompletion { producer.close() }
        job.value = loop
        ownerScope.value = coroutineScope
    }

    /**
     * Stops the grab loop. Non-blocking and idempotent: the loop releases the grabber and converter
     * once it leaves the native call it is in, however many times this is called.
     */
    fun stop() {
        synchronized(startLock) { job.value }?.cancel()
    }

    private fun release(owned: FrameGrabber, converter: FrameConverter) {
        try {
            owned.stop()
        } catch (e: Exception) {
            errorHandler(e)
        } catch (e: LinkageError) {
            errorHandler(missingNatives(e))
        }
        try {
            owned.release()
        } catch (e: Exception) {
            errorHandler(e)
        } catch (e: LinkageError) {
            errorHandler(missingNatives(e))
        }
        try {
            converter.release()
        } catch (e: Exception) {
            errorHandler(e)
        } catch (e: LinkageError) {
            errorHandler(missingNatives(e))
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

    private fun createGrabber(): FrameGrabber = when (getOperatingSystem()) {
        OperatingSystem.MAC -> {
            // On macOS, use AVFoundation with device index as string
            FFmpegFrameGrabber("0")
        }
        OperatingSystem.WINDOWS -> {
            VideoInputFrameGrabber(0)
        }
        OperatingSystem.LINUX -> {
            // Prefer /dev/video0 but fall back to the first available readable device (#52, #53)
            val videoDevice = findBestVideoDevice()
            CameraKLogger.d("CameraK", "Using video device: $videoDevice")
            FFmpegFrameGrabber(videoDevice)
        }
    }.apply {
        frameRate = 30.0
        // Default to 720p (was 480p) to use higher resolution by default (#52)
        val (width, height) = targetResolution ?: (1280 to 720)
        imageWidth = width
        imageHeight = height
    }

    private fun findBestVideoDevice(): String {
        val defaultDevice = "/dev/video0"
        val defaultFile = java.io.File(defaultDevice)

        if (defaultFile.exists() && defaultFile.canRead()) return defaultDevice

        val devDir = java.io.File("/dev")
        val candidates =
            devDir
                .listFiles { file ->
                    file.name.startsWith("video") && file.canRead()
                }?.sortedBy { it.name } ?: emptyList()

        return candidates.firstOrNull()?.absolutePath ?: defaultDevice
    }

    private fun getOperatingSystem(): OperatingSystem {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> OperatingSystem.MAC
            os.contains("windows") -> OperatingSystem.WINDOWS
            else -> OperatingSystem.LINUX
        }
    }
}

// One lock per camera, held by a grab loop from before it opens the camera until it has released
// it, so a new controller's loop waits for an old controller's loop that is still inside grab().
// Every default grabber opens the system's first camera; a custom grabber is its own device. Weak
// keys let a custom grabber be collected once no loop uses it.
private val cameraDeviceLocks = WeakHashMap<Any, Mutex>()
private val defaultCamera = Any()

private fun cameraDeviceLock(customGrabber: FrameGrabber?): Mutex =
    synchronized(cameraDeviceLocks) { cameraDeviceLocks.getOrPut(customGrabber ?: defaultCamera) { Mutex() } }

/** A copy of one of the grab loop's frames, which its receiver may keep, and when it was grabbed. */
internal class CapturedFrame(val image: BufferedImage, val captureNanos: Long)

private fun BufferedImage.copy(): BufferedImage = BufferedImage(colorModel, copyData(null), isAlphaPremultiplied, null)

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
