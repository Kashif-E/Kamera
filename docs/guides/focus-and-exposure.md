# Focus and Exposure

!!! note "Platforms"
    The API below is common, and the Compose tap is in place on every platform. Android and iOS
    implement it in the next wave; until then their cameras report no focus point and no exposure
    compensation, so the calls return `Unsupported`. Desktop cameras do not support either.

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
| Android | In the next wave | In the next wave |
| iOS | In the next wave | In the next wave |
| Desktop | `Unsupported(FOCUS_POINT)` | `Unsupported(EXPOSURE_COMPENSATION)` |

## Testing

`FakeCapabilities.backAndFront()` gives the fake's back camera a focus point and exposure indexes -6
to 6, and its front camera -3 to 3 with no focus point; pass `focusAndExposure = false` for neither.
`FakeCameraEngine` records `lastFocusPoint` and `appliedExposureCompensationIndex`,
`reportExposureIndex(index)` plays the platform's report, and `failNextFocus(error)` fails the next
focus. See [Testing](testing.md).

## Next Steps

- [Zoom Control](zoom-control.md)
- [CameraKStateHolder reference](../api/state-holder.md)
