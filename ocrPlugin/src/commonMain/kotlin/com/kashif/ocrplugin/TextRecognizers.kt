package com.kashif.ocrPlugin

import com.kashif.cameraK.frames.FrameRect

/**
 * A grayscale image for a recognizer: [width] x [height] luma bytes from the start of [bytes], row
 * after row with no padding. [rotationDegrees] clockwise turns it upright. The bytes are a buffer
 * the plugin reuses, valid only during the call that receives them.
 */
internal class LumaImage(val bytes: ByteArray, val width: Int, val height: Int, val rotationDegrees: Int)

/**
 * A block of text a recognizer found. [bounds] are in the upright image: the luma turned by its
 * rotation for a live frame, the decoded still for [StillTextRecognizer].
 */
internal class FoundBlock(val text: String, val bounds: FrameRect?, val confidence: Float?)

/** Reads live frames for one session, on the frame registration's worker, one frame at a time. */
internal interface LiveTextRecognizer {
    /** Reads [image]; blocks until the platform recognizer answers and throws when it fails. */
    fun recognize(image: LumaImage): List<FoundBlock>

    /** Frees the recognizer's platform resources. Called once, never during [recognize]. */
    fun close()
}

/** Reads one still. */
internal interface StillTextRecognizer {
    /** Reads the image file at [path]; throws when it cannot be read or the platform recognizer fails. */
    suspend fun recognize(path: String): List<FoundBlock>

    /** Frees whatever [recognize] allocated. Called once, on every path, after [recognize]. */
    fun close()
}

/** The platform's recognizers: ML Kit on Android, Vision on iOS, Tesseract on desktop. */
internal interface TextRecognizerFactory {
    /** A recognizer for one session's live frames; throws when the platform recognizer cannot start. */
    fun live(level: OcrLevel): LiveTextRecognizer

    /** A recognizer for one still. */
    fun still(level: OcrLevel): StillTextRecognizer
}

/** This platform's recognizers for [config]. */
internal expect fun platformTextRecognizers(config: OcrConfig): TextRecognizerFactory
