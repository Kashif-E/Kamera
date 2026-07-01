package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * jvm-specific implementation of [ImageSaverPlugin].
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
    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? = withContext(Dispatchers.IO) {
        try {
            val image = ImageIO.read(ByteArrayInputStream(byteArray))
                ?: throw IOException("Could not decode image bytes")

            val ext = if (config.imageFormat == ImageFormat.PNG) "png" else "jpg"
            val fileName = "${imageName ?: "image_${System.currentTimeMillis()}"}.$ext"

            val outputDirectory = resolveOutputDirectory().also { it.mkdirs() }
            val outputFile = File(outputDirectory, fileName)

            outputFile.outputStream().use { stream ->
                if (!ImageIO.write(image, ext, stream)) {
                    throw IOException("No image writer available for format: $ext")
                }
            }
            onImageSaved()
            outputFile.absolutePath
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Failed to save image: ${e.message}", e)
            onImageSavedFailed(e.message ?: "Unknown error")
            null
        }
    }

    // Resolve a real per-user directory (the old code wrote to a relative dir literally named
    // "PICTURES" in the process CWD and ignored customFolderName/imageFormat).
    private fun resolveOutputDirectory(): File {
        // user.home can be null in sandboxed runtimes; fall back to the working dir.
        val home = File(System.getProperty("user.home") ?: System.getProperty("user.dir") ?: ".")
        val base = when (config.directory) {
            Directory.PICTURES, Directory.DCIM -> File(home, "Pictures")
            Directory.DOCUMENTS -> File(home, "Documents")
        }
        return config.customFolderName?.let { File(base, it) } ?: base
    }

    override fun getByteArrayFrom(path: String): ByteArray = try {
        File(path).readBytes()
    } catch (e: Exception) {
        throw IOException("Failed to read image from path: $path", e)
    }
}

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
