# Installation

Add Kamera to your Kotlin Multiplatform project in under 5 minutes.

## Prerequisites

- Kotlin 1.9.0 or higher
- Gradle 8.0 or higher
- Target platforms:
    - **Android**: API 23+ (Android 6.0)
    - **iOS**: iOS 16.0+ on arm64 devices and Apple-silicon simulators (there are no Intel-simulator artifacts since 2.0)
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
            implementation("io.github.kashif-mehmood-km:camerak-qr-scanner")
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

Add usage descriptions to `Info.plist`. iOS terminates the app when it asks for a permission whose usage description is missing, so declare every key for the features you use:

```xml
<!-- Every camera session -->
<key>NSCameraUsageDescription</key>
<string>This app needs camera access to capture photos</string>
<!-- Video recording with audio (VideoConfiguration.enableAudio is true by default) -->
<key>NSMicrophoneUsageDescription</key>
<string>This app needs microphone access to record video with sound</string>
<!-- Saving photos and videos to Photos (Directory.PICTURES or DCIM; captures default to the app's Documents), and the image saver plugin -->
<key>NSPhotoLibraryAddUsageDescription</key>
<string>This app needs to save photos to your library</string>
<!-- RequestStoragePermission, which asks for read-write Photos access, and reading a saved ph:// photo back -->
<key>NSPhotoLibraryUsageDescription</key>
<string>This app needs to show photos it saved to your library</string>
```

| Key | Needed for |
|-----|-----------|
| `NSCameraUsageDescription` | Any camera use |
| `NSMicrophoneUsageDescription` | Recording video with audio; pass `VideoConfiguration(enableAudio = false)` to record without it |
| `NSPhotoLibraryAddUsageDescription` | Saving captures to Photos (`Directory.PICTURES` or `Directory.DCIM`; captures default to the app's Documents) and the image saver plugin |
| `NSPhotoLibraryUsageDescription` | `RequestStoragePermission` and reading a saved `ph://` photo back |

An app that saves only to `Directory.DOCUMENTS` and does not use the image saver plugin needs no `NSPhotoLibraryAddUsageDescription`; it still needs `NSPhotoLibraryUsageDescription` if it calls `RequestStoragePermission`.

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

or depend on the `-platform` artifacts (`ffmpeg-platform`, `javacpp-platform`, ...) and narrow them with the [`org.bytedeco.gradle-javacpp-platform`](https://github.com/bytedeco/gradle-javacpp) plugin, setting `javacppPlatform` (or `-Djavacpp.platform=macosx-arm64` on the Gradle command line) to the platforms you ship. Without natives the camera does not open: the desktop controller logs an `IllegalStateException` that names the missing bytedeco natives, caused by JavaCV's `NoClassDefFoundError` ("Could not initialize class org.bytedeco.ffmpeg.global.avutil"), and the OCR plugin's calls throw `UnsatisfiedLinkError` the first time and `NoClassDefFoundError` after that. The Sample's `build.gradle.kts` adds the natives for the machine that builds it.

On macOS, a packaged app (DMG or PKG) that opens the camera without a camera usage description is
denied or killed by the system's privacy controls (TCC). `./gradlew run` does not show the
problem, because the terminal already holds the camera permission. Add the description, and the
camera entitlement for a signed build with the hardened runtime, to `compose.desktop`:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            macOS {
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCameraUsageDescription</key>
                        <string>This app needs camera access to capture photos</string>
                    """.trimIndent()
                }
                entitlementsFile.set(project.file("macos/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("macos/runtime-entitlements.plist"))
            }
        }
    }
}
```

Both entitlements files grant `com.apple.security.device.camera`, plus what the JVM and JavaCV's
native libraries need under the hardened runtime:

```xml
<key>com.apple.security.cs.allow-jit</key>
<true/>
<key>com.apple.security.cs.allow-unsigned-executable-memory</key>
<true/>
<key>com.apple.security.cs.disable-library-validation</key>
<true/>
<key>com.apple.security.device.camera</key>
<true/>
```

The desktop backend records no audio, so it needs no microphone description. The Sample's
`Sample/build.gradle.kts` and `Sample/macos/` show the full setup.

## Step 3: Sync Project

Run Gradle sync:

```bash
./gradlew build
```

## Optional: Add Plugins

### QR Scanner Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak-qr-scanner:2.0")
}
```

### OCR Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak-ocr:2.0")
}
```

### Image Saver Plugin

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak-media-saver:2.0")
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
