@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.AppleCameraEngine
import com.kashif.cameraK.controller.CustomCameraController
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi

/** The iOS engine over its own session. */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = AppleCameraEngine(
    config = config,
    environment = environment,
    custom = CustomCameraController(
        qualityPrioritization = config.qualityPrioritization,
        initialCameraLens = config.cameraLens ?: CameraLens.BACK,
        aspectRatio = config.aspectRatio,
        targetResolution = config.targetResolution?.let { it.width to it.height },
        mirrorFrontCamera = config.mirrorFrontCamera,
    ),
)
