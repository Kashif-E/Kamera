# Contributing to Kamera

Thank you for your interest in contributing to Kamera. The repository's
[CONTRIBUTING.md](https://github.com/Kashif-E/Kamera/blob/main/CONTRIBUTING.md) is the full guide:
the verification harness, the test-first rule, the lint checks and publishing. This page is the
short version.

## Ways to Contribute

- **Report bugs**: found an issue? Let us know
- **Suggest features**: open a discussion
- **Improve documentation**: fix typos, add examples, clarify
- **Submit pull requests**: fix bugs, add features
- **Help others**: answer questions in Discussions

## Reporting Issues

Before creating an issue, search the existing ones, then give:

- the Kamera version, the platform and OS version, and the device or emulator
- a minimal example that reproduces it
- what you expected and what happened
- the `CameraKError` you see and the log with `CameraKLogger.enabled = true`

### Good Issue Example

````markdown
**Kamera Version:** 2.0
**Platform:** Android 14 (Pixel 7)

**Description:**
The preview stays black with a square aspect ratio.

**Code:**
```kotlin
val holder = rememberCameraKStateHolder(
    CameraConfiguration { aspectRatio = AspectRatio.RATIO_1_1 },
)
CameraKScreen(holder)
```

**State:** `cameraState` is `Ready`; no `Error`.
````

## Development Setup

- JDK 17
- Android Studio or IntelliJ IDEA, with the Android SDK
- Xcode, for the iOS targets

```bash
git clone https://github.com/Kashif-E/Kamera.git
cd Kamera
scripts/verify.sh --quick        # desktop tests, the fastest loop
scripts/verify.sh                # everything CI runs
```

Run the sample app with `./gradlew :Sample:run` (desktop) or `./gradlew :Sample:installDebug`
(Android); open `iosApp` in Xcode for iOS.

## Code Guidelines

- Kotlin coding conventions, formatted by Spotless with ktlint: run `./gradlew spotlessApply`.
  Lines are at most 120 characters.
- Public API has KDoc. A code sample in KDoc is a compiled function in the module's test sources,
  referenced with `@sample`; a fenced code block in KDoc fails `DocsApiTest.kdocSamplesCompile`.
- A command reports its outcome as `CameraKResult`, and a failure as a `CameraKError`, never by
  throwing.
- Every catch that returns a fallback logs the throwable through `CameraKLogger`, naming the
  operation, never a path or a user value.
- Test first: a fix starts with a test that fails without it. Do not loosen an assertion, widen a
  tolerance, delete a case or add an ignore to get a pass.

```kotlin
@Test
fun captureWhileStartingIsNotReady() = runTest {
    val fake = FakeCameraEngine().apply { autoOpen = false }
    val holder = fakeCameraKStateHolder(fake = fake)
    holder.start()
    runCurrent()

    assertEquals(CameraKError.NotReady, holder.capture().errorOrNull)
}
```

## Architecture

1. **Platform engines** (`CameraEngine`, expect/actual): CameraX, AVFoundation, JavaCV. Each
   passes the shared engine contract tests.
2. **`CameraKStateHolder`** (camerak-core): the lifecycle, commands, recording and plugins, over one
   engine. No Compose.
3. **Compose** (camerak): `rememberCameraKStateHolder`, `CameraKScreen`, `CameraPreview`,
   `rememberCameraKPermissionState`.

## Pull Request Process

1. Branch: `git checkout -b fix/your-bug-fix`.
2. Commit with conventional messages (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`).
3. Open the pull request with what changed, why, and how you tested it.
4. `scripts/verify.sh` must be GREEN.

## Breaking Changes

A public API change goes through review, the ABI dumps and the CHANGELOG. A replaced call stays
for one release as an `@Deprecated` hint with a `ReplaceWith`, so users get a quick fix.

## Documentation Changes

The site is built from `docs/` with MkDocs (`mkdocs.yml`). Examples are copy-paste ready and use
the current API: `DocsApiTest.docsReferenceNoRemovedSymbol` fails when a page teaches a removed 1.x
symbol. Only the migration guide names them.

## Questions?

- [GitHub Discussions](https://github.com/Kashif-E/Kamera/discussions)
- [GitHub Issues](https://github.com/Kashif-E/Kamera/issues)
