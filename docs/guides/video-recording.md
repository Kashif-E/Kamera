

# Video Recording

Record high-quality videos with CameraK’s VideoRecorderPlugin. This guide matches the actual usage patterns from the sample app.

---

## Overview

CameraK’s video recording is powered by the `VideoRecorderPlugin`, which provides a simple API for starting, stopping, and monitoring video capture. It supports audio, duration limits, and quality selection.

---


## 1. Prerequisites & Permissions

- CameraK and VideoRecorderPlugin dependencies added to your project
- Camera and storage permissions granted (Android/iOS)

### Android: Update `AndroidManifest.xml`

Add the following permissions:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- Required for video recording with audio -->
```

### iOS: Update `Info.plist`

Add these keys for camera, photo library, and microphone access:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera access required for taking photos and videos</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>Photo library access required for saving videos</string>
<key>NSMicrophoneUsageDescription</key>
<string>Microphone access required for video recording</string>
```

---

## 2. Installation

Add the plugin dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:video_recorder_plugin:0.3")
}
```

---

## 3. Plugin Setup

Configure the plugin with your desired options:

```kotlin
val videoRecorderPlugin = rememberVideoRecorderPlugin(
    config = VideoConfiguration(
        quality = VideoQuality.FHD, // SD, HD, FHD, UHD
        enableAudio = true,         // Record with audio
        maxDurationMs = 300_000L    // Optional: max duration in ms
    )
)
```

Attach the plugin in the `setupPlugins` lambda of `rememberCameraKState`:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(/* ... */),
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(videoRecorderPlugin)
        // Attach other plugins as needed
    }
)
```

---

## 4. Handling Recording Events

Monitor recording state and results using the `recordingEvents` flow:

```kotlin
var isRecording by remember { mutableStateOf(false) }
var recordingDurationMs by remember { mutableStateOf(0L) }

LaunchedEffect(videoRecorderPlugin) {
    videoRecorderPlugin.recordingEvents.collect { event ->
        when (event) {
            is CameraKEvent.RecordingStarted -> {
                isRecording = true
            }
            is CameraKEvent.RecordingStopped,
            is CameraKEvent.RecordingFailed,
            is CameraKEvent.RecordingMaxDurationReached -> {
                isRecording = false
                recordingDurationMs = 0L
            }
            else -> {}
        }
    }
}

LaunchedEffect(isRecording) {
    if (isRecording) {
        while (true) {
            recordingDurationMs = videoRecorderPlugin.recordingDurationMs
            delay(250L)
        }
    }
}
```

---

## 5. UI Integration Example

```kotlin
// Inside your CameraScreen composable:
Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    // ... other controls ...
    ShutterButton(
        mode = cameraMode,
        isRecording = isRecording,
        isCapturing = isCapturing,
        onPhotoCapture = { /* ... */ },
        onVideoToggle = {
            if (isRecording) {
                videoRecorderPlugin.stopRecording()
            } else {
                videoRecorderPlugin.startRecording()
            }
        },
    )
    // ... other controls ...
}
```

---

## 6. Configuration Options

| Option         | Description                        | Example Value         |
| -------------- | ---------------------------------- | -------------------- |
| `quality`      | Video quality (SD/HD/FHD/UHD)      | `VideoQuality.FHD`   |
| `enableAudio`  | Record audio with video            | `true`               |
| `maxDurationMs`| Max duration in milliseconds       | `300_000L`           |

---

## 7. Output Location

- **Android:** Videos saved to DCIM or Pictures directory
- **iOS:** Videos saved to Photos library
- File path is provided in event callbacks

---

## 8. Troubleshooting & Tips

### Recording does not start
- Ensure camera and storage permissions are granted
- Check that the plugin is attached before camera is ready

### Recording stops unexpectedly
- Max duration reached? Listen for `RecordingMaxDurationReached`
- Check for errors in `RecordingFailed` event

### File not found after recording
- Use the file path from the event, and check platform-specific storage rules

---

## 9. See Also

- [Sample App Usage](https://github.com/Kashif-E/CameraK/blob/main/Sample/src/commonMain/kotlin/org/company/app/App.kt)
- [Plugins Guide](plugins.md)
- [CameraKScreen API](../api/camera-k-screen.md)

---

**Need more help?** See the [Troubleshooting](../troubleshooting.md) page or open an issue on GitHub.
