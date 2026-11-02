package com.kashif.ocrPlugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.cameraK.compose.rememberCameraKState
import com.kashif.cameraK.state.CameraKState

// KDoc samples for OcrPlugin, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun RecognizeLiveTextSample(onText: (String) -> Unit) {
    val ocrPlugin = rememberOcrPlugin()
    var recognizedText by remember { mutableStateOf<String?>(null) }
    val cameraState by rememberCameraKState { stateHolder ->
        stateHolder.attachPlugin(ocrPlugin)
    }

    LaunchedEffect(ocrPlugin) {
        ocrPlugin.textFlow.collect { text ->
            recognizedText = text
            onText(text)
        }
    }

    if (cameraState is CameraKState.Ready) {
        // Show the preview and recognizedText.
    }
}
