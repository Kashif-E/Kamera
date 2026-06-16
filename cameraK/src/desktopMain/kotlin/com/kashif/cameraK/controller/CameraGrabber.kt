package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
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
    private var grabber: FrameGrabber? = null
    private var job: Job? = null

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

    fun start(coroutineScope: CoroutineScope, customGrabber: FrameGrabber? = null) {
        if (job?.isActive == true) return

        grabber =
            (customGrabber ?: createGrabber()).apply {
                try {
                    // Set format for macOS AVFoundation before starting
                    if (this is FFmpegFrameGrabber && getOperatingSystem() == OperatingSystem.MAC) {
                        this.format = "avfoundation"
                    }
                    start()
                } catch (e: Exception) {
                    errorHandler(e)
                    return
                }
            }

        converter.setHorizontalFlip(isHorizontallyFlipped)

        job =
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val frame = grabber?.grab()
                        if (frame?.image != null) {
                            converter.convert(frame)?.let { image ->
                                frameFlow.tryEmit(image)
                            }
                        }

                        delay(1)
                    }
                } catch (e: Exception) {
                    errorHandler(e)
                }
            }
    }

    fun stop() {
        job?.cancel()
        try {
            grabber?.stop()
            grabber?.release()
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
