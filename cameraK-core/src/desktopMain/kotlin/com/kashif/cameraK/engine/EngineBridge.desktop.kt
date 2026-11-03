@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.DefaultDeviceSource
import com.kashif.cameraK.controller.DesktopCameraEngine
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The desktop engine over the system's default camera, with the 1.x facade over itself for
 * `Ready.controller`. The platform context's grabber factory is not read until D2'.
 */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = DesktopCameraEngine(config, environment, DefaultDeviceSource(config.targetResolution))

// The bridge no longer uses it; LegacyControllerEngineTest does, until C1' removes both (w3 decisions §4).

/** Every call on [io]; close waits until the grab loop has released the camera. */
internal class DesktopLegacySession(private val controller: CameraController, private val io: CoroutineDispatcher) :
    LegacySession {
    override suspend fun start() = withContext(io) { controller.startSession() }

    override suspend fun stop() = withContext(io) { controller.stopSession() }

    override suspend fun close() = withContext(io) { controller.closeAndJoin() }

    override suspend fun <T> onSessionThread(block: () -> T): T = withContext(io) { block() }
}
