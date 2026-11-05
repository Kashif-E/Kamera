package com.kashif.cameraK.lifecycle

import androidx.lifecycle.Lifecycle
import com.kashif.cameraK.state.CameraKStateHolder

/**
 * Drives the holder from [lifecycle], for a holder the app keeps itself, in its own ViewModel: the
 * holder starts when the lifecycle reaches STARTED and stops when it drops below it, and every move
 * to RESUMED re-checks the camera permission, so a permission granted in the system dialog opens the
 * camera with no retry. `rememberCameraKStateHolder` follows the same rule.
 *
 * Closing the returned binding stops following [lifecycle] and stops the holder; it does not close
 * the holder. A stop while a recording is live keeps the camera open for two seconds first, so a
 * rotation's stop and start carry on with the recording (see [CameraKStateHolder.stop]).
 *
 * @example
 * ```kotlin
 * class CameraViewModel(application: Application) : AndroidViewModel(application) {
 *     val holder = CameraKStateHolder(CameraConfiguration(), CameraKPlatformContext(application))
 *         .also { addCloseable(it) }
 * }
 *
 * // In the Activity or Fragment:
 * val binding = viewModel.holder.bindToLifecycle(lifecycle)
 * ```
 */
public fun CameraKStateHolder.bindToLifecycle(lifecycle: Lifecycle): AutoCloseable {
    val holder = this
    val following = launchInHolderScope {
        var started = false
        lifecycle.currentStateFlow.collect { state ->
            val atLeastStarted = state.isAtLeast(Lifecycle.State.STARTED)
            if (atLeastStarted != started) {
                started = atLeastStarted
                if (atLeastStarted) holder.start() else holder.stop()
            }
            if (state == Lifecycle.State.RESUMED) holder.recheckPermission()
        }
    }
    return AutoCloseable {
        following.cancel()
        holder.stop()
    }
}
