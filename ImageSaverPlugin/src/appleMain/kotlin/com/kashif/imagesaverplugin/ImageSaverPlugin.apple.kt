package com.kashif.imagesaverplugin
import coil3.PlatformContext
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.fixOrientation
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import io.ktor.utils.io.errors.IOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * iOS-specific implementation of [ImageSaverPlugin].
 *
 * @param config The configuration settings for the plugin.
 * @param onImageSaved Callback invoked when the image is successfully saved.
 * @param onImageSavedFailed Callback invoked when the image saving fails.
 */
class IOSImageSaverPlugin(
    config: ImageSaverConfig,
    private val onImageSaved: () -> Unit,
    private val onImageSavedFailed: (String) -> Unit,
) : ImageSaverPlugin(config) {
    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? {
        return withContext(Dispatchers.Main) {
            try {
                val nsData = byteArray.toNSData()

                if (nsData == null) {
                    CameraKLogger.e("CameraK", "Failed to convert ByteArray to NSData.")
                    onImageSavedFailed("Failed to convert ByteArray to NSData.")
                    return@withContext null
                }

                val image = UIImage.imageWithData(nsData)

                if (image == null) {
                    CameraKLogger.e("CameraK", "Failed to convert NSData to UIImage.")
                    onImageSavedFailed("Failed to create UIImage from NSData.")
                    return@withContext null
                }

                // Fix orientation before saving to ensure the image is displayed correctly
                val orientedImage = image.fixOrientation()

                saveToPhotoLibrary(orientedImage)?.let { "ph://$it" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Exception while saving image: ${e.message}")
                onImageSavedFailed(e.message ?: "Unknown exception")
                null
            }
        }
    }

    /**
     * Adds the image to the Photos library and returns its local identifier, or null on failure.
     * Suspends until Photos reports back instead of blocking a thread on the result.
     */
    private suspend fun saveToPhotoLibrary(image: UIImage): String? = suspendCancellableCoroutine { continuation ->
        var assetId: String? = null

        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            val request = PHAssetChangeRequest.creationRequestForAssetFromImage(image)
            assetId = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, error ->
            val savedId = assetId
            if (success && savedId != null) {
                CameraKLogger.d("CameraK", "Image successfully saved to Photos album with ID: $savedId")
                onImageSaved()
                continuation.resume(savedId)
            } else {
                CameraKLogger.e("CameraK", "Failed to save image: ${error?.localizedDescription}")
                onImageSavedFailed(error?.localizedDescription ?: "Unknown error")
                continuation.resume(null)
            }
        }
    }

    override fun getByteArrayFrom(path: String): ByteArray {
        // takePictureToFile() returns a plain file path for Directory.DOCUMENTS, not a ph:// asset id.
        if (!path.startsWith("ph://") && NSFileManager.defaultManager.fileExistsAtPath(path)) {
            val fileData = NSFileManager.defaultManager.contentsAtPath(path)
                ?: throw IOException("Failed to read image file: $path")
            return fileData.toByteArray()
        }

        val assetId = path.removePrefix("ph://")
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), null)
        if (fetchResult.count.toInt() == 0) {
            throw IOException("No asset found with identifier: $path")
        }

        val asset = fetchResult.firstObject as PHAsset
        val options =
            PHImageRequestOptions().apply {
                synchronous = true
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                networkAccessAllowed = false
                version = PHImageRequestOptionsVersionCurrent
            }

        var imageData: NSData? = null

        // The request is synchronous, so the handler has already run by the time this call returns.
        PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
            asset,
            options,
        ) { data, _, _, _ ->
            imageData = data
        }

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
