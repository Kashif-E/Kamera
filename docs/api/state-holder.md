# CameraKStateHolder

`com.kashif.cameraK.state.CameraKStateHolder`, in `camerak-core`. It owns one camera: its
coroutine scope, the platform engine, the plugins and the recording. It has no Compose dependency.

## Creating one

In Compose, `rememberCameraKStateHolder` (camerak) creates it, starts and stops it with the
screen's lifecycle and closes it when the screen goes away:

```kotlin
val holder = rememberCameraKStateHolder(
    config = CameraConfiguration { cameraLens = CameraLens.BACK },
    plugins = listOf(scanner),
    lifecycleOwner = LocalLifecycleOwner.current,   // the default; null to start and stop it yourself
)
```

The holder lives in a ViewModel of the nearest `ViewModelStoreOwner`, one per call site, so an
Activity recreation keeps it, with its plugins and any recording. A new `config` closes it and
builds a new one, which opens its camera once the old one has closed.

Without Compose, build it with the platform context and drive it yourself:

```kotlin
val holder = CameraKStateHolder(CameraConfiguration(), CameraKPlatformContext(applicationContext))
val binding = holder.bindToLifecycle(lifecycle)   // start at STARTED, stop below, re-check on resume
// later
binding.close()
holder.close()
```

`CameraKPlatformContext` takes the Android `Context`, nothing on iOS, and on desktop optionally a
`DesktopGrabberFactory`.

## State

| Member | Type | What it is |
|--------|------|------------|
| `cameraState` | `StateFlow<CameraKState>` | `Idle`, `Starting`, `Ready`, `Interrupted(reason)`, `Error(error)`, `Closed` |
| `uiState` | `StateFlow<CameraUIState>` | `flashMode`, `torchMode`, `cameraLens`, `cameraDeviceType`, `cameraId`, `targetOrientation`, `isCapturing`, `pendingCaptures` |
| `zoom` | `StateFlow<ZoomState>` | `ratio`, `minRatio`, `maxRatio` |
| `capabilities` | `StateFlow<CameraCapabilities>` | Every camera's `LensInfo`, and the modes, formats and features each supports |
| `deviceOrientation` | `StateFlow<DeviceOrientation>` | The device's physical orientation |
| `recordingState` | `StateFlow<RecordingState>` | `Idle`, `Starting`, `Recording`, `Paused`, `Stopping`, `Finished` |
| `captures` | `SharedFlow<CapturedImage>` | Every successful capture, not replayed |
| `events` | `SharedFlow<CameraKEvent>` | `RecordingEnded` and `PluginFailed`, not replayed |
| `frames` | `FrameSource` | Analysis frames for your own code |

`uiState`, `zoom` and the hardware fields change only when the platform reports a change, never
from the requested value. `CameraKState` is closed, so a `when` over it needs no `else`;
`CameraKEvent` is open, so a `when` over an event needs one.

## Lifecycle

| Call | What it does |
|------|--------------|
| `start()` | Opens the camera from `Idle`, or again from `Error`. Returns at once. |
| `stop()` | Closes the camera and returns to `Idle`. A live recording first gets two seconds, so a rotation's stop and start carry on with it. |
| `retry(): Boolean` | Starts again from `Error`; false in any other state. |
| `recheckPermission()` | From `Error(Permission)`, opens the camera once the permission allows it. Call it on resume. |
| `close()` | Releases everything, once; the state becomes `Closed` and every command returns `Failure(Closed)`. |
| `closeAndJoin()` | `close()`, then waits until the release has finished. |

Before opening, the holder checks the camera permission and publishes `Error(Permission)` if it is
missing. It never prompts.

## Commands

Each is a suspend function that returns `CameraKResult<T>`. Before the camera is `Ready` a command
returns `Failure(NotReady)`, and after `close()` `Failure(Closed)`.

| Command | Result | Notes |
|---------|--------|-------|
| `capture()` | `CapturedImage` | Up to three at once; then `Busy(CAPTURE)` |
| `setZoom(ratio)` | `ZoomState` | Clamped into the range; latest wins |
| `setFlashMode(mode)` | `FlashMode` | `Unsupported` for a mode the camera lacks |
| `setTorchMode(mode)` | `TorchMode` | `Unsupported` for a mode the camera lacks |
| `selectCamera(lens, deviceType)` | `LensInfo` | One rebind; `NoCamera` keeps the current camera; `Busy(LENS)` while recording |
| `selectCamera(id)` | `LensInfo` | A `LensInfo.id` from `capabilities` |
| `setTargetOrientation(orientation)` | `Unit` | Fixes capture and recording orientation; null follows the device |
| `prepareRecording(config)` | `Unit` | Adds the recording outputs ahead of time |
| `startRecording(config)` | `RecordingState.Recording` | Once the platform confirms it |
| `stopRecording()` | `RecordedVideo` | Also while Interrupted |
| `pauseRecording()`, `resumeRecording()` | `Unit` | `Unsupported(PAUSE_RESUME)` where the platform cannot |

In `com.kashif.cameraK.controls`: `cycleFlashMode()`, `toggleTorch()` and `toggleLens()`.

## Plugins

`attachPlugin(plugin)` and `detachPlugin(plugin)`; or pass `plugins` at creation, which also binds
frame analysis up front for plugins that read frames. See [Plugins](../guides/plugins.md).

## Errors

`CameraKError` is a closed hierarchy. Each error has `cause` (the platform throwable, if any) and
`isRetryable`:

| Error | Meaning |
|-------|---------|
| `Permission(permission, status)` | A permission is missing; `needsSettings` when only the system settings can grant it |
| `CameraInUse`, `CameraDisabled`, `NoCamera(lens)`, `Disconnected` | The camera could not be opened or went away |
| `Storage(kind, keptFilePath)` | A file could not be written; `keptFilePath` names a file the platform kept |
| `Unsupported(feature)` | The platform or camera cannot do it |
| `NativeLibraryMissing(platform)` | Desktop natives are missing |
| `Hardware(platformCode)`, `Timeout(operation)` | The platform failed or did not answer |
| `Busy(operation)`, `InvalidState(operation)` | The command clashes with one in progress, or makes no sense now |
| `NotReady`, `Closed` | The camera is not open, or the holder is closed |
| `Internal` | A bug; its `cause` is the throwable |

```kotlin
when (val state = holder.cameraState.value) {
    is CameraKState.Error -> when (val error = state.error) {
        is CameraKError.Permission -> if (error.needsSettings) showOpenSettings() else requestPermission()
        else -> if (error.isRetryable) holder.retry()
    }
    else -> Unit
}
```

## Threads

Every member may be called from any thread. The flows are `StateFlow`s and `SharedFlow`s; collect
them where you like. A frame analyzer runs on its registration's own worker, or on the producer
thread when it asks for `synchronous` frames.

## See Also

- [CameraKScreen and CameraPreview](camera-k-screen.md)
- [Configuration](../getting-started/configuration.md)
- [Testing](../guides/testing.md)
