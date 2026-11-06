package com.kashif.ocrplugin

import com.kashif.cameraK.frames.FrameRect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_OneComponent8
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequest
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.Vision.VNRequestTextRecognitionLevelFast
import platform.posix.memcpy

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
    override fun live(level: OcrLevel): LiveTextRecognizer = VisionLiveRecognizer(level, livePerformer)

    override fun still(level: OcrLevel): StillTextRecognizer = VisionStillRecognizer(level, stillPerformer)
}

@OptIn(ExperimentalForeignApi::class)
private class VisionLiveRecognizer(private val level: OcrLevel, private val performer: LiveVisionPerformer) :
    LiveTextRecognizer {
    override fun recognize(image: LumaImage): List<FoundBlock> = autoreleasepool {
        val pixelBuffer = lumaPixelBuffer(image)
        try {
            val upright = uprightSize(image.width, image.height, image.rotationDegrees)
            runTextRequest(liveTextRequestBuilder(level), upright, MIN_LIVE_CONFIDENCE) { request ->
                performer.perform(pixelBuffer, orientationOf(image.rotationDegrees), request)
            }
        } finally {
            CVPixelBufferRelease(pixelBuffer)
        }
    }

    override fun close() = Unit
}

@OptIn(ExperimentalForeignApi::class)
private class VisionStillRecognizer(private val level: OcrLevel, private val performer: VisionPerformer) :
    StillTextRecognizer {
    override suspend fun recognize(path: String): List<FoundBlock> = autoreleasepool {
        val still =
            UIImage.imageWithContentsOfFile(path) ?: throw IllegalArgumentException("The image could not be decoded")
        val cgImage = still.CGImage ?: throw IllegalArgumentException("The image has no bitmap")
        val orientation = orientationOf(still.imageOrientation)
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        val turned = orientation in TURNED_ORIENTATIONS
        val upright = if (turned) height to width else width to height
        runTextRequest({ onComplete -> stillTextRequest(level, onComplete) }, upright, 0f) { request ->
            performer.perform(cgImage, orientation, request)
        }
    }

    override fun close() = Unit
}

/**
 * Runs one text request through [perform] and returns its blocks, with bounds in the upright
 * image's pixels ([upright] is its width and height). Throws when Vision reports an error or
 * returns without answering. Vision calls the completion handler synchronously inside
 * performRequests, if at all, so once [perform] returns the request is answered or never will be.
 */
private fun runTextRequest(
    newRequest: ((VNRequest?, NSError?) -> Unit) -> VNRecognizeTextRequest,
    upright: Pair<Int, Int>,
    minConfidence: Float,
    perform: (VNRecognizeTextRequest) -> NSError?,
): List<FoundBlock> {
    var answer: Result<List<FoundBlock>>? = null
    val request = newRequest { vnRequest, error ->
        answer =
            if (error != null) {
                Result.failure(VisionException(error))
            } else {
                Result.success(blocksOf(vnRequest?.results as? NSArray, upright, minConfidence))
            }
    }
    val failure = perform(request)
    answer?.let { return it.getOrThrow() }
    throw failure?.let(::VisionException) ?: IllegalStateException("Vision returned without a result or an error")
}

@OptIn(ExperimentalForeignApi::class)
private fun blocksOf(results: NSArray?, upright: Pair<Int, Int>, minConfidence: Float): List<FoundBlock> {
    if (results == null) return emptyList()
    val (width, height) = upright
    return (0 until results.count.toInt()).mapNotNull { i ->
        val observation = results.objectAtIndex(i.toULong()) as? VNRecognizedTextObservation ?: return@mapNotNull null
        val candidate = observation.topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return@mapNotNull null
        if (candidate.string.isBlank() || candidate.confidence < minConfidence) return@mapNotNull null
        // Vision's box is normalised, with its origin at the bottom left.
        val bounds = observation.boundingBox.useContents {
            FrameRect(
                (origin.x * width).toInt(),
                ((1.0 - origin.y - size.height) * height).toInt(),
                ((origin.x + size.width) * width).toInt(),
                ((1.0 - origin.y) * height).toInt(),
            )
        }
        FoundBlock(candidate.string, bounds, candidate.confidence)
    }
}

/**
 * Copies [image]'s luma into a new one-plane 8-bit pixel buffer, row by row into the buffer's own
 * row stride. The caller releases it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun lumaPixelBuffer(image: LumaImage): CVPixelBufferRef {
    val pixelBuffer = memScoped {
        val created = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            image.width.convert(),
            image.height.convert(),
            kCVPixelFormatType_OneComponent8,
            null,
            created.ptr,
        )
        check(status == kCVReturnSuccess) { "CVPixelBufferCreate failed with $status" }
        checkNotNull(created.value) { "CVPixelBufferCreate returned no buffer" }
    }
    try {
        check(CVPixelBufferLockBaseAddress(pixelBuffer, 0u) == kCVReturnSuccess) {
            "The pixel buffer could not be locked"
        }
        try {
            val base = checkNotNull(CVPixelBufferGetBaseAddress(pixelBuffer)) { "The pixel buffer has no memory" }
            val rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer).toLong()
            image.bytes.usePinned { pinned ->
                for (row in 0 until image.height) {
                    val destination = interpretCPointer<ByteVar>(base.rawValue + row * rowBytes)
                    memcpy(destination, pinned.addressOf(row * image.width), image.width.convert())
                }
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, 0u)
        }
    } catch (e: Throwable) {
        CVPixelBufferRelease(pixelBuffer)
        throw e
    }
    return pixelBuffer
}

/**
 * The Vision request live OCR runs on each frame it reads, at [level]; [onComplete] receives
 * Vision's result. Live frames skip language correction and small print, which Vision misreads in
 * live frames.
 */
internal fun liveTextRequest(level: OcrLevel, onComplete: (VNRequest?, NSError?) -> Unit): VNRecognizeTextRequest =
    VNRecognizeTextRequest(onComplete).apply {
        recognitionLevel = visionLevel(level)
        usesLanguageCorrection = false
        minimumTextHeight = MIN_LIVE_TEXT_HEIGHT
        recognitionLanguages = listOf(LIVE_LANGUAGE)
    }

private fun liveTextRequestBuilder(level: OcrLevel): ((VNRequest?, NSError?) -> Unit) -> VNRecognizeTextRequest =
    { onComplete -> liveTextRequest(level, onComplete) }

/** The Vision request [OcrPlugin.recognize] runs on a still, at [level], with language correction. */
internal fun stillTextRequest(level: OcrLevel, onComplete: (VNRequest?, NSError?) -> Unit): VNRecognizeTextRequest =
    VNRecognizeTextRequest(onComplete).apply {
        recognitionLevel = visionLevel(level)
        usesLanguageCorrection = true
    }

private fun visionLevel(level: OcrLevel) =
    if (level == OcrLevel.ACCURATE) VNRequestTextRecognitionLevelAccurate else VNRequestTextRecognitionLevelFast

/** The CGImagePropertyOrientation that turns a buffer upright by [rotationDegrees] clockwise. */
internal fun orientationOf(rotationDegrees: Int): UInt = when (rotationDegrees) {
    90 -> ORIENTATION_RIGHT
    180 -> ORIENTATION_DOWN
    270 -> ORIENTATION_LEFT
    else -> ORIENTATION_UP
}

private fun orientationOf(orientation: UIImageOrientation): UInt = when (orientation) {
    UIImageOrientation.UIImageOrientationDown -> ORIENTATION_DOWN
    UIImageOrientation.UIImageOrientationLeft -> ORIENTATION_LEFT
    UIImageOrientation.UIImageOrientationRight -> ORIENTATION_RIGHT
    UIImageOrientation.UIImageOrientationUpMirrored -> 2u
    UIImageOrientation.UIImageOrientationDownMirrored -> 4u
    UIImageOrientation.UIImageOrientationLeftMirrored -> 5u
    UIImageOrientation.UIImageOrientationRightMirrored -> 7u
    else -> ORIENTATION_UP
}

private fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees == 90 || rotationDegrees == 270) height to width else width to height

/** An error Vision reported, carried as a Kotlin exception so it can be logged. */
private class VisionException(error: NSError) :
    Exception("Vision error ${error.domain} ${error.code}: ${error.localizedDescription}")

// CGImagePropertyOrientation values.
private const val ORIENTATION_UP = 1u
private const val ORIENTATION_DOWN = 3u
private const val ORIENTATION_RIGHT = 6u
private const val ORIENTATION_LEFT = 8u
private val TURNED_ORIENTATIONS = setOf(5u, 6u, 7u, 8u)

// Live candidates below this confidence are dropped: at the fast level they are mostly noise.
private const val MIN_LIVE_CONFIDENCE = 0.3f

// Text shorter than this fraction of the frame height is ignored, which skips the small print
// Vision misreads in live frames and saves time on each one.
private const val MIN_LIVE_TEXT_HEIGHT = 0.1f

// Live recognition reads English, as Android's Latin ML Kit model and desktop's eng model do.
private const val LIVE_LANGUAGE = "en-US"
