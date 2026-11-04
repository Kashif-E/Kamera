package com.kashif.cameraK.controller

import android.content.Context
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CameraK"

/**
 * Names and creates the files captures and recordings are written to, all in app-owned storage
 * (Decision 10): [outputDirectory] when the configuration names one, else `filesDir/CameraK`. A
 * recording's own `VideoConfiguration.outputDirectory` wins for that recording.
 *
 * Directories are created on [io], off the main thread, once each; one that cannot be created is
 * `Storage(NOT_WRITABLE)` before CameraX is asked for anything. A file is claimed on the calling
 * thread with an atomic createNewFile, so no two captures share a name.
 */
internal class OutputFileFactory(
    private val context: Context,
    outputDirectory: String?,
    private val io: CoroutineDispatcher,
) {
    /** Where stills go, and recordings that name no directory of their own. */
    val defaultDirectory: File = outputDirectory?.let(::File) ?: appCaptureDirectory(context)

    // Directories known to exist, so a capture after the first needs no trip to io.
    private val ensured: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Creates [dir] on the io dispatcher unless it is known to exist; false when it cannot be created. */
    suspend fun ensureDirectory(dir: File): Boolean {
        if (dir.path in ensured) return true
        val exists = withContext(io) {
            try {
                dir.isDirectory || dir.mkdirs() || dir.isDirectory
            } catch (e: SecurityException) {
                CameraKLogger.e(TAG, "Creating the output directory was refused", e)
                false
            }
        }
        if (exists) ensured += dir.path
        return exists
    }

    /** A new file for a still CameraX writes; its extension is [extension]. */
    suspend fun photoFile(extension: String): CameraKResult<File> = newFile(defaultDirectory, "IMG", extension)

    /** A new file beside [beside], for the PNG a JPEG capture is transcoded into. */
    fun siblingFile(beside: File, extension: String): CameraKResult<File> =
        claim(beside.parentFile ?: defaultDirectory, "IMG", extension)

    /** A new file for a recording. */
    suspend fun videoFile(config: VideoConfiguration): CameraKResult<File> =
        newFile(config.outputDirectory?.let(::File) ?: defaultDirectory, config.filePrefix, "mp4")

    private suspend fun newFile(dir: File, prefix: String, extension: String): CameraKResult<File> {
        if (!ensureDirectory(dir)) {
            CameraKLogger.e(TAG, "Creating the output directory failed")
            return CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_WRITABLE))
        }
        return claim(dir, prefix, extension)
    }

    private fun claim(dir: File, prefix: String, extension: String): CameraKResult<File> = try {
        CameraKResult.Success(newCaptureFile(dir, prefix, extension))
    } catch (e: IOException) {
        CameraKLogger.e(TAG, "Creating an output file failed", e)
        CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e))
    }
}
