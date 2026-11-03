@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.DesktopCameraControllerBuilder
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Until the desktop engine lands: the 1.x controller, built as `rememberCameraKState` built it. The
 * platform context's grabber factory is not read yet.
 */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine {
    val controller = DesktopCameraControllerBuilder()
        .apply {
            setImageFormat(config.imageFormat)
            setDirectory(config.directory)
            setAspectRatio(config.aspectRatio)
            setMirrorFrontCamera(config.mirrorFrontCamera)
            config.targetResolution?.let { (width, height) -> setResolution(width, height) }
        }.build()
    return LegacyControllerEngine(
        legacyController = controller,
        preview = InertPreviewSource,
        session = DesktopLegacySession(controller, environment.dispatchers.io),
        environment = environment,
    )
}

/** Every call on [io]; close waits until the grab loop has released the camera. */
internal class DesktopLegacySession(private val controller: CameraController, private val io: CoroutineDispatcher) :
    LegacySession {
    override suspend fun start() = withContext(io) { controller.startSession() }

    override suspend fun stop() = withContext(io) { controller.stopSession() }

    override suspend fun close() = withContext(io) { controller.closeAndJoin() }

    override suspend fun <T> onSessionThread(block: () -> T): T = withContext(io) { block() }
}

// Nothing reads it: camerak's desktop preview collects Ready.controller's frames until the engine lands.
private object InertPreviewSource : PlatformPreviewSource {
    override val frames: StateFlow<PreviewFrame?> = MutableStateFlow<PreviewFrame?>(null).asStateFlow()
}
