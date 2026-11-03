@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The hardware cases take their values from the engine's capabilities: an engine runs the steps
 * its cameras allow, the rest are listed as skipped, and a case with nothing left to run fails.
 */
class ContractKitCapabilitiesTest {
    // What the desktop engine offers before it enumerates devices: one camera, no torch, fixed zoom.
    private val oneFixedCamera = CameraCapabilities(
        allLenses = listOf(
            LensInfo(
                id = "only",
                deviceType = CameraDeviceType.DEFAULT,
                lens = CameraLens.BACK,
                minZoom = 1f,
                maxZoom = 1f,
                hasFlash = false,
                isLogical = false,
            ),
        ),
        flashModes = mapOf("only" to setOf(FlashMode.OFF)),
        torchModes = mapOf("only" to setOf(TorchMode.OFF)),
        supportsPauseResume = false,
        supportedVideoQualities = emptySet(),
        supportsVideoBitrate = false,
        supportedAspectRatios = AspectRatio.entries.toSet(),
        supportedImageFormats = setOf(ImageFormat.JPEG),
        supportedAnalysisFormats = setOf(FrameFormat.YUV_420),
        supportsSquareCapture = false,
    )

    // Two cameras that both take every flash and torch mode and share a zoom range, so a camera switch
    // can re-apply everything the first camera was given.
    private val twoFullCameras = CameraCapabilities(
        allLenses = listOf(
            fullLens("wide", CameraLens.BACK, maxZoom = 10f),
            fullLens("selfie", CameraLens.FRONT, maxZoom = 4f),
        ),
        flashModes = mapOf("wide" to FlashMode.entries.toSet(), "selfie" to FlashMode.entries.toSet()),
        torchModes = mapOf("wide" to TorchMode.entries.toSet(), "selfie" to TorchMode.entries.toSet()),
        supportsPauseResume = true,
        supportedVideoQualities = emptySet(),
        supportsVideoBitrate = false,
        supportedAspectRatios = AspectRatio.entries.toSet(),
        supportedImageFormats = setOf(ImageFormat.JPEG),
        supportedAnalysisFormats = setOf(FrameFormat.YUV_420),
        supportsSquareCapture = false,
    )

    private fun fullLens(id: String, lens: CameraLens, maxZoom: Float) = LensInfo(
        id = id,
        deviceType = CameraDeviceType.WIDE_ANGLE,
        lens = lens,
        minZoom = 1f,
        maxZoom = maxZoom,
        hasFlash = true,
        isLogical = false,
    )

    @Test
    fun everyHardwareStepRunsWhenTheCamerasAllowIt() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(FakeEngineDriver(twoFullCameras), requires = emptySet(), skips = skips)

        cases.hardwareChangesOnlyFromPlatformObserver()
        cases.desiredHardwareReappliedAfterRestartAndCameraSwitch()

        assertEquals(emptyList(), skips.skipped)
    }

    @Test
    fun cameraSwitchListsTheReappliedValuesTheOtherCameraCannotTake() = runTest {
        // The back camera takes flash ON and torch ON; the front one supports OFF only and a narrower zoom range.
        val skips = ContractSkips()
        val cases = ContractCases(FakeEngineDriver(), requires = emptySet(), skips = skips)

        cases.desiredHardwareReappliedAfterRestartAndCameraSwitch()

        assertEquals(
            listOf(
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: flash after the switch",
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: torch after the switch",
            ),
            skips.skipped.map { it.substringBefore(" skipped (") },
        )
    }

    @Test
    fun oneCameraWithoutTorchOrZoomSkipsAndListsTheStepsItCannotRun() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(FakeEngineDriver(oneFixedCamera), requires = emptySet(), skips = skips)

        cases.hardwareChangesOnlyFromPlatformObserver()
        cases.desiredHardwareReappliedAfterRestartAndCameraSwitch()

        assertEquals(
            listOf(
                "hardwareChangesOnlyFromPlatformObserver: torch",
                "hardwareChangesOnlyFromPlatformObserver: zoom",
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: flash after restart",
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: torch after restart",
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: zoom after restart",
                "desiredHardwareReappliedAfterRestartAndCameraSwitch: camera switch",
            ),
            skips.skipped.map { it.substringBefore(" skipped (") },
        )
    }

    @Test
    fun aCaseWhoseEveryStepIsSkippedFailsWithNothingToVerify() = runTest {
        val skips = ContractSkips()
        val cases =
            ContractCases(NoBoundCameraDriver(FakeEngineDriver(oneFixedCamera)), requires = emptySet(), skips = skips)

        val failure = assertFailsWith<AssertionError> { cases.hardwareChangesOnlyFromPlatformObserver() }

        assertContains(failure.message.orEmpty(), "nothing to verify on this engine")
        assertEquals(3, skips.skipped.size, "every step is listed: ${skips.skipped}")
    }

    @Test
    fun commandsCaseRunsTheFlashStepOnAnEngineWithNothingAdjustable() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(FakeEngineDriver(oneFixedCamera), requires = emptySet(), skips = skips)

        cases.commandsLeaveHardwareUntilThePlatformReports()

        assertEquals(
            listOf(
                "commandsLeaveHardwareUntilThePlatformReports: setTorchMode",
                "commandsLeaveHardwareUntilThePlatformReports: setZoom",
                "commandsLeaveHardwareUntilThePlatformReports: setFlashMode",
            ),
            skips.skipped.map { it.substringBefore(" skipped (") },
        )
    }

    @Test
    fun commandsCaseWithNoBoundCameraSkipsTheFlashStepAndFailsWithNothingToVerify() = runTest {
        val skips = ContractSkips()
        val cases =
            ContractCases(NoBoundCameraDriver(FakeEngineDriver(oneFixedCamera)), requires = emptySet(), skips = skips)

        val failure = assertFailsWith<AssertionError> { cases.commandsLeaveHardwareUntilThePlatformReports() }

        assertContains(failure.message.orEmpty(), "nothing to verify on this engine")
        assertContains(
            skips.skipped.map { it.substringBefore(" skipped (") },
            "commandsLeaveHardwareUntilThePlatformReports: setFlashMode to the reported mode",
        )
    }

    @Test
    fun flashStepsOverAReadBackDriverExpectTheReadBackRightAfterTheCommand() = runTest {
        val skips = ContractSkips()
        val driver = ReadBackFlashFakeDriver(twoFullCameras)
        val cases = ContractCases(driver, requires = emptySet(), skips = skips)

        cases.commandsLeaveHardwareUntilThePlatformReports()

        assertEquals(emptyList(), skips.skipped, "both flash steps ran")
        assertEquals(emptyList(), driver.flashReports, "the kit reported flash through changeHardware")
    }

    @Test
    fun flashStepsOverAnObserverDriverAreUnchanged() = runTest {
        val skips = ContractSkips()
        val driver = ReportsRecordingDriver(twoFullCameras)
        val cases = ContractCases(driver, requires = emptySet(), skips = skips)

        cases.commandsLeaveHardwareUntilThePlatformReports()

        assertEquals(emptyList(), skips.skipped, "both flash steps ran")
        // The reported mode first, then the second mode the bound camera takes, each as a platform observer report.
        assertEquals(listOf(FlashMode.OFF, FlashMode.ON), driver.flashReports)
    }

    @Test
    fun preOpenInterruptionCaseIsSkippedAndListedForAnEngineWithoutIt() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(InterruptsOnlyWhenOpenDriver(), requires = emptySet(), skips = skips)

        cases.interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(this)

        assertEquals(
            listOf(
                "interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall: every step skipped " +
                    "(the engine has no interruption before Open)",
            ),
            skips.skipped,
        )
    }

    @Test
    fun preOpenInterruptionCaseTakesThePlatformsOwnReason() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(OwnInterruptionReasonDriver(), requires = emptySet(), skips = skips)

        cases.interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(this)

        assertEquals(emptyList(), skips.skipped, "the case ran over a driver with interruption before Open")
    }

    @Test
    fun cameraCaseReadsCapabilitiesTheEngineEnumeratesAfterConstruction() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(EnumeratesOnSettleDriver(), requires = emptySet(), skips = skips)

        cases.reportedCameraIsTheOneTheEngineOpenedOn()

        assertEquals(emptyList(), skips.skipped)
    }

    @Test
    fun observerCaseReadsCapabilitiesTheEngineEnumeratesAfterConstruction() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(EnumeratesOnSettleDriver(), requires = emptySet(), skips = skips)

        cases.hardwareChangesOnlyFromPlatformObserver()

        assertEquals(emptyList(), skips.skipped)
    }

    // The fake driver as K1 left it, listing the flash values the kit reports as platform observer reports.
    private class ReportsRecordingDriver(capabilities: CameraCapabilities) : FakeEngineDriver(capabilities) {
        val flashReports = mutableListOf<FlashMode>()

        override fun changeHardware(change: HardwareChange) {
            change.flashMode?.let(flashReports::add)
            super.changeHardware(change)
        }
    }

    // A platform that never says which camera it bound gives the hardware cases nothing to compare against.
    private class NoBoundCameraDriver(private val delegate: FakeEngineDriver) : EngineContractDriver by delegate {
        override fun boundSettings(): BoundSettings = delegate.boundSettings().copy(cameraId = null)
    }

    // The desktop engine's driver: the platform interrupts an open session only, never a start it holds.
    private class InterruptsOnlyWhenOpenDriver(private val delegate: FakeEngineDriver = FakeEngineDriver()) :
        EngineContractDriver by delegate {
        private lateinit var engine: CameraEngine

        override fun createEngine(config: CameraConfiguration): CameraEngine =
            delegate.createEngine(config).also { engine = it }

        override fun interrupt(reason: InterruptionReason) {
            check(engine.state.value == EngineState.Open) {
                "this engine has no interruption before Open"
            }
            delegate.interrupt(reason)
        }
    }

    // A platform that maps every interruption to a reason of its own, as CameraX and AVFoundation do.
    private class OwnInterruptionReasonDriver : FakeEngineDriver() {
        override fun interrupt(reason: InterruptionReason) = super.interrupt(InterruptionReason.SYSTEM_PRESSURE)
    }

    // An engine that enumerates its cameras after construction, on its scheduler: until the first settle its
    // capabilities are EMPTY, as a platform engine's are while the enumeration it launched is still queued.
    private class EnumeratesOnSettleDriver : FakeEngineDriver() {
        private lateinit var engine: EnumeratesAfterConstruction

        override fun createEngine(config: CameraConfiguration): CameraEngine =
            EnumeratesAfterConstruction(super.createEngine(config) as FakeCameraEngine).also { engine = it }

        override suspend fun settle() {
            super.settle()
            engine.enumerate()
        }
    }

    private class EnumeratesAfterConstruction(private val fake: FakeCameraEngine) : CameraEngine by fake {
        private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
        override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

        fun enumerate() {
            _capabilities.value = fake.capabilities.value
        }
    }
}
