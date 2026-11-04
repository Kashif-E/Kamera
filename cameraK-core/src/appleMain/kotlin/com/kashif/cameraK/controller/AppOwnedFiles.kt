package com.kashif.cameraK.controller

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/*
 * Where core writes stills and recordings: app-owned storage only (Decision 10). The configured output
 * directory when there is one, else Documents/CameraK, which iOS neither purges nor shows in Photos.
 */

/** [outputDirectory], or Documents/CameraK; created when missing. Throws, with the file system's error as cause, when it cannot be. */
internal fun appOwnedDirectory(outputDirectory: String?): String {
    val directory = outputDirectory ?: "${documentsDirectory()}/CameraK"
    createDirectoryIfMissing(directory, if (outputDirectory != null) "the output directory" else "Documents/CameraK")
    return directory
}

/** The app's Documents directory; the temporary directory when there is none. */
@OptIn(ExperimentalForeignApi::class)
private fun documentsDirectory(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: NSTemporaryDirectory()

/**
 * Creates the directory at [path] when there is none; throws, with the file system's NSError as the
 * cause, when it cannot. [what] names the directory in the message, never its path.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun createDirectoryIfMissing(path: String, what: String) = memScoped {
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(path)) return@memScoped
    val error = alloc<ObjCObjectVar<NSError?>>()
    val created = fileManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = error.ptr,
    )
    if (!created) {
        val cause = error.value
        throw IllegalStateException(
            "Could not create $what${cause?.let { ": ${it.summary}" }.orEmpty()}",
            cause?.let(::NSErrorException),
        )
    }
}

/** Removes the file at [path], if any; a failure is logged by the caller's operation, never by path. */
@OptIn(ExperimentalForeignApi::class)
internal fun removeFileIfPresent(path: String): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, null)
