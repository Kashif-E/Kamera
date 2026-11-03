@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
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

    @Test
    fun everyHardwareStepRunsWhenTheCamerasAllowIt() = runTest {
        val skips = ContractSkips()
        val cases = ContractCases(FakeEngineDriver(), requires = emptySet(), skips = skips)

        cases.hardwareChangesOnlyFromPlatformObserver()
        cases.desiredHardwareReappliedAfterRestartAndCameraSwitch()

        assertEquals(emptyList(), skips.skipped)
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

    // A platform that never says which camera it bound gives the hardware cases nothing to compare against.
    private class NoBoundCameraDriver(private val delegate: FakeEngineDriver) : EngineContractDriver by delegate {
        override fun boundSettings(): BoundSettings = delegate.boundSettings().copy(cameraId = null)
    }
}
