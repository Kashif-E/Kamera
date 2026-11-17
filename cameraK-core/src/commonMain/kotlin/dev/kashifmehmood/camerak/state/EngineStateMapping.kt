@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.state

import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/**
 * The holder's state for this engine state, given the holder's [current] state. Closed and Opening leave [current] as it is: the holder writes Idle and Starting itself, so
 * neither the Closed an engine replays to a new session nor a stop's Closed can overwrite them.
 * Nothing leaves Closed.
 */
internal fun EngineState.toCameraKState(current: CameraKState): CameraKState = when {
    current is CameraKState.Closed -> current
    this is EngineState.Open -> CameraKState.Ready
    this is EngineState.Interrupted -> CameraKState.Interrupted(reason)
    this is EngineState.Failed -> CameraKState.Error(error)
    else -> current
}
