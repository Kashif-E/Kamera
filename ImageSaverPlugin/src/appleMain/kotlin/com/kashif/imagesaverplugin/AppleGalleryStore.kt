package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.capture.localIdentifier
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
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
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.CGImageSourceGetType
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Photos library. Every destination imports into it and folders are ignored. An import copies
 * the app-owned file into one `performChanges`, off the calling thread; a failed or unfinished
 * import is `Storage(GALLERY_IMPORT_FAILED)` naming the file it kept, which it never touches. Photos
 * holds an import while its add-only prompt is up, so the wait is bounded by [importTimeout].
 *
 * A read streams the asset's photo resource with `PHAssetResourceManager` into a file in
 * `Library/Caches/CameraK`, reads it and deletes it.
 */
@OptIn(ExperimentalForeignApi::class, InternalCameraKApi::class, CameraKPlatformApi::class)
internal class AppleGalleryStore(
    private val photos: PhotoLibraryGateway = SystemPhotoLibrary,
    private val importTimeout: Duration = IMPORT_TIMEOUT,
) : GalleryStore {
    override val galleryPermission: CameraKPermission get() = TODO("IS': the gallery permission")

    // performChanges itself raises the add-only prompt, so a save at NotDetermined goes ahead.
    override val addPrompts: Boolean get() = true

    override suspend fun addImage(
        source: MediaSource,
        format: ImageFormat,
        name: String?,
    ): CameraKResult<GalleryAsset> = when (source) {
        is MediaSource.File -> import(source.path, MediaKind.IMAGE, name)
        is MediaSource.Bytes -> addBytes(source.bytes)
    }

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> =
        import(path, MediaKind.VIDEO, name)

    private suspend fun import(path: String, kind: MediaKind, name: String?): CameraKResult<GalleryAsset> =
        TODO("IS\': save(image) and save(video)")

    // The deprecated byte save: the bytes are stored byte for byte, so EXIF stays and nothing is decoded.
    private suspend fun addBytes(bytes: ByteArray): CameraKResult<GalleryAsset> = withContext(Dispatchers.Default) {
        try {
            val data = bytes.toNSData()
            val type = imageTypeOf(data) ?: throw IOException("The data is not an image")
            val localIdentifier = suspendCancellableCoroutine { continuation ->
                photos.createPhotoAsset(data, type) { localIdentifier, error ->
                    if (localIdentifier != null) {
                        continuation.resume(localIdentifier)
                    } else {
                        continuation.resumeWithException(
                            error?.let(::PhotosException) ?: IOException("Photos stored nothing"),
                        )
                    }
                }
            }
            CameraKLogger.d(TAG, "Image saved to the Photos library")
            CameraKResult.Success(GalleryAsset("image", localIdentifier))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Failed to save image to Photos", e)
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.GALLERY_IMPORT_FAILED, cause = e))
        }
    }

    /** The uniform type of the image encoded in [data], read from its header without decoding it; null when it is not one. */
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

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = TODO("IS\': readBytes(saved)")

    override fun legacyLocation(asset: GalleryAsset): String = "ph://${asset.localIdentifier}"

    override fun readLegacyBlocking(location: String): ByteArray {
        val options = PHImageRequestOptions().apply {
            synchronous = true
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            networkAccessAllowed = false
            version = PHImageRequestOptionsVersionCurrent
        }
        // A synchronous request runs the handler on this thread before it returns, so the data is
        // already here when requestImageData does.
        var imageData: NSData? = null
        photos.requestImageData(location.removePrefix(PH_SCHEME), options) { data, _ -> imageData = data }
            ?: throw IOException("There is no such asset in Photos")
        return (imageData ?: throw IOException("Photos delivered no data for the asset")).toByteArray()
    }

    override suspend fun readLegacy(location: String): ByteArray = withContext(Dispatchers.Default) {
        val options = PHImageRequestOptions().apply {
            synchronous = false
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            networkAccessAllowed = false
            version = PHImageRequestOptionsVersionCurrent
        }
        val data = suspendCancellableCoroutine { continuation ->
            val requestId = photos.requestImageData(location.removePrefix(PH_SCHEME), options) { data, _ ->
                if (data != null) {
                    continuation.resume(data)
                } else {
                    continuation.resumeWithException(IOException("Photos delivered no data for the asset"))
                }
            }
            if (requestId == null) {
                continuation.resumeWithException(IOException("There is no such asset in Photos"))
            } else {
                continuation.invokeOnCancellation { photos.cancelImageRequest(requestId) }
            }
        }
        data.toByteArray()
    }

    // A capture's own file, at a plain path or a file:// URL.
    private fun readFile(location: String): ByteArray {
        val path = fileUrlOf(location).path ?: throw IOException("The location is not a file")
        val data =
            NSFileManager.defaultManager.contentsAtPath(path) ?: throw IOException("Failed to read the image file")
        return data.toByteArray()
    }

    /** `Library/Caches/CameraK`, created when missing. */
    private fun cameraKCachesDirectory(): String {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()
            as? String ?: throw IOException("The app has no Caches directory")
        val directory = "$caches/CameraK"
        if (!NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
            throw IOException("Creating the CameraK caches directory failed")
        }
        return directory
    }
}

/** A file URL for [path], which may be a `file://` URL or a plain file-system path. */
internal fun fileUrlOf(path: String): NSURL = if (path.startsWith(
        "file:",
    )
) {
    NSURL.URLWithString(path) ?: NSURL.fileURLWithPath(path)
} else {
    NSURL.fileURLWithPath(path)
}

/** What Photos reported for a refused write or read: its domain and code, never a path. */
internal class PhotosException(val error: NSError) :
    IOException("Photos refused the request (${error.domain} ${error.code})")

/** Photos did not finish an import within [timeout], as while its add-only prompt is unanswered. */
internal class PhotosTimeout(timeout: Duration) : IOException("Photos did not finish the import within $timeout")

private const val TAG = "ImageSaverPlugin"
private const val PH_SCHEME = "ph://"

// Photos holds an import while its add-only prompt is up; this leaves the user time to answer it.
private val IMPORT_TIMEOUT = 60.seconds

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
