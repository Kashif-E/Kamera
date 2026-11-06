package com.kashif.mediasaverplugin

import com.kashif.cameraK.enums.ImageFormat

/**
 * How the saver publishes, built with [MediaSaverConfig]. Compares by value.
 *
 * @property autoSave Whether every capture that reaches the holder's `captures` is saved. Off by default.
 * @property prefix Auto-save names are `<prefix>_<epochMillis>`; at most 50 characters from
 *   `A-Z a-z 0-9 . _ -`. Null names an auto-save the platform's way.
 * @property destination Where saves go. [GalleryDestination.PICTURES] by default.
 * @property folderName The folder inside [destination], 1 to 64 characters from `A-Z a-z 0-9 . _ -`,
 *   not `.` or `..`. Null means `CameraK`. iOS has no folders and ignores it.
 * @property imageFormat The format a deprecated byte save is stored in. A [com.kashif.cameraK.capture.CapturedImage]
 *   is copied in its own format and never decoded.
 */
public class MediaSaverConfig private constructor(builder: Builder) {
    public val autoSave: Boolean = builder.autoSave
    public val prefix: String? = builder.prefix
    public val destination: GalleryDestination = builder.destination
    public val folderName: String? = builder.folderName
    public val imageFormat: ImageFormat = builder.imageFormat

    /** The configuration's fields, each starting at the configuration it copies, or the default. */
    public class Builder internal constructor(from: MediaSaverConfig?) {
        public var autoSave: Boolean = from?.autoSave ?: false
        public var prefix: String? = from?.prefix
        public var destination: GalleryDestination = from?.destination ?: GalleryDestination.PICTURES
        public var folderName: String? = from?.folderName
        public var imageFormat: ImageFormat = from?.imageFormat ?: ImageFormat.JPEG

        internal fun build(): MediaSaverConfig {
            prefix?.let(::requireAutoSavePrefix)
            folderName?.let { requireSafeFileName(it, "folderName") }
            return MediaSaverConfig(this)
        }
    }

    /** A copy with the fields [block] sets changed. */
    public fun copy(block: Builder.() -> Unit): MediaSaverConfig = Builder(this).apply(block).build()

    override fun equals(other: Any?): Boolean = other is MediaSaverConfig &&
        autoSave == other.autoSave &&
        prefix == other.prefix &&
        destination == other.destination &&
        folderName == other.folderName &&
        imageFormat == other.imageFormat

    override fun hashCode(): Int {
        var result = autoSave.hashCode()
        result = 31 * result + (prefix?.hashCode() ?: 0)
        result = 31 * result + destination.hashCode()
        result = 31 * result + (folderName?.hashCode() ?: 0)
        result = 31 * result + imageFormat.hashCode()
        return result
    }

    override fun toString(): String = "MediaSaverConfig(autoSave=$autoSave, destination=$destination, " +
        "imageFormat=$imageFormat, prefix set=${prefix != null}, folderName set=${folderName != null})"
}

/**
 * Builds a [MediaSaverConfig]. Every field has its default unless [block] sets it.
 *
 * @throws IllegalArgumentException when the prefix or the folder name breaks its character rule. The
 *   message names the field, never its value.
 */
public fun MediaSaverConfig(block: MediaSaverConfig.Builder.() -> Unit = {}): MediaSaverConfig =
    MediaSaverConfig.Builder(null).apply(block).build()
