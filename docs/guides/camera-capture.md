# Camera Capture

`holder.capture()` takes a photo, writes it to an app-owned file and returns
`CameraKResult<CapturedImage>`.

## Basic capture

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = {
    scope.launch {
        when (val result = holder.capture()) {
            is CameraKResult.Success -> show(result.value.path)
            is CameraKResult.Failure -> showError(result.error)
        }
    }
}) { Text("Capture") }
```

`CapturedImage` carries the file's `path`, its `format` and its `width` and `height`. The file is
in `CameraConfiguration.outputDirectory`, or in app-owned storage when that is null:
`filesDir/CameraK` on Android, `Documents/CameraK` on iOS, `~/Documents/CameraK` on desktop. It
never reaches the gallery on its own; publish it with [`MediaSaverPlugin`](plugins.md#media-saver-plugin).

The result helpers keep the call short:

```kotlin
holder.capture()
    .onSuccess { image -> thumbnails += image.path }
    .onFailure { error -> snackbar.showSnackbar("Capture failed") }
```

## Every capture, from anywhere

`holder.captures` emits every successful capture once its file is written, including one whose
caller was cancelled (the screen left while the shutter was open). Collect it where the app keeps
its gallery:

```kotlin
LaunchedEffect(holder) {
    holder.captures.collect { image -> gallery.add(image.path) }
}
```

It is not replayed: collect it before you capture.

## Showing that a capture is in flight

```kotlin
val uiState by holder.uiState.collectAsStateWithLifecycle()

Button(
    onClick = { scope.launch { holder.capture() } },
    enabled = !uiState.isCapturing,
) { Text(if (uiState.isCapturing) "Saving..." else "Capture") }
```

`uiState.isCapturing` is true while any capture is in flight, and `pendingCaptures` counts them.
Up to three captures may run at once; a fourth returns `Failure(CameraKError.Busy(CameraOperation.CAPTURE))`.

## Errors

A failed capture is a `CameraKError`:

| Error | When | Retry? |
|-------|------|--------|
| `NotReady` | The camera is not Ready (starting, interrupted, failed) | Yes, once it is Ready |
| `Closed` | The holder was closed | No |
| `Busy(CAPTURE)` | Three captures already in flight | Yes |
| `Storage(kind, keptFilePath)` | The file could not be written: `FULL`, `NOT_WRITABLE`, `NOT_FOUND` | Unless `FULL` |
| `Unsupported(IMAGE_FORMAT)` | The camera cannot write the configured format, e.g. HEIC without HEVC on iOS | No |
| `Hardware`, `Timeout(CAPTURE)` | The platform failed or did not answer | Yes |

`error.isRetryable` says whether trying again can succeed, and `error.cause` keeps the platform's
throwable. Its message may contain a path, so do not send it off the device unscrubbed.

```kotlin
holder.capture().onFailure { error ->
    message = when (error) {
        is CameraKError.Storage -> "Not enough space or no access to the folder"
        CameraKError.NotReady -> "The camera is not ready yet"
        else -> if (error.isRetryable) "Try again" else "Capture is not available"
    }
}
```

## Capture with flash

The flash mode applies to captures. Set it before the shutter:

```kotlin
scope.launch {
    holder.setFlashMode(FlashMode.ON)
        .onFailure { /* Unsupported: this camera has no flash */ }
    holder.capture()
}
```

See [Flash and Torch](flash-and-torch.md).

## A custom folder

```kotlin
val holder = rememberCameraKStateHolder(
    CameraConfiguration { outputDirectory = "$appFilesDir/photos" },
)
```

The directory is used as given, and nothing checks that it belongs to the app.

## Image formats

`ImageFormat.JPEG` (the default) and `PNG` work everywhere. `HEIC` works on iOS where the camera
offers HEVC; elsewhere the start, or on iOS the capture, fails with `Unsupported(IMAGE_FORMAT)`.
`holder.capabilities.value.supportedImageFormats` lists what the platform writes. On Android a PNG
capture is decoded from CameraX's JPEG and written upright, which takes longer than a JPEG.

## Next Steps

- [Flash and Torch](flash-and-torch.md)
- [Plugins](plugins.md): save captures to the gallery, read text from them
- [Video Recording](video-recording.md)
