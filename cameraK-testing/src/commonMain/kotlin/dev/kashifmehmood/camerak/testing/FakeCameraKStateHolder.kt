@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.testing

import dev.kashifmehmood.camerak.permissions.CameraKPermissionChecker
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKPlugin
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope

/**
 * A holder over [fake], on this test's scheduler: the app-facing entry point for tests, needing no
 * opt-in. [fake] checks [config] on start, as a platform would. The holder is cancelled when the
 * test body ends; close it to test closing.
 *
 * @sample dev.kashifmehmood.camerak.samples.fakeCameraKStateHolderSample
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
