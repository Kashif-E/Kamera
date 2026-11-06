# Device Checklist

`scripts/verify.sh` runs on a machine with no camera: fakes, Robolectric and the simulator. What only
real hardware can show is checked here, on each release candidate, before the release ships. The
maintainer runs it and records, under each item, the candidate's version, the device and OS, the
result and the date. An item that fails blocks the release.

Build the Sample app from the release candidate's commit for every run.

## Release-gate benchmarks (Android)

The `:benchmark` module (Macrobenchmark) runs against the Sample's `benchmark` build type on the
Pixel 7. Record each result.

- `BindCountBenchmark.exactlyOneBindToFirstFrame` `<B1p command>`
- `BindCountBenchmark.zeroRebindsOnAnalyzerRegisterAndUnregister` `<B1p command>`
- `TimeToFirstFrameBenchmark.againstBareCameraX` `<B1p command>`
- `SquareCaptureBenchmark.shutterToFileWithAndWithoutTheSquareViewPort` `<B1p command>`
- `VideoBindBenchmark.videoBindMainThreadTime` `<B1p command>`

`exactlyOneBindToFirstFrame` and `zeroRebindsOnAnalyzerRegisterAndUnregister` pass or fail.
`againstBareCameraX` is recorded against the bare-CameraX activity; the budget comes in 2.1.
`shutterToFileWithAndWithoutTheSquareViewPort` measures what `AspectRatio.RATIO_1_1` costs per photo;
its number goes into that entry's KDoc, which says until then that the cost has not been measured.
`videoBindMainThreadTime` is the `CameraK.videoBind` section, the time the `VideoCapture` bind holds
the main thread.

## Android: Pixel 7

- [ ] **Preview.** The Sample's preview runs at once, with no black frame and no stretch, in the
      default 16:9 and in 4:3 and 1:1.
- [ ] **Turning the device.** Rotating it keeps the preview running, keeps the holder (the camera does
      not reopen and the state does not go back to Starting) and turns the controls upright.
- [ ] **Letterboxing.** In portrait and landscape the preview is the configured aspect ratio,
      centred, and a capture shows the same framing as the preview.
- [ ] **Cold start.** From a cold start the camera binds once: one `CX:bindToLifecycle` before the
      first frame (the first benchmark above shows it; also check it by eye in a system trace).
- [ ] **Recording.** A recording from the Sample, with and without audio, plays in the system
      player, with the duration the Sample showed.
- [ ] **Rotating mid-recording.** Rotating during a recording does not end it: one file, playable,
      covering the whole recording.
- [ ] **Playable probe.** A recording CameraX ends on its own (a `maxDuration` of a few seconds) is
      kept only when it plays; a recording stopped at once, before any frame, leaves no file and
      reports a failure.
- [ ] **VideoCapture bind on Main.** Record the `CameraK.videoBind` time from the benchmark above,
      and check that the preview does not stall when recording starts.

## iPhone

- [ ] **QR preview with analysis (Decision 3).** With the QR plugin at its default configuration,
      decoding through Vision on the shared 420f frames, the preview frame rate stays within 5% of
      the preview alone, at both p50 and p90. Measure with Instruments as frames presented on the
      preview layer, at least 10 iterations of each, on the oldest iOS 16 device available. Record
      the numbers, the device and the OS in `docs/perf/ios-qr-preview-with-analysis.md`. If it
      fails, the metadata-output fallback is built before release.
- [ ] **Analyzer frames through Core Image.** The analyzer plugin's JPEGs come from the real Core
      Image renderer: upright with their `rotationDegrees`, colours correct, and scaled so the long
      side is `maxDimension` (try 640 and null).
- [ ] **Vision barcodes.** The QR plugin reads QR codes and EAN-13 at Vision's default request
      revision, with bounding boxes on the code.
- [ ] **Lens-switch signpost.** Record the `CameraK.lensSwitch` interval (subsystem
      `io.github.kashif-mehmood-km.camerak`, category `CameraK`) while switching lenses in the Sample:
      `<B1p command>`. Discard a switch that logged `CameraK.lensSwitch.failed` or `.noFrame`.
- [ ] **Engine contract, by hand.** In the Sample:
    - the preview runs and the Sample reports Ready after opening;
    - after a lens switch, the preview and the reported lens are the other camera;
    - torch on and zoom 2x come back after the app goes to the background and returns;
    - leaving the camera screen turns the camera indicator off, and the camera reopens.
- [ ] **Reading back an add-only save.** With only add-only Photos access granted, save a capture
      with `MediaSaverPlugin.save`, then call `readBytes` on it: confirm that it fails until the app
      has read access, and succeeds once read access is granted. This confirms why an app that calls
      `readBytes` needs `NSPhotoLibraryUsageDescription`.

## iPad

- [ ] **Front Center Stage camera.** On an iPad whose front camera supports Center Stage, the default
      front camera opens (CameraDeviceType `DEFAULT` may pick the ultra-wide), the preview is upright
      and the reported lens and device type match the camera that is live.

## Desktop

- [ ] **Windows (Decision 17).** On Windows, the default camera opens through FFmpeg's dshow and the
      cameras are listed in `holder.capabilities`. Record whether `videoinput` loads against
      javacpp 1.5.12.
- [ ] **macOS index fallback.** On a Mac with two identical cameras, each listed camera opens the
      camera it names (the fallback from `uniqueID` to the device index picks the right one).

## After the run

Fill in the results above in the release's commit, and copy each benchmark's number into the
CHANGELOG's release notes.
