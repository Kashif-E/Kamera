package com.kashif.cameraK.testing

import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope

/** S2' red: the declaration only. */
@CameraKEngineApi
public fun TestScope.cameraKTestEnvironment(
    permissionChecker: CameraKPermissionChecker = FakePermissionChecker(),
): CameraKEnvironment = TODO("S2' red $permissionChecker")
