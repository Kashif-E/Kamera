package com.kashif.cameraK.controller

import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Core writes app-owned files only (Decision 10). The deprecated `ImageCaptureManager` wrote to a
 * `captured_images` folder in the process's working directory, created at construction; it now
 * writes to the app-owned default, `Documents/CameraK` under the user's home, created at the first
 * capture.
 */
@Suppress("DEPRECATION")
class ImageCaptureManagerTest {
    private val home: File = Files.createTempDirectory("camerak-manager").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun capturesLandInTheAppOwnedDefaultNotTheWorkingDirectory() = runBlocking {
        val workingDirectoryFolder = File("captured_images")
        val existedBefore = workingDirectoryFolder.exists()
        val sut = ImageCaptureManager(home)

        val file = sut.captureImage(BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR)).getOrThrow()

        assertEquals(File(home, "Documents/CameraK").canonicalFile, file.canonicalFile.parentFile, "folder")
        assertNotNull(ImageIO.read(file), "the capture is not a readable image")
        assertEquals(existedBefore, workingDirectoryFolder.exists(), "a folder was created in the working directory")
    }
}
