package com.kashif.cameraK.compose

import androidx.compose.runtime.saveable.Saver
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode

/** The user's camera choices, saved across process death. */
internal data class CameraUserState(
    val lens: CameraLens,
    val flashMode: FlashMode,
    val torchMode: TorchMode,
    val zoomRatio: Float,
)

internal val CameraUserStateSaver: Saver<CameraUserState?, Any> = Saver(
    save = { TODO("LC'") },
    restore = { TODO("LC'") },
)
