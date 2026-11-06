package com.kashif.mediasaverplugin

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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
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
 * `Library/Caches/CameraK`, reads it and deletes it. A read cancelled while Photos is still writing
 * deletes the file once Photos has finished, since the write itself cannot be cancelled.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, InternalCameraKApi::class, CameraKPlatformApi::class)
internal class AppleGalleryStore(
    private val photos: PhotoLibraryGateway = SystemPhotoLibrary,
    private val importTimeout: Duration = IMPORT_TIMEOUT,
) : GalleryStore {
    override val galleryPermission: CameraKPermission get() = CameraKPermission.GALLERY_ADD

    // performChanges itself raises the add-only prompt, so a save at NotDetermined goes ahead.
    override val addPrompts: Boolean get() = true

    override suspend fun addImage(path: String, format: ImageFormat, name: String?): CameraKResult<GalleryAsset> =
        import(path, MediaKind.IMAGE, name)

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> =
        import(path, MediaKind.VIDEO, name)

    private suspend fun import(path: String, kind: MediaKind, name: String?): CameraKResult<GalleryAsset> =
        withContext(Dispatchers.Default) {
            val url = fileUrlOf(path)
            val outcome = withTimeoutOrNull(importTimeout) {
                suspendCancellableCoroutine { continuation ->
                    photos.createAsset(url, kind) { localIdentifier, error ->
                        continuation.resume(localIdentifier to error)
                    }
                }
            }
            val fileName = url.lastPathComponent ?: "media"
            val localIdentifier = outcome?.first
            when {
                localIdentifier != null -> {
                    CameraKLogger.d(TAG, "Imported into the Photos library")
                    val displayName = name?.let { "$it.${fileName.substringAfterLast('.', "")}".trimEnd('.') }
                    CameraKResult.Success(GalleryAsset(displayName ?: fileName, localIdentifier))
                }
                else -> {
                    // The saver logs the failure once, with this cause.
                    val cause = outcome?.second?.let(::PhotosException) ?: PhotosTimeout(importTimeout)
                    CameraKResult.Failure(
                        CameraKError.Storage(StorageFailure.GALLERY_IMPORT_FAILED, keptFilePath = path, cause = cause),
                    )
                }
            }
        }

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = withContext(Dispatchers.Default) {
        // Set once Photos has finished with the file, so only then may this read delete it.
        var written: String? = null
        try {
            val path = "${cameraKCachesDirectory()}/read_${NSUUID().UUIDString}"
            val error = suspendCancellableCoroutine { continuation ->
                val started = photos.writeAssetData(asset.localIdentifier, NSURL.fileURLWithPath(path)) { error ->
                    // Photos cannot cancel the write, so a read cancelled before it finished leaves
                    // the delete to here, after Photos has written the file.
                    continuation.resume(error) { _, _, _ -> deleteReadFile(path) }
                }
                if (!started) continuation.resumeWithException(IOException("There is no such asset in Photos"))
            }
            written = path
            if (error != null) throw PhotosException(error)
            val data = NSFileManager.defaultManager.contentsAtPath(path) ?: throw IOException("Photos wrote no data")
            CameraKResult.Success(data.toByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading an asset from Photos failed", e)
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_FOUND, cause = e))
        } finally {
            written?.let(::deleteReadFile)
        }
    }

    // A read's file in Caches/CameraK, if Photos wrote one. A failed delete is logged without the path.
    private fun deleteReadFile(path: String) {
        val files = NSFileManager.defaultManager
        if (!files.fileExistsAtPath(path)) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!files.removeItemAtPath(path, error.ptr)) {
                CameraKLogger.e(
                    TAG,
                    "Deleting a Photos read's temporary file failed",
                    FileManagerException(error.value),
                )
            }
        }
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

/** What the file manager reported for a refused change: its domain and code, never a path. */
internal class FileManagerException(error: NSError?) :
    IOException("The file manager refused the change (${error?.domain} ${error?.code})")

/** Photos did not finish an import within [timeout], as while its add-only prompt is unanswered. */
internal class PhotosTimeout(timeout: Duration) : IOException("Photos did not finish the import within $timeout")

private const val TAG = "MediaSaver"

// Photos holds an import while its add-only prompt is up; this leaves the user time to answer it.
private val IMPORT_TIMEOUT = 60.seconds
