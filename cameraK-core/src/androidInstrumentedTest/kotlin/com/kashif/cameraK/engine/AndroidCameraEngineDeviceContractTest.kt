package com.kashif.cameraK.engine

import android.Manifest
import androidx.test.rule.GrantPermissionRule
import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import org.junit.Rule

/**
 * The engine contract on a device, over real CameraX: the release gate's device subset (design
 * §12.4 B1). verify.sh only compiles it; the maintainer runs the five cases that call no fault hook
 * and need no sub-interface on a Pixel 7:
 *
 * ```
 * ./gradlew :cameraK-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest#stateIsOpenOrInterruptedWhenStartReturnsSuccess,\
 * com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest#callsAfterCloseReturnClosed,\
 * com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest#closeIsIdempotentAndReleasesOnce,\
 * com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest#reportedCameraIsTheOneTheEngineOpenedOn,\
 * com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest#desiredHardwareReappliedAfterRestartAndCameraSwitch
 * ```
 *
 * Run whole, the other cases report as skipped: each calls a fault hook, which [DeviceEngineDriver]
 * cannot honour, or needs a sub-interface it does not implement.
 */
internal class AndroidCameraEngineDeviceContractTest :
    CameraEngineContractTest(DeviceEngineDriver(), requires = emptySet()) {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
}
