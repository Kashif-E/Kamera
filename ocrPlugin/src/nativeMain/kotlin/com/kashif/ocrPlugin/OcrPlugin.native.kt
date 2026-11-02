package com.kashif.ocrPlugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoStabilizationModeStandard
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrderDefault
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreImage.CIImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSArray
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.date
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequest
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap, config: OcrConfig): String =
    recognizeText(bitmap, ::performVisionRequest)

/**
 * Runs a Vision request over an image, so a test can stand in for Vision. Returns the error Vision
 * reported, or null when the request ran.
 */
internal fun interface VisionPerformer {
    @OptIn(ExperimentalForeignApi::class)
    fun perform(image: CGImageRef, request: VNRecognizeTextRequest): NSError?
}

/** [VisionPerformer] over VNImageRequestHandler.performRequests. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun performVisionRequest(image: CGImageRef, request: VNRecognizeTextRequest): NSError? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val handler = VNImageRequestHandler(image, mapOf<Any?, String>())
    if (handler.performRequests(listOf(request), error.ptr)) null else error.value
}

/**
 * Reads the text in [bitmap] once, running Vision through [performer] off the caller's thread.
 * Returns "" when the bitmap cannot be handed to Vision or Vision fails; the failure is logged.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun recognizeText(bitmap: ImageBitmap, performer: VisionPerformer): String =
    withContext(Dispatchers.Default) {
        val cgImage = cgImageOf(bitmap)
        if (cgImage == null) {
            CameraKLogger.e(TAG, "Text extraction failed: the bitmap's pixels could not be read")
            return@withContext ""
        }

        try {
            suspendCancellableCoroutine { continuation ->
                val request =
                    VNRecognizeTextRequest { request, error ->
                        if (error != null) {
                            CameraKLogger.e(TAG, "Text extraction failed", VisionException(error))
                            continuation.resume("")
                            return@VNRecognizeTextRequest
                        }

                        val results = request?.results as? NSArray
                        if (results == null) {
                            continuation.resume("")
                            return@VNRecognizeTextRequest
                        }

                        val recognizedText =
                            buildString {
                                for (i in 0 until results.count.toInt()) {
                                    val observation =
                                        results.objectAtIndex(i.toULong()) as? VNRecognizedTextObservation
                                    observation?.let { obs ->
                                        val candidatesArray = obs.topCandidates(1u)

                                        if (candidatesArray.isNotEmpty()) {
                                            val text =
                                                (candidatesArray.first() as? NSObject)?.valueForKey("string") as? String
                                            if (!text.isNullOrBlank()) {
                                                if (isNotEmpty()) append("\n")
                                                append(text)
                                            }
                                        }
                                    }
                                }
                            }

                        continuation.resume(recognizedText.trim())
                    }

                request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
                request.usesLanguageCorrection = true

                // Vision calls the completion handler synchronously inside performRequests, if at all,
                // so once perform returns the continuation is resumed already or never will be.
                val failure =
                    try {
                        performer.perform(cgImage, request)?.let(::VisionException)
                    } catch (e: Exception) {
                        e
                    }
                if (continuation.isActive) {
                    if (failure != null) {
                        CameraKLogger.e(TAG, "Text extraction failed: Vision did not run the request", failure)
                    } else {
                        CameraKLogger.e(TAG, "Text extraction failed: Vision returned without a result or an error")
                    }
                    continuation.resume("")
                }
            }
        } finally {
            CGImageRelease(cgImage)
        }
    }

/**
 * Copies [bitmap]'s pixels once into a new sRGB CGImage, without encoding them, or returns null when
 * they cannot be read. The caller releases the image.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun cgImageOf(bitmap: ImageBitmap): CGImageRef? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val rowBytes = width * 4
    val pixels =
        bitmap.asSkiaBitmap().readPixels(
            ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            rowBytes,
            0,
            0,
        ) ?: return null

    // The provider reads the pinned array in place and unpins it when Core Graphics releases it.
    val pinned = pixels.pin()
    val owner = StableRef.create(pinned)
    val provider =
        CGDataProviderCreateWithData(
            owner.asCPointer(),
            pinned.addressOf(0),
            pixels.size.convert(),
            releasePinnedPixels,
        )
    if (provider == null) {
        owner.dispose()
        pinned.unpin()
        return null
    }
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    return try {
        CGImageCreate(
            width.convert(),
            height.convert(),
            8u,
            32u,
            rowBytes.convert(),
            space,
            // RGBA bytes in memory, alpha premultiplied: the layout readPixels was asked for.
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrderDefault,
            provider,
            null,
            false,
            CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
    } finally {
        CGColorSpaceRelease(space)
        CGDataProviderRelease(provider)
    }
}

@OptIn(ExperimentalForeignApi::class)
private val releasePinnedPixels =
    staticCFunction { info: COpaquePointer?, _: COpaquePointer?, _: ULong ->
        info?.asStableRef<Pinned<ByteArray>>()?.let { owner ->
            owner.get().unpin()
            owner.dispose()
        }
        Unit
    }

private const val TAG = "TextRecognition"

/** An error Vision reported, carried as a Kotlin exception so it can be logged. */
private class VisionException(error: NSError) :
    Exception("Vision error ${error.domain} ${error.code}: ${error.localizedDescription}")

/**
 * Delegate for handling video frame capture and processing.
 * Processes frames asynchronously to extract text using Vision framework.
 *
 * @property onText Callback invoked when text is successfully extracted from a frame.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoDataDelegate(private val onText: (String) -> Unit) :
    NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    @Volatile
    private var isProcessingFrame = false
    private val processingQueue =
        dispatch_queue_create(
            "com.kashif.ocrPlugin.processing",
            null,
        )

    @Volatile
    private var lastProcessedTime: Double = 0.0
    private val minimumProcessingInterval = 0.5 // Process max 2 frames per second

    /**
     * Processes a captured video frame to extract text.
     *
     * @param output The capture output that provided the sample buffer.
     * @param didOutputSampleBuffer The captured video frame sample buffer.
     * @param fromConnection The connection from which the sample buffer was received.
     */
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        if (didOutputSampleBuffer == null) return

        // Skip if already processing
        if (isProcessingFrame) return

        val currentTime = NSDate.date().timeIntervalSince1970()
        if (currentTime - lastProcessedTime < minimumProcessingInterval) {
            return
        }

        // Mark as processing before async work
        isProcessingFrame = true
        lastProcessedTime = currentTime

        CFRetain(didOutputSampleBuffer)

        dispatch_async(processingQueue) {
            processFrame(didOutputSampleBuffer)
        }
    }

    private fun processFrame(sampleBuffer: CMSampleBufferRef) {
        try {
            val pixelBuffer =
                CMSampleBufferGetImageBuffer(sampleBuffer) ?: run {
                    cleanupAndRelease(sampleBuffer)
                    return
                }

            val ciImage =
                CIImage.imageWithCVPixelBuffer(pixelBuffer) ?: run {
                    cleanupAndRelease(sampleBuffer)
                    return
                }

            val request =
                liveTextRequest { vnRequest, error ->
                    if (error != null) {
                        cleanupAndRelease(sampleBuffer)
                        return@liveTextRequest
                    }

                    handleRecognitionResults(vnRequest?.results as? NSArray, sampleBuffer)
                }

            val handler =
                ciImage.pixelBuffer?.let {
                    VNImageRequestHandler(it, emptyMap<Any?, String>())
                } ?: run {
                    cleanupAndRelease(sampleBuffer)
                    return
                }

            handler?.performRequests(listOf(request), null)
        } catch (e: Exception) {
            cleanupAndRelease(sampleBuffer)
        }
    }

    private fun handleRecognitionResults(resultsArray: NSArray?, sampleBuffer: CMSampleBufferRef) {
        if (resultsArray == null || resultsArray.count.toInt() == 0) {
            cleanupAndRelease(sampleBuffer)
            return
        }

        /**
         * Collects recognized text from Vision results.
         * Filters candidates by confidence threshold (0.3) to avoid low-confidence noise.
         */
        val recognizedStrings = mutableListOf<String>()

        for (i in 0 until resultsArray.count.toInt()) {
            val observation = resultsArray.objectAtIndex(i.toULong()) as? VNRecognizedTextObservation
            observation?.let {
                val topCandidates = it.topCandidates(1u) as NSArray
                if (topCandidates.count > 0uL) {
                    val candidate = topCandidates.objectAtIndex(0uL) as? VNRecognizedText
                    val confidence = candidate?.confidence ?: 0.0f

                    if (confidence > 0.3f) {
                        candidate?.string?.let { text ->
                            if (text.isNotBlank()) {
                                recognizedStrings.add(text)
                            }
                        }
                    }
                }
            }
        }

        val extractedText = recognizedStrings.joinToString("\n")
        if (extractedText.isNotBlank()) {
            dispatch_async(dispatch_get_main_queue()) {
                onText(extractedText.trim())
                cleanupAndRelease(sampleBuffer)
            }
        } else {
            cleanupAndRelease(sampleBuffer)
        }
    }

    /**
     * Cleans up and releases the video frame sample buffer.
     *
     * @param sampleBuffer The sample buffer to release.
     */
    private fun cleanupAndRelease(sampleBuffer: CMSampleBufferRef) {
        isProcessingFrame = false
        CFRelease(sampleBuffer)
    }
}

/** The Vision request live OCR runs on each frame it reads; [onComplete] receives Vision's result. */
internal fun liveTextRequest(onComplete: (VNRequest?, NSError?) -> Unit): VNRecognizeTextRequest =
    VNRecognizeTextRequest(onComplete).apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        usesLanguageCorrection = false
        minimumTextHeight = 0.1f
        recognitionLanguages = listOf("en-US")
    }

/** The video output live OCR reads frames from, before its sample-buffer delegate is set. */
@OptIn(ExperimentalForeignApi::class)
internal fun ocrVideoDataOutput(): AVCaptureVideoDataOutput =
    AVCaptureVideoDataOutput().apply {
        setVideoSettings(
            mapOf(
                kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
            ),
        )
        setAlwaysDiscardsLateVideoFrames(true)
    }

/**
 * Enables continuous text recognition on the iOS camera controller.
 *
 * Uses configuration queue pattern (Apple WWDC pattern) to safely add video output.
 * Processes camera frames asynchronously and emits detected text via callback.
 * Does NOT call startSession() - session is started by main camera setup.
 *
 * Tracks the video output to prevent duplicate setup and allow proper cleanup.
 *
 * @param cameraController The camera controller providing frames
 * @param onText Callback invoked when text is detected with the extracted text
 */
@OptIn(ExperimentalForeignApi::class)
actual fun startRecognition(
    cameraController: CameraController,
    config: OcrConfig,
    onText: (String) -> Unit,
): RecognitionHandle {
    // Idempotency/teardown is owned by the plugin (it stops the prior handle before re-starting),
    // so no module-global dedup is needed here.
    val videoDataOutput =
        ocrVideoDataOutput().apply {
            setSampleBufferDelegate(
                VideoDataDelegate(onText),
                dispatch_queue_create("com.kashif.ocrPlugin.videoQueue", null),
            )
        }

    // Queue configuration change atomically (prevents startRunning inside begin/commit crash)
    cameraController.queueConfigurationChange {
        cameraController.safeAddOutput(videoDataOutput)

        (videoDataOutput.connections.firstOrNull() as? AVCaptureConnection)?.let { connection ->
            if (connection.supportsVideoOrientation) {
                connection.setVideoOrientation(AVCaptureVideoOrientationPortrait)
            }
            if (connection.supportsVideoStabilization) {
                connection.setPreferredVideoStabilizationMode(
                    AVCaptureVideoStabilizationModeStandard,
                )
            }
        }
    }

    return RecognitionHandle {
        videoDataOutput.setSampleBufferDelegate(null, null)
        cameraController.safeRemoveOutput(videoDataOutput)
    }
}
