package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import com.kashif.cameraK.enums.DeviceOrientation

@Stable
public interface CameraPreviewScope : BoxScope {
    public val deviceOrientation: DeviceOrientation

    /**
     * The preview's content rectangle in this overlay's coordinates, in pixels: the area the camera
     * image fills, with any letterbox or pillarbox bars outside it. On every platform the overlay is
     * laid out over this rectangle, so it is the overlay's own box, from (0, 0) to its size, and a
     * point's offset over the overlay divided by this size is where it falls in the image.
     */
    public val contentBounds: Rect

    public fun Modifier.keepUpright(): Modifier
}

/**
 * Runs [overlay] over this box, the content rectangle of [contentBounds], with a [CameraPreviewScope];
 * every platform's EnginePreview reaches it through PreviewContent. The scope is kept while the box,
 * orientation and bounds stay the same, so the overlay skips when the preview recomposes for anything
 * else.
 */
@Suppress("NOTHING_TO_INLINE")
@Composable
internal inline fun BoxScope.CameraPreviewOverlay(
    deviceOrientation: DeviceOrientation,
    contentBounds: Rect,
    noinline overlay: @Composable CameraPreviewScope.() -> Unit,
) {
    val scope = remember(this, deviceOrientation, contentBounds) {
        CameraPreviewScopeImpl(this, deviceOrientation, contentBounds)
    }
    scope.overlay()
}

internal class CameraPreviewScopeImpl(
    private val boxScope: BoxScope,
    override val deviceOrientation: DeviceOrientation,
    override val contentBounds: Rect,
) : CameraPreviewScope,
    BoxScope by boxScope {

    override fun Modifier.keepUpright(): Modifier = this.then(
        Modifier.graphicsLayer {
            rotationZ = deviceOrientation.compensationDegrees
        },
    )
}
