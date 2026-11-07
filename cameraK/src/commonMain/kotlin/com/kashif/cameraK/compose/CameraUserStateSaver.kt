package com.kashif.cameraK.compose

import androidx.compose.runtime.saveable.Saver
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.state.CameraConfiguration

/**
 * The user's camera choices, as the platform last reported them, saved with rememberSaveable so a
 * holder built in a new process after process death starts where the user left it (design §7.3).
 * [lens] and [deviceType] are null while the holder asks for the platform default camera, so a
 * configuration with no lens keeps meaning the platform default (§6.1 "Default lens"). The camera id
 * is not saved (w7 G5).
 */
internal data class CameraUserState(
    val lens: CameraLens?,
    val deviceType: CameraDeviceType?,
    val flashMode: FlashMode,
    val torchMode: TorchMode,
    val zoomRatio: Float,
    val exposureCompensationIndex: Int = 0,
)

/** [this] with the saved choices; unchanged when nothing was saved, and the configured camera while none was picked. */
internal fun CameraConfiguration.seededBy(state: CameraUserState?): CameraConfiguration = if (state == null) {
    this
} else {
    copy {
        state.lens?.let { cameraLens = it }
        state.deviceType?.let { cameraDeviceType = it }
        flashMode = state.flashMode
        torchMode = state.torchMode
        initialZoomRatio = state.zoomRatio
    }
}

/** Saves to the names of the choices and the zoom ratio, which every platform's saved state can hold. */
internal val CameraUserStateSaver: Saver<CameraUserState?, Any> = Saver(
    save = { state ->
        state?.let {
            listOf(it.lens?.name, it.deviceType?.name, it.flashMode.name, it.torchMode.name, it.zoomRatio)
        }
    },
    restore = ::restoreCameraUserState,
)

// Null for anything this saver did not write, such as a value saved by another version of it.
private fun restoreCameraUserState(saved: Any): CameraUserState? {
    val values = saved as? List<*> ?: return null
    if (values.size != SAVED_FIELDS) return null
    return CameraUserState(
        lens = values[0]?.let { name -> CameraLens.entries.firstOrNull { it.name == name } ?: return null },
        deviceType = values[1]?.let { name -> CameraDeviceType.entries.firstOrNull { it.name == name } ?: return null },
        flashMode = FlashMode.entries.firstOrNull { it.name == values[2] } ?: return null,
        torchMode = TorchMode.entries.firstOrNull { it.name == values[3] } ?: return null,
        zoomRatio = values[4] as? Float ?: return null,
    )
}

private const val SAVED_FIELDS = 5

/** The latest [CameraUserState] of one call site; rememberSaveable saves whatever it holds then. */
internal class CameraUserStateBox(var value: CameraUserState?) {
    companion object {
        val Saver: Saver<CameraUserStateBox, Any> = Saver(
            save = { box -> with(CameraUserStateSaver) { save(box.value) } },
            restore = { saved -> CameraUserStateBox(CameraUserStateSaver.restore(saved)) },
        )
    }
}
