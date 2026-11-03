@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewFrame
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

internal actual fun fakePreviewSource(): PlatformPreviewSource = object : PlatformPreviewSource {
    override val frames: StateFlow<PreviewFrame?> = MutableStateFlow<PreviewFrame?>(fixedFrame()).asStateFlow()
}

// Left half dark, right half light, so scaling and mirroring show in a golden.
private fun fixedFrame(): PreviewFrame {
    val image = BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_3BYTE_BGR)
    for (y in 0 until FRAME_HEIGHT) {
        for (x in 0 until FRAME_WIDTH) image.setRGB(x, y, if (x < FRAME_WIDTH / 2) DARK else LIGHT)
    }
    return PreviewFrame(image, timestampNanos = 0)
}

private const val FRAME_WIDTH = 640
private const val FRAME_HEIGHT = 480
private const val DARK = 0x202020
private const val LIGHT = 0xE0E0E0
