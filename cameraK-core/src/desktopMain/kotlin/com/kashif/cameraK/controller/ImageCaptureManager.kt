package com.kashif.cameraK.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

@Deprecated(
    "Unused by CameraK, and it writes to a captured_images folder in the working directory. Use " +
        "CameraController.takePictureToFile(). ImageCaptureManager is removed in CameraK 2.1.",
)
class ImageCaptureManager internal constructor(@Suppress("unused") private val userHome: File) {
    constructor() : this(defaultUserHome())

    private val isCapturing = AtomicBoolean(false)
    private val outputDir =
        File("captured_images").apply {
            if (!exists()) mkdirs()
        }

    suspend fun captureImage(image: BufferedImage): Result<File> = withContext(Dispatchers.IO) {
        if (!isCapturing.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Capture already in progress"))
        }

        try {
            val timestamp =
                LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"),
                )
            val outputFile = File(outputDir, "capture_$timestamp.jpg")

            Result.runCatching {
                ImageIO.write(image, "jpg", outputFile)
                outputFile
            }
        } finally {
            isCapturing.set(false)
        }
    }
}
