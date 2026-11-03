@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * The Android engine over CameraX, on the application context. Its 1.x facade is the engine's own
 * [LegacyControllerAccess.legacyController].
 */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = AndroidCameraEngine(config, context.applicationContext, environment)
