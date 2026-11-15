# Desktop recordings with audio

**Date:** 2026-11-14
**Status:** Proposed. The approach was agreed in session; this document is awaiting approval. A builder check
read it; its questions are answered below, marked "my call" where the choice was mine.
**Replaces:** Decision 16 ("desktop records video only"): `enableAudio = true` on desktop is `Unsupported(AUDIO)` today.

## Why

Asked for in session as "Support audio". On desktop, `VideoConfiguration.enableAudio = true` fails with
`Failure(Unsupported(AUDIO))`, while Android and iOS record sound with the same switch. On macOS it is worse:
the holder's microphone gate runs first and fails the start with `Permission(MICROPHONE, NotDetermined)`, a
permission no prompt can ever grant, because desktop never opens the microphone. The device matrix on this Mac
showed it: 16 video cases with audio BLOCKED on 2026-11-14.

## Goals

- `enableAudio = true` on desktop records the default microphone into the MP4 as an AAC track, in sync with the
  video, on macOS, Linux and Windows. The app API does not change: the same `VideoConfiguration.enableAudio`,
  the same `startRecording` / `prepareRecording`.
- `EngineRecordingEvent.Started.hasAudio` and `RecordedVideo.hasAudio` are true for a recording with an audio
  track.
- Pause and resume leave the paused time out of the audio, as they already do for the video.
- `maxDuration` and `maxFileSizeBytes` keep working, now counting the audio's bytes too.
- No microphone, or one that cannot be opened: the recording fails before it starts, and nothing is recorded.
  It never silently produces a file without sound.
- macOS: the microphone prompt appears when the first recording with audio starts, as the camera prompt
  appears when the camera opens. A denied or restricted microphone fails the start with
  `Permission(MICROPHONE, status)`, as on the other platforms.
- The desktop Sample, packaged for macOS, can use the microphone: the usage text and the entitlement.

## Non-goals

- Choosing a microphone. The default capture device is used; no device list or selection API.
- Audio level metering, gain, or any audio processing.
- An audio track in the analysis frames or plugins.
- A `rememberCameraKPermissionState(MICROPHONE).launchRequest()` that prompts on macOS. It still prompts for
  nothing on desktop; the prompt comes with the recording.
- Correcting long-term drift between the microphone's sample clock and the system clock beyond aligning the
  start (see Sync). Recordings are short clips; a drift of well under a second an hour is accepted.

## Anti-goals: these would make it a failure even if it works

- A file whose audio track is silent or missing while `hasAudio` says true, where CameraK could have known.
- Any change to the recorder's threading invariant: one coroutine owns the recorder from `start()` to
  `release()`. The microphone's thread never calls the recorder.
- A new native dependency, or new per-OS device naming through FFmpeg's input formats (avfoundation, dshow,
  alsa/pulse). Most of the desktop camera bugs came from those.

## Decided

- **Capture:** Java Sound (`javax.sound.sampled.TargetDataLine`), the JDK's own microphone API, on all three
  OSes. It is in `java.desktop`, which every Compose desktop app already ships.
- **Encoding:** the existing `FFmpegFrameRecorder` gets an audio stream (AAC) when `enableAudio` is set, and the
  samples are written with `recordSamples`. FFmpeg stamps audio by sample count; `setTimestamp` only sets the
  video frame number, so the two do not interfere.
- **Threading:** a capture thread reads the line and hands chunks of samples to the recording through a
  bounded queue. The recording's coroutine, which already owns the recorder, writes queued audio before each
  video frame and drains the queue when the recording ends.
- **Pause:** samples captured while the recording is paused are dropped. Because audio is stamped by sample
  count, the pause leaves the audio as it leaves the video.
- **Sync:** the audio starts at the same instant as the video's recording clock. Samples captured before that
  instant are dropped, and if the line starts later, silence fills the gap, so both tracks start together.
- **The holder's microphone gate** asks the platform checker's `allowsOpen(status)`, as the camera's start
  already does, instead of requiring Granted. Android and iOS allow only Granted, so nothing changes there; the
  desktop checker also allows NotDetermined, because the microphone's open shows the macOS prompt. The Mac
  checker keeps reading the real microphone status.
- **Errors:**
  - no capture line supports the format (no microphone, as on CI runners): `Unsupported(AUDIO)`, from
    `prepareRecording` and `startRecording`, before any recorder opens;
  - the line exists but cannot be opened (busy, or the OS refused it): `Hardware(null, cause)`; the recorder and
    the file are released and deleted;
  - macOS Denied, PermanentlyDenied or Restricted: `Permission(MICROPHONE, status)` from the holder's gate.
- **The microphone failing during a recording** (my call): a line that closes or whose `read()` throws ends
  the recording as INTERRUPTED, as the camera's frames ending does. The file is kept when playable, so the app
  keeps what was recorded, and the track never goes silent while `hasAudio` says true. The capture thread only
  queues the failure; the recording's coroutine sees it before the next video frame and ends through its one
  end path. A line that stays open but stops delivering samples is not detected (a known limit).
  `InterruptionReason.AUDIO_IN_USE` is the camera session's, not used here.
- **The cameraK-testing fake** (my call): with `allowsOpenWhenNotDetermined = true` its microphone gate now
  allows NotDetermined too, matching the desktop checker it stands in for.
- **Microphone seam:** `DesktopMedia` gains a microphone factory, so tests run with a fake microphone that
  produces synthetic samples, and with none.
- **Sample:** `NSMicrophoneUsageDescription` in the Info.plist keys and `com.apple.security.device.audio-input`
  in both entitlement files. `checkMacOsCameraPackaging`, the lint check, also requires them.
- **Docs:** troubleshooting's "desktop records no audio" line, the installation guide's macOS packaging section
  (the microphone usage text and entitlement), a CHANGELOG entry, and the engine contract kit's comment
  ("Desktop rejects enableAudio").
- **CI** (my call): tests assert against the fake microphone only, with samples and with none. Nothing asserts
  on the runners' real Java Sound, whose answer differs by OS; `.github/workflows/desktop.yml` does not change.
- **Process:** work on the local `ci/desktop` branch, red and green commits; `scripts/verify.sh` run through the
  session's Gradle lock script (`scratchpad/gslot.sh`, which keeps parallel worktrees from building at once).
  The Mac check uses the packaged Sample (`:Sample:createDistributable`, ad-hoc signed, launched with `open`),
  the setup already used for the camera prompt on 2026-11-14: a rebuild is a new app to macOS, so the
  microphone prompt shows without resetting any other app's permission. The user clicks Allow; the recording is
  played back to confirm the sound is there and in sync. Then an independent read-only review and a local
  fast-forward merge into `fix/engineering-bar`. Nothing is pushed.

## Known limits, documented rather than fixed

- (Fixed after the device check.) macOS: Java Sound's own prompt left AVFoundation's microphone status
  NotDetermined in the process after the answer, and a denial recorded silence. CameraK now asks through
  AVFoundation's `requestAccessForMediaType:` before opening the microphone, so a denial fails the start
  with `Permission` and the status reads the answer at once.
- Windows: with "Let desktop apps access your microphone" off, Windows delivers silence, not an error. Java
  Sound cannot tell the two apart.
- Linux and Windows real-microphone recording is tested only where someone has a machine with a microphone. CI
  runners have none, so CI covers the no-microphone path and the encoder with synthetic samples.

## Left to the builder

- The capture format: 44.1 kHz or 48 kHz, mono or stereo, 16-bit, with a fallback when the default line does not
  support the first choice. The AAC bitrate.
- The queue's size and what happens when it is full (the encoder is far behind); log it whatever the choice.
- How the start is aligned in code, and the chunk size read from the line.
- Where the microphone's line is opened relative to the recorder's `start()`, provided a line that cannot open
  leaves no file and no recorder.
- Test names and which existing desktop tests change: `DesktopAudioTest` asserts the old Unsupported behaviour
  and is replaced.
