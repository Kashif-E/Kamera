package com.kashif.videorecorderplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.kashif.cameraK.compose.rememberCameraKState
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality

// KDoc samples for VideoRecorderPlugin, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun RecordVideoSample(recording: Boolean) {
    val videoPlugin =
        rememberVideoRecorderPlugin(
            config =
            VideoConfiguration(
                quality = VideoQuality.FHD,
                enableAudio = true,
                maxDurationMs = 60_000L,
            ),
        )
    val cameraState by rememberCameraKState(setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(videoPlugin)
    })

    if (cameraState is CameraKState.Ready) {
        if (recording) videoPlugin.startRecording() else videoPlugin.stopRecording()
    }
}
