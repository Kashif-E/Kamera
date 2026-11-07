@file:OptIn(CameraKEngineApi::class)

package org.company.app.devicematrix

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The matrix enumerates its space from the enums and the capabilities, never from a hard-coded list. */
class MatrixEnumerationTest {
    private val backAndFront = FakeCapabilities.backAndFront()
    private val cases = enumerateCases(backAndFront)

    private val perLens =
        CameraDeviceType.entries.size * AspectRatio.entries.size * photoResolutions.size *
            ImageFormat.entries.size * QualityPrioritization.entries.size * FlashMode.entries.size

    @Test
    fun thePhotoMatrixIsTheFullCartesianProduct_withMirroringOnTheFrontLensOnly() {
        val photos = cases.map { it.spec }.filterIsInstance<PhotoSpec>()
        val back = photos.filter { it.lens == CameraLens.BACK }
        val front = photos.filter { it.lens == CameraLens.FRONT }

        assertEquals(perLens, back.size, "back photo cases")
        assertEquals(perLens * 2, front.size, "front photo cases: both mirror values")
        assertTrue(back.none { it.mirrorFrontCamera }, "the back lens has no mirror variant")
        assertEquals(setOf(false, true), front.map { it.mirrorFrontCamera }.toSet())
        assertEquals(photos.size, photos.toSet().size, "every photo case is distinct")
        assertEquals(CameraDeviceType.entries.toSet(), photos.map { it.deviceType }.toSet())
        assertEquals(AspectRatio.entries.toSet(), photos.map { it.aspectRatio }.toSet())
        assertEquals(photoResolutions.toSet(), photos.map { it.targetResolution }.toSet())
        assertEquals(ImageFormat.entries.toSet(), photos.map { it.imageFormat }.toSet())
        assertEquals(QualityPrioritization.entries.toSet(), photos.map { it.quality }.toSet())
        assertEquals(FlashMode.entries.toSet(), photos.map { it.flashMode }.toSet())
    }

    @Test
    fun theFlashModeVariesInnermost_soCasesSharingAHolderAreContiguous() {
        val photos = cases.map { it.spec }.filterIsInstance<PhotoSpec>()
        val runs = photos.fold(mutableListOf<MutableList<PhotoSpec>>()) { acc, spec ->
            if (acc.lastOrNull()?.last()?.holderKey == spec.holderKey) acc.last() += spec else acc += mutableListOf(spec)
            acc
        }
        assertEquals(photos.size / FlashMode.entries.size, runs.size, "one run per holder")
        assertTrue(runs.all { run -> run.map { it.flashMode } == FlashMode.entries }, "each run is every flash mode")
    }

    @Test
    fun theOtherSections_coverEveryCameraAndEveryEnumValue() {
        val specs = cases.map { it.spec }
        val features = specs.filterIsInstance<FeatureSpec>()
        assertEquals(backAndFront.allLenses.size * FeatureCheck.entries.size, features.size)
        assertEquals(backAndFront.allLenses.map { it.id }.toSet(), features.map { it.cameraId }.toSet())

        assertEquals(2 * FlashMode.entries.size, specs.filterIsInstance<ConfigFlashSpec>().size)
        assertEquals(
            2 * FrameFormat.entries.size * analysisResolutions.size,
            specs.filterIsInstance<AnalysisSpec>().size,
        )
        assertEquals(PluginKind.entries.size, specs.filterIsInstance<PluginSpec>().size)
        assertEquals(
            2 * AspectRatio.entries.size * VideoQuality.entries.size * 2,
            specs.filterIsInstance<VideoSpec>().size,
        )
        assertEquals(LifecycleKind.entries.size, specs.filterIsInstance<LifecycleSpec>().size)
    }

    @Test
    fun theSectionsRunInOrder_andTheIndexesAreContiguousAndStable() {
        assertEquals(cases.indices.toList(), cases.map { it.index })
        assertEquals(cases.map { it.id }, enumerateCases(backAndFront).map { it.id }, "the same capabilities, the same matrix")
        assertEquals(cases.size, cases.map { it.id }.toSet().size, "every id is unique")
        val order = cases.map { it.spec.section }.distinct()
        assertEquals(MatrixSection.entries, order)
    }

    @Test
    fun aDeviceWithOnlyABackCamera_hasNoFrontCases() {
        val back = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 8f, true, false)
        val external = LensInfo("usb", CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, false, false, true)
        val capabilities = CameraCapabilities(
            allLenses = listOf(back, external),
            flashModes = mapOf("0" to FlashMode.entries.toSet()),
            torchModes = mapOf("0" to TorchMode.entries.toSet()),
            supportsPauseResume = true,
            supportedVideoQualities = VideoQuality.entries.toSet(),
            supportsVideoBitrate = true,
            supportedAspectRatios = AspectRatio.entries.toSet(),
            supportedImageFormats = setOf(ImageFormat.JPEG),
            supportedAnalysisFormats = FrameFormat.entries.toSet(),
            supportsSquareCapture = true,
        )
        val specs = enumerateCases(capabilities).map { it.spec }

        assertEquals(perLens, specs.filterIsInstance<PhotoSpec>().size)
        assertTrue(specs.filterIsInstance<PhotoSpec>().all { it.lens == CameraLens.BACK })
        assertEquals(setOf("0"), specs.filterIsInstance<FeatureSpec>().map { it.cameraId }.toSet(), "external left out")
    }

    @Test
    fun launchArguments_selectTheRun() {
        assertEquals(null, matrixArgsFromLaunchArguments(listOf("/app/iosApp")))
        assertEquals(MatrixArgs(), matrixArgsFromLaunchArguments(listOf("/app/iosApp", "-cameraKMatrix")))
        assertEquals(
            MatrixArgs(offset = 120, sections = setOf(MatrixSection.PHOTO, MatrixSection.VIDEO), limit = 5),
            matrixArgsFromLaunchArguments(
                listOf(
                    "/app/iosApp",
                    "-cameraKMatrix",
                    "-cameraKMatrixOffset",
                    "120",
                    "-cameraKMatrixSections",
                    "photo, video,bogus",
                    "-cameraKMatrixLimit",
                    "5",
                ),
            ),
        )
        assertEquals(MatrixSection.entries.toSet(), parseSections(" "))
    }
}
