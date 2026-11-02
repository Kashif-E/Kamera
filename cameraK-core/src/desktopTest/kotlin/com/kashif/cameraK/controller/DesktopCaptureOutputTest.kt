package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desktop captures are real files in the place the configuration names: a mirrored JPEG is a JPEG,
 * photos go to the configured [Directory] under the user's home, and videos default to the user's
 * movies folder, never the process working directory.
 */
class DesktopCaptureOutputTest {

    private val home: File = Files.createTempDirectory("camerak-home").toFile()
    private val written = mutableListOf<File>()

    private fun controller(
        format: ImageFormat = ImageFormat.JPEG,
        directory: Directory = Directory.PICTURES,
        horizontalFlip: Boolean = false,
        writeCapture: (BufferedImage, ImageFormat, File) -> Unit = ::writeImage,
        readCapture: (File) -> ByteArray = File::readBytes,
    ) = CameraController(
        imageFormat = format,
        directory = directory,
        customGrabber = FakeFrameGrabber().apply { openGate() },
        sessionDispatcher = Dispatchers.Unconfined,
        recorderFactory = { _, _ -> FakeFrameRecorder().apply { finishFirstRecord() } },
        horizontalFlip = horizontalFlip,
        userHome = home,
        writeCapture = writeCapture,
        readCapture = readCapture,
    )

    private fun capture(sut: CameraController): File = runBlocking {
        val result = withTimeout(10_000) { sut.takePictureToFile() }
        File(assertIs<ImageCaptureResult.SuccessWithFile>(result, "capture result: $result").filePath)
            .also { written += it }
    }

    private inline fun <T> session(sut: CameraController, block: () -> T): T = try {
        sut.startSession()
        block()
    } finally {
        sut.cleanup()
        written.forEach { it.delete() }
        home.deleteRecursively()
    }

    @Test
    fun mirroredJpegCapture_writesAReadableJpegOfTheFrame() {
        val sut = controller(horizontalFlip = true)
        session(sut) {
            val file = capture(sut)

            assertTrue(file.length() > 0, "the mirrored JPEG capture wrote an empty file")
            val image = assertNotNull(ImageIO.read(file), "the mirrored JPEG capture is not a readable image")
            assertEquals(64 to 48, image.width to image.height)
        }
    }

    @Test
    fun picturesCapture_landsInPicturesCameraKUnderTheUsersHome() =
        assertCaptureLandsIn(Directory.PICTURES, File(home, "Pictures/CameraK"))

    @Test
    fun documentsCapture_landsInDocumentsCameraKUnderTheUsersHome() =
        assertCaptureLandsIn(Directory.DOCUMENTS, File(home, "Documents/CameraK"))

    private fun assertCaptureLandsIn(directory: Directory, expected: File) {
        val sut = controller(format = ImageFormat.PNG, directory = directory)
        session(sut) {
            val file = capture(sut)

            assertEquals(expected.canonicalFile, file.canonicalFile.parentFile, "folder of the capture")
            assertTrue(file.name.endsWith(".png"), "extension of ${file.name}")
            assertNotNull(ImageIO.read(file), "the capture is not a readable image")
        }
    }

    @Test
    fun twoCapturesInARow_writeTwoDistinctFiles() {
        val sut = controller()
        session(sut) {
            val first = capture(sut)
            val second = capture(sut)

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertTrue(first.length() > 0 && second.length() > 0, "both captures keep their bytes")
        }
    }

    @Test
    fun capture_isReadableAndWritableOnlyByItsOwner() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val sut = controller()
        session(sut) {
            val file = capture(sut)

            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "permissions of the captured photo",
            )
        }
    }

    @Test
    fun recordingWithoutAnOutputDirectory_goesToTheUsersMoviesFolder() = runBlocking {
        val sut = controller()
        val moviesFolder = if (System.getProperty("os.name").lowercase().contains("mac")) "Movies" else "Videos"
        var path: String? = null
        try {
            path = sut.startRecording(VideoConfiguration(enableAudio = false))

            assertEquals(File(home, "$moviesFolder/CameraK").canonicalFile, File(path).canonicalFile.parentFile)
        } finally {
            withTimeout(10_000) { sut.stopRecording() }
            sut.cleanup()
            home.deleteRecursively()
            // The working-directory folder the old default created, if this run made it.
            path?.let { File(it).parentFile }?.takeIf { it.name == "captured_videos" && it.list()?.isEmpty() == true }
                ?.delete()
        }
    }

    @Test
    fun aThrowingCaptureListener_keepsThePhotoAndTheCaptureSucceeds() {
        val sut = controller()
        sut.addImageCaptureListener { throw IllegalStateException("a consumer's listener failed") }
        session(sut) {
            val file = capture(sut)

            assertTrue(file.exists(), "the photo was deleted because a listener threw")
            assertNotNull(ImageIO.read(file), "the photo is not a readable image")
        }
    }

    @Test
    fun aCaptureWithNoListener_neverReadsTheFileBack() {
        val reads = AtomicInteger(0)
        val sut = controller(readCapture = {
            reads.incrementAndGet()
            it.readBytes()
        })
        session(sut) {
            capture(sut)
            assertEquals(0, reads.get(), "file read-backs with no listener")

            val listener: (ByteArray) -> Unit = {}
            sut.addImageCaptureListener(listener)
            sut.removeImageCaptureListener(listener)
            capture(sut)
            assertEquals(0, reads.get(), "file read-backs after the only listener was removed")
        }
    }

    @Test
    fun aCaptureWithAListener_givesItTheBytesOfTheFile() {
        var received: ByteArray? = null
        val sut = controller()
        sut.addImageCaptureListener { received = it }
        session(sut) {
            val file = capture(sut)

            assertTrue(file.readBytes().contentEquals(assertNotNull(received, "the listener got no bytes")))
        }
    }

    @Test
    fun aFailedWrite_returnsAnErrorAndLeavesNoFileBehind() {
        val sut = controller(
            writeCapture = { _, _, file ->
                file.writeBytes(ByteArray(16))
                throw IOException("disk full")
            },
        )
        session(sut) {
            val result = runBlocking { withTimeout(10_000) { sut.takePictureToFile() } }

            assertIs<ImageCaptureResult.Error>(result, "capture result after a failed write")
            assertEquals(emptyList(), File(home, "Pictures/CameraK").list()?.toList().orEmpty(), "files left")
        }
    }

    @Test
    fun theFoldersCapturesAreWrittenTo_areOwnerOnly() = runBlocking {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return@runBlocking
        val sut = controller()
        val moviesFolder = if (System.getProperty("os.name").lowercase().contains("mac")) "Movies" else "Videos"
        session(sut) {
            capture(sut)
            sut.startRecording(VideoConfiguration(enableAudio = false))
            withTimeout(10_000) { sut.stopRecording() }

            for (folder in listOf(File(home, "Pictures/CameraK"), File(home, "$moviesFolder/CameraK"))) {
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(folder.toPath()),
                    "permissions of ${folder.relativeTo(home)}",
                )
            }
        }
    }
}
