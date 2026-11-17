#!/usr/bin/env bash
# On a booted emulator: the engine's device contract on the emulator's camera, the Sample's device matrix,
# a holder churn with the app's memory sampled after a GC, then the release-gate Macrobenchmark.
# Output in ci-out/android. Each part runs whatever the one before it did; status.txt has every exit code.
set -u
OUT=ci-out/android
mkdir -p "$OUT"
# Lenient: the verification metadata lacks Linux-only Android tooling (aapt2); the report lists what is missing.
G="./gradlew --no-daemon --dependency-verification=lenient"
P=org.company.app.androidApp
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
{
  echo "android $(adb shell getprop ro.build.version.release) sdk $(adb shell getprop ro.build.version.sdk) $(adb shell getprop ro.product.model)"
  adb shell dumpsys media.camera | grep -E '^ *Camera [0-9]+|Facing' | head -10
} > "$OUT/device.txt" 2>&1
cat "$OUT/device.txt"

$G :cameraK-core:connectedDebugAndroidTest --continue > "$OUT/contract.txt" 2>&1
echo "contract exit $?" | tee -a "$OUT/status.txt"
cp -r cameraK-core/build/outputs/androidTest-results "$OUT/contract-results" 2>/dev/null
cp -r cameraK-core/build/reports/androidTests "$OUT/contract-report" 2>/dev/null

$G :Sample:installDebug > "$OUT/install.txt" 2>&1
echo "install exit $?" | tee -a "$OUT/status.txt"
for perm in CAMERA RECORD_AUDIO POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null
done

# run NAME TIMEOUT_MINUTES INTERVAL_SECONDS extras...: the matrix with the app's memory every INTERVAL seconds.
run() {
  local name=$1 timeout=$2 interval=$3; shift 3
  local dir="$OUT/$name" start=$SECONDS last=-$interval outcome=timeout pid=""
  mkdir -p "$dir"
  adb shell am force-stop $P
  adb shell run-as $P rm -rf files/camerak-matrix
  adb logcat -c
  adb shell am start -n $P/org.company.app.AppActivity --ez camerak.matrix true "$@" > /dev/null
  echo "seconds,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,threads,progress" > "$dir/memory.csv"
  while [ $((SECONDS - start)) -lt $((timeout * 60)) ]; do
    adb logcat -d -s CameraKMatrix:I > "$dir/console.txt" 2>/dev/null
    pid=$(adb shell pidof $P | tr -d '\r')
    if [ -n "$pid" ] && [ $((SECONDS - last)) -ge "$interval" ]; then
      last=$SECONDS
      # SIGUSR1 makes ART collect; root on the emulator may send it.
      adb shell kill -10 "$pid"; sleep 2
      local mem; mem=$(adb shell dumpsys meminfo "$pid" | tr -d '\r')
      local pss java native graphics threads progress
      pss=$(echo "$mem" | awk '/TOTAL PSS:/{print $3; exit}')
      java=$(echo "$mem" | awk '/Java Heap:/{print $3; exit}')
      native=$(echo "$mem" | awk '/Native Heap:/{print $3; exit}')
      graphics=$(echo "$mem" | awk '/Graphics:/{print $2; exit}')
      threads=$(adb shell ls "/proc/$pid/task" | wc -l)
      progress=$(grep -oE '(BEGIN #[0-9]+|CHURN [0-9]+/[0-9]+)' "$dir/console.txt" | tail -1)
      echo "$((SECONDS - start)),$pss,$java,$native,$graphics,$threads,$progress" >> "$dir/memory.csv"
    fi
    if grep -q "DONE" "$dir/console.txt"; then outcome=done; break; fi
    if [ -z "$pid" ] && [ $((SECONDS - start)) -gt 30 ]; then outcome="app died"; break; fi
    sleep 5
  done
  adb logcat -d > "$dir/logcat.txt"
  adb exec-out run-as $P cat files/camerak-matrix/report.json > "$dir/report.json" 2>/dev/null
  adb exec-out run-as $P cat files/camerak-matrix/report.md > "$dir/report.md" 2>/dev/null
  adb shell am force-stop $P
  echo "$name: $outcome after $((SECONDS - start)) s; $(grep -oE 'DONE .*' "$dir/console.txt" | tail -1)" | tee -a "$OUT/status.txt"
}

run matrix 75 60 --es camerak.matrix.sections features,configflash,analysis,plugins,video,lifecycle,photo
run churn 40 15 --ei camerak.matrix.churn 1000
adb exec-out screencap -p > "$OUT/screen.png"

$G :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED \
  > "$OUT/benchmark.txt" 2>&1
echo "benchmark exit $?" | tee -a "$OUT/status.txt"
cp -r benchmark/build/outputs/connected_android_test_additional_output "$OUT/benchmark-output" 2>/dev/null
cp -r benchmark/build/outputs/androidTest-results "$OUT/benchmark-results" 2>/dev/null
cp -r build/reports/dependency-verification "$OUT/dependency-verification" 2>/dev/null
exit 0
