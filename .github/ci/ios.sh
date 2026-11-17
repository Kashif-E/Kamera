#!/usr/bin/env bash
# Builds the iOS Sample for a simulator with Xcode, installs it, and runs the device matrix and a holder churn
# in it, sampling the app's resident memory, then runs `leaks` on the live process. A simulator has no camera,
# so this checks the app and the library's lifecycle without one, not capture. Output in ci-out/ios.
set -u
OUT=ci-out/ios
mkdir -p "$OUT"
BID=com.kashif.camera.iosApp
DEV=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
ios = [(runtime, d) for runtime, ds in devices.items() if "iOS" in runtime for d in ds if d["name"].startswith("iPhone")]
ios.sort(key=lambda rd: rd[0])
print(ios[-1][1]["udid"])')
xcrun simctl list devices | grep "$DEV" | tee "$OUT/device.txt"
xcrun simctl boot "$DEV"
xcrun simctl bootstatus "$DEV" -b > /dev/null

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator \
  -destination "id=$DEV" -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO build > "$OUT/xcodebuild.txt" 2>&1
code=$?
echo "xcodebuild exit $code" | tee -a "$OUT/status.txt"
tail -30 "$OUT/xcodebuild.txt"
[ $code -eq 0 ] || exit 1
APP=$(find build/ios/Build/Products -maxdepth 2 -name "*.app" | head -1)
xcrun simctl install "$DEV" "$APP"
for service in camera microphone photos-add; do xcrun simctl privacy "$DEV" grant $service $BID; done

run() {
  local name=$1 timeout=$2 interval=$3; shift 3
  local dir="$OUT/$name" start=$SECONDS last=-$interval outcome=timeout pid=""
  mkdir -p "$dir"
  xcrun simctl terminate "$DEV" $BID > /dev/null 2>&1
  xcrun simctl launch --console-pty "$DEV" $BID -cameraKMatrix -cameraKMatrixLog "$@" > "$dir/console.txt" 2>&1 &
  local launcher=$!
  echo "seconds,rss_kb,threads,progress" > "$dir/memory.csv"
  while [ $((SECONDS - start)) -lt $((timeout * 60)) ]; do
    pid=$(pgrep -f "$(basename "$APP" .app).app/" | head -1)
    if [ -n "$pid" ] && [ $((SECONDS - last)) -ge "$interval" ]; then
      last=$SECONDS
      local progress
      progress=$(grep -oE 'CameraKMatrix: (BEGIN #[0-9]+|CHURN [0-9]+/[0-9]+)' "$dir/console.txt" | tail -1 | sed 's/CameraKMatrix: //')
      echo "$((SECONDS - start)),$(ps -o rss= -p "$pid" | tr -d ' '),$(ps -M -p "$pid" | tail -n +2 | wc -l | tr -d ' '),$progress" >> "$dir/memory.csv"
    fi
    if grep -q "CameraKMatrix: DONE" "$dir/console.txt"; then outcome=done; break; fi
    if ! kill -0 $launcher 2>/dev/null; then outcome=exited; break; fi
    sleep 5
  done
  sleep 3
  [ -n "$pid" ] && leaks "$pid" > "$dir/leaks.txt" 2>&1
  local container
  container=$(xcrun simctl get_app_container "$DEV" $BID data 2>/dev/null)
  cp "$container"/Documents/camerak-matrix/report.* "$dir/" 2>/dev/null
  xcrun simctl terminate "$DEV" $BID > /dev/null 2>&1
  kill $launcher 2>/dev/null
  echo "$name: $outcome after $((SECONDS - start)) s; $(grep -oE 'DONE .*' "$dir/console.txt" | tail -1); $(grep -m1 -E 'leaks? for' "$dir/leaks.txt" 2>/dev/null)" \
    | tee -a "$OUT/status.txt"
}

run matrix 30 30 -cameraKMatrixSections features,lifecycle,video,photo -cameraKMatrixLimit 120
run churn 15 10 -cameraKMatrixChurn 200
cp -r ~/Library/Logs/DiagnosticReports "$OUT/crash-reports" 2>/dev/null
exit 0
