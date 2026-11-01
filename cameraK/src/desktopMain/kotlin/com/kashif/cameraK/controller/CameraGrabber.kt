package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.VideoInputFrameGrabber
import java.awt.image.BufferedImage

class CameraGrabber(
    private val frameFlow: MutableSharedFlow<BufferedImage>,
    private val errorHandler: (Throwable) -> Unit,
    private val targetResolution: Pair<Int, Int>? = null,
) {
    private val converter = FrameConverter()

    @Volatile private var grabber: FrameGrabber? = null
    private val job = atomic<Job?>(null)

    private var isHorizontallyFlipped = false

    fun setHorizontalFlip(flip: Boolean) {
        this.isHorizontallyFlipped = flip
        converter.setHorizontalFlip(flip)
    }

    fun grabCurrentFrame(): BufferedImage? {
        val frame = grabber?.grab()
        return if (frame?.image != null) {
            converter.convert(frame)
        } else {
            null
        }
    }

    /**
     * Starts the grab loop in [coroutineScope]. The loop owns the grabber for its whole life: it
     * opens it, grabs from it, and stops and releases it in its own `finally`, so the native handle
     * is never freed while a `grab()` or a conversion of the grabber's frame is still running. A
     * start that follows [stop] waits for the previous loop to release its grabber first.
     */
    fun start(coroutineScope: CoroutineScope, customGrabber: FrameGrabber? = null) {
        val previous = job.value
        if (previous?.isActive == true) return

        val loop =
            coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                var owned: FrameGrabber? = null
                try {
                    previous?.join()
                    owned = customGrabber ?: createGrabber()
                    // Set format for macOS AVFoundation before starting
                    if (owned is FFmpegFrameGrabber && getOperatingSystem() == OperatingSystem.MAC) {
                        owned.format = "avfoundation"
                    }
                    owned.start()
                    grabber = owned
                    converter.setHorizontalFlip(isHorizontallyFlipped)

                    while (isActive) {
                        val frame = owned.grab()
                        if (frame?.image != null) {
                            converter.convert(frame)?.let { image ->
                                frameFlow.tryEmit(image)
                            }
                        }

                        delay(1)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorHandler(e)
                } finally {
                    grabber = null
                    owned?.let(::release)
                }
            }
        if (!job.compareAndSet(previous, loop)) loop.cancel()
    }

    /**
     * Stops the grab loop. Non-blocking and idempotent: the loop releases the grabber and converter
     * once it leaves the native call it is in, however many times this is called.
     */
    fun stop() {
        job.value?.cancel()
    }

    private fun release(owned: FrameGrabber) {
        try {
            owned.stop()
        } catch (e: Exception) {
            errorHandler(e)
        }
        try {
            owned.release()
        } catch (e: Exception) {
            errorHandler(e)
        }
        try {
            converter.release()
        } catch (e: Exception) {
            errorHandler(e)
        }
    }

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

enum class OperatingSystem {
    WINDOWS,
    MAC,
    LINUX,
}
