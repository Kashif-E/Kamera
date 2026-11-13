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
 * never opens or closes the camera, which the holder does. Of two previews of one holder, as in a
 * crossfade or a dialog over a screen, desktop shows the camera in both; Android and iOS in the newer,
 * and in the older again once the newer leaves.
 *
 * @param holder The camera to show.
 * @param modifier Modifier for the preview's box.
 * @param contentDescription What the preview shows, for accessibility services; null for none.
 * @param tapToFocus Whether a tap on the content rectangle calls [CameraKStateHolder.focusAt] at that
 *   point. A tap does nothing on a camera whose capabilities do not list it in `supportsFocusPoint`,
 *   and an element of [overlay] that handles the tap itself keeps it.
 * @param focusIndicator Drawn over [overlay] from a tap until 1.5 seconds after the camera answered,
 *   in a box covering the content rectangle; place it at [FocusTap.position]. The default is a small
 *   ring.
 * @param overlay Drawn over the frame, with [CameraPreviewScope] for orientation-aware content.
 */
@Composable
public fun CameraPreview(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tapToFocus: Boolean = true,
    focusIndicator: @Composable (FocusTap) -> Unit = { DefaultFocusIndicator(it) },
    overlay: @Composable CameraPreviewScope.() -> Unit = {},
) {
    val deviceOrientation by holder.deviceOrientation.collectAsState()
    val described = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    val content: @Composable CameraPreviewScope.() -> Unit = if (tapToFocus) {
        { TapToFocus(holder, focusIndicator, overlay) }
    } else {
        overlay
    }
    EnginePreview(holder.previewSource, deviceOrientation, described, content)
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
