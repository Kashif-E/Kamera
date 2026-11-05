package com.kashif.ocrPlugin

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreGraphics.CGImageRef
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.NSError
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRequest
import platform.Vision.VNRequestTextRecognitionLevelFast

internal actual fun platformTextRecognizers(config: OcrConfig): TextRecognizerFactory = AppleTextRecognizers()

/**
 * Runs a Vision request over a still, so a test can stand in for Vision. Returns the error Vision
 * reported, or null when the request ran.
 */
internal fun interface VisionPerformer {
    @OptIn(ExperimentalForeignApi::class)
    fun perform(image: CGImageRef, orientation: UInt, request: VNRecognizeTextRequest): NSError?
}

/**
 * Runs live OCR's Vision request over one frame's luma, so a test can stand in for Vision. Returns
 * the error Vision reported, or null when the request ran.
 */
internal fun interface LiveVisionPerformer {
    @OptIn(ExperimentalForeignApi::class)
    fun perform(pixelBuffer: CVPixelBufferRef, orientation: UInt, request: VNRecognizeTextRequest): NSError?
}

/** [VisionPerformer] over VNImageRequestHandler.performRequests. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun performVisionRequest(image: CGImageRef, orientation: UInt, request: VNRecognizeTextRequest): NSError? =
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val handler = VNImageRequestHandler(image, orientation, emptyMap<Any?, Any?>())
        if (handler.performRequests(listOf(request), error.ptr)) null else error.value
    }

/** [LiveVisionPerformer] over VNImageRequestHandler.performRequests. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun performLiveVisionRequest(
    pixelBuffer: CVPixelBufferRef,
    orientation: UInt,
    request: VNRecognizeTextRequest,
): NSError? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val handler = VNImageRequestHandler(pixelBuffer, orientation, emptyMap<Any?, Any?>())
    if (handler.performRequests(listOf(request), error.ptr)) null else error.value
}

/**
 * The iOS recognizers: Vision. Live frames reach it as a one-plane 8-bit pixel buffer built from
 * the frame's luma, never the camera's own sample buffer, so the plugin retains none; a still is
 * decoded from its file with its orientation.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleTextRecognizers(
    private val livePerformer: LiveVisionPerformer = LiveVisionPerformer(::performLiveVisionRequest),
    private val stillPerformer: VisionPerformer = VisionPerformer(::performVisionRequest),
) : TextRecognizerFactory {
    override fun live(level: OcrLevel): LiveTextRecognizer = TODO("OC': Vision live recognizer over $livePerformer")

    override fun still(level: OcrLevel): StillTextRecognizer = TODO("OC': Vision still recognizer over $stillPerformer")
}

/**
 * The Vision request live OCR runs on each frame it reads, at [level]; [onComplete] receives
 * Vision's result. Live frames skip language correction and small print, which Vision misreads in
 * live frames.
 */
internal fun liveTextRequest(level: OcrLevel, onComplete: (VNRequest?, NSError?) -> Unit): VNRecognizeTextRequest =
    VNRecognizeTextRequest(onComplete).apply {
        // OC': the level does not reach Vision yet ($level).
        recognitionLevel = VNRequestTextRecognitionLevelFast
        usesLanguageCorrection = false
        minimumTextHeight = MIN_LIVE_TEXT_HEIGHT
        recognitionLanguages = listOf(LIVE_LANGUAGE)
    }

// Text shorter than this fraction of the frame height is ignored, which skips the small print
// Vision misreads in live frames and saves time on each one.
private const val MIN_LIVE_TEXT_HEIGHT = 0.1f

// Live recognition reads English, as Android's Latin ML Kit model and desktop's eng model do.
private const val LIVE_LANGUAGE = "en-US"
