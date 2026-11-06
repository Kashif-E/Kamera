@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope

/**
 * A holder over [fake], on this test's scheduler: the app-facing entry point for tests, needing no
 * opt-in. [fake] checks [config] on start, as a platform would. The holder is cancelled when the
 * test body ends; close it to test closing.
 *
 * @sample com.kashif.cameraK.samples.fakeCameraKStateHolderSample
 */
public fun TestScope.fakeCameraKStateHolder(
    config: CameraConfiguration = CameraConfiguration(),
    fake: FakeCameraEngine = FakeCameraEngine(),
    plugins: List<CameraKPlugin> = emptyList(),
    permissionChecker: CameraKPermissionChecker = FakePermissionChecker(),
): CameraKStateHolder {
    fake.configuration = config
    return CameraKStateHolder(config, { _, _ -> fake }, plugins, cameraKTestEnvironment(permissionChecker))
}
