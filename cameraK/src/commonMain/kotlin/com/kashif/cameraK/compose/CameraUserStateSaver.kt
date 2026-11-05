package com.kashif.cameraK.compose

import androidx.compose.runtime.saveable.Saver
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.state.CameraConfiguration

/**
 * The user's camera choices, as the platform last reported them, saved with rememberSaveable so a
 * holder built in a new process after process death starts where the user left it (design §7.3).
 * The zoom is saved but not yet restored: CF' restores it with `initialZoomRatio`.
 */
internal data class CameraUserState(
    val lens: CameraLens,
    val flashMode: FlashMode,
    val torchMode: TorchMode,
    val zoomRatio: Float,
)

/** [this] with the saved lens, flash and torch; unchanged when nothing was saved. */
internal fun CameraConfiguration.seededBy(state: CameraUserState?): CameraConfiguration = if (state == null) {
    this
} else {
    copy {
        cameraLens = state.lens
        flashMode = state.flashMode
        torchMode = state.torchMode
    }
}

/** Saves to the names of the three choices and the zoom ratio, which every platform's saved state can hold. */
internal val CameraUserStateSaver: Saver<CameraUserState?, Any> = Saver(
    save = { state -> state?.let { listOf(it.lens.name, it.flashMode.name, it.torchMode.name, it.zoomRatio) } },
    restore = ::restoreCameraUserState,
)

// Null for anything this saver did not write, such as a value saved by another version of it.
private fun restoreCameraUserState(saved: Any): CameraUserState? {
    val values = saved as? List<*> ?: return null
    if (values.size != SAVED_FIELDS) return null
    return CameraUserState(
        lens = CameraLens.entries.firstOrNull { it.name == values[0] } ?: return null,
        flashMode = FlashMode.entries.firstOrNull { it.name == values[1] } ?: return null,
        torchMode = TorchMode.entries.firstOrNull { it.name == values[2] } ?: return null,
        zoomRatio = values[3] as? Float ?: return null,
    )
}

private const val SAVED_FIELDS = 4

/** The latest [CameraUserState] of one call site; rememberSaveable saves whatever it holds then. */
internal class CameraUserStateBox(var value: CameraUserState?) {
    companion object {
        val Saver: Saver<CameraUserStateBox, Any> = Saver(
            save = { box -> with(CameraUserStateSaver) { save(box.value) } },
            restore = { saved -> CameraUserStateBox(CameraUserStateSaver.restore(saved)) },
        )
    }
}
