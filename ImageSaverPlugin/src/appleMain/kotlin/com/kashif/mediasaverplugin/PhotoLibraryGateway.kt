package com.kashif.mediasaverplugin

import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHPhotoLibrary

/** The Photos library calls the saver makes, so tests can run without photo-library access. */
internal interface PhotoLibraryGateway {
    /**
     * Adds the file at [fileUrl] to the library as a new asset's photo or video resource, copying it,
     * in one `performChanges`, which raises the add-only prompt when the app has not been asked yet.
     * [completion] runs once, on a queue Photos chooses, with the new asset's local identifier (null
     * when the import failed) and the error. The file itself is left as it was.
     */
    fun createAsset(fileUrl: NSURL, kind: MediaKind, completion: (localIdentifier: String?, error: NSError?) -> Unit)

    /**
     * Streams the photo resource of the asset with [localIdentifier] into the file at [fileUrl] with
     * `PHAssetResourceManager`, without loading it into memory or blocking the caller. Returns false,
     * calling nothing, when there is no such asset or resource; otherwise [completion] runs once, on
     * a queue Photos chooses, with the error, or null once the file is written.
     */
    fun writeAssetData(localIdentifier: String, fileUrl: NSURL, completion: (error: NSError?) -> Unit): Boolean
}

internal object SystemPhotoLibrary : PhotoLibraryGateway {
    override fun createAsset(
        fileUrl: NSURL,
        kind: MediaKind,
        completion: (localIdentifier: String?, error: NSError?) -> Unit,
    ) {
        var localIdentifier: String? = null
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            val request = PHAssetCreationRequest.creationRequestForAsset()
            val type = if (kind == MediaKind.IMAGE) PHAssetResourceTypePhoto else PHAssetResourceTypeVideo
            request.addResourceWithType(type, fileUrl, PHAssetResourceCreationOptions())
            localIdentifier = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, error ->
            completion(if (success) localIdentifier else null, error)
        }
    }

    override fun writeAssetData(
        localIdentifier: String,
        fileUrl: NSURL,
        completion: (error: NSError?) -> Unit,
    ): Boolean {
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null).firstObject as? PHAsset
            ?: return false
        val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        val resource = resources.firstOrNull { it.type == PHAssetResourceTypePhoto } ?: resources.firstOrNull()
            ?: return false
        val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = false }
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(resource, fileUrl, options) { error ->
            completion(error)
        }
        return true
    }
}
