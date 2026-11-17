@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/** The engine a holder built through the production constructor runs on: this platform's own. */
internal expect fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine
