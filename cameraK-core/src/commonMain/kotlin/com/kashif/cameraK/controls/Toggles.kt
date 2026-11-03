package com.kashif.cameraK.controls

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKStateHolder

/** The order [cycleFlashMode] steps through. */
private val FLASH_CYCLE = listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON)

/**
 * Sets the next flash mode in the order OFF, AUTO, ON, skipping the modes the current camera does
 * not offer. Without a bound camera only OFF is offered, so it sets OFF.
 */
public suspend fun CameraKStateHolder.cycleFlashMode(): CameraKResult<FlashMode> {
    val state = uiState.value
    val supported = state.cameraId?.let { capabilities.value.supportedFlashModes(it) } ?: setOf(FlashMode.OFF)
    return setFlashMode(nextFlashMode(state.flashMode ?: FlashMode.OFF, supported))
}

/** Turns the torch ON when it is not ON, and OFF when it is. */
public suspend fun CameraKStateHolder.toggleTorch(): CameraKResult<TorchMode> =
    setTorchMode(if (uiState.value.torchMode == TorchMode.ON) TorchMode.OFF else TorchMode.ON)

/** Switches to the front camera from any other, and to the back camera from the front, device type DEFAULT. */
public suspend fun CameraKStateHolder.toggleLens(): CameraKResult<LensInfo> =
    selectCamera(if (uiState.value.cameraLens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT)

/** The mode after [current] in OFF, AUTO, ON that [supported] contains; OFF when it contains none. */
internal fun nextFlashMode(current: FlashMode, supported: Set<FlashMode>): FlashMode {
    val from = FLASH_CYCLE.indexOf(current)
    return (1..FLASH_CYCLE.size)
        .map { FLASH_CYCLE[(from + it) % FLASH_CYCLE.size] }
        .firstOrNull { it in supported }
        ?: FlashMode.OFF
}
