package com.kashif.imagesaverplugin

import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage

/** The Photos library calls [IOSImageSaverPlugin] makes, so tests can run without photo-library access. */
internal interface PhotoLibraryGateway {
    /**
     * Writes [image] to the library as a new asset. [completion] runs once, on a queue Photos
     * chooses, with the new asset's local identifier (null when the write failed) and the error.
     */
    fun createImageAsset(image: UIImage, completion: (localIdentifier: String?, error: NSError?) -> Unit)

    /**
     * Requests the image data of the asset with [localIdentifier], returning false when there is
     * no such asset. [resultHandler] follows PHImageManager's contract for [options]: with
     * `synchronous = true` it runs on the calling thread before this function returns.
     */
    fun requestImageData(
        localIdentifier: String,
        options: PHImageRequestOptions,
        resultHandler: (NSData?) -> Unit,
    ): Boolean
}

internal object SystemPhotoLibrary : PhotoLibraryGateway {
    override fun createImageAsset(image: UIImage, completion: (localIdentifier: String?, error: NSError?) -> Unit) {
        var localIdentifier: String? = null
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            val request = PHAssetChangeRequest.creationRequestForAssetFromImage(image)
            localIdentifier = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, error ->
            completion(if (success) localIdentifier else null, error)
        }
    }

    override fun requestImageData(
        localIdentifier: String,
        options: PHImageRequestOptions,
        resultHandler: (NSData?) -> Unit,
    ): Boolean {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
        if (fetchResult.count.toInt() == 0) return false
        val asset = fetchResult.firstObject as PHAsset
        PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(asset, options) { data, _, _, _ ->
            resultHandler(data)
        }
        return true
    }
}
