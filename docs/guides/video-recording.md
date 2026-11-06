# Video Recording

Recording is part of the core: the holder records, with no plugin. Its commands return their
outcome, and `holder.recordingState` says where the recording is.

## Permissions

A recording is silent unless its `VideoConfiguration` sets `enableAudio = true`. With audio it needs
the microphone permission: `RECORD_AUDIO` in the Android manifest and `NSMicrophoneUsageDescription`
in the iOS `Info.plist` (see [Installation](../getting-started/installation.md)). Ask for it before
recording:

```kotlin
val microphone = rememberCameraKPermissionState(CameraKPermission.MICROPHONE)
```

Without it, `startRecording` with audio returns `Failure(CameraKError.Permission(MICROPHONE, status))`
and nothing records. Desktop records no audio: a recording with `enableAudio = true` returns
`Failure(CameraKError.Unsupported(CameraFeature.AUDIO))`.

## Start and stop

```kotlin
scope.launch {
    holder.startRecording(
        VideoConfiguration {
            quality = VideoQuality.FHD
            maxDuration = 60.seconds
        },
    ).onFailure { error -> showError(error) }
}

scope.launch {
    holder.stopRecording()                      // CameraKResult<RecordedVideo>
        .onSuccess { video -> show(video.path, video.duration) }
}
```

`startRecording` returns once the platform confirms that recording began, with the
`RecordingState.Recording` it is in. A second start while one is live is
`Failure(Busy(RECORD_START))`, and a start the platform does not confirm within five seconds is
`Failure(Timeout(RECORD_START))`.

`stopRecording` returns the finished `RecordedVideo`: its `path`, `duration`, `width`, `height`,
`bitrateBps` and `hasAudio`. Stopping when nothing records is `Failure(InvalidState)`. A stop also
runs while the camera is Interrupted, so the platform can still finish the file.

To avoid the short rebind the first recording can cause, call `holder.prepareRecording(config)`
when the recording screen opens.

## Pause and resume

```kotlin
scope.launch { holder.pauseRecording() }
scope.launch { holder.resumeRecording() }
```

Where the platform cannot pause, `pauseRecording` is `Failure(Unsupported(PAUSE_RESUME))`;
`holder.capabilities.value.supportsPauseResume` says so up front. Both are `Failure(NotReady)`
while the camera is Interrupted.

## The recording's state

```kotlin
val recording by holder.recordingState.collectAsStateWithLifecycle()
val elapsed by rememberRecordingElapsed(holder)

when (val state = recording) {
    RecordingState.Idle -> RecordButton(onClick = { scope.launch { holder.startRecording() } })
    is RecordingState.Starting -> Text("Starting...")
    is RecordingState.Recording, is RecordingState.Paused -> {
        Text("${elapsed.inWholeMinutes}:${(elapsed.inWholeSeconds % 60).toString().padStart(2, '0')}")
        StopButton(onClick = { scope.launch { holder.stopRecording() } })
    }
    is RecordingState.Stopping -> Text("Saving...")
    is RecordingState.Finished -> {
        state.result.onSuccess { video -> Text("Saved ${video.duration}") }
        RecordButton(onClick = { scope.launch { holder.startRecording() } })
    }
}
```

`RecordingState` is closed: `Idle`, `Starting`, `Recording`, `Paused`, `Stopping`, then `Finished`
with the `result` and the `reason` after any end. `rememberRecordingElapsed(holder)` (camerak) is
the recorded time, updated once a second while recording, held while paused and zero otherwise;
outside Compose, `recordingState.value.elapsed()` reads it.

## Ends you did not ask for

A recording that ends without `stopRecording` (a limit, an interruption, an error, the screen
stopping, `close()`) arrives as `CameraKEvent.RecordingEnded` on `holder.events`, with the same
outcome in `recordingState` as `Finished`:

```kotlin
LaunchedEffect(holder) {
    holder.events.collect { event ->
        if (event is CameraKEvent.RecordingEnded) {
            when (event.reason) {
                RecordingEndReason.MAX_DURATION -> showMessage("Time limit reached")
                RecordingEndReason.MAX_FILE_SIZE -> showMessage("Size limit reached")
                else -> event.result.onFailure { error -> showError(error) }
            }
        }
    }
}
```

The reasons are `USER`, `MAX_DURATION`, `MAX_FILE_SIZE`, `INTERRUPTED`, `ERROR` and `SHUTDOWN`. A
file that the platform kept after a failure is named by `CameraKError.Storage.keptFilePath`.

## Rotation

`rememberCameraKStateHolder` keeps the holder across an Activity recreation, and a stop followed by
a start within two seconds carries on with the same recording. A rotation therefore does not end a
recording; leaving the screen does, with reason `INTERRUPTED`.

## Configuration

| Field | Default | Meaning |
|-------|---------|---------|
| `quality` | `FHD` | `SD`, `HD`, `FHD` or `UHD`: size and bitrate |
| `enableAudio` | `false` | Record the microphone; needs its permission |
| `maxDuration` | `null` | The longest the recording runs; null is unlimited. The file is kept |
| `maxFileSizeBytes` | `null` | The largest the file grows; null is unlimited. The file is kept |
| `outputDirectory` | `null` | Where the file goes; null uses `CameraConfiguration.outputDirectory`, else app-owned storage |
| `filePrefix` | `"VID"` | 1 to 64 characters from `A-Z a-z 0-9 . _ -` |

A negative limit or an invalid prefix throws `IllegalArgumentException` when the configuration is
built.

## Where files go

Recordings are app-owned files: `filesDir/CameraK` on Android, `Documents/CameraK` on iOS,
`~/Movies/CameraK` on macOS and `~/Videos/CameraK` on Linux and Windows, unless an
`outputDirectory` says otherwise. To publish one to the gallery, pass it to
`MediaSaverPlugin.save(video)` (see [Plugins](plugins.md#media-saver-plugin)).

## Lens switches

`selectCamera` and `toggleLens` during a recording return `Failure(Busy(LENS))` (or
`Busy(DEVICE_TYPE)`) and change nothing.

## Next Steps

- [Plugins](plugins.md)
- [CameraKStateHolder reference](../api/state-holder.md)
