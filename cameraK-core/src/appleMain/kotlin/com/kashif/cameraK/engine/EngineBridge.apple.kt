@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.builder.createIOSCameraControllerBuilder
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVCaptureVideoPreviewLayer

/** Until the iOS engine lands: the 1.x controller, built as `rememberCameraKState` built it. */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine {
    val controller = createIOSCameraControllerBuilder()
        .apply {
            setFlashMode(config.flashMode)
            setTorchMode(config.torchMode)
            setCameraLens(config.cameraLens)
            setImageFormat(config.imageFormat)
            setQualityPrioritization(config.qualityPrioritization)
            setPreferredCameraDeviceType(config.cameraDeviceType)
            setAspectRatio(config.aspectRatio)
            setDirectory(config.directory)
            setMirrorFrontCamera(config.mirrorFrontCamera)
            config.targetResolution?.let { (width, height) -> setResolution(width, height) }
        }.build()
    return LegacyControllerEngine(
        legacyController = controller,
        preview = InertPreviewSource,
        session = AppleLegacySession(controller, environment.dispatchers.main),
        environment = environment,
    )
}

/** Every call on the main thread: the 1.x controller is a UIViewController. */
internal class AppleLegacySession(private val controller: CameraController, private val main: CoroutineDispatcher) :
    LegacySession {
    override suspend fun start() = withContext(main) { controller.startSession() }

    override suspend fun stop() = withContext(main) { controller.stopSession() }

    override suspend fun close() = withContext(main) { controller.cleanup() }

    override suspend fun <T> onSessionThread(block: () -> T): T = withContext(main) { block() }
}

// Nothing reads it: camerak's iOS preview hosts Ready.controller until the engine lands.
private object InertPreviewSource : PlatformPreviewSource {
    override fun createPreviewLayer(): AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer()
}
