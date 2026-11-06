package com.kashif.ocrPlugin

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * How hard a recognizer works on each image. Live recognition uses [OcrConfig.level], [FAST] by
 * default; [OcrPlugin.recognize] takes its own, [ACCURATE] by default.
 *
 * Vision on iOS runs its fast or accurate level. Tesseract on desktop runs its legacy engine at
 * [FAST] when the language data has one, its default engine otherwise, and its LSTM engine at
 * [ACCURATE]. ML Kit on Android has one model and ignores the level.
 */
@JvmInline
public value class OcrLevel private constructor(public val name: String) {
    override fun toString(): String = name

    public companion object {
        /** Quick enough for live frames, at some cost in accuracy. */
        public val FAST: OcrLevel = OcrLevel("FAST")

        /** Slower and more accurate, for a still. */
        public val ACCURATE: OcrLevel = OcrLevel("ACCURATE")
    }
}
