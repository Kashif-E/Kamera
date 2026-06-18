package com.kashif.ocrPlugin

import androidx.compose.ui.graphics.ImageBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoStabilizationModeStandard
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreImage.CIImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSArray
import platform.Foundation.NSDate
import platform.Foundation.date
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.UIKit.UIImage
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap): String = suspendCoroutine { continuation ->
    val imageData = bitmap.toByteArray()?.toNSData()
    if (imageData == null) {
        continuation.resume("")
        return@suspendCoroutine
    }

    val uiImage = UIImage.imageWithData(imageData)
    if (uiImage == null) {
        continuation.resume("")
        return@suspendCoroutine
    }

    val request =
        VNRecognizeTextRequest { request, error ->
            if (error != null) {
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

    try {
        val handler = VNImageRequestHandler(uiImage.CGImage!!, mapOf<Any?, String>())
        handler.performRequests(listOf(request), null)
    } catch (e: Exception) {
        continuation.resume("")
    }
}

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

            /**
             * Text recognition request using Vision framework.
             * Configured for accurate recognition with language correction disabled.
             */
            val request =
                VNRecognizeTextRequest { vnRequest, error ->
                    if (error != null) {
                        cleanupAndRelease(sampleBuffer)
                        return@VNRecognizeTextRequest
                    }

                    handleRecognitionResults(vnRequest?.results as? NSArray, sampleBuffer)
                }.apply {
                    recognitionLevel = VNRequestTextRecognitionLevelAccurate
                    usesLanguageCorrection = false
                    minimumTextHeight = 0.1f
                    recognitionLanguages = listOf("en-US")
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
actual fun startRecognition(cameraController: CameraController, onText: (String) -> Unit): RecognitionHandle {
    // Idempotency/teardown is owned by the plugin (it stops the prior handle before re-starting),
    // so no module-global dedup is needed here.
    val videoDataOutput =
        AVCaptureVideoDataOutput().apply {
            setVideoSettings(
                mapOf(
                    kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
                ),
            )
            setAlwaysDiscardsLateVideoFrames(true)
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
