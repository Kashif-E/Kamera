# Flash and Torch

The flash fires for captures; the torch is a steady light. Both are per camera: what the bound
camera offers is in `holder.capabilities`, and what it is doing is in `holder.uiState`.

## Flash

```kotlin
scope.launch {
    holder.setFlashMode(FlashMode.AUTO)        // CameraKResult<FlashMode>
    holder.cycleFlashMode()                    // the next of OFF, AUTO, ON that this camera offers
}
```

`setFlashMode` returns `Failure(CameraKError.Unsupported(...))` for a mode the current camera does
not offer, such as any mode but OFF on most front cameras. `cycleFlashMode()` (in
`com.kashif.cameraK.controls`) skips the modes the camera lacks, so it never fails for that
reason.

## Torch

```kotlin
scope.launch {
    holder.setTorchMode(TorchMode.ON)          // CameraKResult<TorchMode>
    holder.toggleTorch()                       // ON when off, OFF when on
}
```

## Reading the current modes

```kotlin
val uiState by holder.uiState.collectAsStateWithLifecycle()
val flash = uiState.flashMode                  // null before the first bind
val torch = uiState.torchMode
```

`uiState` shows what the platform reports, not what was asked: after `setTorchMode(ON)` the torch
reads ON once the device turned it on. The system can turn the torch off on its own (heat, another
app), and `uiState` follows.

## What the camera offers

```kotlin
val capabilities by holder.capabilities.collectAsStateWithLifecycle()
val cameraId = uiState.cameraId
val flashModes = cameraId?.let { capabilities.supportedFlashModes(it) }.orEmpty()
val torchModes = cameraId?.let { capabilities.supportedTorchModes(it) }.orEmpty()
```

Hide the controls the camera cannot use:

```kotlin
if (FlashMode.ON in flashModes) {
    IconButton(onClick = { scope.launch { holder.cycleFlashMode() } }) {
        Text(
            when (uiState.flashMode) {
                FlashMode.ON -> "Flash on"
                FlashMode.AUTO -> "Flash auto"
                else -> "Flash off"
            },
        )
    }
}
if (TorchMode.ON in torchModes) {
    IconButton(onClick = { scope.launch { holder.toggleTorch() } }) {
        Text(if (uiState.torchMode == TorchMode.ON) "Torch on" else "Torch off")
    }
}
```

## Initial modes

```kotlin
CameraConfiguration {
    flashMode = FlashMode.AUTO
    torchMode = TorchMode.OFF
}
```

The holder re-applies the flash and torch you set after a camera switch or a restart, where the new
camera offers them.

## Platforms

| Platform | Flash | Torch |
|----------|-------|-------|
| Android | `OFF`, `AUTO`, `ON` on cameras with a flash unit | `OFF`, `ON` (`AUTO` is `Unsupported(TORCH)`) |
| iOS | `OFF`, `AUTO`, `ON` on cameras with a flash unit | `OFF`, `ON`, `AUTO` where the device supports each |
| Desktop | `OFF` only | `OFF` only |

## Next Steps

- [Camera Capture](camera-capture.md)
- [Camera Switching](camera-switching.md)
