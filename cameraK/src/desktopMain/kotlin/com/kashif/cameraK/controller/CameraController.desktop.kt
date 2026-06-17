package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Interface defining the core functionalities of the CameraController.
 */
actual class CameraController(
    private val imageFormat: ImageFormat,
    private val directory: Directory,
    private val horizontalFlip: Boolean = false,
    private val customGrabber: FrameGrabber? = null,
    private val targetResolution: Pair<Int, Int>? = null,
) {
    private var cameraGrabber: CameraGrabber? = null
    private val _frameFlow = MutableSharedFlow<BufferedImage>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameFlow: Flow<BufferedImage> get() = _frameFlow
    private val qualityPriority: QualityPrioritization = QualityPrioritization.NONE

    // Video recording
    private var frameRecorder: FFmpegFrameRecorder? = null

    @Volatile private var isCurrentlyRecording = false

    @Volatile private var isPausedRecording = false
    private var recordingJob: Job? = null
    private var recordingOutputPath: String? = null
    private var recordingStartMs: Long = 0L

    private var listener: (ByteArray) -> Unit = {
        // default no-op listener
    }

    actual suspend fun takePictureToFile(): ImageCaptureResult {
        return withContext(Dispatchers.IO) {
            val currentImage = cameraGrabber?.grabCurrentFrame()

            if (currentImage == null) {
                return@withContext ImageCaptureResult.Error(IllegalStateException("No image available"))
            }

            return@withContext try {
                val ext = if (imageFormat == ImageFormat.PNG) "png" else "jpg"
                val outFile = File.createTempFile("CameraK_", ".$ext")
                ImageIO.write(currentImage, ext, outFile)
                listener(outFile.readBytes())
                ImageCaptureResult.SuccessWithFile(outFile.absolutePath)
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Unhandled exception", e)
                ImageCaptureResult.Error(e)
            }
        }
    }

    /**
     * Toggles the flash mode between ON, OFF, and AUTO.
     */
    actual fun toggleFlashMode() {
        // flash mode not available on desktop
    }

    /**
     * Sets the flash mode of the camera
     *
     * @param mode The desired [FlashMode]
     */
    actual fun setFlashMode(mode: FlashMode) {
        // flash mode not available on desktop
    }

    /**
     * @return the current [FlashMode] of the camera, if available
     */
    actual fun getFlashMode(): FlashMode? = FlashMode.OFF

    /**
     * Toggles the torch mode between ON, OFF, and AUTO.
     *
     * Note: Torch is not available on desktop hardware.
     */
    actual fun toggleTorchMode() {
        // torch not available on desktop
    }

    /**
     * Sets the torch mode of the camera
     *
     * @param mode The desired [TorchMode]
     *
     * Note: Torch is not available on desktop hardware.
     */
    actual fun setTorchMode(mode: TorchMode) {
        // torch not available on desktop
    }

    /**
     * Gets the current torch mode.
     *
     * @return null as Desktop doesn't support torch mode
     */
    actual fun getTorchMode(): TorchMode? = null

    /**
     * Toggles the camera lens between FRONT and BACK.
     */
    actual fun toggleCameraLens() {
        // camera lens not available on desktop
    }

    /**
     * Gets the current camera lens.
     *
     * @return null as Desktop doesn't support camera lens switching
     */
    actual fun getCameraLens(): CameraLens? = null

    /**
     * Gets the current image format.
     *
     * @return The configured [ImageFormat]
     */
    actual fun getImageFormat(): ImageFormat = imageFormat

    /**
     * Gets the current quality prioritization setting.
     *
     * @return The configured [QualityPrioritization] (always NONE on Desktop)
     */
    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    /**
     * Gets the current camera device type.
     *
     * @return The configured [CameraDeviceType] (always DEFAULT on Desktop)
     */
    actual fun getPreferredCameraDeviceType(): CameraDeviceType = CameraDeviceType.DEFAULT

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        // No-op on desktop — single camera
    }

    /**
     * Sets the zoom level.
     *
     * Note: Zoom is not supported on Desktop.
     */
    actual fun setZoom(zoomRatio: Float) {
        // zoom not available on desktop
    }

    /**
     * Gets the current zoom ratio.
     *
     * @return 1.0 as Desktop doesn't support zoom
     */
    actual fun getZoom(): Float = 1.0f

    /**
     * Gets the maximum zoom ratio.
     *
     * @return 1.0 as Desktop doesn't support zoom
     */
    actual fun getMaxZoom(): Float = 1.0f

    /**
     * Starts the camera session.
     */
    actual fun startSession() {
        CoroutineScope(Dispatchers.Default).launch {
            // If there is a custom grabber, use it, else use the default camera grabber
            // Which attempts to use the default camera
            cameraGrabber = CameraGrabber(_frameFlow, {
                CameraKLogger.e("CameraK", "CameraK: Camera error: ${it.message}")
                CameraKLogger.e("CameraK", "Unhandled exception", it)
            }, targetResolution).apply {
                setHorizontalFlip(horizontalFlip)
                start(this@launch, customGrabber)
            }
        }
    }

    /**
     * Stops the camera session.
     */
    actual fun stopSession() {
        cameraGrabber?.stop()
    }

    /**
     * Adds a listener for image capture events.
     *
     * @param listener The listener to add, receiving image data as [ByteArray].
     */
    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    actual fun getDeviceOrientation(): DeviceOrientation = DeviceOrientation.PORTRAIT

    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        // No-op on desktop
    }

    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        // No-op on desktop
    }

    actual fun cleanup() {
        stopVideoRecorderIfActive()
        cameraGrabber?.stop()
    }

    actual suspend fun startRecording(configuration: VideoConfiguration): String = withContext(Dispatchers.IO) {
        val outputPath = createVideoOutputPath(configuration)
        recordingOutputPath = outputPath

        val recorder = FFmpegFrameRecorder(
            outputPath,
            configuration.quality.width,
            configuration.quality.height,
            if (configuration.enableAudio) 1 else 0,
        ).apply {
            videoCodec = avcodec.AV_CODEC_ID_H264
            format = "mp4"
            frameRate = 30.0
            videoBitrate = configuration.quality.bitrateBps
            if (configuration.enableAudio) {
                audioCodec = avcodec.AV_CODEC_ID_AAC
                sampleRate = 44100
                audioBitrate = 128_000
            }
            start()
        }
        frameRecorder = recorder
        isCurrentlyRecording = true
        isPausedRecording = false
        recordingStartMs = System.currentTimeMillis()

        // Launch recording coroutine that grabs frames independently
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val converter = Java2DFrameConverter()
            try {
                while (isActive && isCurrentlyRecording) {
                    if (!isPausedRecording) {
                        val frame = cameraGrabber?.grabCurrentFrame()
                        if (frame != null) {
                            try {
                                val videoFrame = converter.convert(frame)
                                recorder.record(videoFrame)
                            } catch (e: Exception) {
                                CameraKLogger.e("CameraK", "CameraK recording frame error: ${e.message}")
                            }
                        }
                    }
                    delay(33) // ~30fps
                }
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK recording loop error: ${e.message}")
            }
        }

        outputPath
    }

    actual suspend fun stopRecording(): VideoCaptureResult = withContext(Dispatchers.IO) {
        isCurrentlyRecording = false
        recordingJob?.cancel()
        recordingJob = null
        val durationMs = System.currentTimeMillis() - recordingStartMs
        return@withContext try {
            frameRecorder?.stop()
            frameRecorder?.release()
            frameRecorder = null
            VideoCaptureResult.Success(recordingOutputPath ?: "", durationMs)
        } catch (e: Exception) {
            VideoCaptureResult.Error(e)
        }
    }

    actual suspend fun pauseRecording() {
        isPausedRecording = true
    }

    actual suspend fun resumeRecording() {
        isPausedRecording = false
    }

    private fun stopVideoRecorderIfActive() {
        if (isCurrentlyRecording) {
            isCurrentlyRecording = false
            recordingJob?.cancel()
            recordingJob = null
            try {
                frameRecorder?.stop()
                frameRecorder?.release()
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK: Error stopping recorder: ${e.message}")
            }
            frameRecorder = null
        }
    }

    private fun createVideoOutputPath(config: VideoConfiguration): String {
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dir = if (config.outputDirectory != null) {
            File(config.outputDirectory).also { it.mkdirs() }
        } else {
            File("captured_videos").also { it.mkdirs() }
        }
        return File(dir, "${config.filePrefix}_$timestamp.mp4").absolutePath
    }
}
