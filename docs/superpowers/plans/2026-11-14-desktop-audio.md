# Desktop Audio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `VideoConfiguration.enableAudio = true` records the default microphone into desktop MP4s, on macOS, Linux and Windows.

**Architecture:** Java Sound captures the microphone on its own thread and queues chunks of 16-bit samples; the
recording's coroutine, the recorder's only caller, writes queued audio with `recordSamples` before each video
frame and drains it at the end. The holder's microphone gate defers to the checker's `allowsOpen`, so macOS can
prompt on open.

**Tech Stack:** Kotlin 2 / JVM 17, JavaCV `FFmpegFrameRecorder` (AAC), `javax.sound.sampled`, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-14-desktop-audio-design.md`

## Global Constraints

- No new dependency; capture is `javax.sound.sampled` only. No FFmpeg audio input formats.
- Only the recording's coroutine calls the recorder (`start`, `record`, `recordSamples`, `stop`, `release`).
- Every catch that falls back logs the throwable (CameraKLogger, tag `CameraK`), naming the operation.
- Red commit (failing test) then green commit per task; messages end with the session's Co-Authored-By and
  Claude-Session lines. Build only through `scratchpad/gslot.sh ./gradlew …`. Never push.
- Formatting: `./gradlew spotlessApply` before each green commit (ktlint 1.5.0).
- Tests: `./gradlew :cameraK-core:desktopTest --tests "<class>"` for the loop; `scripts/verify.sh` at the end.

## Review Focus

1. A microphone that delivers its first samples late (macOS while the prompt is up): the file's audio still
   starts at the video's start, with silence before the first sample. Task 4 pins it.
2. Pause, then resume with samples queued from the paused stretch: none of them reach the file. Task 4.
3. The encoder far behind (queue full): samples are dropped with a log line, the recording carries on, the
   recorder is never called from the capture thread. Task 4.
4. A stop while the capture thread is blocked in `read()`: the line is closed, the thread ends, nothing calls
   the recorder after `stop()`. Task 4 (FakeFrameRecorder's violations list).
5. A recording with audio on a machine with no microphone: `prepareRecording` and `startRecording` fail with
   `Unsupported(AUDIO)` and no file or recorder is left. Task 5.

---

### Task 1: The holder's microphone gate defers to `allowsOpen`

**Files:**
- Modify: `cameraK-core/src/commonMain/kotlin/com/kashif/cameraK/recording/RecordingController.kt` (`microphoneRefusal`)
- Modify: `cameraK-core/src/commonMain/kotlin/com/kashif/cameraK/state/CameraKStateHolder.kt` (startRecording KDoc)
- Test: `cameraK-core/src/commonTest/kotlin/com/kashif/cameraK/recording/RecordingControllerTest.kt`

**Interfaces:** Produces nothing new. `microphoneRefusal` returns null when `permissionChecker.allowsOpen(status)`.

- [ ] **Step 1: Failing test** `aMicrophoneTheCheckerLetsOpenAtNotDeterminedReachesTheEngine`: `FakePermissionChecker`
  with MICROPHONE NotDetermined and `allowsOpenWhenNotDetermined = true`; `startRecording { enableAudio = true }`
  is Success and `fake.count("startRecording") == 1`. A second case in the same test: without the flag, the start
  is `Failure(Permission(MICROPHONE, NotDetermined))` and the engine count stays 0.
- [ ] **Step 2:** run `:cameraK-core:desktopTest --tests "*RecordingControllerTest"`; expect the first assertion to fail.
- [ ] **Step 3:** `microphoneRefusal` refuses only when a checker exists and `!checker.allowsOpen(status)`. The
  `startRecording` KDoc says the start fails with `Permission(MICROPHONE, status)` at a status the platform does
  not record at (Granted everywhere; NotDetermined too on desktop, where macOS prompts on open).
- [ ] **Step 4:** run the test class on desktop; PASS. (verify.sh in Task 7 covers the other targets.)
- [ ] **Step 5:** commit red, then green.

### Task 2: The microphone seam and Java Sound capture

**Files:**
- Create: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/DesktopMicrophone.kt`
- Modify: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/DesktopMedia.kt` (new `microphone` seam)
- Create: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/FakeMicrophone.kt`
- Test: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopMicrophoneTest.kt`

**Interfaces (produced):**
```kotlin
internal class AudioChunk(val samples: ShortArray, val count: Int, val captureNanos: Long)
internal interface Microphone {            // one open line, mono or stereo 16-bit signed
    val sampleRate: Int
    val channels: Int
    /** Blocks for the next chunk; null once closed; throws when the line fails. */
    fun read(): AudioChunk?
    fun close()                             // unblocks a read in progress
}
/** Null when no capture line supports any candidate format (no microphone). Throws when one exists but will not open. */
internal fun interface MicrophoneOpener { fun open(nanoClock: () -> Long): Microphone? }
internal fun captureFormat(isSupported: (AudioFormat) -> Boolean): AudioFormat?
// DesktopMedia gains: val microphone: MicrophoneOpener = JavaSoundMicrophone
```
`captureNanos` is the clock time of the chunk's first sample: the time `read` returned minus the chunk's duration.

- [ ] **Step 1: Failing tests** in `DesktopMicrophoneTest`:
  - `theFirstSupportedFormatIsChosen`: candidates in order 48000 Hz mono, 44100 Hz mono, 48000 Hz stereo,
    44100 Hz stereo (16-bit signed little-endian); with only 44100 stereo supported, `captureFormat` returns it;
    with none, null.
  - `aChunksCaptureTimeIsItsFirstSample`: a `JavaSoundMicrophone` over a fake `TargetDataLine` (a small test
    double implementing the interface) that returns 4800 samples at clock 1_100 ms, 48 kHz mono: `captureNanos`
    is 1_000 ms.
  - `closeUnblocksARead`: `close()` from another thread makes a blocked `read()` return null within 1 s.
- [ ] **Step 2:** run `--tests "*DesktopMicrophoneTest"`; fails to compile, then fails.
- [ ] **Step 3:** implement `JavaSoundMicrophone` (opens `AudioSystem.getTargetDataLine(format)` with the first
  supported `captureFormat`; reads ~20 ms chunks; `LineUnavailableException`/`SecurityException` from open are
  thrown to the caller). `FakeMicrophone` (test): scripted chunks, an optional failure after N chunks, records
  `close()`.
- [ ] **Step 4:** PASS. **Step 5:** commit red, then green.

### Task 3: An encoder with an audio stream

**Files:**
- Modify: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/DesktopMedia.kt` (`createFrameRecorder`)
- Modify: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/FakeFrameRecorder.kt` (records `recordSamples`)
- Test: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopAudioEncodeTest.kt`

**Interfaces:** `createFrameRecorder(path, config, audio: Pair<Int, Int>? = null)` (sample rate, channels): with
`audio`, `audioCodec = AV_CODEC_ID_AAC`, `sampleRate`, `audioChannels`, `audioBitrate = 128_000`. The recorder
factory seam becomes `(String, VideoConfiguration, Pair<Int, Int>?) -> FrameRecorder`. FakeFrameRecorder adds
`samplesWritten: AtomicLong`, `audioCalls: Int`, and the same `stop()` violation check for `recordSamples`.

- [ ] **Step 1: Failing test** `aRecordingWithSamplesHasAnAacTrackAsLongAsItsVideo` (real FFmpeg): 30 frames of
  320x240 at 1/30 s and 1 s of a 440 Hz sine at 48 kHz mono through the real recorder into a temp file; read back
  with `FFmpegFrameGrabber`: `audioChannels == 1`, `audioCodecName` contains "aac", `lengthInTime` within 100 ms
  of 1 s. A second test: without `audio`, `audioChannels == 0`.
- [ ] **Step 2:** FAIL (no audio stream). **Step 3:** implement. **Step 4:** PASS. **Step 5:** commit red, green.

### Task 4: The recording writes the microphone

**Files:**
- Create: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/AudioFeed.kt`
- Modify: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/DesktopRecording.kt`
- Modify: `cameraK-core/src/commonMain/kotlin/com/kashif/cameraK/recording/RecordingClock.kt`
- Test: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopRecordingAudioTest.kt`

**Interfaces:**
- `RecordingClock.recordedMicrosAt(captureNanos: Long): Long?`: like `timestampMicros` without its
  strictly-increasing state; null while paused or before the start or last resume.
- `AudioFeed(microphone: Microphone, capacityChunks: Int)`: starts its thread on `start()`; `drain(): List<AudioChunk>`
  (non-blocking); `failure: Throwable?`; `close()` closes the line and joins the thread for at most 1 s. A full
  queue drops the newest chunk and logs once per recording.
- `DesktopRecording(…, microphone: Microphone?)`: with one, `hasAudio = true` in `video()`, and the loop, before
  each frame and once at the end, drains the feed: a chunk whose `recordedMicrosAt` is null is dropped; the first
  kept chunk is preceded by `recordedMicros × rate / 1e6` zero samples (trimmed, not padded, if negative); then
  chunks are written contiguously with `recordSamples(rate, channels, ShortBuffer)`. A feed `failure` ends the
  recording with reason INTERRUPTED through the existing end path.

- [ ] **Step 1: Failing tests** over the rig with FakeMicrophone and FakeFrameRecorder:
  - `samplesReachTheRecorderAndTheVideoHasAudio`: 3 frames and 3 chunks of 960 samples (48 kHz mono, 20 ms each,
    captured at the frames' times); stop: `samplesWritten == 2880`, `RecordedVideo.hasAudio`, no violations.
  - `audioThatStartsLateIsPrecededBySilence`: first chunk captured 100 ms after the clock start:
    `samplesWritten == 4800 + 960`.
  - `samplesCapturedWhilePausedAreDropped`: pause at 40 ms, two chunks at 50 and 70 ms, resume at 100 ms, one
    chunk at 110 ms: only the pre-pause and the 110 ms chunks are written.
  - `aMicrophoneThatFailsEndsTheRecordingInterruptedAndKeepsTheFile`: FakeMicrophone throws after 2 chunks; the
    `Finalized` reason is INTERRUPTED and the result is Success with the path.
  - `stopClosesTheLineAndNothingRecordsAfterStop`: stop while the fake's `read()` blocks; `close()` was called,
    FakeFrameRecorder `violations` is empty.
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** PASS, plus `--tests "*DesktopRecording*"` all green.
- [ ] **Step 5:** commit red, then green.

### Task 5: The engine records audio

**Files:**
- Modify: `cameraK-core/src/desktopMain/kotlin/com/kashif/cameraK/controller/DesktopCameraEngine.kt`
  (`prepareRecording`, `beginRecording`, `newRecording`, `recordingListener.onStarted`, class KDoc; delete
  `unsupportedAudio`)
- Modify: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopEngineRig.kt` (`microphone` seam,
  default: no microphone, as CI)
- Replace: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopAudioTest.kt`
- Modify: `cameraK-core/src/desktopTest/kotlin/com/kashif/cameraK/controller/DesktopPrepareRecordingTest.kt`
- Modify: `cameraK-core/src/engineContract/kotlin/com/kashif/cameraK/engine/contract/ContractCases.kt` (comment)

**Interfaces:** Consumes Tasks 2-4. With `enableAudio`: `media.microphone.open(nanoClock)` on the I/O dispatcher
before the file is created; null → `Unsupported(AUDIO)`; a throw → `Hardware(null, cause)`; the microphone is closed
on every failure after it opened. `Started(path, hasAudio = recording.hasAudio)`.

- [ ] **Step 1: Failing tests** in the new `DesktopAudioTest`:
  - `withAMicrophoneARecordingHasAudio`: Started.hasAudio, Finalized RecordedVideo.hasAudio, samples written.
  - `withNoMicrophoneAudioIsUnsupportedAndNothingIsLeft`: start and prepare are `Unsupported(AUDIO)`; no recorder,
    no file in the videos folder, no events; a recording without audio still starts after.
  - `aMicrophoneThatWillNotOpenIsHardwareAndNothingIsLeft`: opener throws `LineUnavailableException`:
    `Hardware(null, cause)`, no recorder, no file.
  - In `DesktopPrepareRecordingTest`, the audio assertion uses a rig with a microphone: Success.
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** `--tests "com.kashif.cameraK.controller.*"` PASS, and
  `:cameraK-core:desktopEngineContractTest` (or the task verify.sh runs for the contract kit) PASS.
- [ ] **Step 5:** commit red, then green.

### Task 6: Sample packaging, docs, changelog

**Files:**
- Modify: `Sample/build.gradle.kts` (Info.plist `NSMicrophoneUsageDescription`; `checkMacOsCameraPackaging` also
  requires it and `com.apple.security.device.audio-input`)
- Modify: `Sample/macos/entitlements.plist`, `Sample/macos/runtime-entitlements.plist`
- Modify: `docs/troubleshooting.md`, `docs/getting-started/installation.md` (macOS packaging: microphone usage text
  and entitlement), `CHANGELOG.md` ("Added": desktop recordings with audio; replaces the Unsupported(AUDIO) line)

- [ ] **Step 1 (red):** extend `checkMacOsCameraPackaging` first; run `./gradlew :Sample:checkMacOsCameraPackaging`:
  FAIL naming the missing usage text and entitlement.
- [ ] **Step 2 (green):** add the plist key and entitlements; the check PASSES. Update the docs; run
  `--tests "com.kashif.cameraK.docs.*"`; PASS.
- [ ] **Step 3:** commit red, then green.

### Task 7: Verify, device check, review, merge

- [ ] `scratchpad/gslot.sh scripts/verify.sh > scratchpad/w28/verify-audio.txt 2>&1`; expect "GREEN (full)" and
  0 failures; read the total.
- [ ] Merge `ci/desktop` into `devtest/w15-device` (wt/w17-device), `:Sample:createDistributable`, run the matrix's
  video section with `mac_matrix_app.sh`; the user clicks Allow on the microphone prompt; expect the 16 audio cases
  to PASS. Record a clip in the Sample with audio, and confirm with `ffprobe`-equivalent read-back (FFmpegFrameGrabber)
  that it has an AAC track; the user plays it to confirm sound and sync.
- [ ] Independent read-only review of `b056a3a2..ci/desktop`; fix findings red/green.
- [ ] `git -C <main checkout> merge --ff-only ci/desktop` into `fix/engineering-bar`. No push.
