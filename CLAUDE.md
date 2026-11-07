# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CameraK is a Kotlin Multiplatform (KMP) camera library for Compose Multiplatform, targeting Android (CameraX), iOS (AVFoundation), and Desktop/JVM (JavaCV). Published to Maven Central under `dev.kashifmehmood`; the 1.x coordinates, in the 1.x group (`LEGACY_GROUP` in gradle.properties), relocate to it.

## Build Commands

```bash
scripts/verify.sh                        # Verification harness (CI runs this); --quick, --module, --only, --record-screenshots
./gradlew verifyAll                      # Same Gradle tasks, without the GREEN/RED summary
./gradlew check                          # Full build + formatting + tests
./gradlew spotlessApply                  # Auto-format all Kotlin code (ktlint)
./gradlew spotlessCheck                  # Check formatting without fixing
./gradlew cameraK-core:build             # Build the Compose-free core library
./gradlew Sample:assembleDebug           # Build Android sample APK
./gradlew Sample:run                     # Run desktop sample app
./gradlew dokkaGeneratePublicationHtml   # Generate API docs
./gradlew cameraK-core:desktopTest       # Run JVM tests for a module
./gradlew cameraK-core:desktopTest --tests "com.kashif.cameraK.video.VideoTypesTest"  # Single test class
```

Tests live in each module's `commonTest` (e.g. `cameraK-core/src/commonTest`). They run per-target — `desktopTest` is the fastest loop; `iosSimulatorArm64Test` / `testDebugUnitTest` cover the other targets.

## Module Structure

- **cameraK-core/** - Core library (`camerak-core`), free of Compose: `CameraKStateHolder`, the platform camera engines, recording, the frame source, the plugin SPI
- **cameraK/** - Compose UI (`camerak`): `rememberCameraKStateHolder`, `CameraKScreen`, `CameraPreview`, `rememberCameraKPermissionState`, `rememberRecordingElapsed`, `compose-stability.conf`; depends on `cameraK-core` as `api`
- **cameraK-testing/** - Test fixtures (`camerak-testing`), published, for apps' test source sets only: `FakeCameraEngine`, `fakeCameraKStateHolder`, `FakeFrameSource`, `FakePermissionChecker`
- **ImageSaverPlugin/** - The media saver plugin (`camerak-media-saver`, `MediaSaverPlugin`): publishes captures and recordings to the gallery
- **qrScannerPlugin/** - QR and barcode scanning (`camerak-qr-scanner`)
- **ocrPlugin/** - Text recognition (`camerak-ocr`; ML Kit / Vision / Tesseract)
- **analyzerPlugin/** - JPEG-encoded analysis frames (`camerak-analyzer`)
- **camerak-bom/** - Bill of materials (`camerak-bom`)
- **relocations/** - Relocation POMs from the 1.x coordinates to the 2.0 ones
- **Sample/** - Multiplatform demo app (Android, iOS, Desktop)
- **cameraKScreenshotTests/** - Paparazzi screenshot tests of the Compose UI on Android; not published
- **benchmark/** - The release gate's Macrobenchmark (`com.android.test`) against the Sample's `benchmark` build; built by verify.sh, run by the maintainer on a device; not published
- **iosApp/** - iOS Xcode project for Swift integration

All published library modules target: `androidTarget`, `jvm("desktop")`, `iosArm64()`, `iosSimulatorArm64()`. There is no Intel-simulator target since 2.0.

## Architecture

**Layered design (top to bottom):**

1. **Compose UI** (`:cameraK`) - `rememberCameraKStateHolder` (holder retained in a ViewModel), `CameraKScreen`, `CameraPreview`
2. **CameraKStateHolder** (`:cameraK-core`) - Pure Kotlin. Exposes `cameraState`, `uiState`, `zoom`, `capabilities`, `recordingState` as StateFlows and `captures`, `events` as SharedFlows; suspend commands return `CameraKResult` with a typed `CameraKError`. Owns the recording and the plugins.
3. **CameraEngine** - one per platform (`AndroidCameraEngine`, `AppleCameraEngine`, `DesktopCameraEngine`), behind `@CameraKEngineApi`; each passes the engine contract kit in `cameraK-core/src/engineContract`.
4. **Platform APIs** - CameraX (Android), AVFoundation (iOS), JavaCV (Desktop)

**Key pattern: expect/actual.** Common interfaces in `commonMain`, platform implementations in `androidMain`, `appleMain`, `desktopMain`. Files use platform suffixes: `.android.kt`, `.apple.kt`.

**Plugin system:** Plugins implement `CameraKPlugin` with `onAttach(context: CameraKPluginContext)` / `onDetach()`. The context carries the holder's flows, `frames`, `captures` and the plugin's own `scope`, and no commands; `launchPerSession { }` runs work per Ready session. Each plugin module has its own commonMain/androidMain/appleMain/desktopMain source sets.

**State management:** `CameraKState` is a closed hierarchy (`Idle`, `Starting`, `Ready`, `Interrupted`, `Error`, `Closed`). `CameraUIState` holds what the platform reports (flash, torch, lens, device type, camera id, captures in flight); zoom is `holder.zoom` and recording is `holder.recordingState`. All exposed as flows - no callbacks.

## Key Conventions

- Packages: `com.kashif.cameraK` (core, Compose UI, testing), `com.kashif.mediasaverplugin`, `com.kashif.qrscannerplugin`, `com.kashif.ocrplugin`, `com.kashif.analyzerplugin` (plugins, all lowercase)
- Formatting: Spotless + ktlint 1.5.0 with `.editorconfig`. Composable function naming is exempt from ktlint rules.
- JVM toolchain: Java 17
- Android: minSdk 23, compileSdk 36
- iOS: minimum and deployment target 16.0. Libraries publish klibs only; the static framework an app embeds is built by `Sample`
- Compose stability: core types carry no Compose annotations; `cameraK/compose-stability.conf` lists them
- Concurrency: AtomicFU for lock-free operations, coroutines + StateFlow throughout
- Capture with `holder.capture()`, which writes an app-owned file and returns `CameraKResult<CapturedImage>`; only `MediaSaverPlugin` writes the gallery
- KDoc samples are compiled `@sample` functions in each module's test sources, never fenced blocks (`DocsApiTest.kdocSamplesCompile`); the docs teach no removed 1.x symbol (`DocsApiTest.docsReferenceNoRemovedSymbol`)

## Publishing

Uses vanniktech maven-publish plugin to Maven Central (Sonatype Central Portal). Coordinates: `dev.kashifmehmood:{artifactId}:{version}`. The `relocations/` projects publish relocation POMs under the 1.x group (`LEGACY_GROUP` in the root properties file), one per 1.x artifact id with a 2.0 successor; the release runs `publishLibraries`, then `publishLegacyRelocations`, one Central Portal deployment per namespace. GPG-signed releases. Publishing credentials live only in `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` environment variables, never in a file in this repository; the tracked root properties file holds build settings only. See CONTRIBUTING.md, "Publishing credentials".

Release metadata lives in one place: `VERSION_NAME`, `GROUP` and the shared `POM_*` keys in the root `gradle.properties`, and each module's `POM_ARTIFACT_ID`, `POM_NAME` and `POM_DESCRIPTION` in its own `gradle.properties`. Android SDK levels are `android-compileSdk` / `android-minSdk` in `gradle/libs.versions.toml`. `checkReleaseMetadata` and `checkAndroidSdkFloor` (lint target) fail when they drift.
