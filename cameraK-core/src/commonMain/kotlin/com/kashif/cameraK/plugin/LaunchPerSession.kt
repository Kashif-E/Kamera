package com.kashif.cameraK.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Runs [block] once per Ready session, in a child of the context's scope that is cancelled when
 * the camera leaves Ready, and again at the next Ready.
 */
public fun CameraKPluginContext.launchPerSession(block: suspend CoroutineScope.() -> Unit): Job =
    TODO("P1: launch per Ready session")
