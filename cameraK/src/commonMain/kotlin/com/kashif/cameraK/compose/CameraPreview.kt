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
 * The camera preview for [holder], with [overlay] drawn over it. The image fills the preview's
 * content rectangle, the largest centred box of its aspect ratio inside the preview's box, with
 * letterbox or pillarbox bars outside it where the two ratios differ. On every platform the overlay
 * is laid out over that rectangle, not the whole box, so its coordinates (a focus reticle, a
 * detection box) land on the image, and [CameraPreviewScope.contentBounds] gives its size. The
 * rectangle's ratio is the camera image's: on Android and iOS the configured aspect ratio turned to
 * the screen's orientation (iOS shows the frame cropped to it, as a capture keeps it), on desktop the
 * frame's own.
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
public fun CameraPreview(
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
 * Draws [source], the engine's preview, with [overlay] (design §7.1): Android renders its
 * SurfaceRequests in CameraXViewfinder, iOS hosts a preview layer, desktop draws the frames.
 */
@Composable
internal expect fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
)
