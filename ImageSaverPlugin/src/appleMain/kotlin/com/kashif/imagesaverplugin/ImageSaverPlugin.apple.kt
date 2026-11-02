package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.CGImageSourceGetType
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
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
     * Stores [byteArray], an encoded image, in the Photos library as a new asset byte for byte, so
     * its EXIF (orientation, location, capture settings) stays as it was and nothing is decoded or
     * re-encoded, and returns its `ph://` location. Runs on [Dispatchers.Default] and suspends while
     * Photos writes, so it never occupies the main thread; cancelling it stops the wait but not a
     * write already handed to Photos.
     */
    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? =
        withContext(Dispatchers.Default) {
            val assetId = try {
                val data = byteArray.toNSData()
                val type = imageTypeOf(data)
                if (type == null) {
                    CameraKLogger.e("CameraK", "The data to save is not an image.")
                    notifyFailed("The data is not an image.")
                    return@withContext null
                }
                createAsset(data, type)
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

    /** The uniform type of the image encoded in [data], read from its header without decoding it; null when it is not one. */
    @OptIn(ExperimentalForeignApi::class)
    private fun imageTypeOf(data: NSData): String? {
        val cfData = CFBridgingRetain(data) as CFDataRef
        val source = CGImageSourceCreateWithData(cfData, null)
        try {
            if (source == null || CGImageSourceGetCount(source) == 0uL) return null
            return CGImageSourceGetType(source)?.let { CFBridgingRelease(CFRetain(it)) as String }
        } finally {
            source?.let(::CFRelease)
            CFRelease(cfData)
        }
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

    private suspend fun createAsset(data: NSData, type: String): String = suspendCancellableCoroutine { continuation ->
        photoLibrary.createPhotoAsset(data, type) { localIdentifier, error ->
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
                val requestId = photoLibrary.requestImageData(assetId, options) { data, _ ->
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
        photoLibrary.requestImageData(assetId, options) { data, _ -> imageData = data }
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
