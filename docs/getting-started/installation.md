# Installation

Add CameraK to your Kotlin Multiplatform project in under 5 minutes.

## Prerequisites

- Kotlin 1.9.0 or higher
- Gradle 8.0 or higher
- Target platforms:
    - **Android**: API 21+ (Android 5.0)
    - **iOS**: iOS 13.0+
    - **Desktop**: JDK 11+

## Step 1: Add Dependencies

### Using Gradle (Kotlin DSL)

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kashif-mehmood-km:camerak:0.3")
        }
    }
}
```

### Using Version Catalog

Add to `gradle/libs.versions.toml`:

```toml
[versions]
camerak = "0.3"

[libraries]
camerak = { module = "io.github.kashif-mehmood-km:camerak", version.ref = "camerak" }
```

Then in `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.camerak)
        }
    }
}
```

### Android-Only Project

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak:0.3")
}
```

## Step 2: Platform-Specific Setup

### Android

Add permissions to `AndroidManifest.xml`:

```xml
<manifest>
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
</manifest>
```

No additional configuration needed — CameraX is included automatically.

### iOS

Add usage descriptions to `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>This app needs camera access to capture photos</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>This app needs to save photos to your library</string>
```

### Desktop (JVM)

No additional setup required. JavaCV dependencies are included automatically.

## Step 3: Sync Project

Run Gradle sync:

```bash
./gradlew build
```

## Optional: Add Plugins

### QR Scanner Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:qr_scanner_plugin:0.3")
}
```

### OCR Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:ocr_plugin:0.3")
}
```

### Image Saver Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:image_saver_plugin:0.3")
}
```

## Verify Installation

Create a simple test to verify:

```kotlin
@Composable
fun TestCameraScreen() {
    val cameraState by rememberCameraKState()

    when (val state = cameraState) {
        is CameraKState.Ready -> Text("CameraK is ready!")
        is CameraKState.Error -> Text("Error: ${state.message}")
        CameraKState.Initializing -> CircularProgressIndicator()
    }
}
```

If you see "CameraK is ready!" — you're all set!

## Troubleshooting

### Android: "CameraX not found"

Ensure you're using API 21+:

```kotlin
android {
    defaultConfig {
        minSdk = 21
    }
}
```

### iOS: "Camera permission denied"

Check your `Info.plist` has `NSCameraUsageDescription`.

### Desktop: "No camera detected"

Ensure a webcam is connected and accessible by your OS.

## Next Steps

- [Quick Start Guide](quick-start.md) — Build your first camera app
- [Configuration](configuration.md) — Customize camera behavior
- [Platform-Specific Guides](../examples/android.md) — Deep dives per platform
