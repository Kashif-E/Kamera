package com.kashif.ocrPlugin

import java.io.File
import java.io.InputStream

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

/** The sources this process offers. */
internal fun processTessdataSources(): TessdataSources = TessdataSources(
    explicitPath = null,
    bundledModel = null,
    modelCacheDir = defaultModelCacheDir(),
    environment = System::getenv,
)

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

/** Returns the directory holding `eng.traineddata`, or null when none of [sources] has one. */
internal fun findTessdataDirectory(sources: TessdataSources): String? {
    val possiblePaths =
        listOf(
            File("ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin").absolutePath,
            File("./ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin").absolutePath,
            sources.environment("TESSDATA_PREFIX"),
            File(
                System.getProperty("user.dir"),
                "ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin",
            ).absolutePath,
        )

    return possiblePaths.firstOrNull { path ->
        path != null && File(path).exists() && File("$path/eng.traineddata").exists()
    }
}
