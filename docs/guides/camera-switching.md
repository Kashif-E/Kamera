# Camera Switching

## Front and back

```kotlin
scope.launch {
    holder.toggleLens()                                  // CameraKResult<LensInfo>
}
```

`toggleLens()` (in `com.kashif.cameraK.controls`) switches to the back camera from the front, and to
the front from any other, with device type `DEFAULT`. The result is the camera that is live
afterwards.

## A lens and device type

```kotlin
scope.launch {
    holder.selectCamera(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE)
        .onFailure { error -> if (error is CameraKError.NoCamera) showMessage("No ultra-wide camera") }
}
```

The switch is one rebind. `NoCamera` leaves the current camera in place.

## An exact camera

`holder.capabilities` lists every camera with its `LensInfo`: `id`, `lens`, `deviceType`, the
zoom range, `hasFlash`, `isLogical` and `isExternal`.

```kotlin
val capabilities by holder.capabilities.collectAsStateWithLifecycle()

LazyColumn {
    items(capabilities.allLenses) { lens ->
        TextButton(onClick = { scope.launch { holder.selectCamera(lens.id) } }) {
            Text("${lens.lens} ${lens.deviceType}${if (lens.isExternal) " (external)" else ""}")
        }
    }
}
```

`capabilities.lenses(CameraLens.BACK)` lists a facing's built-in cameras,
`availableDeviceTypes(CameraLens.BACK)` their device types, and `externalLenses` the USB and
Continuity cameras. On desktop every webcam is a lens with its own `id`. Until the engine has
enumerated the cameras, `capabilities` is `CameraCapabilities.EMPTY`.

## The current camera

```kotlin
val uiState by holder.uiState.collectAsStateWithLifecycle()
uiState.cameraLens          // FRONT or BACK; null before the first bind
uiState.cameraDeviceType
uiState.cameraId            // the bound camera's LensInfo.id
```

## While recording

A switch during a recording is refused, and nothing changes: `Failure(CameraKError.Busy(LENS))`, or
`Busy(DEVICE_TYPE)` when only the device type would change. Disable the switch while
`holder.recordingState` is live.

## What survives a switch

The flash, torch and zoom you set are applied again on the new camera where it offers them; read
`uiState` and `zoom` for what it reports. `rememberCameraKStateHolder` saves the chosen camera and
restores it after process death.

## The camera at open

```kotlin
CameraConfiguration {
    cameraLens = CameraLens.FRONT
    cameraDeviceType = CameraDeviceType.WIDE_ANGLE
}
// or an exact camera:
CameraConfiguration { cameraId = savedCameraId }
```

With no lens and no id the platform default opens: the back camera on Android and iOS, the system
default device on desktop.

## Platforms

| Platform | Device types |
|----------|--------------|
| Android | Classified relative to the main lens: 1.6x its focal length or more is `TELEPHOTO`, 0.75x or less `ULTRA_WIDE`; macro modules are `MACRO` |
| iOS | From the AVFoundation device type (wide, ultra-wide, telephoto) |
| Desktop | Each connected device is a lens with its own `id`; choose one with `selectCamera(id)` |

## Next Steps

- [Zoom Control](zoom-control.md)
- [Configuration](../getting-started/configuration.md)
