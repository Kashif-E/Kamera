# Android Setup

Setup CameraK for Android applications.

## Requirements

- **Minimum SDK**: API 21 (Android 5.0 Lollipop)
- **Target SDK**: API 34+ recommended
- **Kotlin**: 1.9.0+
- **Gradle**: 8.0+
- **Compose**: 1.5.0+

## Step 1: Add Dependency

### build.gradle.kts (Module-level)

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak:0.3")
}
```

### Version Catalog

`gradle/libs.versions.toml`:

```toml
[versions]
camerak = "0.3"

[libraries]
camerak = { module = "io.github.kashif-mehmood-km:camerak", version.ref = "camerak" }
```

`build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.camerak)
}
```

## Step 2: Permissions

### AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Camera feature -->
    <uses-feature
        android:name="android.hardware.camera"
        android:required="true" />

    <!-- Camera permission -->
    <uses-permission android:name="android.permission.CAMERA" />

    <!-- Storage permission (Android 9 and below) -->
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <application
        android:name=".MyApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.App">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

## Step 3: Request Permissions

CameraK includes a built-in, cross-platform permission API. No third-party libraries needed.

### Using CameraK's Permission API

```kotlin
@Composable
fun CameraScreenWithPermissions() {
    val permissions = providePermissions()
    var cameraGranted by remember { mutableStateOf(permissions.hasCameraPermission()) }
    var storageGranted by remember { mutableStateOf(permissions.hasStoragePermission()) }

    if (!cameraGranted) {
        permissions.RequestCameraPermission(
            onGranted = { cameraGranted = true },
            onDenied = { /* Show rationale or denied UI */ }
        )
    }

    if (!storageGranted) {
        permissions.RequestStoragePermission(
            onGranted = { storageGranted = true },
            onDenied = { /* Show rationale or denied UI */ }
        )
    }

    if (cameraGranted && storageGranted) {
        CameraScreen()
    }
}
```

### Camera Screen

Once permissions are granted, use `rememberCameraKState()`:

```kotlin
@Composable
fun CameraScreen() {
    val cameraState by rememberCameraKState()

    when (cameraState) {
        is CameraKState.Ready -> {
            val ready = cameraState as CameraKState.Ready
            CameraPreviewView(
                controller = ready.controller,
                modifier = Modifier.fillMaxSize()
            )
        }
        is CameraKState.Error -> {
            val error = cameraState as CameraKState.Error
            Text("Camera error: ${error.message}")
        }
        CameraKState.Initializing -> {
            CircularProgressIndicator()
        }
    }
}
```

## Step 4: Basic Implementation

```kotlin
@Composable
fun AndroidCameraScreen() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState(
        config = CameraConfiguration(
            cameraLens = CameraLens.BACK,
            flashMode = FlashMode.AUTO,
            aspectRatio = AspectRatio.RATIO_16_9,
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (cameraState) {
            is CameraKState.Ready -> {
                val ready = cameraState as CameraKState.Ready
                val controller = ready.controller

                // Camera preview
                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Capture button
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            when (val result = controller.takePictureToFile()) {
                                is ImageCaptureResult.SuccessWithFile -> {
                                    Toast.makeText(
                                        context,
                                        "Photo saved: ${result.filePath}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is ImageCaptureResult.Error -> {
                                    Toast.makeText(
                                        context,
                                        "Error: ${result.exception.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                }
            }

            is CameraKState.Error -> {
                val error = cameraState as CameraKState.Error
                Text(
                    text = "Camera Error: ${error.message}",
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
```

## Step 5: ProGuard Rules

If using ProGuard/R8, add rules:

### proguard-rules.pro

```proguard
# CameraK
-keep class com.kashif.cameraK.** { *; }
-keepclassmembers class com.kashif.cameraK.** { *; }

# CameraX
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
```

## File Storage Paths

On Android, photos are saved to:

- `Directory.PICTURES` -> `/storage/emulated/0/Pictures/`
- `Directory.DCIM` -> `/storage/emulated/0/DCIM/`
- `Directory.DOCUMENTS` -> `/data/data/your.package/files/Documents/`

## Testing

### Emulator Setup

1. Create AVD with camera support
2. Enable camera in AVD settings:
   - **Front camera**: Webcam or Emulated
   - **Back camera**: VirtualScene or Emulated

### Physical Device

Test on real device for:
- Flash/torch functionality
- Camera switching
- High-resolution capture
- Performance testing

## Common Issues

### "Camera not available"

**Cause:** Emulator doesn't have camera configured.

**Solution:** Use physical device or configure AVD camera.

### "Permission denied"

**Cause:** Camera permission not granted.

**Solution:** Check manifest has `<uses-permission android:name="android.permission.CAMERA" />`.

### "No space left on device"

**Cause:** Storage full.

**Solution:** Clear device storage or reduce image resolution.

### CameraX Initialization Error

**Cause:** CameraX version conflict.

**Solution:** CameraK includes CameraX automatically -- don't add manual CameraX dependencies.

## Platform-Specific Features

### Android-Only Features

- **Image Analysis** -- Process frames in real-time
- **CameraX Integration** -- Built on Android's CameraX
- **Scoped Storage** -- Android 10+ privacy-safe storage

### Configuration Example

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        // Android supports all features
        cameraLens = CameraLens.BACK,
        aspectRatio = AspectRatio.RATIO_16_9,
        targetResolution = 1920 to 1080,
        flashMode = FlashMode.AUTO,
        imageFormat = ImageFormat.JPEG,
        directory = Directory.PICTURES,
        qualityPrioritization = QualityPrioritization.BALANCED,
    )
)
```

## Next Steps

- [Quick Start](../getting-started/quick-start.md) -- Build your first camera app
- [Configuration](../getting-started/configuration.md) -- Customize settings
- [Camera Capture](../guides/camera-capture.md) -- Advanced capture techniques
