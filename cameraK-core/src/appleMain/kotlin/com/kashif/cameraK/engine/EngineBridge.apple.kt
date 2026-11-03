@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.AppleCameraEngine
import com.kashif.cameraK.controller.CustomCameraController
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi

/** The iOS engine over its own session; it carries the 1.x facade over itself to Ready.controller. */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = AppleCameraEngine(
    config = config,
    environment = environment,
    custom = CustomCameraController(
        qualityPrioritization = config.qualityPrioritization,
        initialCameraLens = config.cameraLens,
        aspectRatio = config.aspectRatio,
        targetResolution = config.targetResolution,
        mirrorFrontCamera = config.mirrorFrontCamera,
    ),
)
