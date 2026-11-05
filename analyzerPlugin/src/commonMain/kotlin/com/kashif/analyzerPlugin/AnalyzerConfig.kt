package com.kashif.analyzerPlugin

/**
 * How the analyzer encodes frames, built with [AnalyzerConfig]. Compares by value.
 *
 * @property maxFps At most this many frames a second are encoded. 1 by default. The holder's frame
 *   source applies it before any copy, so a skipped frame costs nothing.
 * @property maxDimension The longest side of an encoded frame in pixels: a larger frame is scaled
 *   down to it, its aspect ratio kept, and a smaller one is never scaled up. 1280 by default; null
 *   encodes every frame at its full size.
 * @property jpegQuality The JPEG quality, from 1 to 100. 80 by default.
 */
public class AnalyzerConfig private constructor(builder: Builder) {
    public val maxFps: Float = builder.maxFps
    public val maxDimension: Int? = builder.maxDimension
    public val jpegQuality: Int = builder.jpegQuality

    /** The configuration's fields, each starting at the configuration it copies, or the default. */
    public class Builder internal constructor(from: AnalyzerConfig?) {
        public var maxFps: Float = from?.maxFps ?: DEFAULT_MAX_FPS
        public var maxDimension: Int? = if (from != null) from.maxDimension else DEFAULT_MAX_DIMENSION
        public var jpegQuality: Int = from?.jpegQuality ?: DEFAULT_JPEG_QUALITY

        internal fun build(): AnalyzerConfig = AnalyzerConfig(this)
    }

    /** A copy with the fields [block] sets changed. */
    public fun copy(block: Builder.() -> Unit): AnalyzerConfig = Builder(this).apply(block).build()

    override fun equals(other: Any?): Boolean = other is AnalyzerConfig &&
        maxFps == other.maxFps &&
        maxDimension == other.maxDimension &&
        jpegQuality == other.jpegQuality

    override fun hashCode(): Int {
        var result = maxFps.hashCode()
        result = 31 * result + (maxDimension ?: 0)
        result = 31 * result + jpegQuality
        return result
    }

    override fun toString(): String =
        "AnalyzerConfig(maxFps=$maxFps, maxDimension=$maxDimension, jpegQuality=$jpegQuality)"

    private companion object {
        const val DEFAULT_MAX_FPS = 1f
        const val DEFAULT_MAX_DIMENSION = 1280
        const val DEFAULT_JPEG_QUALITY = 80
    }
}

/**
 * Builds an [AnalyzerConfig]. Every field has its default unless [block] sets it.
 *
 * @throws IllegalArgumentException when `maxFps` is not a positive number, `maxDimension` is not
 *   positive, or `jpegQuality` is outside 1 to 100.
 */
public fun AnalyzerConfig(block: AnalyzerConfig.Builder.() -> Unit = {}): AnalyzerConfig =
    AnalyzerConfig.Builder(null).apply(block).build()
