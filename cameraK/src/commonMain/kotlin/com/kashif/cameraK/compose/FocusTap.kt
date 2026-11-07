package com.kashif.cameraK.compose

import androidx.compose.ui.geometry.Offset
import com.kashif.cameraK.controls.NormalizedPoint
import dev.drewhamilton.poko.Poko

/**
 * A tap that asked the camera to focus, for [CameraPreview]'s focus indicator. Compares by value.
 *
 * @property position Where the tap landed, in the overlay's coordinates: pixels from the content
 *   rectangle's top-left corner.
 * @property point The same place as a fraction of the content rectangle, as it went to
 *   `CameraKStateHolder.focusAt`.
 * @property outcome Whether the camera has metered there yet.
 */
@Poko
public class FocusTap(public val position: Offset, public val point: NormalizedPoint, public val outcome: FocusOutcome)

/**
 * Closed: how far a focus request has got. A new outcome waits for a major release.
 */
public enum class FocusOutcome {
    /** Sent to the camera, which has not answered yet. */
    PENDING,

    /** The camera meters focus and exposure at the point. */
    FOCUSED,

    /** The camera did not take the point. */
    FAILED,
}
