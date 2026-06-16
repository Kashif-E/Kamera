package com.kashif.imagesaverplugin
import com.kashif.cameraK.utils.CameraKLogger

import coil3.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
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
            val fileName = "${imageName ?: "image_${System.currentTimeMillis()}"}.jpg"
            val outputDirectory = File(config.directory.name)
            val outputFile = File(outputDirectory, fileName)
            val outputStream = FileOutputStream(outputFile)
            ImageIO.write(image, "jpg", outputStream)
            outputStream.close()
            outputFile.absolutePath
        } catch (e: Exception) {
            null
        }
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
