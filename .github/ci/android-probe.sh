#!/usr/bin/env bash
# On a booted emulator: PreviewSurfaceWithAnalysisDeviceTest three times and the video-bind and square
# capture benchmarks, with the whole logcat streamed to a file. Output in ci-out/probe.
set -u
OUT=ci-out/probe
mkdir -p "$OUT"
G="./gradlew --no-daemon --dependency-verification=lenient"
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb logcat -G 16M; adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
for i in 1 2 3; do
  $G :cameraK-core:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.kashif.cameraK.engine.PreviewSurfaceWithAnalysisDeviceTest \
    > "$OUT/preview-$i.txt" 2>&1
  echo "preview run $i exit $?" | tee -a "$OUT/status.txt"
  cp -r cameraK-core/build/outputs/androidTest-results "$OUT/preview-results-$i" 2>/dev/null
done
$G :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kashif.cameraK.benchmark.VideoBindBenchmark,com.kashif.cameraK.benchmark.SquareCaptureBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED \
  > "$OUT/benchmark.txt" 2>&1
echo "benchmark exit $?" | tee -a "$OUT/status.txt"
cp -r benchmark/build/outputs/androidTest-results "$OUT/benchmark-results" 2>/dev/null
kill $LOGCAT
exit 0
