@file:OptIn(CameraKEngineApi::class)

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
import androidx.compose.ui.unit.IntSize
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Draws the engine's frames letterboxed to their own aspect ratio, with the overlay in the frame's
 * box. Frames are converted off the UI thread into [PreviewFrameRenderer]'s two bitmaps; a new frame
 * redraws without recomposing. A null frame (before the first, after stop) draws nothing and keeps
 * the last frame's box, so overlay coordinates do not jump. A new [source] cancels the old collector.
 */
@Composable
internal actual fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
) {
    val renderer = remember(source) { PreviewFrameRenderer() }
    // Read only while drawing, so a new frame redraws the preview without recomposing anything.
    var frameCount by remember(source) { mutableIntStateOf(0) }
    var showing by remember(source) { mutableStateOf(false) }
    // Written only when the frame size changes, so layout runs then and not per frame.
    var frameSize by remember(source) { mutableStateOf<IntSize?>(null) }

    LaunchedEffect(renderer) {
        withContext(Dispatchers.Default) {
            try {
                source.frames.collect { frame ->
                    if (frame == null) {
                        showing = false
                        return@collect
                    }
                    val image = frame.image
                    renderer.render(image)
                    val size = frameSize
                    if (size == null || size.width != image.width || size.height != image.height) {
                        frameSize = IntSize(image.width, image.height)
                    }
                    showing = true
                    frameCount++
                }
            } finally {
                renderer.close()
            }
        }
    }

    val size = frameSize
    val frameBox = size?.let { Modifier.aspectRatio(it.width.toFloat() / it.height) } ?: Modifier.fillMaxSize()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = frameBox) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                frameCount
                if (!showing || size == null) return@Canvas
                val target = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                renderer.drawLatest { bitmap ->
                    // Only a frame of the size this box was laid out for: the draw can run before the
                    // recomposition a new size causes, and would stretch the frame for that one draw.
                    if (bitmap.width == size.width && bitmap.height == size.height) {
                        drawImage(bitmap, dstSize = target)
                    }
                }
            }
            if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
        }
    }
}
