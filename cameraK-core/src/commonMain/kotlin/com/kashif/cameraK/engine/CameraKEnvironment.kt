package com.kashif.cameraK.engine

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Job
import kotlin.time.TimeSource

/**
 * What a holder and its engine run on: dispatchers, the clock, the parent of every coroutine they
 * start, and the permission checker. Apps keep the defaults; tests pass camerak-testing's
 * `cameraKTestEnvironment()`, which puts all of it on the test scheduler.
 *
 * @property parentJob The parent of the holder's and the engine's scopes; null makes them roots.
 * @property permissionChecker What the holder checks the camera permission with before it opens. Null
 *   means no check: every permission counts as Granted, and an open the platform refuses is reported
 *   by the engine. The holder's production constructor passes the platform's checker.
 */
@CameraKEngineApi
public class CameraKEnvironment(
    public val dispatchers: CameraKDispatchers = CameraKDispatchers(),
    public val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    public val parentJob: Job? = null,
    public val permissionChecker: CameraKPermissionChecker? = null,
) {
    /** Worker threads, backoff, tracer and the iOS version override. Core's tests replace it; consumers never see it. */
    internal var runtime: EngineRuntime = EngineRuntime.Default
}
