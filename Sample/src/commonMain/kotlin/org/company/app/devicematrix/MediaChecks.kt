package org.company.app.devicematrix

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat

/** The image format [head], a file's first bytes, starts with: JPEG FF D8, PNG 89 50 4E 47, HEIC an ftyp box. */
internal fun sniffImageFormat(head: ByteArray): ImageFormat? = TODO()

/** The pixel size the image's header states: JPEG's first frame header, PNG's IHDR, HEIC's largest ispe. */
internal fun imageSize(head: ByteArray): MatrixSize? = TODO()

/** The EXIF orientation tag (1 to 8) of a JPEG, or null when it has none. */
internal fun jpegExifOrientation(head: ByteArray): Int? = TODO()

/** The width-to-height ratio of [aspectRatio] in landscape. */
internal fun landscapeRatio(aspectRatio: AspectRatio): Double = TODO()

/** Whether [width] by [height] is [aspectRatio] in either orientation, within 1%. */
internal fun ratioMatches(width: Int, height: Int, aspectRatio: AspectRatio): Boolean = TODO()

/** The major brand of an ISO base media file (MP4 or QuickTime): its ftyp box's, or null without one. */
internal fun videoBrand(head: ByteArray): String? = TODO()

/**
 * The duration, in seconds, of the movie's mvhd box, walking the boxes of a file of [size] bytes
 * through [read] (offset, length); null when it has no moov or mvhd.
 */
internal fun mp4DurationSeconds(size: Long, read: (offset: Long, length: Int) -> ByteArray): Double? = TODO()
