# Installation

## Prerequisites

- Kotlin 2.x and Gradle 8 or later
- Target platforms:
    - **Android**: API 23+ (Android 6.0)
    - **iOS**: iOS 16.0+ on arm64 devices and Apple-silicon simulators (there are no Intel-simulator artifacts since 2.0)
    - **Desktop**: JDK 17+

## Step 1: Add Dependencies

Add the dependencies to your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // The camera with its Compose UI; camerak-core alone for an app without Compose
            implementation("io.github.kashif-mehmood-km:camerak:2.0")

            // Optional plugins
            implementation("io.github.kashif-mehmood-km:camerak-media-saver:2.0")
            implementation("io.github.kashif-mehmood-km:camerak-qr-scanner:2.0")
            implementation("io.github.kashif-mehmood-km:camerak-ocr:2.0")
            implementation("io.github.kashif-mehmood-km:camerak-analyzer:2.0")
        }
        commonTest.dependencies {
            // The fake camera, for tests only
            implementation("io.github.kashif-mehmood-km:camerak-testing:2.0")
        }
    }
}
```

| Artifact | What it is |
|----------|-----------|
| `camerak` | The camera and its Compose UI: `rememberCameraKStateHolder`, `CameraKScreen`, `CameraPreview`, `rememberCameraKPermissionState`. It exports `camerak-core`. |
| `camerak-core` | The camera without Compose: `CameraKStateHolder`, its commands and flows, the plugin and frame APIs. |
| `camerak-testing` | `FakeCameraEngine` and `fakeCameraKStateHolder` for your tests. Declare it in a test source set only. |
| `camerak-media-saver`, `camerak-qr-scanner`, `camerak-ocr`, `camerak-analyzer` | The plugins. |
| `camerak-bom` | Pins every CameraK artifact to one version. |

### Using the BOM

Import the BOM once and leave the versions off the CameraK artifacts:

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

### Using a version catalog

Add to `gradle/libs.versions.toml`:

```toml
[versions]
camerak = "2.0"

[libraries]
camerak = { module = "io.github.kashif-mehmood-km:camerak", version.ref = "camerak" }
camerak-media-saver = { module = "io.github.kashif-mehmood-km:camerak-media-saver", version.ref = "camerak" }
camerak-qr-scanner = { module = "io.github.kashif-mehmood-km:camerak-qr-scanner", version.ref = "camerak" }
camerak-ocr = { module = "io.github.kashif-mehmood-km:camerak-ocr", version.ref = "camerak" }
camerak-analyzer = { module = "io.github.kashif-mehmood-km:camerak-analyzer", version.ref = "camerak" }
camerak-testing = { module = "io.github.kashif-mehmood-km:camerak-testing", version.ref = "camerak" }
```

Then use `implementation(libs.camerak)` and the others in the source sets above.

## Step 2: Platform-Specific Setup

### Android

CameraK's own manifest merges `CAMERA` and the camera `uses-feature` entries (all `required="false"`), and the media saver's merges `WRITE_EXTERNAL_STORAGE` capped at API 28. Declare the rest in your `AndroidManifest.xml`:

```xml
<manifest>
    <uses-permission android:name="android.permission.CAMERA" />
    <!-- Recording with audio only -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <!-- MediaSaverPlugin below API 29 only -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
</manifest>
```

`RECORD_AUDIO` is needed only for a recording with `enableAudio = true` in its `VideoConfiguration`; without it such a recording fails with `CameraKError.Permission` and nothing records. `WRITE_EXTERNAL_STORAGE` grants nothing from API 29, where MediaStore needs no permission for the app's own media, so keep it capped at 28. An app that needs a camera to install declares `android.hardware.camera` with `required="true"` itself.

### iOS

Add the usage descriptions to `Info.plist`. iOS terminates an app that asks for a permission whose usage description is missing, so declare every key for the features you use:

```xml
<!-- Every camera session -->
<key>NSCameraUsageDescription</key>
<string>Camera access is needed to take photos and videos</string>
<!-- Recording with audio (enableAudio = true) -->
<key>NSMicrophoneUsageDescription</key>
<string>Microphone access is needed to record sound with videos</string>
<!-- Saving to Photos with MediaSaverPlugin -->
<key>NSPhotoLibraryAddUsageDescription</key>
<string>Photo library access is needed to save your photos and videos</string>
<!-- Reading a saved photo back with MediaSaverPlugin.readBytes -->
<key>NSPhotoLibraryUsageDescription</key>
<string>Photo library access is needed to show the photos you saved</string>
```

| Key | Needed for |
|-----|-----------|
| `NSCameraUsageDescription` | Every camera session |
| `NSMicrophoneUsageDescription` | A recording with `enableAudio = true`; recordings are silent by default |
| `NSPhotoLibraryAddUsageDescription` | Saving captures or recordings to Photos with `MediaSaverPlugin` |
| `NSPhotoLibraryUsageDescription` | Reading a saved photo back with `MediaSaverPlugin.readBytes` |

Captures and recordings are written to the app's own `Documents/CameraK`, which needs no key. `MediaSaverPlugin.readBytes` fetches the saved asset from Photos by its identifier, which the add-only access that CameraK requests cannot do, so an app that calls it asks for read access itself.

Set the app's deployment target to iOS 16.0 or later. On an older system the camera reports `CameraKState.Error` with `CameraKError.Unsupported(CameraFeature.PLATFORM_VERSION)`.

### Desktop (JVM)

CameraK depends only on bytedeco's Java bindings (`javacv`, `javacpp`, `ffmpeg`, `videoinput`; the OCR plugin adds `tesseract` and `leptonica`), so your app chooses which operating systems' natives it ships. Windows captures through FFmpeg's dshow input, so it needs the `javacpp` and `ffmpeg` natives like the other platforms and no `videoinput` natives: CameraK does not load them. Either declare the classifier jars for each platform you ship:

```kotlin
// desktopMain, one line per library and per platform (macosx-arm64, macosx-x86_64, linux-x86_64,
// linux-arm64, windows-x86_64)
runtimeOnly("org.bytedeco:javacpp:1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:tesseract:5.5.1-1.5.12:macosx-arm64")     // camerak-ocr only
runtimeOnly("org.bytedeco:leptonica:1.85.0-1.5.12:macosx-arm64")    // camerak-ocr only
```

or depend on the `-platform` artifacts (`ffmpeg-platform`, `javacpp-platform`, ...) and narrow them with the [`org.bytedeco.gradle-javacpp-platform`](https://github.com/bytedeco/gradle-javacpp) plugin, setting `javacppPlatform` (or `-Djavacpp.platform=macosx-arm64` on the Gradle command line) to the platforms you ship. Without natives the camera does not open: the holder's state becomes `CameraKState.Error` with `CameraKError.NativeLibraryMissing`, which names the bytedeco platform and keeps JavaCV's `NoClassDefFoundError` ("Could not initialize class org.bytedeco.ffmpeg.global.avutil") as its cause. The OCR plugin's recognitions fail the same way without the Tesseract natives. The Sample's `build.gradle.kts` adds the natives for the machine that builds it.

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

The desktop backend records no audio, so it needs no microphone description: a recording with `enableAudio = true` fails with `CameraKError.Unsupported(CameraFeature.AUDIO)`. The Sample's
`Sample/build.gradle.kts` and `Sample/macos/` show the full setup.

## Step 3: Check the setup

```kotlin
@Composable
fun CameraCheck() {
    val holder = rememberCameraKStateHolder()
    val state by holder.cameraState.collectAsStateWithLifecycle()

    when (val current = state) {
        CameraKState.Idle, CameraKState.Starting -> CircularProgressIndicator()
        CameraKState.Ready, is CameraKState.Interrupted -> Text("The camera is open")
        is CameraKState.Error -> Text("Camera error: ${current.error}")
        CameraKState.Closed -> Unit
    }
}
```

`Error` with `CameraKError.Permission` means the camera permission is not granted yet; ask for it with `rememberCameraKPermissionState(CameraKPermission.CAMERA)` (see the [Quick Start](quick-start.md)).

## Troubleshooting

### Android: the build fails its manifest merge

CameraK needs `minSdk = 23` or higher.

### iOS: the app is killed when the camera opens

`Info.plist` lacks `NSCameraUsageDescription`, or `NSMicrophoneUsageDescription` for a recording with audio.

### Desktop: `NativeLibraryMissing`

Add the bytedeco natives for the platform you run on (above).

## Next Steps

- [Quick Start](quick-start.md): build your first camera screen
- [Configuration](configuration.md): every `CameraConfiguration` field
- [Migrating from 1.x](../migration/2.0.md)
