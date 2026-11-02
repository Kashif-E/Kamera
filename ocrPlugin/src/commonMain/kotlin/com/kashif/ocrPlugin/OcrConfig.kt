package com.kashif.ocrPlugin

import androidx.compose.runtime.Immutable

/**
 * Configuration for [OcrPlugin] and [extractTextFromBitmapImpl].
 *
 * Desktop OCR runs Tesseract, which needs `eng.traineddata`. It takes the first of:
 * 1. [tessdataPath], when set. It is final: if that directory has no `eng.traineddata`, OCR fails
 *    rather than loading a model from somewhere else.
 * 2. A packaged model: the `eng.traineddata` from github.com/tesseract-ocr/tessdata, placed on the
 *    classpath at `com/kashif/ocrPlugin/tessdata/eng.traineddata`. It is loaded only if its SHA-256
 *    matches the one pinned in the library, after it is extracted to a per-user directory
 *    (`~/Library/Application Support/CameraK/tessdata` on macOS, `%LOCALAPPDATA%\CameraK\tessdata`
 *    on Windows, `$XDG_DATA_HOME/CameraK/tessdata` elsewhere).
 * 3. The directory named by the `TESSDATA_PREFIX` environment variable.
 *
 * The working directory is never searched. [tessdataPath] and `TESSDATA_PREFIX` are trusted as
 * given: Tesseract parses whatever model they point at. When no source has a model, starting
 * recognition fails and the plugin reports it.
 *
 * @property tessdataPath Desktop only: a directory containing `eng.traineddata`. Ignored on Android
 *   and iOS.
 * @property maxFps The most camera frames live OCR reads per second, on every platform; frames in
 *   between are skipped unread. Text recognition is a neural network (ML Kit, Vision) or Tesseract,
 *   so reading every frame drains the battery for no gain: text in view changes slowly. Defaults
 *   to 2. Raise it only if results must follow a moving camera more closely.
 */
@Immutable
class OcrConfig(val tessdataPath: String? = null, val maxFps: Int = DEFAULT_LIVE_OCR_MAX_FPS) {
    init {
        require(maxFps > 0) { "maxFps must be positive" }
    }

    // Not a data class: fields will be added, and a data class's copy and componentN would change
    // signature with each one.
    override fun equals(other: Any?): Boolean =
        this === other || other is OcrConfig && tessdataPath == other.tessdataPath && maxFps == other.maxFps

    override fun hashCode(): Int = 31 * tessdataPath.hashCode() + maxFps

    override fun toString(): String = "OcrConfig(tessdataPath=$tessdataPath, maxFps=$maxFps)"
}

/** How many frames a second live OCR reads unless [OcrConfig.maxFps] says otherwise. */
internal const val DEFAULT_LIVE_OCR_MAX_FPS = 2
