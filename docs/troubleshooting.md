# Troubleshooting

Common issues and solutions for CameraK.

## Installation Issues

### "Could not find io.github.kashif-mehmood-km:camerak"

**Cause:** Repository not configured.

**Solution:** Ensure Maven Central is in repositories:

```kotlin
repositories {
    mavenCentral()
}
```

### Gradle Sync Failed

**Cause:** Version conflict or network issue.

**Solution:**
1. Check internet connection
2. Invalidate caches: `File` -> `Invalidate Caches / Restart`
3. Clean build: `./gradlew clean build`

## Permission Issues

### "Camera permission denied" (Android)

**Cause:** User denied camera permission.

**Solution:** Request permission in manifest and code:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

```kotlin
val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
LaunchedEffect(Unit) {
    if (!cameraPermission.status.isGranted) {
        cameraPermission.launchPermissionRequest()
    }
}
```

### "Camera access requires NSCameraUsageDescription" (iOS)

**Cause:** Missing usage description in Info.plist.

**Solution:** Add to Info.plist:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera access required for taking photos</string>
```

## Camera Not Working

### "Camera not available"

**Cause:** Device has no camera or emulator misconfigured.

**Solution:**
- **Physical device**: Ensure camera hardware exists
- **Emulator**: Configure camera in AVD settings
- **Desktop**: Connect webcam

### Camera Preview Black Screen

**Cause:** Camera not initialized or permission denied.

**Solution:**
1. Check camera state is `Ready`
2. Verify permissions granted
3. Restart app

```kotlin
when (cameraState) {
    is CameraKState.Ready -> {
        // Camera operational
    }
    is CameraKState.Error -> {
        val error = cameraState as CameraKState.Error
        println("Error: ${error.message} (retryable: ${error.isRetryable})")
    }
}
```

### Preview Frozen

**Cause:** Camera session stopped.

**Solution:** Restart camera session:

```kotlin
DisposableEffect(Unit) {
    controller.startSession()
    onDispose {
        controller.stopSession()
    }
}
```

## Capture Issues

### "Capture failed: Camera not initialized"

**Cause:** Attempting capture before camera ready.

**Solution:** Only capture when state is `Ready`:

```kotlin
when (cameraState) {
    is CameraKState.Ready -> {
        val controller = (cameraState as CameraKState.Ready).controller
        Button(onClick = {
            scope.launch {
                controller.takePictureToFile()
            }
        }) {
            Text("Capture")
        }
    }
}
```

### "Storage permission denied" (Android < 10)

**Cause:** Missing WRITE_EXTERNAL_STORAGE permission.

**Solution:** Add to manifest:

```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

### Images Not Saving

**Cause:** Invalid directory or storage full.

**Solution:**
1. Check storage space
2. Use valid directory:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        directory = Directory.PICTURES  // or DCIM, DOCUMENTS
    )
)
```

### Poor Image Quality

**Cause:** Low quality prioritization or resolution.

**Solution:** Configure for quality:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        qualityPrioritization = QualityPrioritization.QUALITY,
        targetResolution = 3840 to 2160,  // 4K
        imageFormat = ImageFormat.PNG,  // Lossless
    )
)
```

## Flash/Torch Issues

### Flash Not Working

**Cause:** Front camera selected (no flash) or device doesn't support flash.

**Solution:** Switch to rear camera:

```kotlin
controller.toggleCameraLens()
controller.setFlashMode(FlashMode.ON)
```

### Torch Stays On After Closing App

**Cause:** Torch not disabled in cleanup.

**Solution:** Disable in cleanup:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        controller.setTorchMode(TorchMode.OFF)
        controller.cleanup()
    }
}
```

## Zoom Issues

### Zoom Not Working

**Cause:** Camera doesn't support zoom or at max zoom.

**Solution:** Check zoom support:

```kotlin
val maxZoom = controller.getMaxZoom()
if (maxZoom > 1.0f) {
    controller.setZoom(2.0f)
} else {
    println("Zoom not supported")
}
```

### Zoom Resets When Switching Cameras

**Cause:** Each camera has independent zoom settings.

**Solution:** Re-apply zoom after switch:

```kotlin
val savedZoom = controller.getZoom()
controller.toggleCameraLens()
delay(200)
controller.setZoom(savedZoom.coerceIn(1f, controller.getMaxZoom()))
```

## Performance Issues

### Slow Capture

**Cause:** Using deprecated `takePicture()` instead of `takePictureToFile()`.

**Solution:** Use recommended method:

```kotlin
// Slow (deprecated)
val result = controller.takePicture()

// Fast (recommended)
val result = controller.takePictureToFile()
```

### App Crashes on Capture

**Cause:** Out of memory or too many concurrent captures.

**Solution:**
1. Limit burst captures to 3-5 photos
2. Use lower resolution
3. Use `takePictureToFile()` instead of `takePicture()`

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        targetResolution = 1920 to 1080  // Lower resolution
    )
)
```

### High Memory Usage

**Cause:** Multiple plugins or high-resolution processing.

**Solution:**
1. Reduce plugin count
2. Lower resolution
3. Use `ImageFormat.JPEG` instead of PNG

## Plugin Issues

### QR Scanner Not Detecting Codes

**Cause:** Poor lighting or QR code quality.

**Solution:**
1. Enable torch for better lighting
2. Ensure QR code is clear and unobstructed
3. Move camera closer to QR code

### OCR Not Recognizing Text

**Cause:** Text too small, blurry, or language not supported.

**Solution:**
1. Move camera closer
2. Ensure good lighting
3. Hold camera steady (avoid motion blur)

### Plugin Not Activating

**Cause:** Plugin added after camera already ready.

**Solution:** Add plugins during initialization using `setupPlugins`:

```kotlin
val cameraState by rememberCameraKState(
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(rememberQRScannerPlugin())
        stateHolder.attachPlugin(rememberOcrPlugin())
    },
)
```

## Platform-Specific Issues

### Android

**Issue:** "CameraX binding failed"

**Solution:** Ensure minSdk is 21+:

```kotlin
android {
    defaultConfig {
        minSdk = 21
    }
}
```

**Issue:** "No suitable camera found"

**Solution:** Check device has both front and back cameras, or handle gracefully:

```kotlin
val lens = controller.getCameraLens()
if (lens == null) {
    println("Camera not available")
}
```

### iOS

**Issue:** "Camera preview upside down"

**Solution:** Device orientation handling. CameraK handles this automatically - report if issue persists.

**Issue:** "Camera types not available (TELEPHOTO, ULTRA_WIDE)"

**Solution:** These features require specific iPhone models:
- Ultra-wide: iPhone 11+
- Telephoto: iPhone 7 Plus+

Check availability:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        cameraDeviceType = CameraDeviceType.ULTRA_WIDE
    )
)
// Falls back to DEFAULT if not available
```

### Desktop

**Issue:** "No webcam detected"

**Solution:** Ensure webcam is:
1. Connected to computer
2. Not in use by another application
3. Drivers installed

**Issue:** Flash/torch not working

**Cause:** Desktop webcams don't have flash hardware.

**Solution:** Use external lighting.

## Build Issues

### "Duplicate class" Error

**Cause:** Conflicting dependencies.

**Solution:** Check for duplicate libraries:

```bash
./gradlew :app:dependencies
```

Remove conflicting CameraX or Kotlin versions.

### iOS Build Fails

**Cause:** Cocoapods not configured.

**Solution:** Run in iOS project:

```bash
cd iosApp
pod install
```

## Getting Help

If you're still stuck:

1. **Check Examples**: [Sample Projects](https://github.com/Kashif-E/CameraK/tree/main/Sample)
2. **Search Issues**: [GitHub Issues](https://github.com/Kashif-E/CameraK/issues)
3. **Ask Questions**: [GitHub Discussions](https://github.com/Kashif-E/CameraK/discussions)
4. **Report Bugs**: [New Issue](https://github.com/Kashif-E/CameraK/issues/new)

### When Reporting Issues

Include:
- CameraK version
- Platform (Android/iOS/Desktop)
- Device/emulator details
- Minimal reproducible code
- Stack trace/error messages
- Expected vs actual behavior

**Good issue:**
```
CameraK 0.3
Android 13 (Pixel 6)

Preview shows black screen when using:
```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(...)
)
```

Error: "Camera not available"

Expected: Camera preview displays
```

## Known Limitations

1. **Desktop**: Limited flash/torch support (hardware limitation)
2. **iOS Simulator**: Camera not available (use real device)
3. **Android Emulator**: Limited camera features (use real device for testing)
4. **Burst Capture**: Max 3 concurrent captures (prevents memory issues)

## Next Steps

- [Quick Start](getting-started/quick-start.md) -- Get started quickly
- [Configuration](getting-started/configuration.md) -- Customize behavior
- [API Reference](api/state-holder.md) -- Full API documentation
