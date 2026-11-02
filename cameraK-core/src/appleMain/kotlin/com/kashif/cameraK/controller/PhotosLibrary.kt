package com.kashif.cameraK.controller

import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary

/**
 * The Photos library imports CameraController makes. Production calls PhotoKit; tests substitute one
 * that records each import and answers as they need, since the simulator's Photos library asks for a
 * permission no test can grant.
 */
internal interface PhotosLibrary {
    /**
     * Imports the image file at [fileUrl] as a new asset. [completion] runs once, on a queue Photos
     * chooses, with the new asset's local identifier, or with null and the error when the import
     * failed. Photos may never call it, for example while its permission prompt is unanswered.
     */
    fun importImage(fileUrl: NSURL, completion: (localIdentifier: String?, error: NSError?) -> Unit)

    /** Imports the video file at [fileUrl] as a new asset; [completion] follows [importImage]'s contract. */
    fun importVideo(fileUrl: NSURL, completion: (localIdentifier: String?, error: NSError?) -> Unit)
}

internal object SystemPhotosLibrary : PhotosLibrary {
    override fun importImage(fileUrl: NSURL, completion: (String?, NSError?) -> Unit) =
        import(completion) { PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileUrl) }

    override fun importVideo(fileUrl: NSURL, completion: (String?, NSError?) -> Unit) =
        import(completion) { PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl) }

    private fun import(completion: (String?, NSError?) -> Unit, request: () -> PHAssetChangeRequest?) {
        var localIdentifier: String? = null
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = { localIdentifier = request()?.placeholderForCreatedAsset?.localIdentifier },
            completionHandler = { success, error ->
                completion(localIdentifier.takeIf { success && error == null }, error)
            },
        )
    }
}
