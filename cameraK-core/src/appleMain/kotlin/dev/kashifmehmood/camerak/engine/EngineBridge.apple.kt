@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.controller.AppleCameraEngine
import dev.kashifmehmood.camerak.controller.CustomCameraController
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

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
