package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameRecorder
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Where and how the desktop engine writes its stills and recordings: the seams tests replace.
 *
 * @property userHome The folder the app-owned folders resolve under: the user's home.
 * @property recorderFactory A new, unstarted recorder for an output path and a configuration.
 * @property writeCapture Writes a still to a file in a format, throwing if it cannot.
 */
internal class DesktopMedia(
    val userHome: File = defaultUserHome(),
    val recorderFactory: (String, VideoConfiguration) -> FrameRecorder = ::createFrameRecorder,
    val writeCapture: (BufferedImage, ImageFormat, File) -> Unit = ::writeImage,
)

// user.home can be missing in sandboxed runtimes; fall back to the working directory, as the desktop
// image saver does.
internal fun defaultUserHome(): File = File(System.getProperty("user.home") ?: System.getProperty("user.dir") ?: ".")

// macOS names the user's video folder Movies; Linux (XDG) and Windows name it Videos.
internal fun moviesFolderName(): String =
    if (System.getProperty("os.name").lowercase().contains("mac")) "Movies" else "Videos"

private fun hasPosixPermissions(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()

/**
 * Returns [folder], creating it (and any missing parents) if it does not exist. A folder this
 * creates is open only to its owner where the file system has POSIX permissions; an existing one
 * keeps its permissions.
 */
internal fun createOwnerOnlyDirectory(folder: File): Path {
    val path = folder.toPath().toAbsolutePath()
    if (Files.isDirectory(path)) return path
    path.parent?.let { Files.createDirectories(it) }
    val ownerOnly = if (hasPosixPermissions(path)) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        emptyArray()
    }
    return try {
        Files.createDirectory(path, *ownerOnly)
    } catch (e: FileAlreadyExistsException) {
        // Another capture created it first; use it if it is a folder.
        if (!Files.isDirectory(path)) throw e
        CameraKLogger.w("CameraK", "Capture folder created concurrently, using it", e)
        path
    }
}

/**
 * Creates a new, empty file in [folder] (creating the folder too) whose name no other file has,
 * readable and writable only by its owner where the file system has POSIX permissions.
 */
internal fun createUniqueFile(folder: File, prefix: String, extension: String): File {
    val dir = createOwnerOnlyDirectory(folder)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    val ownerOnly = if (hasPosixPermissions(dir)) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        emptyArray()
    }
    for (attempt in 0 until MAX_NAME_ATTEMPTS) {
        val suffix = if (attempt == 0) "" else "_$attempt"
        try {
            return Files.createFile(dir.resolve("${prefix}_$stamp$suffix.$extension"), *ownerOnly).toFile()
        } catch (e: FileAlreadyExistsException) {
            // Another capture in the same millisecond took this name; try the next suffix.
            CameraKLogger.w("CameraK", "Capture file name taken, trying the next one", e)
        }
    }
    throw IOException("No free file name for $prefix in $MAX_NAME_ATTEMPTS attempts")
}

/**
 * An H.264 MP4 recorder for [configuration], sized from its quality until the controller sets the
 * camera's frame size. Desktop captures no microphone audio, so the file has no audio track even
 * when audio is asked for: an AAC track with no samples would be empty.
 */
internal fun createFrameRecorder(outputPath: String, configuration: VideoConfiguration): FrameRecorder {
    if (configuration.enableAudio) {
        CameraKLogger.w("CameraK", "Desktop records no audio; the video has no audio track")
    }
    return FFmpegFrameRecorder(outputPath, configuration.quality.width, configuration.quality.height, 0).apply {
        videoCodec = avcodec.AV_CODEC_ID_H264
        format = "mp4"
        // The time base for the capture-time timestamps set on each frame.
        frameRate = RECORDING_FRAME_RATE
        videoBitrate = configuration.quality.bitrateBps
    }
}

/**
 * The frame number an encoder at [frameRate] gives a frame stamped [timestampMicros], as
 * `FFmpegFrameRecorder.setTimestamp` computes it. A recorder with no frame rate keeps the timestamp.
 */
internal fun frameNumberAt(timestampMicros: Long, frameRate: Double): Long =
    if (frameRate > 0) Math.round(timestampMicros * frameRate / 1_000_000) else timestampMicros

/** Writes [image] to [file] as [format], throwing if no writer accepts it. */
internal fun writeImage(image: BufferedImage, format: ImageFormat, file: File) {
    if (format == ImageFormat.PNG) {
        if (!ImageIO.write(image, "png", file)) throw IOException("No PNG writer accepts this image")
        return
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
        ?: throw IOException("No JPEG writer is installed")
    try {
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        val stream = ImageIO.createImageOutputStream(file) ?: throw IOException("Cannot open the capture file")
        stream.use { out ->
            writer.output = out
            writer.write(null, IIOImage(image, null, null), params)
        }
    } finally {
        writer.dispose()
    }
}

/**
 * Whether the volume that holds [folder], or its nearest existing parent, has almost no space left:
 * the free-space check that tells a full disk from another write failure (§4.1 row 8).
 */
internal fun isDiskFull(folder: File): Boolean {
    var existing: File? = folder.absoluteFile
    while (existing != null && !existing.exists()) existing = existing.parentFile
    return existing != null && existing.usableSpace < LOW_FREE_SPACE_BYTES
}

// Below this a write failure is a full disk.
private const val LOW_FREE_SPACE_BYTES = 1L shl 20

// The encoder's frame rate: a camera faster than this is recorded at this rate.
private const val RECORDING_FRAME_RATE = 30.0

// Android compresses stills at 95 too, so a desktop JPEG matches it.
private const val JPEG_QUALITY = 0.95f
private const val MAX_NAME_ATTEMPTS = 100
