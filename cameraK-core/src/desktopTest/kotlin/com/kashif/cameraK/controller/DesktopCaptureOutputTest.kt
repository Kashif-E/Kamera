@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desktop captures are real files in app-owned storage (§6.1, Decision 10): a mirrored JPEG is a
 * JPEG; photos go to the configuration's `outputDirectory`, else to the app-owned default
 * `Documents/CameraK` under the user's home (DefaultOutputDirectoryTest); videos go to their own
 * directory, else the configuration's, else the user's movies folder, never the process working
 * directory. HEIC is not a desktop format: the start fails with `Unsupported(IMAGE_FORMAT)`.
 */
class DesktopCaptureOutputTest {
    private val written = mutableListOf<File>()

    private val front = LensInfo("usb-front", CameraDeviceType.DEFAULT, CameraLens.FRONT, 1f, 1f, false, false)

    // An open engine of a fresh rig; the case runs, then the engine closes and the rig's files go.
    private suspend fun TestScope.openEngine(
        config: CameraConfiguration = CameraConfiguration(),
        rigSetup: (DesktopEngineRig) -> Unit = {},
        enumerate: () -> CameraCapabilities = { desktopCapabilities() },
        desired: (DesktopRecordingSteps) -> DesiredHardware = { it.desired },
        case: suspend (DesktopEngineRig, DesktopRecordingSteps, DesktopCameraEngine) -> Unit,
    ) {
        val rig = DesktopEngineRig(testScheduler).also(rigSetup)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(config, enumerate = enumerate)
        try {
            steps.open(sut, desired(steps))
            case(rig, steps, sut)
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun captureWritesUnderTheConfiguredOutputDirectory() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val folder = File(rig.home, "app/stills")
        val sut = rig.engine(
            CameraConfiguration {
                imageFormat = ImageFormat.PNG
                outputDirectory = folder.path
            },
        )
        try {
            DesktopRecordingSteps(this, rig).open(sut)
            val image = engineCapture(rig, sut)

            assertEquals(folder.canonicalFile, File(image.path).canonicalFile.parentFile, "folder of the capture")
            assertTrue(image.path.endsWith(".png"), "extension of ${image.path}")
            assertEquals(ImageFormat.PNG, image.format)
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun recordingWithoutItsOwnDirectoryGoesToTheConfiguredOutputDirectory() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val folder = File(rig.home, "app/media")
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(CameraConfiguration { outputDirectory = folder.path })
        try {
            steps.open(sut)
            val path = steps.startRecording(sut, VideoConfiguration { enableAudio = false })

            assertEquals(folder.canonicalFile, File(path).canonicalFile.parentFile, "folder of the recording")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun heicIsUnsupported() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(CameraConfiguration { imageFormat = ImageFormat.HEIC })
        try {
            rig.settle()
            assertFalse(ImageFormat.HEIC in sut.capabilities.value.supportedImageFormats, "HEIC listed as supported")

            val desired = DesktopRecordingSteps(this, rig).desired
            val start = async { sut.start(desired) }
            rig.settle()

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertEquals(CameraFeature.IMAGE_FORMAT, error.feature)
            val failed = assertIs<CameraKError.Unsupported>(assertIs<EngineState.Failed>(sut.state.value).error)
            assertEquals(CameraFeature.IMAGE_FORMAT, failed.feature)
            assertEquals(0, rig.camera.startCount, "the camera was opened for a configuration it cannot honour")
            assertIs<CameraKResult.Failure>(sut.capture(), "a capture after the refused start")
            assertEquals(emptyList(), rig.home.walk().filter { it.isFile }.toList(), "files written")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    // Characterization: passes before the fix, which deletes it. The mutation M32 "a still finished
    // after close is kept" fails it.
    @Test
    fun aStillFinishedAfterCloseIsNotKept() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        lateinit var sut: DesktopCameraEngine
        rig.writeCapture = { image, format, file ->
            // The engine closes while the still is being written.
            sut.close()
            writeImage(image, format, file)
        }
        sut = rig.engine()
        try {
            DesktopRecordingSteps(this, rig).open(sut)
            val pending = async { sut.capture() }
            rig.settle()
            rig.camera.deliverFrame()
            rig.settle()

            assertEquals(CameraKError.Closed, assertIs<CameraKResult.Failure>(pending.await()).error, "the capture")
            assertEquals(emptyList(), rig.home.walk().filter { it.isFile }.toList(), "files kept after close")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    // The engine's capture of the camera's next frame, which the scripted camera holds until asked.
    private suspend fun TestScope.engineCapture(rig: DesktopEngineRig, sut: DesktopCameraEngine): CapturedImage {
        val pending = async { sut.capture() }
        rig.settle()
        rig.camera.deliverFrame()
        rig.settle()
        return assertIs<CameraKResult.Success<CapturedImage>>(pending.await(), "engine capture").value.also {
            written += File(it.path)
        }
    }

    @Test
    fun mirroredJpegCapture_writesAReadableJpegOfTheFrame() = runTest {
        openEngine(
            // A 4:3 configuration keeps the rig's 64 x 48 frames whole: the engine crops to the ratio.
            config = CameraConfiguration {
                mirrorFrontCamera = true
                aspectRatio = AspectRatio.RATIO_4_3
            },
            enumerate = { desktopCapabilities(listOf(front)) },
            desired = { DesktopRecordingSteps.desiredOn(CameraSelection.ById(front.id)) },
        ) { rig, _, sut ->
            val file = File(engineCapture(rig, sut).path)

            assertTrue(file.length() > 0, "the mirrored JPEG capture wrote an empty file")
            val image = assertNotNull(ImageIO.read(file), "the mirrored JPEG capture is not a readable image")
            assertEquals(64 to 48, image.width to image.height)
        }
    }

    @Test
    fun twoCapturesInARow_writeTwoDistinctFiles() = runTest {
        openEngine { rig, _, sut ->
            val first = File(engineCapture(rig, sut).path)
            val second = File(engineCapture(rig, sut).path)

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertTrue(first.length() > 0 && second.length() > 0, "both captures keep their bytes")
        }
    }

    @Test
    fun capture_isReadableAndWritableOnlyByItsOwner() = runTest {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return@runTest
        openEngine { rig, _, sut ->
            val file = File(engineCapture(rig, sut).path)

            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "permissions of the captured photo",
            )
        }
    }

    // The desktop twin of Android's DefaultOutputDirectoryTest.aRecordingDefaultsToTheAppOwnedLocation:
    // a recording with no directory of its own or the configuration's goes to the user's movies folder.
    @Test
    fun aRecordingDefaultsToTheUsersMoviesFolder() = runTest {
        openEngine { rig, steps, sut ->
            val path = steps.startRecording(sut, VideoConfiguration { enableAudio = false })

            assertEquals(
                File(rig.home, "${moviesFolderName()}/CameraK").canonicalFile,
                File(path).canonicalFile.parentFile,
                "folder of the recording",
            )
            steps.frameAt(1_000)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
        }
    }

    @Test
    fun aFailedWrite_returnsAnErrorAndLeavesNoFileBehind() = runTest {
        openEngine(
            rigSetup = { rig ->
                rig.writeCapture = { _, _, file ->
                    file.writeBytes(ByteArray(16))
                    throw IOException("disk full")
                }
            },
        ) { rig, _, sut ->
            val pending = async { sut.capture() }
            rig.settle()
            rig.camera.deliverFrame()
            rig.settle()

            assertIs<CameraKResult.Failure>(pending.await(), "capture result after a failed write")
            val folder = File(rig.home, "Documents/CameraK")
            assertTrue(folder.isDirectory, "the app-owned folder the capture was written in")
            assertEquals(emptyList(), folder.list()?.toList().orEmpty(), "files left")
        }
    }

    @Test
    fun theFoldersCapturesAreWrittenTo_areOwnerOnly() = runTest {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return@runTest
        openEngine { rig, steps, sut ->
            engineCapture(rig, sut)
            steps.startRecording(sut, VideoConfiguration { enableAudio = false })
            steps.frameAt(1_000)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            for (folder in listOf(
                File(rig.home, "Documents/CameraK"),
                File(rig.home, "${moviesFolderName()}/CameraK"),
            )) {
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(folder.toPath()),
                    "permissions of ${folder.relativeTo(rig.home)}",
                )
            }
        }
    }
}
