@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The kit catches a wrong engine: each test runs one case over the fake with one rule broken, the
 * way a real engine could break it, and expects that case to fail for that reason.
 */
class ContractKitMutationTest {
    @Test
    fun torchCommandMirroredIntoHardwareFailsTheObserverRule() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases { MirroringEngine(it, Mirrored.TORCH) }.commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(failure.message.orEmpty(), "setTorchMode")
    }

    @Test
    fun zoomCommandMirroredIntoZoomFailsTheObserverRule() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases { MirroringEngine(it, Mirrored.ZOOM) }.commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(failure.message.orEmpty(), "setZoom")
    }

    @Test
    fun flashCommandMirroredIntoHardwareFailsTheObserverRule() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases { MirroringEngine(it, Mirrored.FLASH) }.commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(failure.message.orEmpty(), "setFlashMode")
    }

    @Test
    fun closeThatReleasesBeforeTheRecordingIsFinalizedFailsTheShutdownCase() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::ReleasesAtClose).closeDuringRecordingFinalizesWithShutdownSuccess(this)
        }
        assertContains(failure.message.orEmpty(), "close released the camera before the recording was finalized")
    }

    @Test
    fun cancelledStartThatStaysOpeningFailsTheCancelCase() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::StaysOpeningWhenCancelled).cancelledStartLeavesCameraClosed(this)
        }
        assertContains(failure.message.orEmpty(), "Opening")
    }

    @Test
    fun engineReportingTheBackCameraAfterOpeningOnTheFrontFailsTheCameraCase() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::ReportsTheBackCamera).reportedCameraIsTheOneTheEngineOpenedOn()
        }
        assertContains(failure.message.orEmpty(), "after opening on 'front'")
    }

    @Test
    fun torchCommandThatNeverReachesThePlatformFailsTheCommandRule() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::SwallowsTorchCommands).commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(failure.message.orEmpty(), "setTorchMode did not reach the platform")
    }

    @Test
    fun engineBindingAnotherCameraThanTheSelectedOneFailsTheObserverCase() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::BindsTheFrontReportsTheBack).hardwareChangesOnlyFromPlatformObserver()
        }
        assertContains(failure.message.orEmpty(), "the platform bound another camera than the one the case selected")
    }

    @Test
    fun flashCommandRefusedWhileOpenFailsTheFlashStep() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::RefusesFlashWhileOpen).commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(failure.message.orEmpty(), "setFlashMode to the reported mode")
    }

    @Test
    fun flashCommandMirroredIntoStateFailsTheFlashStep() = runTest {
        val failure = assertFailsWith<AssertionError> {
            cases(::MirrorsAnotherFlashMode).commandsLeaveHardwareUntilThePlatformReports()
        }
        assertContains(
            failure.message.orEmpty(),
            "setFlashMode to the reported mode changed hardware before the platform reported it",
        )
    }

    private fun cases(breakIt: (FakeCameraEngine) -> BrokenEngine) =
        ContractCases(BrokenDriver(breakIt), requires = emptySet(), skips = ContractSkips())

    private class BrokenDriver(private val breakIt: (FakeCameraEngine) -> BrokenEngine) : FakeEngineDriver() {
        private lateinit var engine: BrokenEngine

        override fun createEngine(config: CameraConfiguration): CameraEngine =
            breakIt(super.createEngine(config) as FakeCameraEngine).also { engine = it }

        override fun releaseCount(): Int = engine.releaseCount

        override suspend fun settle() {
            super.settle()
            engine.sync()
        }
    }

    // The fake with one rule broken. Flows it overrides follow the fake's at every settle, through sync().
    private abstract class BrokenEngine(val fake: FakeCameraEngine) : CameraEngine by fake {
        open val releaseCount: Int get() = fake.releaseCount

        open fun sync() = Unit
    }

    private enum class Mirrored { TORCH, ZOOM, FLASH }

    // Rule 5 broken: a successful command writes the requested value into hardware or zoom.
    private class MirroringEngine(fake: FakeCameraEngine, private val mirrored: Mirrored) : BrokenEngine(fake) {
        private val _hardware = MutableStateFlow(fake.hardware.value)
        override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()
        private val _zoom = MutableStateFlow(fake.zoom.value)
        override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()
        private var torch: TorchMode? = null
        private var flash: FlashMode? = null
        private var ratio: ZoomState? = null

        override fun sync() {
            val reported = fake.hardware.value
            _hardware.value = HardwareState(
                flash ?: reported.flashMode,
                torch ?: reported.torchMode,
                reported.camera,
                reported.targetOrientation,
            )
            _zoom.value = ratio ?: fake.zoom.value
        }

        override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> {
            val result = fake.setTorchMode(mode)
            if (mirrored == Mirrored.TORCH && result is CameraKResult.Success) torch = mode
            sync()
            return result
        }

        override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
            val result = fake.setFlashMode(mode)
            if (mirrored == Mirrored.FLASH && result is CameraKResult.Success) flash = mode
            sync()
            return result
        }

        override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> {
            val result = fake.setZoom(ratio)
            if (mirrored == Mirrored.ZOOM && result is CameraKResult.Success) this.ratio = result.value
            sync()
            return result
        }
    }

    // Rule 7 broken: close releases the camera at once, before the recording it ends is finalized.
    private class ReleasesAtClose(fake: FakeCameraEngine) : BrokenEngine(fake) {
        override var releaseCount: Int = 0

        override fun close() {
            fake.close()
            releaseCount = 1
        }

        override suspend fun closeAndJoin() = close()
    }

    // Rule 2 broken: a start cancelled while the platform holds the open leaves the engine at Opening.
    private class StaysOpeningWhenCancelled(fake: FakeCameraEngine) : BrokenEngine(fake) {
        private val _state = MutableStateFlow(fake.state.value)
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        private var stuck = false

        override fun sync() {
            if (!stuck) _state.value = fake.state.value
        }

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = try {
            fake.start(desired)
        } catch (e: CancellationException) {
            stuck = true
            throw e
        } finally {
            sync()
        }
    }

    // HardwareState broken: after any bind the engine reports its first back camera, whichever it opened on.
    private class ReportsTheBackCamera(fake: FakeCameraEngine) : BrokenEngine(fake) {
        private val back: LensInfo = fake.capabilities.value.lenses(CameraLens.BACK).first()
        private val _hardware = MutableStateFlow(fake.hardware.value)
        override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

        override fun sync() {
            val reported = fake.hardware.value
            _hardware.value = if (reported.camera == null) {
                reported
            } else {
                HardwareState(reported.flashMode, reported.torchMode, back, reported.targetOrientation)
            }
        }

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = fake.start(desired).also { sync() }

        override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> =
            fake.selectCamera(selection).also { sync() }
    }

    // Rule 5 broken: setTorchMode reports success without passing the command to the platform.
    private class SwallowsTorchCommands(fake: FakeCameraEngine) : BrokenEngine(fake) {
        override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = CameraKResult.Success(mode)
    }

    // Rule 5 broken the other way: setFlashMode is refused while Open, so a camera with one flash mode never takes it.
    private class RefusesFlashWhileOpen(fake: FakeCameraEngine) : BrokenEngine(fake) {
        override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> =
            if (fake.state.value == EngineState.Open) {
                CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.FLASH))
            } else {
                fake.setFlashMode(mode)
            }
    }

    // Rule 5 broken: a successful setFlashMode writes a mode into hardware, and not the one it was given, so even
    // a command that asks for the mode the platform already holds moves hardware before a report.
    private class MirrorsAnotherFlashMode(fake: FakeCameraEngine) : BrokenEngine(fake) {
        private val _hardware = MutableStateFlow(fake.hardware.value)
        override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()
        private var written: FlashMode? = null

        override fun sync() {
            val reported = fake.hardware.value
            _hardware.value =
                HardwareState(
                    written ?: reported.flashMode,
                    reported.torchMode,
                    reported.camera,
                    reported.targetOrientation,
                )
        }

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = fake.start(desired).also { sync() }

        override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
            val result = fake.setFlashMode(mode)
            if (result is CameraKResult.Success) written = FlashMode.entries.first { it != mode }
            sync()
            return result
        }
    }

    // Selection broken: the platform binds the front camera whatever was selected, while hardware reports the
    // selected one.
    private class BindsTheFrontReportsTheBack(fake: FakeCameraEngine) : BrokenEngine(fake) {
        private val back: LensInfo = fake.capabilities.value.lenses(CameraLens.BACK).first()
        private val front: LensInfo = fake.capabilities.value.lenses(CameraLens.FRONT).first()
        private val _hardware = MutableStateFlow(fake.hardware.value)
        override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

        override fun sync() {
            val reported = fake.hardware.value
            _hardware.value = if (reported.camera == null) {
                reported
            } else {
                HardwareState(reported.flashMode, reported.torchMode, back, reported.targetOrientation)
            }
        }

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
            val onFront = DesiredHardware(
                CameraSelection.ById(front.id),
                desired.flashMode,
                desired.torchMode,
                desired.zoomRatio,
                desired.targetOrientation,
            )
            return fake.start(onFront).also { sync() }
        }
    }
}
