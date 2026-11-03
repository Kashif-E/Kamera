@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.os.Looper
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.FakeCamera
import com.kashif.cameraK.controller.FakeCameraXBackend
import com.kashif.cameraK.controller.ResolvedCamera
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.robolectric.Shadows.shadowOf

/**
 * The Android engine's fake platform: [FakeCameraXBackend] with the cameras [lenses] describes, the
 * engine's dispatchers on one test scheduler, and the platform's reports. Robolectric runs a test on
 * the main looper's thread, so the scheduler's thread is the main thread.
 *
 * With [holdReports], each camera records the torch and zoom requests it accepts and reports a
 * change only when the test does. [flashUnitOf] is what each camera's CameraInfo.hasFlashUnit says;
 * by default the lens's own hasFlash, which the enumerator read from the same characteristic.
 */
internal class FakeAndroidPlatform(
    val lenses: List<LensInfo> = TWO_FIXED_CAMERAS,
    private val holdReports: Boolean = false,
    private val flashUnitOf: (LensInfo) -> Boolean = { it.hasFlash },
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val backend = FakeCameraXBackend()

    /** How often the engine enumerated its cameras, and on which thread. */
    val enumerations = mutableListOf<Thread>()

    init {
        backend.binding.cameraFor = { resolved -> cameraFor(resolved) }
    }

    /** The camera the latest bind returned, or null before the first. */
    val camera: FakeCamera? get() = backend.binding.cameras.lastOrNull()

    fun engine(
        scheduler: TestCoroutineScheduler,
        config: CameraConfiguration = CameraConfiguration(),
        background: CoroutineDispatcher? = null,
        runtime: EngineRuntime? = null,
    ): AndroidCameraEngine {
        val main = StandardTestDispatcher(scheduler)
        val environment = CameraKEnvironment(
            dispatchers = CameraKDispatchers(main = main, default = background ?: main, io = background ?: main),
        )
        runtime?.let { environment.runtime = it }
        return AndroidCameraEngine(
            config = config,
            context = context,
            environment = environment,
            backend = backend,
            lensSource = {
                enumerations += Thread.currentThread()
                lenses.map { AndroidLensDescriptor(it, isPhysicalChild = false, parentLogicalId = null) }
            },
        )
    }

    /** Runs what the engine queued on the scheduler and the main looper until both are idle. */
    fun settle(scheduler: TestCoroutineScheduler) {
        repeat(SETTLE_ROUNDS) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    fun report(state: CameraState) {
        camera?.report(state)
    }

    fun reportError(type: CameraState.Type, code: Int) =
        report(CameraState.create(type, CameraState.StateError.create(code)))

    fun reportOpen() = report(CameraState.create(CameraState.Type.OPEN))

    /** The flash mode of the bound ImageCapture; OFF before the first bind. */
    fun boundFlashMode(): FlashMode =
        when (backend.binding.bound.filterIsInstance<ImageCapture>().lastOrNull()?.flashMode) {
            ImageCapture.FLASH_MODE_ON -> FlashMode.ON
            ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
            else -> FlashMode.OFF
        }

    /** The torch the bound camera last accepted; OFF, as CameraX opens a camera, before any request. */
    fun boundTorchMode(): TorchMode = if (camera?.torchRequests?.lastOrNull() == true) TorchMode.ON else TorchMode.OFF

    /** The zoom ratio the bound camera last accepted; 1x, as CameraX opens a camera, before any request. */
    fun boundZoomRatio(): Float = camera?.zoomRequests?.lastOrNull() ?: 1f

    /** The Preview use case currently bound. */
    fun boundPreview(): Preview = backend.binding.bound.filterIsInstance<Preview>().single()

    private fun cameraFor(resolved: ResolvedCamera): FakeCamera {
        val id = when (resolved) {
            is ResolvedCamera.FacingDefault -> lenses.first { it.lens == resolved.lens }.id
            is ResolvedCamera.TopLevel -> resolved.cameraId
            is ResolvedCamera.PhysicalLens -> resolved.physicalId
        }
        val lens = lenses.first { it.id == id }
        return FakeCamera(
            id = id,
            maxZoom = lens.maxZoom,
            minZoom = lens.minZoom,
            hasFlashUnit = flashUnitOf(lens),
            holdReports = holdReports,
        )
    }

    companion object {
        private const val SETTLE_ROUNDS = 4

        val BACK = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 1f, false, false)
        val FRONT = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 1f, false, false)

        /** Two cameras, neither with a flash unit, each with a fixed zoom. */
        val TWO_FIXED_CAMERAS = listOf(BACK, FRONT)

        /** A back camera with a flash unit and a 1x to 8x zoom. */
        val BACK_WITH_FLASH = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 8f, true, false)

        /** A back camera with a flash unit and a zoom range, and a front one with neither. */
        val FLASH_AND_ZOOM = listOf(BACK_WITH_FLASH, FRONT)

        /** A front camera with a flash unit and a 1x to 4x zoom. */
        val FRONT_WITH_FLASH = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 4f, true, false)

        /** The contract driver's cameras from A2: both with a flash unit and a zoom range, so every step runs. */
        val CONTRACT_CAMERAS = listOf(BACK_WITH_FLASH, FRONT_WITH_FLASH)

        val desired = DesiredHardware(
            selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
        )
    }
}
