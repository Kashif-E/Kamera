package com.kashif.cameraK.engine.contract

import kotlinx.coroutines.test.TestResult
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The shared engine contract (`CameraEngine`'s KDoc), run by every engine through its driver. A
 * case whose sub-interface the driver lacks is skipped and listed, so [requires] is what stops a
 * real engine from skipping it.
 */
internal abstract class CameraEngineContractTest(
    private val driver: EngineContractDriver,
    /** The sub-interfaces this engine must implement from its slice on. */
    private val requires: Set<KClass<out EngineContractDriver>>,
) {
    private val skips = ContractSkips()
    private val cases = ContractCases(driver, requires, skips)

    @AfterTest
    fun printSkippedSteps() {
        if (skips.skipped.isNotEmpty()) println(skips.report())
    }

    @Test
    fun driverImplementsEveryRequiredSubInterface() = cases.driverImplementsEveryRequiredSubInterface()

    @Test
    fun startReturnsOnlyAfterPlatformOpen(): TestResult = driver.runCase {
        cases.startReturnsOnlyAfterPlatformOpen(this)
    }

    @Test
    fun stateIsOpenOrInterruptedWhenStartReturnsSuccess(): TestResult =
        driver.runCase { cases.stateIsOpenOrInterruptedWhenStartReturnsSuccess() }

    @Test
    fun failedStartPublishesFailedBeforeReturningFailure(): TestResult =
        driver.runCase { cases.failedStartPublishesFailedBeforeReturningFailure(this) }

    @Test
    fun unsupportedConfigurationStartPublishesFailedAndReturnsUnsupported(): TestResult =
        driver.runCase { cases.unsupportedConfigurationStartPublishesFailedAndReturnsUnsupported() }

    @Test
    fun interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(): TestResult =
        driver.runCase { cases.interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(this) }

    @Test
    fun endInterruptionAfterStopLeavesEngineClosed(): TestResult =
        driver.runCase { cases.endInterruptionAfterStopLeavesEngineClosed() }

    @Test
    fun stopDuringHeldStartLeavesCameraClosedWhenOpenArrivesLate(): TestResult =
        driver.runCase { cases.stopDuringHeldStartLeavesCameraClosedWhenOpenArrivesLate(this) }

    @Test
    fun cancelledStartLeavesCameraClosed(): TestResult = driver.runCase { cases.cancelledStartLeavesCameraClosed(this) }

    @Test
    fun callbacksAfterCloseDoNothing(): TestResult = driver.runCase { cases.callbacksAfterCloseDoNothing() }

    @Test
    fun callsAfterCloseReturnClosed(): TestResult = driver.runCase { cases.callsAfterCloseReturnClosed() }

    @Test
    fun closeIsIdempotentAndReleasesOnce(): TestResult = driver.runCase { cases.closeIsIdempotentAndReleasesOnce() }

    @Test
    fun hardwareChangesOnlyFromPlatformObserver(): TestResult =
        driver.runCase { cases.hardwareChangesOnlyFromPlatformObserver() }

    @Test
    fun desiredHardwareReappliedAfterRestartAndCameraSwitch(): TestResult =
        driver.runCase { cases.desiredHardwareReappliedAfterRestartAndCameraSwitch() }

    @Test
    fun noCallbackOfAFailedGenerationChangesState(): TestResult =
        driver.runCase { cases.noCallbackOfAFailedGenerationChangesState() }

    @Test
    fun stopAfterFailedPublishesClosedAndStartBeginsANewGeneration(): TestResult =
        driver.runCase { cases.stopAfterFailedPublishesClosedAndStartBeginsANewGeneration() }

    @Test
    fun closeDuringRecordingFinalizesWithShutdownSuccess(): TestResult =
        driver.runCase { cases.closeDuringRecordingFinalizesWithShutdownSuccess(this) }

    @Test
    fun stopDuringRecordingFinalizesWithShutdownSuccess(): TestResult =
        driver.runCase { cases.stopDuringRecordingFinalizesWithShutdownSuccess(this) }

    @Test
    fun closeCompletesAPendingCaptureWithClosed(): TestResult =
        driver.runCase { cases.closeCompletesAPendingCaptureWithClosed(this) }

    @Test
    fun throwingPlatformCallbackDoesNotEscape(): TestResult =
        driver.runCase { cases.throwingPlatformCallbackDoesNotEscape(this) }
}
