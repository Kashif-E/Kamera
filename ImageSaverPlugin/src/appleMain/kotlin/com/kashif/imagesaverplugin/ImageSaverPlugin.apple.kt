package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.fixOrientation
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
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
 * @param onImageSaved Called after Photos has stored an image, on a [Dispatchers.Default] thread,
 *   before [saveImage] returns. If it throws, the throwable is logged and the save still succeeds.
 * @param onImageSavedFailed Called with a description when an image could not be stored, on a
 *   [Dispatchers.Default] thread, before [saveImage] returns. If it throws, the throwable is logged.
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
            val assetId = try {
                val image = UIImage.imageWithData(byteArray.toNSData())
                if (image == null) {
                    CameraKLogger.e("CameraK", "Failed to convert NSData to UIImage.")
                    notifyFailed("Failed to create UIImage from NSData.")
                    return@withContext null
                }

                // Fix orientation before saving to ensure the image is displayed correctly
                createAsset(image.fixOrientation())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Failed to save image to Photos", e)
                notifyFailed(e.message ?: "Unknown exception")
                return@withContext null
            }
            CameraKLogger.d("CameraK", "Image saved to the Photos library")
            notifySaved()
            "ph://$assetId"
        }

    private fun notifySaved() {
        try {
            onImageSaved()
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "onImageSaved callback threw", e)
        }
    }

    private fun notifyFailed(message: String) {
        try {
            onImageSavedFailed(message)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "onImageSavedFailed callback threw", e)
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
     * Reads the image data of the Photos asset at [path] (`ph://<local identifier>`) with an
     * asynchronous Photos request. Suspends until Photos delivers the data; cancelling it cancels
     * the request.
     *
     * @throws IOException when there is no such asset or Photos delivers no data.
     */
    override suspend fun readBytes(path: String): ByteArray {
        val assetId = path.removePrefix("ph://")
        val options =
            PHImageRequestOptions().apply {
                synchronous = false
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                networkAccessAllowed = false
                version = PHImageRequestOptionsVersionCurrent
            }
        return withContext(Dispatchers.Default) {
            val data = suspendCancellableCoroutine { continuation ->
                val requestId = photoLibrary.requestImageData(assetId, options) { data ->
                    if (data != null) {
                        continuation.resume(data)
                    } else {
                        continuation.resumeWithException(IOException("Failed to get image data from asset: $path"))
                    }
                }
                if (requestId == null) {
                    continuation.resumeWithException(IOException("No asset found with identifier: $path"))
                } else {
                    continuation.invokeOnCancellation { photoLibrary.cancelImageRequest(requestId) }
                }
            }
            data.toByteArray()
        }
    }

    /**
     * Reads the image data of the Photos asset at [path] (`ph://<local identifier>`).
     *
     * The read is synchronous and blocks the calling thread until Photos returns the data.
     */
    @Deprecated(
        "Blocks the calling thread on a synchronous Photos request. Use readBytes, which suspends.",
        ReplaceWith("readBytes(path)"),
    )
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
        @Suppress("DEPRECATION")
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
        CameraKLogger.e("CameraK", "Failed to resolve asset to file", e)
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
