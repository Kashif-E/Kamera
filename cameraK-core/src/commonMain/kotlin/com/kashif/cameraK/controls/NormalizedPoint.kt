package com.kashif.cameraK.controls

import dev.drewhamilton.poko.Poko

/**
 * A point in the preview's content rectangle, the area the camera image fills, as fractions of its
 * size: [x] from 0 at the left edge to 1 at the right, [y] from 0 at the top to 1 at the bottom, as
 * the preview shows the image. The engine maps it to the sensor. Compares by value.
 *
 * The constructor takes any value; `CameraKStateHolder.focusAt` returns
 * `Failure(InvalidArgument(FOCUS_POINT))` for a coordinate outside [0, 1].
 */
@Poko
public class NormalizedPoint(public val x: Float, public val y: Float)
