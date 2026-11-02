package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kashif.cameraK.enums.DeviceOrientation

@Stable
interface CameraPreviewScope : BoxScope {
    val deviceOrientation: DeviceOrientation
    fun Modifier.keepUpright(): Modifier
}

/**
 * Runs [overlay] over this box with a [CameraPreviewScope]; every platform's CameraPreviewView calls
 * it. The scope is kept while the box and orientation stay the same, so the overlay skips when the
 * preview recomposes for anything else.
 */
@Suppress("NOTHING_TO_INLINE")
@Composable
internal inline fun BoxScope.CameraPreviewOverlay(
    deviceOrientation: DeviceOrientation,
    noinline overlay: @Composable CameraPreviewScope.() -> Unit,
) {
    val scope = remember(this, deviceOrientation) { CameraPreviewScopeImpl(this, deviceOrientation) }
    scope.overlay()
}

internal class CameraPreviewScopeImpl(
    private val boxScope: BoxScope,
    override val deviceOrientation: DeviceOrientation,
) : CameraPreviewScope,
    BoxScope by boxScope {

    override fun Modifier.keepUpright(): Modifier = this.then(
        Modifier.graphicsLayer {
            rotationZ = deviceOrientation.compensationDegrees
        },
    )
}
