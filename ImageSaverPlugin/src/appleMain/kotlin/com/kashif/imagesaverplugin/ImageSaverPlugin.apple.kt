package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.fixOrientation
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import io.ktor.utils.io.errors.IOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.UIKit.UIImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS-specific implementation of [ImageSaverPlugin].
 *
 * @param config The configuration settings for the plugin.
 * @param onImageSaved Callback invoked when the image is successfully saved.
 * @param onImageSavedFailed Callback invoked when the image saving fails.
 */
class IOSImageSaverPlugin internal constructor(
    config: ImageSaverConfig,
    private val onImageSaved: () -> Unit,
    private val onImageSavedFailed: (String) -> Unit,
    private val photoLibrary: PhotoLibraryGateway,
) : ImageSaverPlugin(config) {
    constructor(
        config: ImageSaverConfig,
        onImageSaved: () -> Unit,
        onImageSavedFailed: (String) -> Unit,
    ) : this(config, onImageSaved, onImageSavedFailed, SystemPhotoLibrary)

    /**
     * Decodes [byteArray] and writes it to the Photos library as a new asset, returning its
     * `ph://` location. Runs on [Dispatchers.Default] and suspends while Photos writes, so it never
     * occupies the main thread; cancelling it stops the wait but not a write already handed to Photos.
     */
    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? =
        withContext(Dispatchers.Default) {
            try {
                val image = UIImage.imageWithData(byteArray.toNSData())
                if (image == null) {
                    CameraKLogger.e("CameraK", "Failed to convert NSData to UIImage.")
                    onImageSavedFailed("Failed to create UIImage from NSData.")
                    return@withContext null
                }

                // Fix orientation before saving to ensure the image is displayed correctly
                val assetId = createAsset(image.fixOrientation())
                CameraKLogger.d("CameraK", "Image successfully saved to Photos album with ID: $assetId")
                onImageSaved()
                "ph://$assetId"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Failed to save image to Photos", e)
                onImageSavedFailed(e.message ?: "Unknown exception")
                null
            }
        }

    private suspend fun createAsset(image: UIImage): String = suspendCancellableCoroutine { continuation ->
        photoLibrary.createImageAsset(image) { localIdentifier, error ->
            if (localIdentifier != null) {
                continuation.resume(localIdentifier)
            } else {
                continuation.resumeWithException(IOException(error?.localizedDescription ?: "Unknown error"))
            }
        }
    }

    /**
     * Reads the image data of the Photos asset at [path] (`ph://<local identifier>`).
     *
     * The read is synchronous and blocks the calling thread until Photos returns the data, so call
     * it off the main thread.
     */
    override fun getByteArrayFrom(path: String): ByteArray {
        val assetId = path.removePrefix("ph://")
        val options =
            PHImageRequestOptions().apply {
                synchronous = true
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                networkAccessAllowed = false
                version = PHImageRequestOptionsVersionCurrent
            }

        // A synchronous request runs the handler on this thread before it returns, so the data is
        // already here when requestImageData does.
        var imageData: NSData? = null
        photoLibrary.requestImageData(assetId, options) { data -> imageData = data }
            ?: throw IOException("No asset found with identifier: $path")

        return imageData?.toByteArray() ?: throw IOException("Failed to get image data from asset: $path")
    }

    /**
     * Resolve a Photos asset identifier to a temporary file path on disk.
     * Returns null if the asset cannot be found or written.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun getFilePathFromAsset(path: String): String? = try {
        val data = getByteArrayFrom(path)
        val tempDir = platform.Foundation.NSTemporaryDirectory()
        val fileName = "IMG_${platform.Foundation.NSUUID().UUIDString}.jpg"
        val fullPath = "$tempDir$fileName"
        val success =
            platform.Foundation.NSFileManager.defaultManager.createFileAtPath(
                fullPath,
                data.toNSData(),
                null,
            )
        if (success) fullPath else null
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "Failed to resolve asset to file: ${e.message}")
        null
    }
}

//    /**
//     * Helper class to handle UIImageWriteToSavedPhotosAlbum callbacks.
//     */
//    private class SaveCallback(
//        private val onSuccess: () -> Unit,
//        private val onError: (String) -> Unit
//    ) : NSObject() {
//
//        @OptIn(ExperimentalForeignApi::class)
//        @ObjCAction
//        fun image_didFinishSavingWithError_contextInfo(
//            image: UIImage,
//            error: NSError?,
//            contextInfo: COpaquePointer?
//        ) {
//            if (error == null) {
//                onSuccess()
//            } else {
//                onError(error.localizedDescription ?: "Unknown error")
//            }
//        }
//    }

/**
 * Factory function to create an iOS-specific [ImageSaverPlugin].
 *
 * @param config Configuration settings for the plugin.
 * @return An instance of [IOSImageSaverPlugin].
 */

actual fun createPlatformImageSaverPlugin(context: PlatformContext, config: ImageSaverConfig): ImageSaverPlugin =
    IOSImageSaverPlugin(config = config, onImageSaved = {
        CameraKLogger.d("CameraK", "Image saved successfully!")
    }, onImageSavedFailed = { errorMessage ->

        CameraKLogger.e("CameraK", "Failed to save image: $errorMessage")
    })
