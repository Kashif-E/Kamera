package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.capture.path
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop has no gallery: saves go to a folder under the user's home, `~/Pictures/<folder>` for
 * PICTURES and DCIM and `~/Documents/<folder>` for DOCUMENTS, where `<folder>` is the configured
 * folder name or `CameraK`. Each save creates a new file and never overwrites one: a taken name gets
 * a numeric suffix. A file is copied as it is.
 *
 * Names and the folder name are each 1 to 64 characters from `A-Z a-z 0-9 . _ -` and not `.` or
 * `..`, so a forwarded name can never place a file outside the folder. A folder that is itself a
 * symbolic link is neither read nor written, wherever it points.
 *
 * Reads are limited to the library's own output: this store's folder and the `CameraK` folders
 * under `Pictures` and `Documents`, where CameraK's desktop captures go.
 */
@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
internal class DesktopGalleryStore(
    private val config: MediaSaverConfig,
    private val userHome: File = defaultUserHome(),
) : GalleryStore {
    // Desktop folders need no permission.
    override val galleryPermission: CameraKPermission? get() = null

    override suspend fun addImage(path: String, format: ImageFormat, name: String?): CameraKResult<GalleryAsset> =
        write(path, name, format.extension)

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> {
        val extension = path.substringAfterLast(File.separatorChar).substringAfterLast('.', "mp4")
        return write(path, name, extension)
    }

    private suspend fun write(path: String, name: String?, extension: String): CameraKResult<GalleryAsset> =
        withContext(Dispatchers.IO) {
            var source: Path? = null
            try {
                source = Path.of(path)
                val baseName = name?.let { requireSafeFileName(it, "imageName") } ?: defaultImageName()
                val folder = resolveOutputDirectory()
                val file = createUniqueFile(folder, baseName, extension)
                try {
                    Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    deleteUnfinished(file, e)
                    throw e
                }
                val absolute = file.toAbsolutePath().toString()
                CameraKResult.Success(GalleryAsset(file.fileName.toString(), absolute))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The saver logs the failure once, with this cause.
                CameraKResult.Failure(
                    CameraKError.Storage(
                        // Only the capture's own file missing is NOT_FOUND: Windows also reports a destination
                        // folder it cannot create under a file as a missing path.
                        kind = if (e is NoSuchFileException && e.file == source?.toString()) {
                            StorageFailure.NOT_FOUND
                        } else {
                            StorageFailure.NOT_WRITABLE
                        },
                        cause = e,
                    ),
                )
            }
        }

    // The folder name is one validated name, and the folder it names must be that folder of the
    // destination's directory, not a symbolic link to any other.
    private fun resolveOutputDirectory(): File {
        val base = destinationFolder(config.destination)
        val name = config.folderName?.let { requireSafeFileName(it, "folderName") } ?: OUTPUT_FOLDER
        val folder = File(base, name)
        if (!isOwnFolder(folder, base)) {
            throw SaveFailure("The output folder is a link or resolves outside the configured directory")
        }
        return folder
    }

    private fun destinationFolder(destination: GalleryDestination): File = when (destination) {
        GalleryDestination.PICTURES, GalleryDestination.DCIM -> File(userHome, "Pictures")
        GalleryDestination.DOCUMENTS -> File(userHome, "Documents")
    }

    // The library's own output folders: this store's, and the CameraK folders that CameraK's desktop
    // captures write to. A folder that is a symbolic link, even to its own directory or a folder
    // beside it, is not one of them.
    private fun readRoots(): List<Path> {
        val captureFolders = listOf(GalleryDestination.PICTURES, GalleryDestination.DOCUMENTS)
            .map { destinationFolder(it) }
            .map { base -> File(base, OUTPUT_FOLDER) to base }
        val ownFolder = try {
            listOf(resolveOutputDirectory() to destinationFolder(config.destination))
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "The saver's output folder is not readable", e)
            emptyList()
        }
        return (captureFolders + ownFolder)
            .filter { (folder, base) -> isOwnFolderOrLog(folder, base) }
            .map { (folder, _) -> resolvedPath(folder) }
    }

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            CameraKResult.Success(readOwn(asset.path))
        } catch (e: IOException) {
            CameraKLogger.e(TAG, "Reading a gallery image failed", e)
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_FOUND, cause = e))
        }
    }

    /**
     * Reads an image this store or a CameraK capture wrote. [path] must resolve, after `..` and
     * symbolic links, to a file in one of [readRoots]; any other path, or a file that cannot be read,
     * throws an [IOException] whose message has no path (its cause may).
     */
    private fun readOwn(path: String): ByteArray {
        val (file, roots) = try {
            resolvedPath(File(path)) to readRoots()
        } catch (e: IOException) {
            throw IOException("Failed to resolve the image's path", e)
        }
        if (roots.none { file.startsWith(it) }) {
            throw IOException("The saver reads only images in the library's own output folders")
        }
        return try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            throw IOException("Failed to read the image", e)
        }
    }
}

// Whether [folder], a name in [base], resolves to exactly that name in [base] once symbolic links are
// followed: false when the folder is itself a link, wherever it points. [base] may be a link.
private fun isOwnFolder(folder: File, base: File): Boolean =
    resolvedPath(folder) == resolvedPath(base).resolve(folder.name)

/**
 * [file]'s absolute path with `..` and every symbolic link and junction resolved, through the deepest
 * part that exists; the rest is appended as named. File.canonicalFile follows no symbolic link on
 * Windows, where a linked folder passed the checks above.
 */
private fun resolvedPath(file: File): Path {
    var existing = file.toPath().toAbsolutePath().normalize()
    val missing = ArrayDeque<String>()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        val parent = existing.parent ?: break
        existing.fileName?.let { missing.addFirst(it.toString()) }
        existing = parent
    }
    return missing.fold(existing.toRealPath()) { path, name -> path.resolve(name) }
}

// A folder that cannot be resolved, a dangling link among them, is not one of the store's folders; the
// others stay readable.
private fun isOwnFolderOrLog(folder: File, base: File): Boolean = try {
    isOwnFolder(folder, base)
} catch (e: IOException) {
    CameraKLogger.w(TAG, "A CameraK output folder could not be resolved; it is not read from", e)
    false
}

// A failure the store describes itself, in a message with no path or user data.
internal class SaveFailure(message: String) : IOException(message)

/**
 * Creates a new, empty file `<name>.<extension>` in [folder] (creating the folder too), or
 * `<name>_<n>.<extension>` with the first free n if that name is taken. Never opens an existing file.
 */
private fun createUniqueFile(folder: File, name: String, extension: String): Path {
    val dir = Files.createDirectories(folder.toPath())
    for (attempt in 0 until MAX_NAME_ATTEMPTS) {
        val suffix = if (attempt == 0) "" else "_$attempt"
        try {
            return Files.createFile(dir.resolve("$name$suffix.$extension"))
        } catch (e: FileAlreadyExistsException) {
            // An earlier or concurrent save took this name; try the next suffix.
            CameraKLogger.w(TAG, "Gallery file name taken, trying the next one", e)
        }
    }
    throw SaveFailure("No free file name in $MAX_NAME_ATTEMPTS attempts")
}

private fun deleteUnfinished(file: Path, cause: Exception) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        CameraKLogger.e(TAG, "Failed to delete a partly written gallery file", e)
        cause.addSuppressed(e)
    }
}

private fun defaultImageName(): String =
    "image_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

// user.home can be null in sandboxed runtimes; fall back to the working dir.
private fun defaultUserHome(): File = File(System.getProperty("user.home") ?: System.getProperty("user.dir") ?: ".")

private const val MAX_NAME_ATTEMPTS = 100
private const val TAG = "MediaSaver"

// The folder the store writes to when no folder name is set; CameraK's desktop captures and the
// Android saver use the same name.
private const val OUTPUT_FOLDER = "CameraK"
