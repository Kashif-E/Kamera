package com.kashif.imagesaverplugin

import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestID
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHPhotoLibrary

/** The Photos library calls [IOSImageSaverPlugin] makes, so tests can run without photo-library access. */
internal interface PhotoLibraryGateway {
    /**
     * Stores [data], an encoded image of type [uniformTypeIdentifier], in the library as a new
     * asset's photo resource, byte for byte: no decode, no re-encode, metadata kept. [completion]
     * runs once, on a queue Photos chooses, with the new asset's local identifier (null when the
     * write failed) and the error.
     */
    fun createPhotoAsset(
        data: NSData,
        uniformTypeIdentifier: String,
        completion: (localIdentifier: String?, error: NSError?) -> Unit,
    )

    /**
     * Requests the image data of the asset with [localIdentifier], returning the request's ID, or
     * null when there is no such asset. [resultHandler] follows PHImageManager's contract for
     * [options]: with `synchronous = true` it runs once on the calling thread before this function
     * returns; otherwise it runs once, later, on the main thread, or not at all after
     * [cancelImageRequest].
     */
    fun requestImageData(
        localIdentifier: String,
        options: PHImageRequestOptions,
        resultHandler: (NSData?) -> Unit,
    ): PHImageRequestID?

    /** Cancels an asynchronous request started by [requestImageData]. Synchronous requests cannot be cancelled. */
    fun cancelImageRequest(requestId: PHImageRequestID)
}

internal object SystemPhotoLibrary : PhotoLibraryGateway {
    override fun createPhotoAsset(
        data: NSData,
        uniformTypeIdentifier: String,
        completion: (localIdentifier: String?, error: NSError?) -> Unit,
    ) {
        var localIdentifier: String? = null
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            val request = PHAssetCreationRequest.creationRequestForAsset()
            val options = PHAssetResourceCreationOptions().apply { this.uniformTypeIdentifier = uniformTypeIdentifier }
            request.addResourceWithType(PHAssetResourceTypePhoto, data, options)
            localIdentifier = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, error ->
            completion(if (success) localIdentifier else null, error)
        }
    }

    override fun requestImageData(
        localIdentifier: String,
        options: PHImageRequestOptions,
        resultHandler: (NSData?) -> Unit,
    ): PHImageRequestID? {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
        if (fetchResult.count.toInt() == 0) return null
        val asset = fetchResult.firstObject as PHAsset
        return PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(asset, options) { data, _, _, _ ->
            resultHandler(data)
        }
    }

    override fun cancelImageRequest(requestId: PHImageRequestID) {
        PHImageManager.defaultManager().cancelImageRequest(requestId)
    }
}
