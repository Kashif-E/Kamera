package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kashif.cameraK.enums.DeviceOrientation

@Stable
interface CameraPreviewScope : BoxScope {
    val deviceOrientation: DeviceOrientation
    fun Modifier.keepUpright(): Modifier
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
