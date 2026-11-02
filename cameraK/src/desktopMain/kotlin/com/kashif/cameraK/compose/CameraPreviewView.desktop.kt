package com.kashif.cameraK.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.previewAspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the camera's frames letterboxed to their own aspect ratio, with the overlay in the same box
 * as the frame, so overlay coordinates match the frame a capture writes. Desktop captures the
 * camera's whole frame (no crop to the configured ratio), so the frame's ratio is the one captures
 * have; until the first frame arrives the box takes the configured ratio.
 */
@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    val renderer = remember(controller) { PreviewFrameRenderer() }
    // Read only while drawing, so a new frame redraws the preview without recomposing anything.
    var frameCount by remember(controller) { mutableIntStateOf(0) }
    // Written only when the camera's frame size changes, so layout runs then and not per frame.
    var frameSize by remember(controller) { mutableStateOf<IntSize?>(null) }

    LaunchedEffect(renderer) {
        withContext(Dispatchers.Default) {
            try {
                controller.frameFlow.collect { frame ->
                    renderer.render(frame)
                    val size = frameSize
                    if (size == null || size.width != frame.width || size.height != frame.height) {
                        frameSize = IntSize(frame.width, frame.height)
                    }
                    frameCount++
                }
            } finally {
                renderer.close()
            }
        }
    }

    val ratio = frameSize?.let { it.width.toFloat() / it.height }
        ?: controller.getAspectRatio().previewAspectRatio(DeviceOrientation.LANDSCAPE_LEFT)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.aspectRatio(ratio)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                frameCount
                renderer.drawLatest { bitmap -> drawFitted(bitmap) }
            }
            if (overlay != null) {
                val scope = remember(this, deviceOrientation) { CameraPreviewScopeImpl(this, deviceOrientation) }
                scope.overlay()
            }
        }
    }
}

/** Draws [bitmap] scaled to fit and centred, as `Image` does with its default `ContentScale.Fit`. */
private fun DrawScope.drawFitted(bitmap: ImageBitmap) {
    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
    val fitted = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
    drawImage(
        image = bitmap,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(
            (size.width - fitted.width).roundToInt() / 2,
            (size.height - fitted.height).roundToInt() / 2,
        ),
        dstSize = fitted,
    )
}
