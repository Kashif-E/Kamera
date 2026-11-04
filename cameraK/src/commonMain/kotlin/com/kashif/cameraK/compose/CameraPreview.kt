@file:OptIn(InternalCameraKApi::class, CameraKEngineApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * The camera preview for [holder], with [overlay] drawn in the same box as the frame, so overlay
 * coordinates (a focus reticle, a detection box) land on the frame rather than on letterbox bars.
 *
 * The preview only attaches to the camera's session and detaches when it leaves the composition: it
 * never opens or closes the camera, which the holder does. Two previews of one holder, as in a
 * crossfade, both show it.
 *
 * @param holder The camera to show.
 * @param modifier Modifier for the preview's box.
 * @param contentDescription What the preview shows, for accessibility services; null for none.
 * @param overlay Drawn over the frame, with [CameraPreviewScope] for orientation-aware content.
 */
@Composable
fun CameraPreview(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    overlay: @Composable CameraPreviewScope.() -> Unit = {},
) {
    val deviceOrientation by holder.deviceOrientation.collectAsState()
    val described = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    EnginePreview(holder.previewSource, deviceOrientation, described, overlay)
}

/**
 * Draws [source], the engine's preview, with [overlay] in the frame's box (design §7.1): Android
 * attaches a PreviewView's surface provider, iOS hosts a preview layer, desktop draws the frames.
 */
@Composable
internal expect fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
)
