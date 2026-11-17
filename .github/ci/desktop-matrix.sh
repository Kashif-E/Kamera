#!/usr/bin/env bash
# Runs the Sample's device matrix through Gradle, samples the JVM's heap (after a full GC), live threads and
# resident memory every INTERVAL seconds while it runs, stops it once it logs DONE, and keeps its report.
#   desktop-matrix.sh NAME TIMEOUT_MINUTES INTERVAL_SECONDS -cameraKMatrix ...
# Output in ci-out/desktop-NAME: console.txt, memory.csv, report.json, report.md, status.txt.
set -u
NAME=$1; TIMEOUT=$2; INTERVAL=$3; shift 3
OUT="ci-out/desktop-$NAME"
mkdir -p "$OUT"
LOG="$OUT/console.txt"; MEM="$OUT/memory.csv"
rm -rf "$HOME/camerak-matrix"
export PATH="$JAVA_HOME/bin:$PATH"

if [ "$(uname -s)" = Linux ]; then
  xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew :Sample:run --no-daemon "--args=$*" > "$LOG" 2>&1 &
else
  ./gradlew :Sample:run --no-daemon "--args=$*" > "$LOG" 2>&1 &
fi
GRADLE=$!

rss_kb() {
  if [ -r "/proc/$1/status" ]; then
    awk '/VmRSS/{print $2}' "/proc/$1/status"
  else
    powershell -NoProfile -Command "[int]((Get-Process -Id $1).WorkingSet64/1024)" 2>/dev/null | tr -d '\r'
  fi
}

echo "seconds,heap_used_kb,heap_committed_kb,threads,rss_kb,progress" > "$MEM"
pid=""; start=$SECONDS; deadline=$((SECONDS + TIMEOUT * 60)); last=-$INTERVAL; outcome=timeout
while [ $SECONDS -lt $deadline ]; do
  [ -z "$pid" ] && pid=$(jcmd -l 2>/dev/null | awk '/MainKt/{print $1; exit}')
  if [ -n "$pid" ] && [ $((SECONDS - last)) -ge "$INTERVAL" ]; then
    last=$SECONDS
    jcmd "$pid" GC.run > /dev/null 2>&1
    heap=$(jcmd "$pid" GC.heap_info 2>/dev/null | grep -m1 -oE 'total [0-9]+K, used [0-9]+K')
    committed=$(echo "$heap" | grep -oE 'total [0-9]+' | grep -oE '[0-9]+')
    used=$(echo "$heap" | grep -oE 'used [0-9]+' | grep -oE '[0-9]+')
    threads=$(jcmd "$pid" Thread.print 2>/dev/null | grep -c '^"')
    progress=$(grep -oE 'CameraKMatrix: (BEGIN #[0-9]+|CHURN [0-9]+/[0-9]+)' "$LOG" | tail -1 | sed 's/CameraKMatrix: //')
    echo "$((SECONDS - start)),${used:-},${committed:-},$threads,$(rss_kb "$pid"),$progress" >> "$MEM"
  fi
  if grep -q "CameraKMatrix: DONE" "$LOG"; then outcome=done; break; fi
  if ! kill -0 $GRADLE 2>/dev/null; then outcome=exited; break; fi
  sleep 5
done
sleep 5
if [ -n "$pid" ]; then
  if [ "$(uname -s)" = Linux ]; then kill "$pid" 2>/dev/null; else taskkill //PID "$pid" //F > /dev/null 2>&1; fi
fi
sleep 5
kill $GRADLE 2>/dev/null
cp "$HOME"/camerak-matrix/report.* "$OUT/" 2>/dev/null
echo "$NAME: $outcome after $((SECONDS - start)) s; $(grep -oE 'DONE .*' "$LOG" | tail -1)" | tee "$OUT/status.txt"
grep -E 'CameraKMatrix: (START|MATRIX|DONE|EXCEPTION)' "$LOG" | head -20
