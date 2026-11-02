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
 * @param config The configuration settings for the plugin.
 * @param onImageSaved Callback invoked when the image is successfully saved.
 * @param onImageSavedFailed Callback invoked when the image saving fails.
 */
class JVMImageSaverPlugin(
    config: ImageSaverConfig,
    private val onImageSaved: () -> Unit,
    private val onImageSavedFailed: (String) -> Unit,
) : ImageSaverPlugin(config) {
    // The folder the configured Directory resolves under: the user's home.
    private var userHome: File = defaultUserHome()

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
        try {
            val format = config.imageFormat
            val bytes = if (sniffFormat(byteArray) == format) byteArray else transcode(byteArray, format)
            val file = createUniqueFile(resolveOutputDirectory(), imageName ?: defaultImageName(), format.extension)
            try {
                Files.write(file, bytes)
            } catch (e: IOException) {
                deleteUnfinished(file, e)
                throw e
            }
            onImageSaved()
            ImageSaveResult.Saved(file.toAbsolutePath().toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Failed to save image", e)
            onImageSavedFailed(e.message ?: "Unknown error")
            ImageSaveResult.Failed(e)
        }
    }

    // Resolve a real per-user directory (the old code wrote to a relative dir literally named
    // "PICTURES" in the process CWD and ignored customFolderName/imageFormat).
    private fun resolveOutputDirectory(): File {
        val base = when (config.directory) {
            Directory.PICTURES, Directory.DCIM -> File(userHome, "Pictures")
            Directory.DOCUMENTS -> File(userHome, "Documents")
        }
        return config.customFolderName?.let { File(base, it) } ?: base
    }

    @Deprecated(
        "Blocks the calling thread while it reads. Use readBytes, which suspends.",
        ReplaceWith("readBytes(path)"),
    )
    override fun getByteArrayFrom(path: String): ByteArray = try {
        File(path).readBytes()
    } catch (e: Exception) {
        throw IOException("Failed to read image from path: $path", e)
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
    val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Could not decode image bytes")
    val out = ByteArrayOutputStream()
    when (format) {
        ImageFormat.PNG -> if (!ImageIO.write(
                decoded,
                "png",
                out,
            )
        ) {
            throw IOException("No PNG writer accepts this image")
        }
        ImageFormat.JPEG -> writeJpeg(withoutAlpha(decoded), out)
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
        ?: throw IOException("No JPEG writer is installed")
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
    throw IOException("No free file name in $MAX_NAME_ATTEMPTS attempts")
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
