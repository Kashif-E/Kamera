# Testing

`camerak-testing` gives your tests a camera they script: `FakeCameraEngine` plays the platform, and
the holder over it behaves as on a device, because the fake passes the same contract tests as the
real engines. Declare it in a test source set only:

```kotlin
commonTest.dependencies {
    implementation("io.github.kashif-mehmood-km:camerak-testing:2.0")
}
```

## A holder over the fake

```kotlin
@Test
fun showsTheCamera() = runTest {
    val fake = FakeCameraEngine().apply { autoOpen = false }
    val holder = fakeCameraKStateHolder(fake = fake)

    holder.start()
    runCurrent()
    assertEquals(CameraKState.Starting, holder.cameraState.value)

    fake.deliverOpen()
    runCurrent()
    assertEquals(CameraKState.Ready, holder.cameraState.value)
}
```

`fakeCameraKStateHolder(config, fake, plugins, permissionChecker)` needs no opt-in. Everything runs
on the test's scheduler, and the holder is cancelled when the test body ends; close it to test
closing.

## Scripting the fake

| Call | What the platform does |
|------|------------------------|
| `autoOpen = false`, then `deliverOpen()` | Holds the open until the test lets it through |
| `failOpen(error)` | The open fails, e.g. `failOpen(CameraKError.CameraInUse())` |
| `fail(error)` | A running camera fails, e.g. `fail(CameraKError.Disconnected())` |
| `interrupt(reason)`, `endInterruption()` | The system takes the camera and gives it back |
| `reportFlash`, `reportTorch`, `reportZoom`, `reportCamera` | The hardware changes on its own |
| `captureResult = { ... }` | What the next captures return |
| `holdNextCapture()`, `releaseCapture()` | A capture whose callback is late |
| `autoStartRecording`, `autoFinalizeRecording`, `deliverRecordingStarted()`, `deliverRecordingFinalized()`, `finishRecording(result, reason)` | The recording milestones |
| `supportsPause` | Whether pause and resume are supported |

`calls`, `startCalls`, `stopCalls`, `closeCalls`, `setZoomCalls` and the `applied…` values record
what the holder asked the platform to do.

Build results with `FakeCaptures`:

```kotlin
fake.captureResult = { CameraKResult.Success(FakeCaptures.image(path = "/fake/a.jpg")) }
```

and capabilities with `FakeCapabilities.backAndFront(...)`, passed to `FakeCameraEngine(capabilities)`.

## Permissions

```kotlin
val permissions = FakePermissionChecker(PermissionStatus.Denied(shouldShowRationale = false))
val holder = fakeCameraKStateHolder(permissionChecker = permissions)
holder.start()
runCurrent()
// cameraState is Error(Permission(CAMERA, Denied))

permissions.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
holder.recheckPermission()
runCurrent()
// the camera opens
```

## Frames

The fake's frame source delivers what the test emits, through the same admission as the real
sources (busy skip, `maxFps` by the frames' timestamps):

```kotlin
val fake = FakeCameraEngine()
val holder = fakeCameraKStateHolder(fake = fake, plugins = listOf(myPlugin))
holder.start()
runCurrent()

fake.fakeFrames.emit(TestFrame.luma(width = 4, height = 4, bytes = ByteArray(16) { 0x7F }))
runCurrent()
```

`emit` delivers on the calling thread and only while the camera is open. What an analyzer throws is
caught and logged, so assert on what the analyzer recorded, after `emit` returns.

An `emit` from inside an analyzer, to that same registration, is dropped: the registration's
analyzer is still running, so the busy rule skips the frame, as a platform source would. Emit the
next frame from the test after the analyzer returns.

## Compose

`rememberCameraKStateHolder` keeps its holder in the nearest `ViewModelStoreOwner`. A bare
`ImageComposeScene` (or another host with no owner of its own) shares Compose's process-wide owner,
which is never cleared, so a holder remembered there outlives the scene and the next test sees it.
Provide your own owner and clear it when the test ends:

```kotlin
val owner = object : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
scene.setContent {
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        CameraScreen()
    }
}
// after the test:
owner.viewModelStore.clear()
```

## Engine-level tests

`cameraKTestEnvironment()` (dispatchers and time on the test scheduler) and the holder's engine
constructor `CameraKStateHolder(config, engineFactory, plugins, environment)` are
`@CameraKEngineApi`: opt in when you test an engine or a library built on CameraK.

## Compose stability

`camerak` marks core types such as `CameraUIState`, `CameraKError` and `RecordingState` stable in its
`compose-stability.conf`. If your own composables take these types, add the same file to your
module's `stabilityConfigurationFiles` so they skip recomposition too.

## Next Steps

- [Plugins](plugins.md)
- [CameraKStateHolder reference](../api/state-holder.md)
