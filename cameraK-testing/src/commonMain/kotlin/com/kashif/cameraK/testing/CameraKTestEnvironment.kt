package com.kashif.cameraK.testing

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * An environment for core and plugin tests that build a holder through the engine constructor.
 * Every dispatcher is on the test scheduler and the time source is the scheduler's: one clock, not
 * two.
 *
 * The parent job is a fresh SupervisorJob under `backgroundScope`, per call. backgroundScope's job
 * is not a child of the test's job, so runTest does not wait for a holder the test never closes,
 * and cancels it when the test body ends. It is a child rather than backgroundScope's job itself so
 * that its children are CameraK's scopes only, not the test's own background collectors.
 */
@CameraKEngineApi
public fun TestScope.cameraKTestEnvironment(
    permissionChecker: CameraKPermissionChecker = FakePermissionChecker(),
): CameraKEnvironment {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return CameraKEnvironment(
        dispatchers = CameraKDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
        timeSource = testScheduler.timeSource,
        parentJob = SupervisorJob(backgroundScope.coroutineContext.job),
        permissionChecker = permissionChecker,
    )
}
