@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.FocusMeteringAction
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.MeteringPointBuild
import com.kashif.cameraK.controller.PreviewGeometry
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.CONTRACT_CAMERAS
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `focusAt` on CameraX (w13 Z-focus-android, G1): the tap is mapped through the preview's crop,
 * rotation and mirror to a buffer pixel, metered with AF, AE and AWB where the camera supports each,
 * with CameraX's default auto-cancel, and CameraX's outcome is mapped: focused or metering-only is
 * Success, AF that did not lock is Timeout, a request a newer one, a reset or a rebind superseded is
 * Busy, one the camera's closing cancelled is Closed, anything else Hardware. Without a preview
 * surface there is no geometry to map through, so the tap is InvalidState.
 */
@RunWith(RobolectricTestRunner::class)
class FocusAtTest {
    private val platform = FakeAndroidPlatform(CONTRACT_CAMERAS, meteringOf = FakeAndroidPlatform.CONTRACT_METERING)

    @Test
    fun aTapMetersAtTheBufferPixelThePreviewDrawsThere() = runTest {
        // 16:9 cropped from a 4:3 buffer, drawn turned 90 degrees: (0.25, 0.1) on screen is buffer pixel (64, 330).
        platform.backend.previewGeometryReported = PreviewGeometry(Size(640, 480), Rect(0, 60, 640, 420), 90)
        val engine = open()

        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.25f, 0.1f)))

        assertEquals(MeteringPointBuild(64f, 330f, 640, 480), platform.backend.meteringPoints.last())
        val request = platform.camera!!.metering.focusRequests.single()
        assertEquals(setOf(AF, AE, AWB), request.flags, "the back camera meters AF, AE and AWB")
        assertTrue(request.action.isAutoCancelEnabled, "CameraX's own auto-cancel returns to continuous metering")
        assertEquals(5_000L, request.action.autoCancelDurationInMillis, "CameraX's default auto-cancel")
        engine.closeAndJoin()
    }

    @Test
    fun theFrontCamerasMirroredPreviewIsUndone() = runTest {
        // A full 4:3 buffer drawn turned 270 degrees and mirrored: (0.25, 0.1) on screen is buffer pixel (576, 360).
        platform.backend.previewGeometryReported = PreviewGeometry(Size(640, 480), Rect(0, 0, 640, 480), 270)
        val engine = open()
        selectFront(engine)

        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.25f, 0.1f)))

        assertEquals(MeteringPointBuild(576f, 360f, 640, 480), platform.backend.meteringPoints.last())
        engine.closeAndJoin()
    }

    @Test
    fun afThatDoesNotLockIsTimeout() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        assertFalse(focus.isCompleted, "focusAt returned before CameraX reported the outcome")
        metering.focusRequests.single().complete(focused = false)
        platform.settle(testScheduler)

        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Timeout>(failure(focus.await())).operation)
        engine.closeAndJoin()
    }

    // CameraX reports isFocusSuccessful false for an action without AF: metering alone completes the tap.
    @Test
    fun aFixedFocusCameraMetersWithoutAfAndSucceedsOnceCameraXCompletes() = runTest {
        val engine = open()
        selectFront(engine)
        val metering = platform.camera!!.metering
        metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        val request = metering.focusRequests.single()
        assertEquals(setOf(AE, AWB), request.flags, "AF, which the fixed-focus camera lacks, was not dropped")
        request.complete(focused = false)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), focus.await())
        engine.closeAndJoin()
    }

    @Test
    fun aNewerFocusSupersedesThePendingOneWithBusy() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdFocus = true

        val first = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        val second = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        platform.settle(testScheduler)
        metering.focusRequests.last().complete(focused = true)
        platform.settle(testScheduler)

        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(first.await())).operation)
        assertEquals(CameraKResult.Success(Unit), second.await())
        engine.closeAndJoin()
    }

    @Test
    fun aResetSupersedesThePendingFocusWithBusy() = runTest {
        val engine = open()
        platform.camera!!.metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.resetFocusAndExposure())
        platform.settle(testScheduler)

        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(focus.await())).operation)
        engine.closeAndJoin()
    }

    @Test
    fun aCameraSwitchSupersedesThePendingFocusWithBusy() = runTest {
        val engine = open()
        platform.camera!!.metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        selectFront(engine)

        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(focus.await())).operation)
        engine.closeAndJoin()
    }

    @Test
    fun theCameraClosingUnderAPendingFocusIsClosed() = runTest {
        val engine = open()
        platform.camera!!.metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        engine.stop()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(focus.await()))
        engine.closeAndJoin()
    }

    @Test
    fun aCloseUnderAPendingFocusIsClosed() = runTest {
        val engine = open()
        platform.camera!!.metering.holdFocus = true

        val focus = async { engine.focusAt(CENTRE) }
        platform.settle(testScheduler)
        engine.close()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(focus.await()))
        engine.closeAndJoin()
    }

    @Test
    fun anyOtherFailureIsHardwareWithItsCause() = runTest {
        val engine = open()
        val cause = IllegalStateException("the capture session refused the request")
        platform.camera!!.metering.focusFailure = cause

        val error = assertIs<CameraKError.Hardware>(failure(engine.focusAt(CENTRE)))

        assertEquals(null, error.platformCode)
        assertSame(cause, error.cause)
        engine.closeAndJoin()
    }

    @Test
    fun withoutAPreviewSurfaceTheTapIsInvalidState() = runTest {
        platform.backend.previewGeometryReported = null
        val engine = open()

        val error = assertIs<CameraKError.InvalidState>(failure(engine.focusAt(CENTRE)))

        assertEquals(CameraOperation.FOCUS_POINT, error.operation)
        assertTrue(platform.camera!!.metering.focusRequests.isEmpty(), "a point was guessed without the geometry")
        engine.closeAndJoin()
    }

    @Test
    fun aCameraThatMetersAtNoPointIsUnsupported() = runTest {
        val platform = FakeAndroidPlatform(CONTRACT_CAMERAS)
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val error = assertIs<CameraKError.Unsupported>(failure(engine.focusAt(CENTRE)))

        assertEquals(CameraFeature.FOCUS_POINT, error.feature)
        assertFalse(engine.capabilities.value.supportsFocusPoint(CONTRACT_CAMERAS.first().id))
        assertTrue(platform.camera!!.metering.focusRequests.isEmpty())
        engine.closeAndJoin()
    }

    @Test
    fun focusAtLeavesHardwareAlone() = runTest {
        val engine = open()
        val hardware = engine.hardware.value

        assertEquals(CameraKResult.Success(Unit), engine.focusAt(CENTRE))
        platform.settle(testScheduler)

        assertEquals(hardware, engine.hardware.value)
        engine.closeAndJoin()
    }

    @Test
    fun afterCloseEveryFocusAndExposureCallIsClosedWithoutReachingCameraX() = runTest {
        val engine = open()
        val camera = platform.camera!!
        engine.close()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(engine.focusAt(CENTRE)))
        assertIs<CameraKError.Closed>(failure(engine.setExposureCompensation(2)))
        assertIs<CameraKError.Closed>(failure(engine.resetFocusAndExposure()))
        assertTrue(camera.metering.focusRequests.isEmpty(), "focusAt reached CameraX after close")
        assertTrue(camera.metering.exposureRequests.isEmpty(), "setExposureCompensation reached CameraX after close")
        assertEquals(0, camera.metering.cancelCalls, "resetFocusAndExposure reached CameraX after close")
        engine.closeAndJoin()
    }

    private suspend fun TestScope.open(): AndroidCameraEngine =
        platform.engine(testScheduler).also { open(platform, it) }

    private suspend fun TestScope.selectFront(engine: AndroidCameraEngine) {
        val selected = engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT))
        assertEquals(FRONT.id, assertIs<CameraKResult.Success<LensInfo>>(selected, "select the front camera").value.id)
        platform.settle(testScheduler)
    }

    private fun failure(result: CameraKResult<*>): CameraKError = assertIs<CameraKResult.Failure>(result).error

    private companion object {
        val CENTRE = NormalizedPoint(0.5f, 0.5f)
        val FRONT = CONTRACT_CAMERAS.single { it.lens == CameraLens.FRONT }
        const val AF = FocusMeteringAction.FLAG_AF
        const val AE = FocusMeteringAction.FLAG_AE
        const val AWB = FocusMeteringAction.FLAG_AWB
    }
}
