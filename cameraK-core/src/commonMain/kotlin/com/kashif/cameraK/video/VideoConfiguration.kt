package com.kashif.cameraK.video

import kotlin.time.Duration

/**
 * Immutable configuration for video recording sessions, built with the DSL:
 *
 * ```kotlin
 * val video = VideoConfiguration {
 *     quality = VideoQuality.HD
 *     maxDuration = 30.seconds
 * }
 * ```
 *
 * Every field the block leaves unset takes its default; [copy] builds a changed copy.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC). Off unless the app asks: with it on, a
 *                        recording needs the microphone permission. Desktop records no audio and logs a
 *                        warning when this is true.
 * @property maxDuration The longest the recording may run; null means unlimited. The platform stops
 *                        the recording at it and keeps the file.
 * @property outputDirectory The directory the video file is written to, used as given. Null uses
 *                           `CameraConfiguration.outputDirectory`, else the platform's app-owned
 *                           default:
 *                           - Android: `filesDir/CameraK`.
 *                           - iOS: `Documents/CameraK`.
 *                           - Desktop: `~/Movies/CameraK` on macOS, `~/Videos/CameraK` on Linux and Windows.
 *                           Nothing checks that a directory given here is app-owned. No recording
 *                           reaches MediaStore, the media scanner or Photos.
 * @property filePrefix Prefix for the generated filename: 1 to 64 characters from `A-Z a-z 0-9 . _ -`,
 *                     and not `.` or `..`, so it can never name another directory.
 * @property maxFileSizeBytes The largest the file may grow; null means unlimited. The platform stops
 *                           the recording when the file reaches it and keeps the file.
 * @throws IllegalArgumentException from the builder if [maxDuration] or [maxFileSizeBytes] is
 *   negative, or [filePrefix] is not a valid prefix.
 */
public class VideoConfiguration private constructor(builder: Builder) {
    public val quality: VideoQuality = builder.quality
    public val enableAudio: Boolean = builder.enableAudio
    public val maxDuration: Duration? = builder.maxDuration
    public val outputDirectory: String? = builder.outputDirectory
    public val filePrefix: String = builder.filePrefix
    public val maxFileSizeBytes: Long? = builder.maxFileSizeBytes

    /** A copy with the fields [block] sets; every other field keeps this configuration's value. */
    public fun copy(block: Builder.() -> Unit): VideoConfiguration = Builder(this).apply(block).build()

    private fun fields(): List<Pair<String, Any?>> = listOf(
        "quality" to quality,
        "enableAudio" to enableAudio,
        "maxDuration" to maxDuration,
        "outputDirectory" to outputDirectory,
        "filePrefix" to filePrefix,
        "maxFileSizeBytes" to maxFileSizeBytes,
    )

    override fun equals(other: Any?): Boolean = other is VideoConfiguration && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        fields().joinToString(prefix = "VideoConfiguration(", postfix = ")") { (name, value) -> "$name=$value" }

    /** The DSL's receiver: one property per field, each starting at the default or at the copied value. */
    public class Builder internal constructor(from: VideoConfiguration?) {
        public var quality: VideoQuality = from?.quality ?: VideoDefaults.QUALITY
        public var enableAudio: Boolean = from?.enableAudio ?: VideoDefaults.ENABLE_AUDIO
        public var maxDuration: Duration? = if (from != null) from.maxDuration else VideoDefaults.MAX_DURATION
        public var outputDirectory: String? =
            if (from != null) from.outputDirectory else VideoDefaults.OUTPUT_DIRECTORY
        public var filePrefix: String = from?.filePrefix ?: VideoDefaults.FILE_PREFIX
        public var maxFileSizeBytes: Long? =
            if (from != null) from.maxFileSizeBytes else VideoDefaults.MAX_FILE_SIZE_BYTES

        internal fun build(): VideoConfiguration {
            val duration = maxDuration
            require(duration == null || !duration.isNegative()) {
                "maxDuration must be null (unlimited) or not negative, was $duration"
            }
            val size = maxFileSizeBytes
            require(size == null || size >= 0L) {
                "maxFileSizeBytes must be null (unlimited) or not negative, was $size"
            }
            require(FILE_PREFIX.matches(filePrefix) && filePrefix != "." && filePrefix != "..") {
                "filePrefix must be 1 to 64 characters from A-Z a-z 0-9 . _ - and not . or .."
            }
            return VideoConfiguration(this)
        }

        private companion object {
            val FILE_PREFIX = Regex("[A-Za-z0-9._-]{1,64}")
        }
    }
}

/** A video configuration with the fields [block] sets and the defaults for the rest. */
public fun VideoConfiguration(block: VideoConfiguration.Builder.() -> Unit = {}): VideoConfiguration =
    VideoConfiguration.Builder(null).apply(block).build()

/**
 * The named-argument factory, kept only so a 1.x call gets the rewrite to the DSL. It does not
 * compile. It takes 1.x's `maxDurationMs` beside `maxDuration`, so both kinds of call land here.
 */
@Deprecated(
    message = "VideoConfiguration is built with its DSL in 2.0: VideoConfiguration { quality = VideoQuality.HD }. " +
        "maxDurationMs is maxDuration, a Duration; 0 for unlimited is null.",
    replaceWith = ReplaceWith(
        "VideoConfiguration { this.quality = quality; this.enableAudio = enableAudio; " +
            "this.maxDuration = maxDuration; this.outputDirectory = outputDirectory; " +
            "this.filePrefix = filePrefix; this.maxFileSizeBytes = maxFileSizeBytes }",
    ),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
public fun VideoConfiguration(
    quality: VideoQuality = VideoQuality.FHD,
    enableAudio: Boolean = false,
    maxDuration: Duration? = null,
    outputDirectory: String? = null,
    filePrefix: String = "VID",
    maxFileSizeBytes: Long? = null,
    maxDurationMs: Long = 0L,
): VideoConfiguration = throw UnsupportedOperationException("Use VideoConfiguration { … }")

/** The one source of the video configuration's defaults. */
internal object VideoDefaults {
    val QUALITY = VideoQuality.FHD

    /** The microphone is opt-in. */
    const val ENABLE_AUDIO = false
    val MAX_DURATION: Duration? = null
    val OUTPUT_DIRECTORY: String? = null
    const val FILE_PREFIX = "VID"
    val MAX_FILE_SIZE_BYTES: Long? = null
}
