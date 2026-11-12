# Focus and Exposure

!!! note "Platforms"
    The API below is common, and the Compose tap is in place on every platform. Android and iOS
    support it. Desktop cameras do not support either.

## Tap to focus

`CameraPreview` and `CameraKScreen` focus where the user taps, by default:

```kotlin
CameraKScreen(holder)                       // tapToFocus = true
CameraPreview(holder, tapToFocus = false)   // no tap handling
```

A tap inside the preview's content rectangle, the area the image fills, calls `holder.focusAt` at
that point. A tap on the letterbox bars does nothing, and so does a tap on a camera whose
capabilities do not list a focus point. A button in your overlay that handles the tap keeps it.

A small ring shows at the tap until 1.5 seconds after the camera answered. Replace it with
`focusIndicator`, which gets a `FocusTap`:

```kotlin
CameraPreview(
    holder,
    focusIndicator = { tap ->
        val alpha = if (tap.outcome == FocusOutcome.FAILED) 0.4f else 1f
        Box(
            Modifier
                .offset { IntOffset(tap.position.x.roundToInt() - 40, tap.position.y.roundToInt() - 40) }
                .size(80.dp)
                .border(2.dp, Color.Yellow.copy(alpha = alpha), CircleShape),
        )
    },
)
```

`tap.position` is in the overlay's coordinates, `tap.point` is what went to the camera, and
`tap.outcome` is `PENDING`, then `FOCUSED` or `FAILED`.

## Focus at a point

```kotlin
scope.launch {
    holder.focusAt(NormalizedPoint(0.5f, 0.5f))   // CameraKResult<Unit>
}
```

`NormalizedPoint(x, y)` is a fraction of the preview's content rectangle: (0, 0) is its top-left
corner and (1, 1) its bottom-right, as the preview shows the image. The camera meters focus and
exposure there, then returns to its own metering after the platform's timeout; a camera switch
drops the point. A coordinate outside [0, 1] is `Failure(InvalidArgument(FOCUS_POINT))`, and a
camera without a focus point is `Failure(Unsupported(FOCUS_POINT))`.

## Exposure compensation

```kotlin
val capabilities by holder.capabilities.collectAsStateWithLifecycle()
val ui by holder.uiState.collectAsStateWithLifecycle()
val range = ui.cameraId?.let(capabilities::exposureCompensationRange)
if (range != null) {
    Slider(
        value = ui.exposureCompensationIndex.toFloat(),
        onValueChange = { scope.launch { holder.setExposureCompensation(it.roundToInt()) } },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
    )
}
```

The index is in the bound camera's `exposureCompensationRange(cameraId)`, and each step is
`exposureCompensationStep(cameraId)` EV. An index outside the range is
`Failure(InvalidArgument(EXPOSURE_COMPENSATION))`; a camera without compensation is
`Failure(Unsupported(EXPOSURE_COMPENSATION))`. `uiState.exposureCompensationIndex` shows the index
once the platform has applied it.

The holder keeps the index. After a camera switch or a restart it applies it again, clamped into the
new camera's range. `rememberCameraKStateHolder` also restores it after process death.

## Reset

```kotlin
scope.launch { holder.resetFocusAndExposure() }
```

Returns the camera to its own metering and exposure index 0, for what it supports.
`Failure(Unsupported(FOCUS_POINT))` on a camera that supports neither.

## The exposure at open

```kotlin
CameraConfiguration { initialExposureCompensationIndex = -3 }
```

Clamped into the camera's range; a camera without compensation ignores it.

## Platforms

| Platform | Focus point | Exposure compensation |
|----------|-------------|-----------------------|
| Android | Supported, where CameraX meters at a point | Supported, where CameraX reports a range |
| iOS | Supported, where the camera has a focus or exposure point of interest | Supported, in CameraK's 1/3 EV steps within ±2 EV |
| Desktop | `Unsupported(FOCUS_POINT)` | `Unsupported(EXPOSURE_COMPENSATION)` |

### Android

Each camera's capabilities fill in once it has been bound, from CameraX: a focus point where
`CameraInfo.isFocusMeteringSupported` accepts a centre AF and AE action, and the range and step of
`ExposureState` where it supports compensation. A camera never opened yet reports neither.

A device type that maps to a physical lens inside a logical multi-camera, such as the Pixel 7's
ultra-wide, binds the logical camera, and CameraX describes that one. Such a lens meters only what its
own characteristics allow: no AF on a fixed-focus lens (a minimum focus distance of 0) or one with no AF
region, and no AE or AWB where it takes no region of that kind. A fixed-focus ultra-wide therefore
meters exposure and answers `Success`.

`focusAt` maps the point through the preview's crop, rotation and, on the front camera, its mirror,
then meters there with AF, AE and AWB, keeping each the camera supports. CameraX returns to
continuous metering after its default 5 seconds.

| What happened | `focusAt` returns |
|---------------|-------------------|
| The camera focused | `Success` |
| A fixed-focus camera metered (no AF in the action) | `Success` |
| AF ended without locking, in a dark or featureless scene | `Success`, as on iOS |
| AF was still searching when CameraX gave up, 5 seconds after the tap | `Failure(Timeout(FOCUS_POINT))` |
| A newer `focusAt`, a reset or a camera switch replaced it | `Failure(Busy(FOCUS_POINT))` |
| The camera stopped or closed first, even if `start` followed at once | `Failure(Closed)` |
| The preview has no surface yet | `Failure(InvalidState(FOCUS_POINT))` |
| CameraX failed it otherwise | `Failure(Hardware(null, cause))` |

`setExposureCompensation` returns once CameraX has applied the index, and `uiState` shows the index
`ExposureState` reads back. A newer call, a reset or a camera switch replaces one still in flight, which
returns `Failure(Busy(EXPOSURE_COMPENSATION))` and leaves the held index as it was, even when the old camera
applied it after the switch; stopped or closed first, it returns `Failure(Closed)`. A camera without
compensation drops the held index, and `uiState` shows 0. `resetFocusAndExposure` cancels the focus point
and sets index 0.

### iOS

iOS has no exposure index, so CameraK defines one: each step is 1/3 EV, and a camera's
`exposureCompensationRange` is the indexes whose bias lies inside its `exposureTargetBias` range, cut to
±2 EV, which is -6 to 6 on every current iPhone. CameraK never sets a bias outside the camera's range. ±2 EV matches Android's typical range and the system Camera app. A camera whose range
holds no index but 0 reports no compensation. `exposureCompensationStep` is 1/3.

Each camera's capabilities are known from the start: a focus point where the camera supports a focus
or an exposure point of interest, and the range above.

`focusAt` converts the point through the preview layer, which accounts for the preview's crop, its
rotation and the front camera's mirror. It sets the focus and exposure points with one-shot autofocus
and autoexposure, for what the camera supports. When the scene changes (AVFoundation's subject-area
change) the camera returns to continuous focus and exposure at the centre.

| What happened | `focusAt` returns |
|---------------|-------------------|
| The camera stopped focusing, or was not focusing 150 ms after the tap | `Success` |
| A camera without a focus point metered exposure | `Success` |
| The camera was still focusing 2 seconds after the tap | `Failure(Timeout(FOCUS_POINT))` |
| A newer `focusAt`, a reset or a camera switch replaced it | `Failure(Busy(FOCUS_POINT))` |
| The camera stopped or closed first | `Failure(Closed)` |
| The camera refused its configuration lock | `Failure(Hardware(code, cause))` |

`setExposureCompensation` returns once AVFoundation has applied the bias, and `uiState` shows the index
the camera's bias reads back as. A newer call replaces one still in flight, which returns
`Failure(Busy(EXPOSURE_COMPENSATION))`. A camera without compensation drops the held index, and
`uiState` shows 0. `resetFocusAndExposure` returns to continuous focus and exposure at the centre and
sets index 0.

## Testing

`FakeCapabilities.backAndFront()` gives the fake's back camera a focus point and exposure indexes -6
to 6, and its front camera -3 to 3 with no focus point; pass `focusAndExposure = false` for neither.
`FakeCameraEngine` records `lastFocusPoint` and `appliedExposureCompensationIndex`,
`reportExposureIndex(index)` plays the platform's report, and `failNextFocus(error)` fails the next
focus. See [Testing](testing.md).

## Next Steps

- [Zoom Control](zoom-control.md)
- [CameraKStateHolder reference](../api/state-holder.md)
