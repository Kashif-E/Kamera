package com.kashif.imagesaverplugin
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import coil3.PlatformContext
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android-specific implementation of [ImageSaverPlugin] using MediaStore.
 *
 * Images are saved under `<directory>/<customFolderName or "CameraK">/` in shared storage:
 * [Directory.PICTURES] and [Directory.DCIM] through `MediaStore.Images`, and [Directory.DOCUMENTS]
 * through `MediaStore.Files`, because from API 29 `MediaStore.Images` only accepts `DCIM/` and
 * `Pictures/`. Below API 29 the absolute path is set explicitly, which needs `WRITE_EXTERNAL_STORAGE`:
 * path separators in the name are replaced with `_`, and the file is created before the row is
 * inserted, taking the first free name (`name`, `name_1`, ...), so an existing file or a concurrent
 * save is never overwritten.
 *
 * @param context The Android [Context] used to access ContentResolver.
 * @param config The configuration settings for the plugin.
 */
class AndroidImageSaverPlugin(private val context: Context, config: ImageSaverConfig) : ImageSaverPlugin(config) {
    override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? =
        (save(byteArray, imageName) as? ImageSaveResult.Saved)?.location

    override suspend fun save(byteArray: ByteArray, imageName: String?): ImageSaveResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${imageName ?: "IMG_$timeStamp"}.${config.imageFormat.extension}"
        val directory = config.directory.publicDirectoryName
        val folder = config.customFolderName ?: DEFAULT_FOLDER

        // Set once the row exists, so every failure after the insert deletes it again (on Q+ it is
        // still IS_PENDING and would otherwise linger as an invisible, unfinished entry).
        var insertedUri: Uri? = null
        // Below API 29, the empty file that reserves the name until the bytes are written.
        var claimedFile: File? = null
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.MIME_TYPE, config.imageFormat.mimeType)
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
            val collection: Uri = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                config.directory == Directory.DOCUMENTS -> MediaStore.Files.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val imageUri =
                resolver.insert(collection, contentValues)
                    ?: throw IOException("Failed to create new MediaStore record.")
            insertedUri = imageUri

            resolver.openOutputStream(imageUri).use { outputStream ->
                if (outputStream == null) throw IOException("Failed to get output stream.")
                outputStream.write(byteArray)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(imageUri, published, null, null)
            }

            CameraKLogger.d("CameraK", "Image saved to MediaStore")
            ImageSaveResult.Saved(imageUri.toString())
        } catch (e: Exception) {
            // SecurityException (no WRITE_EXTERNAL_STORAGE below API 29) and IllegalArgumentException
            // from MediaStore are as likely as IOException; none of them may escape into the caller's
            // coroutine.
            CameraKLogger.e("CameraK", "Saving image to MediaStore failed", e)
            insertedUri?.let { deleteUnfinishedRow(resolver, it) }
            claimedFile?.let(::deleteUnfinishedFile)
            ImageSaveResult.Failed(e)
        }
    }

    @Suppress("DEPRECATION") // Only used below API 29, where it is the shared-storage location.
    private fun claimLegacyDestination(directory: String, folder: String, fileName: String): File {
        val parent = File(Environment.getExternalStoragePublicDirectory(directory), folder)
        // mkdirs() also returns false when a concurrent save has just created the folder.
        if (!parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create the destination folder.")
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
                CameraKLogger.e("CameraK", "Deleting the unfinished image file failed")
            }
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Deleting the unfinished image file failed", e)
        }
    }

    private fun deleteUnfinishedRow(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Deleting the unfinished MediaStore row failed", e)
        }
    }

    @Deprecated(
        "Blocks the calling thread while it reads. Use readBytes, which suspends.",
        ReplaceWith("readBytes(path)"),
    )
    override fun getByteArrayFrom(path: String): ByteArray {
        try {
            // takePictureToFile() returns a raw filesystem path; the image saver may hand back a
            // content:// URI. Support both: read a plain file directly, otherwise via the resolver.
            val file = File(path)
            if (file.exists()) {
                return file.readBytes()
            }
            val uri = Uri.parse(path)
            return context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: throw IOException("Failed to open input stream")
        } catch (e: Exception) {
            throw IOException("Failed to read image from path/URI: $path", e)
        }
    }

    /**
     * Retrieves the file system path from a MediaStore URI.
     * Note: For future use with legacy storage access.
     *
     * @param uri The MediaStore URI.
     * @return The file path, or null if unavailable.
     */
    fun getActualPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                if (cursor.moveToFirst()) {
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Unhandled exception", e)
            null
        }
    }
}

private const val DEFAULT_FOLDER = "CameraK"

private val Directory.publicDirectoryName: String
    get() = when (this) {
        Directory.PICTURES -> Environment.DIRECTORY_PICTURES
        Directory.DCIM -> Environment.DIRECTORY_DCIM
        Directory.DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
    }

/**
 * Factory for creating Android-specific [ImageSaverPlugin] instances.
 *
 * Uses scoped storage API for safe, permission-aware file access.
 *
 * @param context Platform context for ContentResolver access.
 * @param config Configuration settings for the plugin.
 * @return An [AndroidImageSaverPlugin] instance.
 */
actual fun createPlatformImageSaverPlugin(context: PlatformContext, config: ImageSaverConfig): ImageSaverPlugin =
    AndroidImageSaverPlugin(context, config)
