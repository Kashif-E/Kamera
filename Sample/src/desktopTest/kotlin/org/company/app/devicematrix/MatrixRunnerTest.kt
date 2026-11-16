@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app.devicematrix

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import com.kashif.mediasaverplugin.MediaSaverPlugin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The runner drives holders over fake engines that write real files, and reports what it found. */
class MatrixRunnerTest {
    private val directory = kotlin.io.path.createTempDirectory("matrix-runner").toFile()
    private val report get() = File(directory, "report.json").readText()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A platform over fake engines; each capture writes a header-only image of [imageSize]'s size. */
    private inner class TestPlatform(
        private val scope: TestScope,
        private val capabilities: CameraCapabilities = FakeCapabilities.backAndFront(),
        private val imageSize: (CameraConfiguration) -> MatrixSize = { MatrixSize(4000, 3000) },
        override val promptsOnOpen: Boolean = false,
    ) : MatrixPlatform {
        val engines = mutableListOf<FakeCameraEngine>()
        val holders = mutableListOf<CameraKStateHolder>()
        val captures = mutableListOf<String>()
        val lines = mutableListOf<String>()
        override val platformName = "test"
        override val deviceDescription = "fake engine"
        override val reportDirectory: String = directory.path
        override val files: MatrixFiles = platformFiles

        init {
            // Plays the platform reporting the flash mode it applied, as a real engine does.
            scope.backgroundScope.launch {
                while (true) {
                    engines.lastOrNull()?.let { it.reportFlash(it.appliedFlashMode) }
                    delay(10)
                }
            }
        }

        override fun newHolder(config: CameraConfiguration, plugins: List<CameraKPlugin>): CameraKStateHolder {
            val engine = FakeCameraEngine(capabilities)
            engine.captureResult = {
                val size = imageSize(config)
                val file = File(directory, "IMG_${captures.size}.${config.imageFormat.extension}")
                file.writeBytes(
                    when (config.imageFormat) {
                        ImageFormat.PNG -> testPng(size.width, size.height)
                        else -> testJpeg(size.width, size.height)
                    },
                )
                captures += file.path
                CameraKResult.Success(FakeCaptures.image(file.path, size.width, size.height, config.imageFormat))
            }
            engines += engine
            return scope.fakeCameraKStateHolder(config, engine, plugins).also { holders += it }
        }

        override fun newMediaSaver(): MediaSaverPlugin? = null

        // Where the prompt comes with the device's open (macOS), the camera stays NotDetermined until a
        // holder starts, and the microphone until a recording with audio starts.
        override fun permission(permission: CameraKPermission): PermissionStatus = when {
            !promptsOnOpen -> PermissionStatus.Granted
            permission == CameraKPermission.CAMERA && holders.any { it.cameraState.value != CameraKState.Idle } ->
                PermissionStatus.Granted
            permission == CameraKPermission.MICROPHONE && engines.any { "startRecording" in it.calls } ->
                PermissionStatus.Granted
            else -> PermissionStatus.NotDetermined
        }

        override fun log(line: String) {
            lines += line
        }
    }

    private fun firstPhotoIndex(capabilities: CameraCapabilities, matching: (PhotoSpec) -> Boolean): Int =
        enumerateCases(capabilities).first { (it.spec as? PhotoSpec)?.let(matching) == true }.index

    @Test
    fun aPhotoGroupCapturesEveryFlashMode_checksEachFile_andDeletesIt() = runTest {
        val platform = TestPlatform(this)
        val offset = firstPhotoIndex(FakeCapabilities.backAndFront()) { it.lens == CameraLens.BACK }

        MatrixRunner(platform, MatrixArgs(offset = offset, sections = setOf(MatrixSection.PHOTO), limit = 3)).run()

        val text = report
        assertEquals(3, Regex(""""verdict":"PASS"""").findAll(text).count(), text)
        assertTrue(text.contains(""""magic":"JPEG""""), text)
        assertTrue(text.contains(""""decodedWidth":4000"""), text)
        assertTrue(text.trimEnd().endsWith("]"), "the finished report is closed")
        assertEquals(3, platform.captures.size)
        assertTrue(platform.captures.none { File(it).exists() }, "every capture is deleted after its checks")
        assertEquals(
            listOf(1, 1),
            platform.engines.map {
                it.releaseCount
            },
            "the probe's holder and the group's, each closed",
        )
        assertTrue(platform.lines.any { "PASS" in it && "photo/BACK" in it }, platform.lines.joinToString("\n"))
    }

    @Test
    fun aFormatTheCapabilitiesLeaveOut_isUnsupportedOk() = runTest {
        val capabilities = FakeCapabilities.backAndFront(imageFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG))
        val platform = TestPlatform(this, capabilities)
        val offset = firstPhotoIndex(capabilities) { it.imageFormat == ImageFormat.HEIC }

        MatrixRunner(platform, MatrixArgs(offset = offset, sections = setOf(MatrixSection.PHOTO), limit = 3)).run()

        assertEquals(3, Regex(""""verdict":"UNSUPPORTED-OK"""").findAll(report).count(), report)
        assertTrue(report.contains("Unsupported(IMAGE_FORMAT)"), report)
    }

    @Test
    fun anImageOfTheWrongRatio_fails() = runTest {
        val platform = TestPlatform(this)
        // The back lens with the flash off: the ratio is the only check that can fail.
        val offset = firstPhotoIndex(FakeCapabilities.backAndFront()) {
            it.aspectRatio == AspectRatio.RATIO_16_9 && it.lens == CameraLens.BACK && it.flashMode == FlashMode.OFF
        }

        MatrixRunner(platform, MatrixArgs(offset = offset, sections = setOf(MatrixSection.PHOTO), limit = 1)).run()

        assertEquals(1, Regex(""""verdict":"FAIL"""").findAll(report).count(), report)
        assertTrue(report.contains("ratio"), report)
    }

    @Test
    fun aResumeAppendsTheNextCasesToTheSameReport() = runTest {
        val platform = TestPlatform(this)
        val offset = firstPhotoIndex(FakeCapabilities.backAndFront()) { it.lens == CameraLens.BACK }

        MatrixRunner(platform, MatrixArgs(offset = offset, sections = setOf(MatrixSection.PHOTO), limit = 1)).run()
        MatrixRunner(platform, MatrixArgs(offset = offset + 1, sections = setOf(MatrixSection.PHOTO), limit = 1)).run()

        assertTrue(report.contains(""""index":$offset,""") && report.contains(""""index":${offset + 1},"""), report)
        assertEquals(1, Regex("""^\[""", RegexOption.MULTILINE).findAll(report).count())
    }

    @Test
    fun theLifecycleCases_passOnAnEngineThatKeepsTheContract() = runTest {
        val platform = TestPlatform(this)
        val cases = enumerateCases(FakeCapabilities.backAndFront())
        val startStop = cases.first { (it.spec as? LifecycleSpec)?.kind == LifecycleKind.START_STOP_20 }.index

        MatrixRunner(
            platform,
            MatrixArgs(offset = startStop, sections = setOf(MatrixSection.LIFECYCLE), limit = 2),
        ).run()

        assertEquals(2, Regex(""""verdict":"PASS"""").findAll(report).count(), report)
        assertTrue(report.contains(""""cycles":20"""), report)
    }

    @Test
    fun whereTheCameraPromptComesWithItsOpen_theRunnerOpensTheCameraInsteadOfWaiting() = runTest {
        val platform = TestPlatform(this, promptsOnOpen = true)
        val startStop = enumerateCases(FakeCapabilities.backAndFront())
            .first { (it.spec as? LifecycleSpec)?.kind == LifecycleKind.START_STOP_20 }.index

        MatrixRunner(
            platform,
            MatrixArgs(offset = startStop, sections = setOf(MatrixSection.LIFECYCLE), limit = 1),
        ).run()

        val log = platform.lines.joinToString("\n")
        assertTrue(platform.lines.none { it.startsWith("BLOCKED") }, log)
        assertEquals(1, Regex(""""verdict":"PASS"""").findAll(report).count(), report)
    }

    // The first audio case would otherwise meet the microphone's prompt inside its 10 s command timeout.
    @Test
    fun whereTheMicrophonePromptComesWithARecording_theRunnerAnswersItBeforeTheCases() = runTest {
        val platform = TestPlatform(this, promptsOnOpen = true)
        val startStop = enumerateCases(FakeCapabilities.backAndFront())
            .first { (it.spec as? LifecycleSpec)?.kind == LifecycleKind.START_STOP_20 }.index

        MatrixRunner(
            platform,
            MatrixArgs(offset = startStop, sections = setOf(MatrixSection.LIFECYCLE), limit = 1),
        ).run()

        assertTrue(""""permissions":{"CAMERA":"Granted","MICROPHONE":"Granted"}""" in report, report)
        assertTrue(platform.engines.first().calls.none { it == "startRecording" }, "the camera's prompt holder recorded")
        assertTrue(platform.engines.drop(1).first().calls.containsAll(listOf("startRecording", "stopRecording")))
    }
}
