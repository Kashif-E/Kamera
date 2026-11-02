package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import java.io.File

private const val TAG = "CameraK"
private const val CAMERAK_FOLDER = "CameraK"

/**
 * Where a photo goes: a file in app-specific storage, or a MediaStore row CameraX inserts for a
 * gallery [Directory]. Exactly one of the two is set.
 */
internal class PhotoDestination(val file: File?, val galleryValues: ContentValues?)

/** Names and creates the files captures and recordings are written to, and cleans up after failed ones. */
internal class OutputFileFactory(
    private val context: Context,
    private val directory: Directory,
    private val photoFormat: ImageFormat,
) {
    /**
     * The destination of a new photo. Gallery destinations are written by CameraX through MediaStore;
     * everything else is a file in app-specific storage.
     *
     * @throws java.io.IOException when the destination cannot be created.
     */
    fun photoDestination(): PhotoDestination = if (directory.isGallery) {
        PhotoDestination(file = null, galleryValues = galleryImageValues())
    } else {
        PhotoDestination(file = appPhotoFile(), galleryValues = null)
    }

    /** The file a capture that does not go to the gallery is written to. */
    private fun appPhotoFile(): File {
        val cameraKDir = appCaptureDirectory(context)
        if (!cameraKDir.exists()) {
            cameraKDir.mkdirs()
        }
        return newCaptureFile(cameraKDir, "IMG", photoFormat.extension)
    }

    /**
     * The MediaStore row CameraX inserts for a gallery capture. From API 29 the folder is a
     * RELATIVE_PATH and needs no permission; below it the row names its file in the public folder,
     * which needs WRITE_EXTERNAL_STORAGE (declared with maxSdkVersion 28 in the library manifest).
     */
    private fun galleryImageValues(): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.MIME_TYPE, photoFormat.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newCaptureFileName("IMG", photoFormat.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directory.publicDirectoryName}/$CAMERAK_FOLDER")
        } else {
            @Suppress("DEPRECATION") // Below API 29 this is the public folder MediaStore writes to.
            val parent = File(
                Environment.getExternalStoragePublicDirectory(directory.publicDirectoryName),
                CAMERAK_FOLDER,
            ).also { it.mkdirs() }
            val file = newCaptureFile(parent, "IMG", photoFormat.extension)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
    }

    /** The file a new recording is written to. */
    fun videoFile(config: VideoConfiguration): File {
        val dir = if (config.outputDirectory != null) {
            File(config.outputDirectory).also { it.mkdirs() }
        } else {
            when (directory) {
                Directory.PICTURES, Directory.DCIM -> File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "CameraK",
                )
                Directory.DOCUMENTS -> appCaptureDirectory(context)
            }.also { it.mkdirs() }
        }
        return newCaptureFile(dir, config.filePrefix, "mp4")
    }

    /**
     * When writing a gallery capture fails, CameraX has already inserted its MediaStore row and does
     * not delete it (CameraX 1.5.3 FileUtil.copyFileToMediaStore marks it not pending and throws), and
     * onError does not say which row it was. The row is found by what this capture gave it: its
     * file path below API 29, otherwise its display name, which is unique by construction.
     */
    fun deleteFailedGalleryCapture(values: ContentValues) {
        val legacyPath = values.getAsString(MediaStore.MediaColumns.DATA)
        val (column, value) = if (legacyPath != null) {
            MediaStore.MediaColumns.DATA to legacyPath
        } else {
            MediaStore.MediaColumns.DISPLAY_NAME to values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        }
        try {
            context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "$column = ?", arrayOf(value))
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting the gallery entry of a failed capture failed", e)
        }
        // Below API 29 the gallery file was claimed before CameraX inserted its row.
        legacyPath?.let { File(it).delete() }
    }

    /** Deletes the gallery entry of a capture nobody will be told about. */
    fun deleteGalleryEntry(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting a discarded gallery capture failed", e)
        }
    }

    private companion object {
        val Directory.isGallery: Boolean get() = this == Directory.PICTURES || this == Directory.DCIM

        val Directory.publicDirectoryName: String
            get() = when (this) {
                Directory.PICTURES -> Environment.DIRECTORY_PICTURES
                Directory.DCIM -> Environment.DIRECTORY_DCIM
                Directory.DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
            }
    }
}
