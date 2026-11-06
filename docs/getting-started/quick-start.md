# Quick Start

Build a camera screen that asks for the permission, shows the preview and takes a photo.

## 1. Ask for the camera permission

The holder checks the camera permission before it opens the camera and reports
`CameraKState.Error(CameraKError.Permission)` when it is missing; it never prompts. Ask from your UI:

```kotlin
@Composable
fun CameraRoute() {
    val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA)
    if (permissions.allGranted) {
        CameraScreen()
    } else {
        Column {
            Text("The camera needs your permission.")
            Button(onClick = permissions::launchRequest) { Text("Allow camera") }
        }
    }
}
```

`launchRequest()` shows the system prompt for what can still be asked. When the user grants it in
the system settings instead, the holder sees it on the next resume and opens the camera with no
retry.

## 2. Show the camera

```kotlin
@Composable
fun CameraScreen() {
    val holder = rememberCameraKStateHolder(
        config = CameraConfiguration {
            cameraLens = CameraLens.BACK
            aspectRatio = AspectRatio.RATIO_4_3
        },
    )
    val scope = rememberCoroutineScope()

    CameraKScreen(
        holder = holder,
        modifier = Modifier.fillMaxSize(),
        loading = { CircularProgressIndicator() },
        error = { error, retry ->
            Column {
                Text("Camera unavailable: ${error::class.simpleName}")
                if (error.isRetryable) Button(onClick = retry) { Text("Try again") }
            }
        },
    ) {
        CaptureButton(
            onClick = { scope.launch { holder.capture() } },
            modifier = Modifier.align(Alignment.BottomCenter).keepUpright(),
        )
    }
}
```

What each piece does:

- `rememberCameraKStateHolder(config)` creates the holder and keeps it in a ViewModel of the screen,
  so a rotation keeps the camera. It starts the camera when the screen reaches STARTED, stops it below
  and closes it when the screen is gone. A new `config` builds a new holder.
- `CameraKScreen` shows `loading` while the camera opens, the preview with your content over it while
  it is Ready (or Interrupted, so the controls stay reachable), and `error` when it fails. Its
  content lambda is a `CameraPreviewScope`, a `BoxScope` whose `keepUpright()` turns a control to
  the device's orientation.
- `holder.capture()` takes a photo to an app-owned file and returns `CameraKResult<CapturedImage>`.

## 3. Use the result

```kotlin
scope.launch {
    holder.capture()
        .onSuccess { image -> lastPhoto = image.path }
        .onFailure { error -> message = "Capture failed: $error" }
}
```

Every command returns `CameraKResult`: `Success(value)` or `Failure(error)`, with `onSuccess`,
`onFailure`, `fold`, `map`, `getOrNull` and `errorOrNull`. A command issued before the camera is
Ready returns `Failure(CameraKError.NotReady)`; after `close()`, `Failure(CameraKError.Closed)`.

## 4. Add flash and lens controls

```kotlin
val uiState by holder.uiState.collectAsStateWithLifecycle()

Row {
    IconButton(onClick = { scope.launch { holder.cycleFlashMode() } }) {
        Text(
            when (uiState.flashMode) {
                FlashMode.ON -> "Flash on"
                FlashMode.AUTO -> "Flash auto"
                else -> "Flash off"
            },
        )
    }
    IconButton(onClick = { scope.launch { holder.toggleLens() } }) {
        Text(if (uiState.cameraLens == CameraLens.FRONT) "Front" else "Back")
    }
}
```

`uiState` changes only when the platform reports the change, so the label shows what the camera is
doing, not what was asked. `cycleFlashMode`, `toggleTorch` and `toggleLens` are in
`com.kashif.cameraK.controls`.

## 5. Add a plugin

```kotlin
val scanner = rememberQRScannerPlugin()
val holder = rememberCameraKStateHolder(plugins = listOf(scanner))

LaunchedEffect(scanner) {
    scanner.results.collect { barcode -> println("Scanned ${barcode.format}") }
}
```

Pass plugins when the holder is created, so a plugin that reads frames gets them from the first
bind. See [Plugins](../guides/plugins.md).

## Next Steps

- [Configuration](configuration.md): every `CameraConfiguration` field
- [Camera Capture](../guides/camera-capture.md)
- [Video Recording](../guides/video-recording.md)
- [CameraKStateHolder reference](../api/state-holder.md)
