@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.builder.createAndroidCameraControllerBuilder
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Until the Android engine lands: the 1.x controller, built as `rememberCameraKState` built it, over
 * the bridge's own [LegacyLifecycleOwner] instead of the Activity's.
 */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine {
    val owner = LegacyLifecycleOwner()
    val controller = createAndroidCameraControllerBuilder(context.applicationContext, owner)
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
        session = AndroidLegacySession(controller, owner, environment.dispatchers.main),
        environment = environment,
    )
}

/**
 * The 1.x controller's LifecycleOwner, which the engine drives instead of the Activity. It starts at
 * INITIALIZED, so a preview that binds before the first start does not open the camera.
 */
internal class LegacyLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Main thread only, as LifecycleRegistry requires. */
    fun moveTo(state: Lifecycle.State) {
        // A registry cannot go from INITIALIZED straight to DESTROYED.
        if (state == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = state
    }
}

/**
 * Every call on the main thread. Start resumes the registry (`startSession` is a no-op on Android);
 * stop moves it to CREATED without `stopSession`, so the use cases the preview bound stay bound and
 * the next start reopens the camera without a rebind; close cleans the controller up and destroys
 * the registry.
 */
internal class AndroidLegacySession(
    private val controller: CameraController,
    private val owner: LegacyLifecycleOwner,
    private val main: CoroutineDispatcher,
) : LegacySession {
    override suspend fun start() = withContext(main) {
        controller.startSession()
        owner.moveTo(Lifecycle.State.RESUMED)
    }

    override suspend fun stop() = withContext(main) { owner.moveTo(Lifecycle.State.CREATED) }

    override suspend fun close() = withContext(main) {
        try {
            controller.cleanup()
        } finally {
            owner.moveTo(Lifecycle.State.DESTROYED)
        }
    }

    override suspend fun <T> onSessionThread(block: () -> T): T = withContext(main) { block() }
}

// Nothing reads it: camerak's Android preview binds through Ready.controller until the engine lands.
private object InertPreviewSource : PlatformPreviewSource {
    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment = InertAttachment

    private object InertAttachment : PreviewAttachment {
        override fun close() = Unit
    }
}
