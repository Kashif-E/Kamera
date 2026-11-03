package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.test.TestScope

/** H1' red stub: a holder over a [FakeCameraEngine] on the test scheduler. */
public fun TestScope.fakeCameraKStateHolder(
    config: CameraConfiguration = CameraConfiguration(),
    fake: FakeCameraEngine = FakeCameraEngine(),
    plugins: List<CameraKPlugin> = emptyList(),
    permissionChecker: CameraKPermissionChecker = FakePermissionChecker(),
): CameraKStateHolder = TODO("H1' red stub: fakeCameraKStateHolder")
