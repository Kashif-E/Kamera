#!/usr/bin/env bash
# On a booted emulator: replays ranges of the Sample's device matrix with the whole logcat streamed to
# a file, plus the camera service's dump of every camera's static metadata. Output in ci-out/probe.
set -u
OUT=ci-out/probe
mkdir -p "$OUT"
G="./gradlew --no-daemon --dependency-verification=lenient"
P=org.company.app.androidApp
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb shell dumpsys media.camera > "$OUT/media-camera.txt" 2>&1
$G :Sample:installDebug > "$OUT/install.txt" 2>&1
echo "install exit $?" | tee -a "$OUT/status.txt"
for perm in CAMERA RECORD_AUDIO POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null
done
adb logcat -G 16M; adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

# replay NAME OFFSET LIMIT: photo-section cases OFFSET.. (offset within the section), LIMIT of them.
replay() {
  local name=$1 offset=$2 limit=$3 start=$SECONDS
  adb shell am force-stop $P
  adb shell run-as $P rm -rf files/camerak-matrix
  adb shell log -t CameraKProbe "REPLAY $name offset=$offset limit=$limit"
  adb shell am start -n $P/org.company.app.AppActivity --ez camerak.matrix true \
    --es camerak.matrix.sections photo --ei camerak.matrix.offset "$offset" --ei camerak.matrix.limit "$limit" > /dev/null
  while [ $((SECONDS - start)) -lt 900 ]; do
    adb logcat -d -s CameraKMatrix:I | grep -q "DONE" && break
    sleep 5
  done
  adb exec-out run-as $P cat files/camerak-matrix/report.json > "$OUT/$name.json" 2>/dev/null
  adb shell am force-stop $P
  adb logcat -d -s CameraKMatrix:I | grep -E 'DONE|\] #' | tail -n "$((limit + 1))" > "$OUT/$name.txt"
  echo "$name: $(grep -o 'DONE .*' "$OUT/$name.txt" | tail -1) after $((SECONDS - start)) s" | tee -a "$OUT/status.txt"
  adb logcat -c
}

replay telephoto 1152 30
replay speed-wide 75 12
replay speed-tele 1468 10
kill $LOGCAT
exit 0
