@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi

/** The engine a holder built through the production constructor runs on: this platform's own. */
internal expect fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine
