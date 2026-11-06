# CameraKScreen and CameraPreview

The Compose UI in `camerak` (`com.kashif.cameraK.compose`). Both take a `CameraKStateHolder`,
usually from `rememberCameraKStateHolder`, and neither opens or closes the camera: the holder does.

## CameraKScreen

```kotlin
@Composable
fun CameraKScreen(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = {},
    error: @Composable (error: CameraKError, retry: () -> Unit) -> Unit = /* retries once */,
    content: @Composable CameraPreviewScope.() -> Unit = {},
)
```

| `cameraState` | Shows |
|---------------|-------|
| `Idle`, `Starting` | `loading` |
| `Ready`, `Interrupted` | The preview, with `content` over it. Both stay composed from one state to the other, so a Stop button stays reachable while a phone call holds the camera |
| `Error` | `error(error, retry)`; `retry` calls `holder.retry()` |
| `Closed` | Nothing |

The defaults draw no text or colours of the library's own. The default `error` slot retries a
retryable error once, until the camera is Ready again; it leaves `Error(Permission)` to the
holder's resume re-check.

```kotlin
val holder = rememberCameraKStateHolder()
val scope = rememberCoroutineScope()

CameraKScreen(
    holder = holder,
    modifier = Modifier.fillMaxSize(),
    loading = { CircularProgressIndicator() },
    error = { error, retry ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (error) {
                    is CameraKError.Permission -> "Camera permission needed"
                    is CameraKError.CameraInUse -> "Another app is using the camera"
                    else -> "The camera failed"
                },
            )
            if (error.isRetryable) Button(onClick = retry) { Text("Try again") }
        }
    },
) {
    val state by holder.cameraState.collectAsStateWithLifecycle()
    if (state is CameraKState.Interrupted) {
        Text("Paused", Modifier.align(Alignment.Center))
    }
    Button(
        onClick = { scope.launch { holder.capture() } },
        modifier = Modifier.align(Alignment.BottomCenter).keepUpright(),
    ) { Text("Capture") }
}
```

## CameraPreview

The preview alone, for a layout of your own:

```kotlin
@Composable
fun CameraPreview(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    overlay: @Composable CameraPreviewScope.() -> Unit = {},
)
```

```kotlin
val state by holder.cameraState.collectAsStateWithLifecycle()
Column {
    if (state is CameraKState.Ready) {
        CameraPreview(holder, Modifier.weight(1f).fillMaxWidth(), contentDescription = "Camera preview")
    }
    Controls(holder)
}
```

The frame is fitted into the preview's box, letterboxed where the aspect ratios differ. What the
overlay's box covers depends on the platform:

- **Android**: the viewfinder and the overlay share a centred box of the configured aspect ratio,
  turned to the screen's orientation, so overlay coordinates map 1:1 to the camera image.
- **iOS**: the preview fills a centred box of the configured aspect ratio, showing what a capture
  keeps; the overlay's box is the whole preview box.
- **Desktop**: the overlay's box is the frame's.

Two previews of one holder, as in a crossfade, both show it. To run the camera with no preview at
all, use the holder without `CameraPreview`.

## CameraPreviewScope

The `content` of `CameraKScreen` and the `overlay` of `CameraPreview` run in a
`CameraPreviewScope`, a `BoxScope` with:

- `deviceOrientation`: the device's physical orientation.
- `Modifier.keepUpright()`: turns a control to the device's orientation, for a screen locked to
  portrait.

## rememberCameraKPermissionState

```kotlin
@Composable
fun rememberCameraKPermissionState(
    vararg permissions: CameraKPermission,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit = {},
): CameraKPermissionState
```

`statuses` holds each permission's `PermissionStatus` (`Granted`, `Limited`, `Denied`,
`PermanentlyDenied`, `Restricted`, `NotDetermined`), read again on every resume and after each
request; `allGranted` is true when all are `Granted`. `launchRequest()` shows the system prompts for
what can still be asked; call it from an event handler, never from composition. A permanently
denied permission gets no prompt: send the user to the system settings.

The permissions are `CAMERA`, `MICROPHONE` (recordings with audio) and `GALLERY_ADD` (the media
saver).

## rememberRecordingElapsed

```kotlin
val elapsed by rememberRecordingElapsed(holder, tick = 1.seconds)
```

The live recording's elapsed time as `State<Duration>`: updated once per `tick` while recording,
held while paused, zero otherwise.

## See Also

- [CameraKStateHolder](state-holder.md)
- [Quick Start](../getting-started/quick-start.md)
