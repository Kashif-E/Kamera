package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.capture.path
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Desktop has no gallery: saves go to a folder under the user's home, `~/Pictures/<folder>` for
 * PICTURES and DCIM and `~/Documents/<folder>` for DOCUMENTS, where `<folder>` is the configured
 * folder name or `CameraK`. Each save creates a new file and never overwrites one: a taken name gets
 * a numeric suffix. A file is copied as it is; a deprecated byte save already in the configured
 * format is written as it is, and other bytes are decoded and encoded once into that format.
 *
 * Names and the folder name are each 1 to 64 characters from `A-Z a-z 0-9 . _ -` and not `.` or
 * `..`, so a forwarded name can never place a file outside the folder. A folder that is itself a
 * symbolic link is neither read nor written, wherever it points.
 *
 * Reads are limited to the library's own output: this store's folder and the `CameraK` folders
 * under `Pictures` and `Documents`, where CameraK's desktop captures go.
 */
@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
internal class DesktopGalleryStore(
    private val config: MediaSaverConfig,
    private val userHome: File = defaultUserHome(),
) : GalleryStore {
    // Desktop folders need no permission.
    override val galleryPermission: CameraKPermission? get() = null

    override suspend fun addImage(
        source: MediaSource,
        format: ImageFormat,
        name: String?,
    ): CameraKResult<GalleryAsset> = write(name, format.extension) { file ->
        when (source) {
            is MediaSource.File -> Files.copy(Path.of(source.path), file, StandardCopyOption.REPLACE_EXISTING)
            is MediaSource.Bytes -> {
                val bytes = if (sniffFormat(source.bytes) ==
                    format
                ) {
                    source.bytes
                } else {
                    transcode(source.bytes, format)
                }
                Files.write(file, bytes)
            }
        }
    }

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> {
        val extension = path.substringAfterLast(File.separatorChar).substringAfterLast('.', "mp4")
        return write(name, extension) { file -> Files.copy(Path.of(path), file, StandardCopyOption.REPLACE_EXISTING) }
    }

    private suspend fun write(name: String?, extension: String, fill: (Path) -> Unit): CameraKResult<GalleryAsset> =
        withContext(Dispatchers.IO) {
            try {
                val baseName = name?.let { requireSafeFileName(it, "imageName") } ?: defaultImageName()
                val folder = resolveOutputDirectory()
                val file = createUniqueFile(folder, baseName, extension)
                try {
                    fill(file)
                } catch (e: Exception) {
                    deleteUnfinished(file, e)
                    throw e
                }
                val absolute = file.toAbsolutePath().toString()
                CameraKResult.Success(GalleryAsset(file.fileName.toString(), absolute))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Saving to the gallery folder failed", e)
                CameraKResult.Failure(
                    CameraKError.Storage(
                        kind = if (e is NoSuchFileException) StorageFailure.NOT_FOUND else StorageFailure.NOT_WRITABLE,
                        cause = e,
                    ),
                )
            }
        }

    // The folder name is one validated name, and the folder it names must be that folder of the
    // destination's directory, not a symbolic link to any other.
    private fun resolveOutputDirectory(): File {
        val base = destinationFolder(config.destination)
        val name = config.folderName?.let { requireSafeFileName(it, "folderName") } ?: OUTPUT_FOLDER
        val folder = File(base, name)
        if (!isOwnFolder(folder, base)) {
            throw SaveFailure("The output folder is a link or resolves outside the configured directory")
        }
        return folder
    }

    private fun destinationFolder(destination: GalleryDestination): File = when (destination) {
        GalleryDestination.PICTURES, GalleryDestination.DCIM -> File(userHome, "Pictures")
        GalleryDestination.DOCUMENTS -> File(userHome, "Documents")
    }

    // The library's own output folders: this store's, and the CameraK folders that CameraK's desktop
    // captures write to. A folder that is a symbolic link, even to its own directory or a folder
    // beside it, is not one of them.
    private fun readRoots(): List<Path> {
        val captureFolders = listOf(GalleryDestination.PICTURES, GalleryDestination.DOCUMENTS)
            .map { destinationFolder(it) }
            .map { base -> File(base, OUTPUT_FOLDER) to base }
        val ownFolder = try {
            listOf(resolveOutputDirectory() to destinationFolder(config.destination))
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "The saver's output folder is not readable", e)
            emptyList()
        }
        return (captureFolders + ownFolder)
            .filter { (folder, base) -> isOwnFolder(folder, base) }
            .map { (folder, _) -> folder.canonicalFile.toPath() }
    }

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            CameraKResult.Success(readOwn(asset.path))
        } catch (e: IOException) {
            CameraKLogger.e(TAG, "Reading a gallery image failed", e)
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_FOUND, cause = e))
        }
    }

    override fun legacyLocation(asset: GalleryAsset): String = asset.path

    override fun readLegacyBlocking(location: String): ByteArray = readOwn(location)

    override suspend fun readLegacy(location: String): ByteArray = withContext(Dispatchers.IO) { readOwn(location) }

    /**
     * Reads an image this store or a CameraK capture wrote. [path] must resolve, after `..` and
     * symbolic links, to a file in one of [readRoots]; any other path, or a file that cannot be read,
     * throws an [IOException] whose message has no path (its cause may).
     */
    private fun readOwn(path: String): ByteArray {
        val (file, roots) = try {
            File(path).canonicalFile.toPath() to readRoots()
        } catch (e: IOException) {
            throw IOException("Failed to resolve the image's path", e)
        }
        if (roots.none { file.startsWith(it) }) {
            throw IOException("The saver reads only images in the library's own output folders")
        }
        return try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            throw IOException("Failed to read the image", e)
        }
    }
}

// Whether [folder], a name in [base], resolves to exactly that name in [base] once symbolic links are
// followed: false when the folder is itself a link, wherever it points. [base] may be a link.
private fun isOwnFolder(folder: File, base: File): Boolean =
    folder.canonicalFile.toPath() == base.canonicalFile.toPath().resolve(folder.name)

// A failure the store describes itself, in a message with no path or user data.
internal class SaveFailure(message: String) : IOException(message)

/** The format [bytes] are encoded in, from their magic bytes, or null if neither JPEG nor PNG. */
private fun sniffFormat(bytes: ByteArray): ImageFormat? = when {
    bytes.startsWith(JPEG_MAGIC) -> ImageFormat.JPEG
    bytes.startsWith(PNG_MAGIC) -> ImageFormat.PNG
    else -> null
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** Decodes [bytes] and encodes them once as [format], at an explicit quality for JPEG. */
private fun transcode(bytes: ByteArray, format: ImageFormat): ByteArray {
    val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw SaveFailure("Could not decode image bytes")
    val out = ByteArrayOutputStream()
    when (format) {
        ImageFormat.PNG -> if (!ImageIO.write(decoded, "png", out)) {
            throw SaveFailure("No PNG writer accepts this image")
        }
        ImageFormat.JPEG -> writeJpeg(withoutAlpha(decoded), out)
        ImageFormat.HEIC -> throw SaveFailure("HEIC is not supported on desktop")
    }
    return out.toByteArray()
}

// JPEG has no alpha channel, and ImageIO's JPEG writer rejects images that have one.
private fun withoutAlpha(image: BufferedImage): BufferedImage {
    if (!image.colorModel.hasAlpha()) return image
    val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics = opaque.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null)
    } finally {
        graphics.dispose()
    }
    return opaque
}

private fun writeJpeg(image: BufferedImage, out: ByteArrayOutputStream) {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
        ?: throw SaveFailure("No JPEG writer is installed")
    try {
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), params)
        }
    } finally {
        writer.dispose()
    }
}

/**
 * Creates a new, empty file `<name>.<extension>` in [folder] (creating the folder too), or
 * `<name>_<n>.<extension>` with the first free n if that name is taken. Never opens an existing file.
 */
private fun createUniqueFile(folder: File, name: String, extension: String): Path {
    val dir = Files.createDirectories(folder.toPath())
    for (attempt in 0 until MAX_NAME_ATTEMPTS) {
        val suffix = if (attempt == 0) "" else "_$attempt"
        try {
            return Files.createFile(dir.resolve("$name$suffix.$extension"))
        } catch (e: FileAlreadyExistsException) {
            // An earlier or concurrent save took this name; try the next suffix.
            CameraKLogger.w(TAG, "Gallery file name taken, trying the next one", e)
        }
    }
    throw SaveFailure("No free file name in $MAX_NAME_ATTEMPTS attempts")
}

private fun deleteUnfinished(file: Path, cause: Exception) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        CameraKLogger.e(TAG, "Failed to delete a partly written gallery file", e)
        cause.addSuppressed(e)
    }
}

private fun defaultImageName(): String =
    "image_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

// user.home can be null in sandboxed runtimes; fall back to the working dir.
private fun defaultUserHome(): File = File(System.getProperty("user.home") ?: System.getProperty("user.dir") ?: ".")

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

// The quality CameraK's own captures use, so a converted image matches a direct capture.
private const val JPEG_QUALITY = 0.95f
private const val MAX_NAME_ATTEMPTS = 100
private const val TAG = "ImageSaverPlugin"

// The folder the store writes to when no folder name is set; CameraK's desktop captures and the
// Android saver use the same name.
private const val OUTPUT_FOLDER = "CameraK"
