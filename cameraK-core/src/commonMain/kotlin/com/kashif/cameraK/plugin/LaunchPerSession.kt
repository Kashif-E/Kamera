package com.kashif.cameraK.plugin

import com.kashif.cameraK.state.CameraKState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs [block] once per Ready session, in a child of the context's scope that is cancelled when
 * the camera leaves Ready, and again at the next Ready. This replaces collecting `cameraState`
 * for Ready values in every plugin.
 *
 * @sample com.kashif.cameraK.samples.launchPerSessionSample
 */
public fun CameraKPluginContext.launchPerSession(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
    cameraState.map { it is CameraKState.Ready }.distinctUntilChanged().collectLatest { ready ->
        if (ready) coroutineScope { block() }
    }
}
