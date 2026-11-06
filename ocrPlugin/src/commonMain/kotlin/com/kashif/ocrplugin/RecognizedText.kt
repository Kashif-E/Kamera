package com.kashif.ocrPlugin

import com.kashif.cameraK.frames.FrameRect
import dev.drewhamilton.poko.Poko

/**
 * Text read from one live frame, or from a still by [OcrPlugin.recognize]. Compares by value.
 *
 * @property text The whole text: the blocks' texts joined by newlines.
 * @property blocks The blocks of text the recognizer found, in its reading order.
 * @property timestampNanos The live frame's `timestampNanos`; 0 for a still.
 */
@Poko
public class RecognizedText(
    public val text: String,
    public val blocks: List<TextBlock>,
    public val timestampNanos: Long,
)

/**
 * One block of recognized text. Compares by value.
 *
 * @property text The block's text.
 * @property bounds Where the block is: in frame pixels for a live frame (the frame's own
 *   orientation, as its `cropRect`), in the upright image's pixels for a still. Null when the
 *   recognizer reports no position, as Tesseract on desktop does not.
 * @property confidence The recognizer's confidence from 0 to 1, or null when it reports none, as ML
 *   Kit and Tesseract do not.
 */
public class TextBlock(public val text: String, public val bounds: FrameRect?, public val confidence: Float?) {
    // Not Poko: FrameRect compares by identity, so the bounds compare by their edges here.
    override fun equals(other: Any?): Boolean = this === other ||
        other is TextBlock &&
        text == other.text &&
        sameRect(bounds, other.bounds) &&
        confidence == other.confidence

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + rectHash(bounds)
        result = 31 * result + (confidence?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "TextBlock(text=$text, bounds=${rectString(bounds)}, confidence=$confidence)"
}
