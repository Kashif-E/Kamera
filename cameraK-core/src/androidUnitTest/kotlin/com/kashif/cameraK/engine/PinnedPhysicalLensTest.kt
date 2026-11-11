@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.util.Size
import android.view.Surface
import androidx.camera.camera2.impl.Camera2ImplConfig
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.VideoCapture
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.LensOutputSizes
import com.kashif.cameraK.capabilities.PhysicalLensFacts
import com.kashif.cameraK.capabilities.PhysicalMetering
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.FakeMetering
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.FakeAndroidPlatform.PhysicalChild
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bind that pins a physical lens of a logical multi-camera binds the logical camera, and CameraX sizes
 * and describes that one (w14, Pixel 7). Every use case keeps only the sizes the pinned lens itself
 * outputs for its format, and a metering action asks only for what the pinned lens meters: the Pixel 7's
 * ultra-wide (camera 3 inside logical camera 0) lists no 4032x3024 output, which its logical camera
 * offers first for 4:3, and is fixed-focus behind a logical camera that focuses.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedPhysicalLensTest {
    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun everyUseCaseOfAPinnedLensKeepsOnlyTheSizesThatLensOutputsForItsFormat() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS)
        val engine = openUltraWide(platform)

        assertEquals(ULTRA_WIDE_STILL, keptBy(bound<ImageCapture>(platform).resolutionSelector))
        assertEquals(ULTRA_WIDE_PREVIEW, keptBy(bound<Preview>(platform).resolutionSelector))
        assertEquals(ULTRA_WIDE_ANALYSIS, keptBy(bound<ImageAnalysis>(platform).resolutionSelector))
        engine.closeAndJoin()
    }

    @Test
    fun aCameraBoundAsItselfKeepsEverySizeCameraXOffers() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS)
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        open(platform, engine)

        assertNull(bound<ImageCapture>(platform).resolutionSelector!!.resolutionFilter)
        assertNull(bound<Preview>(platform).resolutionSelector!!.resolutionFilter)
        assertNull(bound<ImageAnalysis>(platform).resolutionSelector!!.resolutionFilter)
        engine.closeAndJoin()
    }

    @Test
    fun aPinnedLensWhoseOutputsWereNotReadKeepsEverySizeCameraXOffers() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS.copy(outputs = null))
        val engine = openUltraWide(platform)

        assertNull(bound<ImageCapture>(platform).resolutionSelector!!.resolutionFilter)
        assertNull(bound<Preview>(platform).resolutionSelector!!.resolutionFilter)
        assertNull(bound<ImageAnalysis>(platform).resolutionSelector!!.resolutionFilter)
        engine.closeAndJoin()
    }

    // An empty list would leave CameraX no size to bind; the warning says the pin may not hold.
    @Test
    fun aPinnedLensThatOutputsNoneOfCameraXsSizesKeepsThemAllAndWarns() = runTest {
        val logged = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) logged += message }
        val elsewhere = listOf(Size(320, 240))
        val platform = platformWith(ULTRA_WIDE_FACTS.copy(outputs = LensOutputSizes(elsewhere, elsewhere, elsewhere)))
        val engine = openUltraWide(platform)

        assertEquals(LOGICAL_SIZES, keptBy(bound<ImageCapture>(platform).resolutionSelector))
        assertTrue(logged.any { "outputs none of the sizes" in it }, "no warning: $logged")
        engine.closeAndJoin()
    }

    // CameraX reports AF for the logical camera, so it keeps FLAG_AF; the fixed-focus lens never locks and
    // CameraX reports the action done without focus after its 5 s auto-cancel: on the device, Timeout.
    @Test
    fun aFixedFocusPinnedLensMetersWithoutAfAndSucceedsOnceCameraXCompletes() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS)
        val engine = openUltraWide(platform)
        val metering = platform.camera!!.metering
        metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        val request = metering.focusRequests.single()
        assertEquals(setOf(AE), request.flags, "the pinned lens meters AE alone: no AF, no AWB region")
        request.complete(focused = false)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), focus.await())
        assertTrue(engine.capabilities.value.supportsFocusPoint(ULTRA_WIDE.id), "a lens that meters at a point")
        engine.closeAndJoin()
    }

    @Test
    fun aPinnedLensThatMetersNothingAtAPointHasNoFocusPoint() = runTest {
        val none = PhysicalMetering(autoFocus = false, autoExposure = false, autoWhiteBalance = false)
        val platform = platformWith(ULTRA_WIDE_FACTS.copy(metering = none))
        val engine = openUltraWide(platform)

        assertFalse(engine.capabilities.value.supportsFocusPoint(ULTRA_WIDE.id), "the capabilities offer a focus point")
        val result = engine.focusAt(CENTRE)
        platform.settle(testScheduler)

        assertEquals(CameraFeature.FOCUS_POINT, assertIs<CameraKError.Unsupported>(failure(result)).feature)
        assertTrue(platform.camera!!.metering.focusRequests.isEmpty(), "the action reached CameraX")
        engine.closeAndJoin()
    }

    @Test
    fun aRecordingOnAPinnedLensStreamsItsVideoFromThatLens() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS)
        val engine = openUltraWide(platform)

        startRecording(platform, engine, VideoConfiguration { enableAudio = false })

        assertEquals(ULTRA_WIDE.id, pinOf(bound<VideoCapture<*>>(platform)), "the VideoCapture's physical lens")
        engine.closeAndJoin()
    }

    @Test
    fun aVideoCapturePreparedOnTheLogicalCameraIsNotBoundToAPinnedLens() = runTest {
        val platform = platformWith(ULTRA_WIDE_FACTS)
        val engine = platform.engine(testScheduler, CameraConfiguration())
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(ULTRA_WIDE_DESIRED.on(CameraDeviceType.WIDE_ANGLE)))
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(VideoConfiguration { enableAudio = false }))
        platform.settle(testScheduler)

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(ULTRA_WIDE.id)))
        platform.settle(testScheduler)
        val afterSwitch = platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>().map(::pinOf)
        startRecording(platform, engine, VideoConfiguration { enableAudio = false })

        val otherLenses = afterSwitch.filterNot { it == ULTRA_WIDE.id }
        assertEquals(emptyList(), otherLenses, "other lenses bound with the ultra-wide")
        assertEquals(ULTRA_WIDE.id, pinOf(bound<VideoCapture<*>>(platform)), "the recording's VideoCapture lens")
        engine.closeAndJoin()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun pinOf(useCase: UseCase): String? = Camera2ImplConfig(useCase.currentConfig).getPhysicalCameraId(null)

    private fun DesiredHardware.on(type: CameraDeviceType) = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, type),
        flashMode = flashMode,
        torchMode = torchMode,
        zoomRatio = zoomRatio,
        targetOrientation = targetOrientation,
    )

    private fun platformWith(facts: PhysicalLensFacts) = FakeAndroidPlatform(
        lenses = listOf(LOGICAL_BACK, ULTRA_WIDE, FRONT),
        meteringOf = { FakeMetering.FULL },
        physicalChildren = mapOf(ULTRA_WIDE.id to PhysicalChild(LOGICAL_BACK.id, facts)),
    )

    private suspend fun TestScope.openUltraWide(platform: FakeAndroidPlatform): AndroidCameraEngine {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(ULTRA_WIDE_DESIRED), "setup: start")
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value, "setup: the camera opened")
        assertEquals(ULTRA_WIDE.id, engine.hardware.value.camera?.id, "setup: the ultra-wide is bound")
        return engine
    }

    private inline fun <reified T : UseCase> bound(platform: FakeAndroidPlatform): T =
        platform.backend.binding.bound.filterIsInstance<T>().single()

    // The sizes the selector's filter keeps of the logical camera's, in CameraX's order.
    private fun keptBy(selector: ResolutionSelector?): List<Size> {
        val filter = assertNotNull(selector?.resolutionFilter, "the selector has no resolution filter")
        return filter.filter(LOGICAL_SIZES, Surface.ROTATION_0)
    }

    private fun failure(result: CameraKResult<*>): CameraKError = assertIs<CameraKResult.Failure>(result).error

    private companion object {
        val LOGICAL_BACK = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 0.67f, 8f, true, true)
        val ULTRA_WIDE = LensInfo("3", CameraDeviceType.ULTRA_WIDE, CameraLens.BACK, 1f, 8f, true, false)
        val FRONT = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 1f, false, false)

        // What CameraX offers for the logical camera, largest first: 4032x3024 is the logical camera's.
        val LOGICAL_SIZES = listOf(
            Size(4032, 3024),
            Size(4000, 3000),
            Size(2560, 1920),
            Size(1920, 1440),
            Size(1440, 1080),
            Size(1280, 960),
            Size(640, 480),
        )

        // A different list per format, so a use case filtered by another format's list shows.
        val ULTRA_WIDE_STILL = listOf(Size(4000, 3000), Size(2560, 1920))
        val ULTRA_WIDE_PREVIEW = listOf(Size(1920, 1440), Size(1440, 1080))
        val ULTRA_WIDE_ANALYSIS = listOf(Size(1280, 960), Size(640, 480))

        // The Pixel 7's camera 3: LENS_INFO_MINIMUM_FOCUS_DISTANCE 0, CONTROL_MAX_REGIONS [AE 1, AWB 0, AF 0].
        val ULTRA_WIDE_FACTS = PhysicalLensFacts(
            outputs = LensOutputSizes(
                still = ULTRA_WIDE_STILL + Size(320, 240),
                preview = ULTRA_WIDE_PREVIEW + Size(320, 240),
                analysis = ULTRA_WIDE_ANALYSIS + Size(320, 240),
            ),
            metering = PhysicalMetering(autoFocus = false, autoExposure = true, autoWhiteBalance = false),
        )

        val ULTRA_WIDE_DESIRED = DesiredHardware(
            selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE),
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
        )

        val CENTRE = NormalizedPoint(0.5f, 0.5f)
        const val AE = FocusMeteringAction.FLAG_AE
    }
}
