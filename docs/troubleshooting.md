# Troubleshooting

Most problems show up as `CameraKState.Error(error)` or a command's `Failure(error)`. The error's
type says what went wrong, `isRetryable` whether trying again can help, and `cause` keeps the
platform's throwable. Turn on logging to see more:

```kotlin
CameraKLogger.enabled = true
```

## Installation

### "Could not find io.github.kashif-mehmood-km:camerak"

Check that `mavenCentral()` is in your repositories and that the version is `2.0`. The 1.x plugin
ids (`qr_scanner_plugin` and the others) still resolve, through a relocation, to the 2.0 artifacts;
switch to the new ids (see the [migration guide](migration/2.0.md)).

### Unresolved references after upgrading

2.0 replaced the 1.x API. The IDE offers a quick fix for most 1.x calls; the
[migration guide](migration/2.0.md) maps the rest.

## Permissions

### `Error(Permission(CAMERA, status))`

The holder never prompts. Ask with `rememberCameraKPermissionState(CameraKPermission.CAMERA)` and
`launchRequest()`. Once the permission is granted, the holder opens the camera on the next resume.
When `error.needsSettings` is true (`PermanentlyDenied`), only the system settings can grant it:
show a button that opens them.

### iOS: the app is killed when the camera opens

`Info.plist` lacks `NSCameraUsageDescription`, or `NSMicrophoneUsageDescription` for a recording
with audio. See [Installation](getting-started/installation.md#ios).

### macOS desktop: the packaged app cannot open the camera

The bundle needs `NSCameraUsageDescription`, and a signed build with the hardened runtime the camera
entitlement. `./gradlew run` hides the problem because the terminal holds the permission.

## The camera does not open

### `Error(NoCamera)`

No camera faces that way. Check `holder.capabilities.value.allLenses`.

### `Error(CameraInUse)` or `Error(Disconnected)`

Another app holds the camera, or it went away. Both are retryable: `holder.retry()`, or the default
error slot of `CameraKScreen`, which retries once.

### `Error(Unsupported(feature))`

The configuration asks for something the platform cannot do: `IMAGE_FORMAT` for HEIC on Android or
desktop, `PLATFORM_VERSION` on iOS below 16. `holder.capabilities` lists what is supported.

### Desktop: `Error(NativeLibraryMissing)`

The bytedeco natives for this platform are missing. Add them as the
[installation guide](getting-started/installation.md#desktop-jvm) shows.

### The camera stays `Idle`

On Android and iOS the holder opens the camera only while the screen is started. If you pass
`lifecycleOwner = null` to `rememberCameraKStateHolder`, call `holder.start()` yourself.

### The camera reopened after a configuration change

A new `CameraConfiguration` builds a new holder. Build the configuration from remembered or saved
values, so a recomposition passes an equal one, and change the running camera with the holder's
commands instead.

## Preview

### Black preview

Check `cameraState`: the preview shows a frame only while the camera is `Ready`. Two previews of one
holder both show it; a preview never opens the camera itself.

### Overlay coordinates are off

The overlay is laid out over the image's content rectangle, not the whole preview box: draw in
its coordinates, and use `contentBounds` for its size; see
[CameraPreview](api/camera-k-screen.md#camerapreview).

## Capture

### `Failure(NotReady)`

The camera is not `Ready`: still starting, interrupted or failed. Disable the shutter until
`cameraState` is `Ready`.

### `Failure(Busy(CAPTURE))`

Three captures are already in flight. Disable the shutter while `uiState.isCapturing`.

### `Failure(Storage(kind))`

The file could not be written: `FULL`, `NOT_WRITABLE` or `NOT_FOUND`. Check `outputDirectory`.

### Photos do not appear in the gallery

Captures are app-owned files. Publish them with `MediaSaverPlugin` (`save(image)` or
`autoSave = true`).

## Flash, torch and zoom

`setFlashMode`, `setTorchMode` and `setZoom` return `Unsupported` for what the current camera does
not offer: most front cameras have no flash, and desktop has no flash, torch or zoom. Read
`capabilities.supportedFlashModes(cameraId)` and `holder.zoom` first.

## Recording

- `Failure(Permission(MICROPHONE, status))`: `enableAudio = true` without the microphone permission.
- `Failure(Unsupported(AUDIO))` on desktop: desktop records no audio.
- `Failure(Busy(LENS))` from a lens switch: switching is refused while recording.
- A recording ended on its own: collect `CameraKEvent.RecordingEnded` from `holder.events`; its
  `reason` says why.

## Plugins

### A plugin reports nothing

Check its `status`. `Failed(error)` names the failure, and `holder.events` carried a
`PluginFailed`. A frame reader that was attached after the holder was created binds frame analysis
late, on Android once and only between recordings; pass plugins at creation.

### Desktop OCR fails

Tesseract needs `eng.traineddata`. See [Plugins](guides/plugins.md#desktop-language-data).

### The media saver fails with `Permission(GALLERY_ADD, status)`

The saver shows no prompt. Ask for `saver.galleryPermission` with `rememberCameraKPermissionState`.

## Getting Help

When you open an issue, include the CameraK version, the platform and OS version, the device, the
`CameraKError` you see (its type and `isRetryable`) and the log with `CameraKLogger.enabled = true`.
CameraK's log lines name operations, not your data, but check before you paste a platform
exception's message, which can contain a path.

- [GitHub Issues](https://github.com/Kashif-E/Kamera/issues)
- [GitHub Discussions](https://github.com/Kashif-E/Kamera/discussions)
