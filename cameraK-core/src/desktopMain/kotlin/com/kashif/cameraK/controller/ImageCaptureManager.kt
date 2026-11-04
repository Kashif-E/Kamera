package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Writes a still as a JPEG to the app-owned default folder, `Documents/CameraK` under the user's
 * home (Decision 10), created at the first capture, one owner-only file per capture.
 */
@Deprecated(
    "Unused by CameraK. Use CameraController.takePictureToFile(). ImageCaptureManager is removed in CameraK 2.1.",
)
class ImageCaptureManager internal constructor(private val userHome: File) {
    constructor() : this(defaultUserHome())

    private val isCapturing = AtomicBoolean(false)

    suspend fun captureImage(image: BufferedImage): Result<File> = withContext(Dispatchers.IO) {
        if (!isCapturing.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Capture already in progress"))
        }

        try {
            runCatching {
                createUniqueFile(File(File(userHome, "Documents"), "CameraK"), "capture", "jpg").also { file ->
                    if (!ImageIO.write(image, "jpg", file)) {
                        file.delete()
                        throw IOException("No JPEG writer accepts this image")
                    }
                }
            }.onFailure { CameraKLogger.e("CameraK", "ImageCaptureManager could not write a capture", it) }
        } finally {
            isCapturing.set(false)
        }
    }
}
