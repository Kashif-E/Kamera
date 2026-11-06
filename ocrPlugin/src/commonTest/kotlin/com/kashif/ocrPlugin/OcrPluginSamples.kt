package com.kashif.ocrPlugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.compose.rememberCameraKStateHolder
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState

// KDoc samples for OcrPlugin, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun RecognizeLiveTextSample(onText: (String) -> Unit) {
    val ocrPlugin = rememberOcrPlugin(OcrConfig { maxFps = 2f })
    var recognizedText by remember { mutableStateOf<String?>(null) }
    val holder = rememberCameraKStateHolder(plugins = listOf(ocrPlugin))
    val cameraState by holder.cameraState.collectAsState()

    LaunchedEffect(ocrPlugin) {
        ocrPlugin.results.collect { recognized ->
            recognizedText = recognized.text
            onText(recognized.text)
        }
    }

    if (cameraState is CameraKState.Ready) {
        // Show the preview and recognizedText.
    }
}

internal suspend fun recognizeStillSample(ocrPlugin: OcrPlugin, image: CapturedImage): String? =
    when (val result = ocrPlugin.recognize(image)) {
        is CameraKResult.Success -> result.value.text
        is CameraKResult.Failure -> null
    }
