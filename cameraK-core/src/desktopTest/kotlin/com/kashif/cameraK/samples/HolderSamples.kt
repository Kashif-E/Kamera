package com.kashif.cameraK.samples

import androidx.lifecycle.Lifecycle
import com.kashif.cameraK.controls.cycleFlashMode
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.lifecycle.bindToLifecycle
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlin.time.Duration.Companion.seconds

// KDoc samples for camerak-core, referenced with @sample. They live here so the compiler checks them.

/** What a sample screen shows; an app draws these in its own UI. */
internal interface SampleScreen {
    fun showLoading()

    fun showCamera()

    fun showError(error: CameraKError, onRetry: () -> Unit)

    fun showPluginError(pluginId: String)

    fun showFlashIcon(name: String)
}

internal suspend fun cameraKStateHolderSample(platformContext: CameraKPlatformContext, screen: SampleScreen) {
    val holder = CameraKStateHolder(CameraConfiguration(), platformContext)
    holder.start()
    try {
        holder.cameraState.collect { state ->
            when (state) {
                CameraKState.Idle, CameraKState.Starting -> screen.showLoading()
                CameraKState.Ready, is CameraKState.Interrupted -> screen.showCamera()
                is CameraKState.Error -> screen.showError(state.error, onRetry = { holder.retry() })
                CameraKState.Closed -> Unit
            }
        }
    } finally {
        // When the screen goes away:
        holder.close()
    }
}

internal fun cameraKStateSample(holder: CameraKStateHolder, screen: SampleScreen) {
    // Closed hierarchy: no else branch.
    when (val state = holder.cameraState.value) {
        CameraKState.Idle, CameraKState.Starting -> screen.showLoading()
        CameraKState.Ready, is CameraKState.Interrupted -> screen.showCamera()
        is CameraKState.Error -> screen.showError(state.error, onRetry = { holder.retry() })
        CameraKState.Closed -> Unit
    }
}

internal suspend fun cameraKEventSample(holder: CameraKStateHolder, screen: SampleScreen) {
    holder.events.collect { event ->
        // Open hierarchy: the else branch is required.
        when (event) {
            is CameraKEvent.PluginFailed -> screen.showPluginError(event.pluginId)
            else -> Unit
        }
    }
}

internal fun cameraConfigurationSample(platformContext: CameraKPlatformContext): CameraKStateHolder {
    val config = CameraConfiguration {
        flashMode = FlashMode.AUTO
        cameraLens = CameraLens.BACK
        qualityPrioritization = QualityPrioritization.QUALITY
    }
    // In Compose: rememberCameraKStateHolder(config).
    return CameraKStateHolder(config, platformContext)
}

internal suspend fun cameraUIStateSample(holder: CameraKStateHolder, screen: SampleScreen) {
    holder.uiState.collect { uiState ->
        screen.showFlashIcon(
            when (uiState.flashMode) {
                FlashMode.ON -> "flash_on"
                FlashMode.AUTO -> "flash_auto"
                else -> "flash_off"
            },
        )
    }
}

internal suspend fun cameraUIStateFlashButtonSample(holder: CameraKStateHolder) {
    // The flash button's click; it is disabled while uiState.isCapturing.
    if (!holder.uiState.value.isCapturing) holder.cycleFlashMode()
}

internal fun videoConfigurationSample(): VideoConfiguration = VideoConfiguration {
    quality = VideoQuality.HD
    maxDuration = 30.seconds
}

internal fun bindToLifecycleSample(platformContext: CameraKPlatformContext, lifecycle: Lifecycle): AutoCloseable {
    // The holder lives as long as the app's own ViewModel and is closed when the ViewModel is cleared.
    val holder = CameraKStateHolder(CameraConfiguration(), platformContext)
    // In the Activity or Fragment, for each lifecycle that shows the camera:
    return holder.bindToLifecycle(lifecycle)
}

internal fun cameraKLoggerSample(log: (String, Throwable?) -> Unit) {
    CameraKLogger.enabled = true
    // Optional: forward to your own logging stack.
    CameraKLogger.sink = { level, tag, message, throwable ->
        log("${level.name} $tag: $message", throwable)
    }
}
