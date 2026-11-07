# Android

## Requirements

- `minSdk = 23` or higher
- CameraX comes with CameraK; you add no CameraX dependency
- A physical device for flash, torch, lens switching and performance; the emulator's cameras cover
  the basics

## Dependency

For an Android-only module:

```kotlin
dependencies {
    implementation("dev.kashifmehmood:camerak:2.0")
}
```

## Manifest

CameraK's manifest merges `CAMERA` and the camera `uses-feature` entries with `required="false"`.
Add what your features need (see [Installation](../getting-started/installation.md#android)):

```xml
<!-- Recording with audio only -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- An app that cannot work without a camera -->
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

## A complete screen

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { CameraRoute() }
        }
    }
}

@Composable
fun CameraRoute() {
    val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA)
    when {
        permissions.allGranted -> CameraScreen()
        permissions.statuses[CameraKPermission.CAMERA] == PermissionStatus.PermanentlyDenied ->
            Text("Allow the camera in the system settings")
        else -> Button(onClick = permissions::launchRequest) { Text("Allow camera") }
    }
}

@Composable
fun CameraScreen() {
    val holder = rememberCameraKStateHolder(
        CameraConfiguration {
            cameraLens = CameraLens.BACK
            aspectRatio = AspectRatio.RATIO_4_3
        },
    )
    val scope = rememberCoroutineScope()
    val uiState by holder.uiState.collectAsStateWithLifecycle()

    CameraKScreen(holder, Modifier.fillMaxSize(), loading = { CircularProgressIndicator() }) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(onClick = { scope.launch { holder.cycleFlashMode() } }) {
                Text("Flash ${uiState.flashMode ?: FlashMode.OFF}")
            }
            Button(onClick = { scope.launch { holder.capture() } }, enabled = !uiState.isCapturing) {
                Text("Capture")
            }
            Button(onClick = { scope.launch { holder.toggleLens() } }) { Text("Switch") }
        }
    }
}
```

`rememberCameraKStateHolder` keeps the holder in the Activity's ViewModel store, so a rotation does
not reopen the camera or end a recording, and it re-checks the permission on every resume, so a
permission granted in the system settings opens the camera with no retry.

## A holder in your own ViewModel

Without Compose, or with a ViewModel of your own:

```kotlin
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    val holder = CameraKStateHolder(CameraConfiguration(), CameraKPlatformContext(application))
        .also { addCloseable(it) }
}

// In the Activity or Fragment:
val binding = viewModel.holder.bindToLifecycle(lifecycle)
```

`bindToLifecycle` starts the holder at STARTED, stops it below and re-checks the permission on
resume. Closing the binding stops the holder without closing it.

## Files

Captures and recordings go to `filesDir/CameraK`, private to the app, unless
`CameraConfiguration.outputDirectory` says otherwise. They reach the gallery only through
`MediaSaverPlugin` (MediaStore; `WRITE_EXTERNAL_STORAGE` up to API 28, which the saver's manifest
declares).

## R8

CameraK ships no consumer keep rules. If a minified build fails inside CameraK, add
`-keep class com.kashif.cameraK.** { *; }` and report the issue.

## Platform notes

- **Device types**: back lenses are classified relative to the main lens, so a 2x telephoto is
  `TELEPHOTO`. Logical multi-camera devices list their physical lenses where the system exposes
  them.
- **Square photos**: `AspectRatio.RATIO_1_1` re-encodes each photo; capture 4:3 and crop to avoid it.
- **PNG**: decoded from CameraX's JPEG and written upright, slower than JPEG.
- **Torch**: `TorchMode.AUTO` is `Unsupported(TORCH)`.

## Common Issues

### `Error(NoCamera)`

The device or emulator has no camera facing that way. Check `holder.capabilities.value.allLenses`.

### `Error(Permission)` that never clears

`status` is `PermanentlyDenied`: `error.needsSettings` is true and only the system settings can
grant it.

### `Error(CameraInUse)`

Another app holds the camera. The error is retryable; the default error slot of `CameraKScreen`
retries once.

## Next Steps

- [Quick Start](../getting-started/quick-start.md)
- [Troubleshooting](../troubleshooting.md)
