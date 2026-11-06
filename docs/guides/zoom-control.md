# Zoom Control

## Set the zoom

```kotlin
scope.launch {
    holder.setZoom(2f)            // CameraKResult<ZoomState>
}
```

A ratio outside the camera's range is clamped into it, and the result is the zoom the platform
applied. Calls are latest-wins: while one waits, a newer call replaces it, and both return the
newer call's result. That makes `setZoom` safe to call at gesture rate.

## Read the zoom

```kotlin
val zoom by holder.zoom.collectAsStateWithLifecycle()
Text("${zoom.ratio}x (${zoom.minRatio}x to ${zoom.maxRatio}x)")
```

`holder.zoom` is a `ZoomState` with the current `ratio` and the range the bound camera allows, as
the platform reports them. It changes with every zoom the platform applies, including its own
smoothing, and with each camera switch.

## Pinch to zoom

```kotlin
CameraKScreen(holder) {
    val zoom by holder.zoom.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(holder) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    val target = (holder.zoom.value.ratio * gestureZoom)
                        .coerceIn(zoom.minRatio, zoom.maxRatio)
                    scope.launch { holder.setZoom(target) }
                }
            },
    )
}
```

Read `holder.zoom.value` inside the gesture so each step starts from the zoom the platform last
reported.

## Zoom slider

```kotlin
val zoom by holder.zoom.collectAsStateWithLifecycle()
if (zoom.maxRatio > zoom.minRatio) {
    Slider(
        value = zoom.ratio,
        onValueChange = { ratio -> scope.launch { holder.setZoom(ratio) } },
        valueRange = zoom.minRatio..zoom.maxRatio,
    )
}
```

## Quick zoom buttons

```kotlin
Row {
    listOf(1f, 2f, 5f).filter { it <= zoom.maxRatio }.forEach { ratio ->
        TextButton(onClick = { scope.launch { holder.setZoom(ratio) } }) { Text("${ratio.toInt()}x") }
    }
}
```

On a phone with several back cameras, `minRatio` can be below 1 (0.5 on an ultra-wide logical
camera). To switch to a specific lens instead, see [Camera Switching](camera-switching.md).

## The zoom at open

```kotlin
CameraConfiguration { initialZoomRatio = 2f }
```

The holder re-applies the zoom you set after a restart. `rememberCameraKStateHolder` also restores
it after process death.

## Platforms

| Platform | Range |
|----------|-------|
| Android | The bound camera's `ZoomState` from CameraX |
| iOS | The device's `videoZoomFactor` range |
| Desktop | Fixed at 1x: `setZoom(1f)` succeeds, any other ratio is `Unsupported(ZOOM)` |

## Next Steps

- [Camera Switching](camera-switching.md)
- [CameraKStateHolder reference](../api/state-holder.md)
