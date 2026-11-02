package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
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
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * jvm-specific implementation of [ImageSaverPlugin].
 *
 * Bytes already in the configured [ImageSaverConfig.imageFormat] are written as they are, so a
 * camera JPEG keeps its quality and its EXIF; other bytes are decoded and encoded once into that
 * format. Each save creates a new file and never overwrites one: a name that is taken gets a
 * numeric suffix.
 *
 * Images go to `<directory>/<customFolderName or CameraK>` under the user's home, where
 * [ImageSaverConfig.directory] PICTURES and DCIM mean `Pictures` and DOCUMENTS means `Documents`.
 *
 * `imageName` and [ImageSaverConfig.customFolderName] must each be 1 to 64 characters from
 * `A-Z a-z 0-9 . _ -` and not `.` or `..`; any other name fails the save with an
 * [IllegalArgumentException], so a forwarded name can never place a file outside the folder. An
 * auto-save names its file `<prefix>_<epochMillis>`, which adds 14 characters, so
 * [ImageSaverConfig.prefix] may be at most 50 of those characters; the constructor throws an
 * [IllegalArgumentException] for any other prefix.
 *
 * [getByteArrayFrom] and [readBytes] read only the library's own output: this saver's folder and
 * the `CameraK` folders under `Pictures` and `Documents`, where CameraK's desktop captures go. Any
 * other file, including the user's own files elsewhere in `Pictures` or `Documents`, is refused. A
 * `CameraK` or custom folder that is itself a symbolic link is neither read nor written, wherever it
 * points.
 *
 * @param config The configuration settings for the plugin.
 * @param onImageSaved Callback invoked when the image is successfully saved, on a `Dispatchers.IO`
 *   thread. If it throws, the throwable is logged and the save is still reported as saved.
 * @param onImageSavedFailed Callback invoked when the image saving fails, on a `Dispatchers.IO`
 *   thread, with a reason that names the operation but no path. If it throws, the throwable is
 *   logged and save() still returns [ImageSaveResult.Failed].
 */
class JVMImageSaverPlugin(
    config: ImageSaverConfig,
    private val onImageSaved: () -> Unit,
    private val onImageSavedFailed: (String) -> Unit,
) : ImageSaverPlugin(config) {
    // The folder the configured Directory resolves under: the user's home.
    private var userHome: File = defaultUserHome()

    init {
        config.prefix?.let(::requireAutoSavePrefix)
    }

    internal constructor(
        config: ImageSaverConfig,
        onImageSaved: () -> Unit,
        onImageSavedFailed: (String) -> Unit,
        userHome: File,
    ) : this(config, onImageSaved, onImageSavedFailed) {
        this.userHome = userHome
    }

    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? =
        (save(byteArray, imageName) as? ImageSaveResult.Saved)?.location

    override suspend fun save(byteArray: ByteArray, imageName: String?): ImageSaveResult = withContext(Dispatchers.IO) {
        val result = write(byteArray, imageName)
        // The consumer's callbacks run after the outcome is fixed, so one that throws cannot change it.
        when (result) {
            is ImageSaveResult.Saved -> notifyConsumer("onImageSaved") { onImageSaved() }
            is ImageSaveResult.Failed -> notifyConsumer("onImageSavedFailed") {
                onImageSavedFailed(failureReason(result.error))
            }
        }
        result
    }

    private fun write(byteArray: ByteArray, imageName: String?): ImageSaveResult = try {
        val name = imageName?.let { requireSafeFileName(it, "imageName") } ?: defaultImageName()
        val folder = resolveOutputDirectory()
        val format = config.imageFormat
        val bytes = if (sniffFormat(byteArray) == format) byteArray else transcode(byteArray, format)
        val file = createUniqueFile(folder, name, format.extension)
        try {
            Files.write(file, bytes)
        } catch (e: IOException) {
            deleteUnfinished(file, e)
            throw e
        }
        ImageSaveResult.Saved(file.toAbsolutePath().toString())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "Failed to save image", e)
        ImageSaveResult.Failed(e)
    }

    // customFolderName is one validated name, and the folder it names must be that folder of the
    // configured directory, not a symbolic link to any other.
    private fun resolveOutputDirectory(): File {
        val base = directoryFolder(config.directory)
        val name = config.customFolderName?.let { requireSafeFileName(it, "customFolderName") } ?: OUTPUT_FOLDER
        val folder = File(base, name)
        if (!isOwnFolder(folder, base)) {
            throw SaveFailure("The output folder is a link or resolves outside the configured directory")
        }
        return folder
    }

    private fun directoryFolder(directory: Directory): File = when (directory) {
        Directory.PICTURES, Directory.DCIM -> File(userHome, "Pictures")
        Directory.DOCUMENTS -> File(userHome, "Documents")
    }

    // The library's own output folders: this saver's, and the CameraK folders that CameraK's desktop
    // captures write to. A folder that is a symbolic link, even to its own directory or a folder
    // beside it, is not one of them.
    private fun readRoots(): List<Path> {
        val captureFolders = listOf(Directory.PICTURES, Directory.DOCUMENTS)
            .map { directoryFolder(it) }
            .map { base -> File(base, OUTPUT_FOLDER) to base }
        val ownFolder = try {
            listOf(resolveOutputDirectory() to directoryFolder(config.directory))
        } catch (e: Exception) {
            CameraKLogger.w("CameraK", "The image saver's output folder is not readable", e)
            emptyList()
        }
        return (captureFolders + ownFolder)
            .filter { (folder, base) -> isOwnFolder(folder, base) }
            .map { (folder, _) -> folder.canonicalFile.toPath() }
    }

    /**
     * Reads an image this saver or a CameraK capture wrote. [path] must resolve, after `..` and
     * symbolic links, to a file in this saver's folder or in `Pictures/CameraK` or
     * `Documents/CameraK`; any other path, or a file that cannot be read, throws an [IOException]
     * whose message has no path (its cause may).
     */
    @Deprecated(
        "Blocks the calling thread while it reads. Use readBytes, which suspends.",
        ReplaceWith("readBytes(path)"),
    )
    override fun getByteArrayFrom(path: String): ByteArray {
        val (file, roots) = try {
            File(path).canonicalFile.toPath() to readRoots()
        } catch (e: IOException) {
            throw IOException("Failed to resolve the image's path", e)
        }
        if (roots.none { file.startsWith(it) }) {
            throw IOException("getByteArrayFrom reads only images in the library's own output folders")
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

// A failure the saver describes itself, in a message with no path or user data.
private class SaveFailure(message: String) : IOException(message)

/**
 * What onImageSavedFailed is told: the saver's own message, which has no path or user data, or for
 * a platform exception, whose message may hold a path, only its type.
 */
private fun failureReason(error: Throwable): String = when (error) {
    is SaveFailure, is IllegalArgumentException -> error.message ?: "Image could not be saved"
    else -> "Image could not be saved (${error::class.simpleName})"
}

private inline fun notifyConsumer(callback: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "The image saver's $callback callback failed", e)
    }
}

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
        ImageFormat.PNG -> if (!ImageIO.write(
                decoded,
                "png",
                out,
            )
        ) {
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
            CameraKLogger.w("CameraK", "Image file name taken, trying the next one", e)
        }
    }
    throw SaveFailure("No free file name in $MAX_NAME_ATTEMPTS attempts")
}

private fun deleteUnfinished(file: Path, cause: Exception) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        CameraKLogger.e("CameraK", "Failed to delete a partly written image", e)
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

// The folder the saver writes to when no customFolderName is set; CameraK's desktop captures and
// the Android saver use the same name.
private const val OUTPUT_FOLDER = "CameraK"

/**
 * Factory function to create an jvm-specific [ImageSaverPlugin].
 *
 * @param config Configuration settings for the plugin.
 * @return An instance of [JVMImageSaverPlugin].
 */

actual fun createPlatformImageSaverPlugin(context: PlatformContext, config: ImageSaverConfig): ImageSaverPlugin =
    JVMImageSaverPlugin(
        config = config,
        onImageSaved = { CameraKLogger.d("CameraK", "Image saved successfully!") },
        onImageSavedFailed = { errorMessage -> CameraKLogger.e("CameraK", "Failed to save image: $errorMessage") },
    )
