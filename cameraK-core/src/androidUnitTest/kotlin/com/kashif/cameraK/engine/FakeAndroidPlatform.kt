@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Looper
import androidx.camera.camera2.internal.Camera2CameraInfoImpl
import androidx.camera.camera2.internal.compat.CameraManagerCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.PhysicalLensFacts
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.FakeCamera
import com.kashif.cameraK.controller.FakeCameraXBackend
import com.kashif.cameraK.controller.FakeMetering
import com.kashif.cameraK.controller.ResolvedCamera
import com.kashif.cameraK.controller.toCameraSelector
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import java.util.concurrent.ExecutorService

/**
 * The Android engine's fake platform: [FakeCameraXBackend] with the cameras [lenses] describes, the
 * engine's dispatchers on one test scheduler, and the platform's reports. Robolectric runs a test on
 * the main looper's thread, so the scheduler's thread is the main thread.
 *
 * With [holdReports], each camera records the torch and zoom requests it accepts and reports a
 * change only when the test does. [flashUnitOf] is what each camera's CameraInfo.hasFlashUnit says;
 * by default the lens's own hasFlash, which the enumerator read from the same characteristic.
 *
 * [meteringOf] is what each camera meters with and its exposure compensation; by default nothing.
 *
 * With [offeredByCameraX], CameraX offers only those cameras, which may differ from what the
 * enumeration listed (a USB camera unplugged since): each camera a bind asks for is selected by its
 * real CameraX selector over them, and a bind that selects none is refused as CameraX refuses it.
 *
 * [physicalChildren] names the lenses that are physical lenses of a logical multi-camera, as the
 * enumeration lists them. A bind that pins one binds its logical camera, as CameraX does: the camera it
 * returns is the parent's, with the parent's flash unit, zoom and metering.
 */
internal class FakeAndroidPlatform(
    val lenses: List<LensInfo> = TWO_FIXED_CAMERAS,
    private val holdReports: Boolean = false,
    private val flashUnitOf: (LensInfo) -> Boolean = { it.hasFlash },
    private val offeredByCameraX: List<LensInfo>? = null,
    private val meteringOf: (LensInfo) -> FakeMetering = { FakeMetering() },
    private val physicalChildren: Map<String, PhysicalChild> = emptyMap(),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val backend = FakeCameraXBackend()

    /** How often the engine enumerated its cameras, and on which thread. */
    val enumerations = mutableListOf<Thread>()

    init {
        backend.binding.cameraFor = { resolved -> cameraFor(resolved) }
        if (offeredByCameraX != null) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            offeredByCameraX.forEach { shadowOf(manager).addCamera(it.id, characteristicsOf(it)) }
            backend.binding.selects = { resolved -> selectedIds(resolved).isNotEmpty() }
        }
    }

    /** The camera the latest bind returned, or null before the first. */
    val camera: FakeCamera? get() = backend.binding.cameras.lastOrNull()

    fun engine(
        scheduler: TestCoroutineScheduler,
        config: CameraConfiguration = CameraConfiguration(),
        background: CoroutineDispatcher? = null,
        runtime: EngineRuntime? = null,
        imageProcessing: ExecutorService? = null,
        analyzerExecutor: ExecutorService? = null,
    ): AndroidCameraEngine {
        val main = StandardTestDispatcher(scheduler)
        val environment = CameraKEnvironment(
            dispatchers = CameraKDispatchers(main = main, default = background ?: main, io = background ?: main),
        )
        runtime?.let { environment.runtime = it }
        return engineIn(environment, config, imageProcessing, analyzerExecutor)
    }

    /** An engine on [environment]'s dispatchers, as a holder's factory builds it. */
    fun engineIn(
        environment: CameraKEnvironment,
        config: CameraConfiguration = CameraConfiguration(),
        imageProcessing: ExecutorService? = null,
        analyzerExecutor: ExecutorService? = null,
    ): AndroidCameraEngine = when {
        imageProcessing == null && analyzerExecutor == null ->
            AndroidCameraEngine(config, context, environment, backend, lensSource)
        analyzerExecutor == null ->
            AndroidCameraEngine(
                config,
                context,
                environment,
                backend,
                lensSource,
                imageProcessingExecutor = imageProcessing!!,
            )
        imageProcessing == null ->
            AndroidCameraEngine(config, context, environment, backend, lensSource, analyzerExecutor = analyzerExecutor)
        else -> AndroidCameraEngine(
            config,
            context,
            environment,
            backend,
            lensSource,
            imageProcessing,
            analyzerExecutor,
        )
    }

    private val lensSource: () -> List<AndroidLensDescriptor> = {
        enumerations += Thread.currentThread()
        lenses.map { lens ->
            val child = physicalChildren[lens.id]
            AndroidLensDescriptor(lens, child != null, child?.parentLogicalId, child?.facts)
        }
    }

    /**
     * Runs what the engine queued on the scheduler and the main looper until both are idle; a session bound on
     * an open camera reports its first capture result ([FakeCameraXBackend.streams]).
     */
    fun settle(scheduler: TestCoroutineScheduler) {
        repeat(SETTLE_ROUNDS) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            camera?.takeIf {
                it.isOpen
            }?.let { backend.reportFirstResult(it.metering.exposureState.exposureCompensationIndex) }
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

    // The ids of the offered cameras [resolved]'s selector keeps, in CameraX's order.
    private fun selectedIds(resolved: ResolvedCamera): List<String> {
        val offered = checkNotNull(offeredByCameraX)
        val cameraManager = CameraManagerCompat.from(context)
        val infos = offered.map { Camera2CameraInfoImpl(it.id, cameraManager) }
        return resolved.toCameraSelector().filter(infos).map { Camera2CameraInfo.from(it).cameraId }
    }

    private fun characteristicsOf(lens: LensInfo): CameraCharacteristics {
        val facing = when {
            lens.isExternal -> CameraCharacteristics.LENS_FACING_EXTERNAL
            lens.lens == CameraLens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            else -> CameraCharacteristics.LENS_FACING_BACK
        }
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
            set(CameraCharacteristics.LENS_FACING, facing)
            set(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            )
        }
        return characteristics
    }

    private fun cameraFor(resolved: ResolvedCamera): FakeCamera {
        val id = if (offeredByCameraX != null) {
            selectedIds(resolved).first()
        } else {
            when (resolved) {
                is ResolvedCamera.FacingDefault -> lenses.first { it.lens == resolved.lens }.id
                is ResolvedCamera.TopLevel -> resolved.cameraId
                is ResolvedCamera.External -> resolved.cameraId
                is ResolvedCamera.PhysicalLens -> checkNotNull(resolved.parentLogicalId)
            }
        }
        val lens = lenses.first { it.id == id }
        return FakeCamera(
            id = id,
            maxZoom = lens.maxZoom,
            minZoom = lens.minZoom,
            hasFlashUnit = flashUnitOf(lens),
            holdReports = holdReports,
            meters = meteringOf(lens),
        )
    }

    /** A physical lens of the logical camera [parentLogicalId], with what it can do on its own. */
    class PhysicalChild(val parentLogicalId: String, val facts: PhysicalLensFacts?)

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

        /**
         * What the contract driver's cameras meter with: the back camera focuses at a point and takes -12 to
         * 12, the fixed-focus front camera meters exposure at a point and takes -6 to 6, so a switch clamps.
         */
        val CONTRACT_METERING: (LensInfo) -> FakeMetering =
            { if (it.lens == CameraLens.FRONT) FakeMetering.FIXED_FOCUS else FakeMetering.FULL }

        val desired = DesiredHardware(
            selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
        )
    }
}
