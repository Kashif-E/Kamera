package com.kashif.cameraK.controls

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKStateHolder

public suspend fun CameraKStateHolder.cycleFlashMode(): CameraKResult<FlashMode> = TODO("H2' red stub")

public suspend fun CameraKStateHolder.toggleTorch(): CameraKResult<TorchMode> = TODO("H2' red stub")

public suspend fun CameraKStateHolder.toggleLens(): CameraKResult<LensInfo> = TODO("H2' red stub")

internal fun nextFlashMode(current: FlashMode, supported: Set<FlashMode>): FlashMode = TODO("H2' red stub")
