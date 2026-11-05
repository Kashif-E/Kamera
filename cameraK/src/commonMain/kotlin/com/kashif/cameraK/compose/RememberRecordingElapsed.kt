package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.kashif.cameraK.state.CameraKStateHolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The holder's recorded time, updated once per [tick]. */
@Composable
public fun rememberRecordingElapsed(holder: CameraKStateHolder, tick: Duration = 1.seconds): State<Duration> =
    TODO("LC'")
