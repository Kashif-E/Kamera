#!/usr/bin/env bash
# On a booted emulator: EmulatorProbeDeviceTest with the whole logcat streamed to a file, plus the
# camera service's dump of every camera's static metadata. Output in ci-out/probe.
set -u
OUT=ci-out/probe
mkdir -p "$OUT"
G="./gradlew --no-daemon --dependency-verification=lenient"
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb shell dumpsys media.camera > "$OUT/media-camera.txt" 2>&1
adb logcat -G 16M; adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
$G :cameraK-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kashif.cameraK.engine.EmulatorProbeDeviceTest \
  > "$OUT/probe.txt" 2>&1
echo "probe exit $?" | tee -a "$OUT/status.txt"
kill $LOGCAT
grep 'CameraKProbe' "$OUT/logcat.txt" > "$OUT/probe-lines.txt"
cp -r cameraK-core/build/outputs/androidTest-results "$OUT/results" 2>/dev/null
exit 0
