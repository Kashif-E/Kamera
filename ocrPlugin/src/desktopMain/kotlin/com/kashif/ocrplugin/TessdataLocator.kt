package com.kashif.ocrplugin

import com.kashif.cameraK.utils.CameraKLogger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Where desktop OCR may find Tesseract's language data, passed in so a test can stand in for the
 * process environment and the classpath.
 *
 * @property explicitPath The tessdata directory the integrator configured, if any.
 * @property bundledModel An `eng.traineddata` packaged on the classpath, if any.
 * @property modelCacheDir The per-user directory a packaged model is extracted to.
 * @property environment Reads a process environment variable.
 */
internal class TessdataSources(
    val explicitPath: String?,
    val bundledModel: BundledModel?,
    val modelCacheDir: File,
    val environment: (String) -> String?,
)

/**
 * A language model packaged as a classpath resource.
 *
 * @property sha256 The lowercase hex SHA-256 the model's bytes must have.
 * @property open Opens the packaged bytes, or returns null when the resource is absent.
 */
internal class BundledModel(val sha256: String, val open: () -> InputStream?)

/** Classpath location of the optional packaged model, under the 2.0 package's directory. */
internal const val BUNDLED_MODEL_RESOURCE = "/com/kashif/ocrplugin/tessdata/eng.traineddata"

/** Where 1.x apps packaged the model, before the package became lowercase; read only as a fallback. */
internal const val LEGACY_BUNDLED_MODEL_RESOURCE = "/com/kashif/ocrPlugin/tessdata/eng.traineddata"

/** SHA-256 of `eng.traineddata` from github.com/tesseract-ocr/tessdata (git blob f4744c20, 23,466,654 bytes). */
internal const val BUNDLED_MODEL_SHA256 = "daa0c97d651c19fba3b25e81317cd697e9908c8208090c94c3905381c23fc047"

private const val MODEL_FILE = "eng.traineddata"
private const val TAG = "CameraK"

/** The sources this process offers for [config]. */
internal fun processTessdataSources(config: OcrConfig): TessdataSources = TessdataSources(
    explicitPath = config.tessdataPath,
    bundledModel = BundledModel(BUNDLED_MODEL_SHA256) {
        openBundledModel(OCRProcessor::class.java::getResourceAsStream)
    },
    modelCacheDir = defaultModelCacheDir(),
    environment = System::getenv,
)

/**
 * Opens the packaged model through [resources], which reads one absolute classpath resource, or returns
 * null. The 2.0 path comes first; a model only at the 1.x path is opened with one warning to move it.
 */
internal fun openBundledModel(resources: (String) -> InputStream?): InputStream? =
    resources(BUNDLED_MODEL_RESOURCE) ?: resources(LEGACY_BUNDLED_MODEL_RESOURCE)?.also {
        CameraKLogger.w(
            TAG,
            "OcrPlugin: the packaged $MODEL_FILE is in the 1.x resource directory com/kashif/ocrPlugin/tessdata; " +
                "move it to com/kashif/ocrplugin/tessdata, which CameraK 2.0 reads first",
        )
    }

/** A per-user application data directory for CameraK's extracted models. */
internal fun defaultModelCacheDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val base =
        when {
            os.contains("mac") -> File(home, "Library/Application Support")
            os.contains("win") -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(home, ".local/share")
        }
    return File(base, "CameraK/tessdata")
}

/**
 * Returns the directory holding `eng.traineddata`, or null when none of [sources] has one. The
 * order is the explicit path, then the packaged model verified against its pinned SHA-256 and
 * extracted to the cache directory, then `TESSDATA_PREFIX`. An explicit path is final: when it has
 * no model, no other source replaces it. The working directory is never searched.
 */
internal fun findTessdataDirectory(sources: TessdataSources): String? {
    sources.explicitPath?.let { path ->
        if (hasModel(File(path))) return path
        CameraKLogger.w(TAG, "OcrPlugin: the configured tessdata directory has no $MODEL_FILE")
        return null
    }
    sources.bundledModel?.let { model ->
        extractVerified(model, sources.modelCacheDir)?.let { return it.path }
    }
    return sources.environment("TESSDATA_PREFIX")?.takeIf { it.isNotBlank() && hasModel(File(it)) }
}

private fun hasModel(dir: File): Boolean = File(dir, MODEL_FILE).isFile

/**
 * Copies the packaged model into a directory of [cacheDir] named for its hash, unless a copy with
 * the pinned hash is already there, and returns that directory. Returns null when the resource is
 * absent or its bytes do not match the pin.
 */
private fun extractVerified(model: BundledModel, cacheDir: File): File? {
    val dir = File(cacheDir, "eng-${model.sha256.take(16)}")
    val target = File(dir, MODEL_FILE)
    return try {
        if (target.isFile && sha256Of(target.inputStream()) == model.sha256) return dir
        val input = model.open() ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, digest).use { stream ->
            createOwnerOnlyDirectories(dir.toPath())
            // createTempFile makes the file rw------- where POSIX permissions exist.
            val partial = Files.createTempFile(dir.toPath(), "eng", ".part")
            try {
                Files.copy(stream, partial, REPLACE_EXISTING)
                if (digest.digest().toHex() != model.sha256) {
                    CameraKLogger.e(
                        TAG,
                        "OcrPlugin: the packaged $MODEL_FILE does not match its pinned SHA-256; not loading it",
                    )
                    return null
                }
                Files.move(partial, target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
        dir
    } catch (e: IOException) {
        CameraKLogger.e(TAG, "OcrPlugin: Failed to extract the packaged language model", e)
        null
    }
}

private fun createOwnerOnlyDirectories(dir: Path) {
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createDirectories(dir)
    }
}

private fun sha256Of(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (stream.read(buffer) != -1) Unit
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
