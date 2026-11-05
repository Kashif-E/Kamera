package com.kashif.imagesaverplugin

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.capture.uri
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.captureFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaStore. From API 29 a row is inserted with `IS_PENDING = 1` and a `RELATIVE_PATH` in the
 * destination's collection, the bytes are copied in, and the row is published with `IS_PENDING = 0`;
 * a failure after the insert deletes the row. `MediaStore.Images` and `MediaStore.Video` accept only
 * their own primary folders, so [GalleryDestination.DOCUMENTS] goes through `MediaStore.Files`.
 *
 * Below API 29 there is no `RELATIVE_PATH`: the file under the public directory is created first,
 * taking the first free name (`name`, `name_1`, ...), so an existing file or a concurrent save is
 * never overwritten, and the row names it in `DATA`. That needs `WRITE_EXTERNAL_STORAGE`, which the
 * saver's manifest declares up to API 28.
 */
@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
internal class AndroidGalleryStore(
    context: Context,
    private val config: MediaSaverConfig,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GalleryStore {
    private val app: Context = context.applicationContext
    private val resolver: ContentResolver get() = app.contentResolver

    // Android captures are JPEG unless the engine transcoded them, so a deprecated byte save's mismatch
    // with the configured format repeats on every save; it is logged once.
    private val formatMismatchWarned = AtomicBoolean(false)

    override val galleryPermission: CameraKPermission?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else CameraKPermission.GALLERY_ADD

    override suspend fun addImage(
        source: MediaSource,
        format: ImageFormat,
        name: String?,
    ): CameraKResult<GalleryAsset> {
        val actual = when (source) {
            is MediaSource.File -> format
            is MediaSource.Bytes -> bytesFormat(source.bytes)
        }
        val fileName = "${name ?: captureFileName(
            "IMG",
            actual.extension,
        ).substringBeforeLast('.')}.${actual.extension}"
        return insert(source, MediaKind.IMAGE, fileName, actual.mimeType)
    }

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> {
        val extension = path.substringAfterLast('/').substringAfterLast('.', "mp4").lowercase()
        val fileName = "${name ?: captureFileName("VID", extension).substringBeforeLast('.')}.$extension"
        return insert(MediaSource.File(path), MediaKind.VIDEO, fileName, videoMimeType(extension))
    }

    private fun bytesFormat(bytes: ByteArray): ImageFormat {
        val format = encodedFormatOf(bytes) ?: config.imageFormat
        if (format != config.imageFormat && formatMismatchWarned.compareAndSet(false, true)) {
            CameraKLogger.w(
                TAG,
                "Image bytes are $format, not the configured ${config.imageFormat}; saving as $format " +
                    "(logged once per plugin)",
            )
        }
        return format
    }

    private suspend fun insert(
        source: MediaSource,
        kind: MediaKind,
        fileName: String,
        mimeType: String,
    ): CameraKResult<GalleryAsset> = withContext(io) {
        val directory = publicDirectory(config.destination, kind)
        val folder = config.folderName ?: DEFAULT_FOLDER
        // Set once the row exists, so every failure after the insert deletes it again (from API 29 it
        // is still IS_PENDING and would otherwise linger as an invisible, unfinished entry).
        var insertedUri: Uri? = null
        // Below API 29, the empty file that reserves the name until the bytes are written.
        var claimedFile: File? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$folder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    // Without DATA, MediaProvider below API 29 names the file itself under Pictures/.
                    val file = claimLegacyDestination(directory, folder, fileName)
                    claimedFile = file
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                }
            }
            val uri = resolver.insert(collection(config.destination, kind), values)
                ?: throw IOException("MediaStore created no row")
            insertedUri = uri
            resolver.openOutputStream(uri).use { output ->
                if (output == null) throw IOException("MediaStore opened no output stream")
                when (source) {
                    is MediaSource.Bytes -> output.write(source.bytes)
                    is MediaSource.File -> FileInputStream(source.path).use { it.copyTo(output) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            CameraKLogger.d(TAG, "Saved to MediaStore")
            CameraKResult.Success(GalleryAsset(claimedFile?.name ?: fileName, uri))
        } catch (e: CancellationException) {
            insertedUri?.let(::deleteUnfinishedRow)
            claimedFile?.let(::deleteUnfinishedFile)
            throw e
        } catch (e: Exception) {
            // SecurityException and IllegalArgumentException from MediaStore are as likely as
            // IOException; none of them may escape into the caller's coroutine. The saver logs the
            // failure once, with this cause.
            insertedUri?.let(::deleteUnfinishedRow)
            claimedFile?.let(::deleteUnfinishedFile)
            CameraKResult.Failure(storageError(e))
        }
    }

    @Suppress("DEPRECATION") // Only used below API 29, where it is the shared-storage location.
    private fun claimLegacyDestination(directory: String, folder: String, fileName: String): File {
        val parent = File(Environment.getExternalStoragePublicDirectory(directory), folder)
        // mkdirs() also returns false when a concurrent save has just created the folder.
        if (!parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Creating the destination folder failed")
        }
        val safeName = fileName.replace('/', '_').replace('\\', '_')
        val base = safeName.substringBeforeLast('.')
        val extension = safeName.substringAfterLast('.')
        var candidate = File(parent, safeName)
        var suffix = 1
        // createNewFile() is atomic, so two saves can never claim the same name.
        while (!candidate.createNewFile()) {
            candidate = File(parent, "${base}_${suffix++}.$extension")
        }
        return candidate
    }

    private fun deleteUnfinishedFile(file: File) {
        try {
            // Deleting the row below API 29 usually deletes the file as well.
            if (!file.delete() && file.exists()) {
                CameraKLogger.e(TAG, "Deleting the unfinished gallery file failed")
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting the unfinished gallery file failed", e)
        }
    }

    private fun deleteUnfinishedRow(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting the unfinished MediaStore row failed", e)
        }
    }

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = withContext(io) {
        try {
            val bytes = resolver.openInputStream(asset.uri)?.use(InputStream::readBytes)
                ?: throw FileNotFoundException("MediaStore opened no input stream")
            CameraKResult.Success(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading a gallery image failed", e)
            CameraKResult.Failure(storageError(e))
        }
    }

    override fun legacyLocation(asset: GalleryAsset): String = asset.uri.toString()

    override fun readLegacyBlocking(location: String): ByteArray {
        try {
            // A capture is a plain file path; a saved image is a content:// URI. Read either.
            val file = File(location)
            if (file.exists()) return file.readBytes()
            return resolver.openInputStream(Uri.parse(location))?.use(InputStream::readBytes)
                ?: throw IOException("MediaStore opened no input stream")
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading an image failed", e)
            throw IOException("Failed to read the image", e)
        }
    }

    override suspend fun readLegacy(location: String): ByteArray = withContext(io) {
        readLegacyBlocking(location)
    }
}

private const val TAG = "ImageSaverPlugin"
private const val DEFAULT_FOLDER = "CameraK"

/** The public directory a [kind] goes to for [destination], per the destination table of GalleryDestination. */
internal fun publicDirectory(destination: GalleryDestination, kind: MediaKind): String = when (destination) {
    GalleryDestination.PICTURES ->
        if (kind == MediaKind.IMAGE) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
    GalleryDestination.DCIM -> Environment.DIRECTORY_DCIM
    GalleryDestination.DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
}

/**
 * The MediaStore collection a [kind] is inserted into. Below API 29 the row's DATA names the file, so
 * the media table is enough; from 29 the collection must accept the row's RELATIVE_PATH.
 */
private fun collection(destination: GalleryDestination, kind: MediaKind): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return if (kind == MediaKind.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }
    val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    return when {
        destination == GalleryDestination.DOCUMENTS -> MediaStore.Files.getContentUri(volume)
        kind == MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        else -> MediaStore.Video.Media.getContentUri(volume)
    }
}

private fun storageError(e: Exception): CameraKError.Storage = CameraKError.Storage(
    kind = if (e is FileNotFoundException) StorageFailure.NOT_FOUND else StorageFailure.NOT_WRITABLE,
    cause = e,
)

private fun videoMimeType(extension: String): String = when (extension) {
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "3gp" -> "video/3gpp"
    else -> "video/mp4"
}

/**
 * The format [bytes] are encoded in, from their signature, or null when it is neither. A deprecated
 * byte save is labelled by what it holds, not by the configuration.
 */
private fun encodedFormatOf(bytes: ByteArray): ImageFormat? = when {
    bytes.startsWith(0xFF, 0xD8) -> ImageFormat.JPEG
    bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> ImageFormat.PNG
    else -> null
}

private fun ByteArray.startsWith(vararg signature: Int): Boolean =
    size >= signature.size && signature.indices.all { this[it] == signature[it].toByte() }
