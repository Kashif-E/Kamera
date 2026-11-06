package com.kashif.ocrplugin

import androidx.compose.runtime.Immutable
import com.kashif.cameraK.frames.FrameRect

/**
 * How [OcrPlugin] recognizes live text, built with [OcrConfig]. Compares by value. Not a data class:
 * fields will be added, and a data class's `copy` and `componentN` would change signature with each.
 *
 * Desktop OCR runs Tesseract, which needs `eng.traineddata`. It takes the first of:
 * 1. [tessdataPath], when set. It is final: if that directory has no `eng.traineddata`, OCR fails
 *    rather than loading a model from somewhere else.
 * 2. A packaged model: the `eng.traineddata` from github.com/tesseract-ocr/tessdata, placed on the
 *    classpath at `com/kashif/ocrplugin/tessdata/eng.traineddata`. It is loaded only if its SHA-256
 *    matches the one pinned in the library, after it is extracted to a per-user directory
 *    (`~/Library/Application Support/CameraK/tessdata` on macOS, `%LOCALAPPDATA%\CameraK\tessdata`
 *    on Windows, `$XDG_DATA_HOME/CameraK/tessdata` elsewhere). A model at 1.x's
 *    `com/kashif/ocrPlugin/tessdata/eng.traineddata` is still read when the 2.0 path has none, with
 *    one warning to move it.
 * 3. The directory named by the `TESSDATA_PREFIX` environment variable.
 *
 * The working directory is never searched. [tessdataPath] and `TESSDATA_PREFIX` are trusted as
 * given: Tesseract parses whatever model they point at. When no source has a model, live
 * recognition fails and the plugin's status is `Failed`; [OcrPlugin.recognize] returns a `Failure`.
 *
 * @property maxFps The most camera frames live OCR reads per second; frames in between are skipped
 *   unread, before any copy. Text recognition is a neural network (ML Kit, Vision) or Tesseract, so
 *   reading every frame drains the battery for no gain: text in view changes slowly. Defaults to 2.
 * @property region The part of each frame live OCR reads, in frame pixels (the frame's own
 *   orientation, as its `cropRect`), intersected with the frame's `cropRect`. Null reads the whole
 *   `cropRect`. Recognized bounds are in frame pixels either way.
 * @property level How hard live recognition works on each frame. [OcrLevel.FAST] by default.
 * @property tessdataPath Desktop only: a directory containing `eng.traineddata`. Ignored on Android
 *   and iOS.
 */
@Immutable
public class OcrConfig private constructor(builder: Builder) {
    public val maxFps: Float = builder.maxFps
    public val region: FrameRect? = builder.region
    public val level: OcrLevel = builder.level
    public val tessdataPath: String? = builder.tessdataPath

    /** The configuration's fields, each starting at its default. */
    public class Builder internal constructor() {
        public var maxFps: Float = DEFAULT_MAX_FPS
        public var region: FrameRect? = null
        public var level: OcrLevel = OcrLevel.FAST
        public var tessdataPath: String? = null

        internal fun build(): OcrConfig {
            require(maxFps > 0f && maxFps.isFinite()) { "maxFps must be positive and finite" }
            region?.let {
                require(it.right > it.left && it.bottom > it.top) { "region must have a positive width and height" }
            }
            return OcrConfig(this)
        }
    }

    // FrameRect compares by identity, so the region compares by its edges here.
    override fun equals(other: Any?): Boolean = this === other ||
        other is OcrConfig &&
        maxFps == other.maxFps &&
        sameRect(region, other.region) &&
        level == other.level &&
        tessdataPath == other.tessdataPath

    override fun hashCode(): Int {
        var result = maxFps.hashCode()
        result = 31 * result + rectHash(region)
        result = 31 * result + level.hashCode()
        result = 31 * result + (tessdataPath?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "OcrConfig(maxFps=$maxFps, region=${rectString(region)}, level=$level, " +
        "tessdataPath set=${tessdataPath != null})"
}

/**
 * Builds an [OcrConfig]. Every field has its default unless [block] sets it.
 *
 * @throws IllegalArgumentException when `maxFps` is not positive and finite, or `region` is empty.
 */
public fun OcrConfig(block: OcrConfig.Builder.() -> Unit = {}): OcrConfig = OcrConfig.Builder().apply(block).build()

/** How many frames a second live OCR reads unless [OcrConfig.maxFps] says otherwise. */
internal const val DEFAULT_MAX_FPS = 2f

internal fun sameRect(a: FrameRect?, b: FrameRect?): Boolean = a === b ||
    a != null &&
    b != null &&
    a.left == b.left &&
    a.top == b.top &&
    a.right == b.right &&
    a.bottom == b.bottom

internal fun rectHash(rect: FrameRect?): Int =
    rect?.let { ((it.left * 31 + it.top) * 31 + it.right) * 31 + it.bottom } ?: 0

internal fun rectString(rect: FrameRect?): String =
    rect?.let { "FrameRect(${it.left}, ${it.top}, ${it.right}, ${it.bottom})" } ?: "null"
