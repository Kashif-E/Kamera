# Installation

Add Kamera to your Kotlin Multiplatform project in under 5 minutes.

## Prerequisites

- Kotlin 1.9.0 or higher
- Gradle 8.0 or higher
- Target platforms:
    - **Android**: API 23+ (Android 6.0)
    - **iOS**: iOS 13.0+ on arm64 devices and Apple-silicon simulators (there are no Intel-simulator artifacts since 2.0)
    - **Desktop**: JDK 17+

## Step 1: Add Dependencies

### Using Gradle (Kotlin DSL)

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kashif-mehmood-km:camerak:2.0")
        }
    }
}
```

### Using the BOM

`camerak-bom` pins the core and every plugin to one version. Import it once and leave the versions off
the CameraK artifacts:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("io.github.kashif-mehmood-km:camerak-bom:2.0"))
            implementation("io.github.kashif-mehmood-km:camerak")
            implementation("io.github.kashif-mehmood-km:qr_scanner_plugin")
        }
    }
}
```

### Using Version Catalog

Add to `gradle/libs.versions.toml`:

```toml
[versions]
camerak = "2.0"

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
    implementation("io.github.kashif-mehmood-km:camerak:2.0")
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

CameraK depends only on bytedeco's Java bindings (`javacv`, `javacpp`, `ffmpeg`, `videoinput`; the OCR plugin adds `tesseract` and `leptonica`), so your app chooses which operating systems' natives it ships. Either declare the classifier jars for each platform you ship:

```kotlin
// desktopMain, one line per library and per platform (macosx-arm64, macosx-x86_64, linux-x86_64,
// linux-arm64, windows-x86_64)
runtimeOnly("org.bytedeco:javacpp:1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:videoinput:0.200-1.5.9:windows-x86_64")   // Windows camera capture
runtimeOnly("org.bytedeco:tesseract:5.5.1-1.5.12:macosx-arm64")     // ocrPlugin only
runtimeOnly("org.bytedeco:leptonica:1.85.0-1.5.12:macosx-arm64")    // ocrPlugin only
```

or depend on the `-platform` artifacts (`ffmpeg-platform`, `javacpp-platform`, ...) and narrow them with the [`org.bytedeco.gradle-javacpp-platform`](https://github.com/bytedeco/gradle-javacpp) plugin, setting `javacppPlatform` (or `-Djavacpp.platform=macosx-arm64` on the Gradle command line) to the platforms you ship. Without natives, opening the camera fails with an `UnsatisfiedLinkError`. The Sample's `build.gradle.kts` adds the natives for the machine that builds it.

## Step 3: Sync Project

Run Gradle sync:

```bash
./gradlew build
```

## Optional: Add Plugins

### QR Scanner Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:qr_scanner_plugin:2.0")
}
```

### OCR Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:ocr_plugin:2.0")
}
```

### Image Saver Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:image_saver_plugin:2.0")
}
```

## Verify Installation

Create a simple test to verify:

```kotlin
@Composable
fun TestCameraScreen() {
    val cameraState by rememberCameraKState()

    when (val state = cameraState) {
        is CameraKState.Ready -> Text("Kamera is ready!")
        is CameraKState.Error -> Text("Error: ${state.message}")
        CameraKState.Initializing -> CircularProgressIndicator()
    }
}
```

If you see "Kamera is ready!" — you're all set!

## Troubleshooting

### Android: "CameraX not found"

Ensure you're using API 23+:

```kotlin
android {
    defaultConfig {
        minSdk = 23
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
