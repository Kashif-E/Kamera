#!/usr/bin/env bash
# CameraK verification harness. Runs the Gradle `verifyAll` task, then scripts/verify_report.py,
# which reads only the results this run wrote and exits non-zero on any failure or empty target.
#
#   scripts/verify.sh                       full run (same as --full)
#   scripts/verify.sh --quick               desktop JVM tests only
#   scripts/verify.sh --module qrScannerPlugin
#   scripts/verify.sh --only android        one of: desktop android ios screenshots lint sample
#   scripts/verify.sh --record-screenshots  re-record desktop and Paparazzi goldens
#   scripts/verify.sh --quick -- --offline  arguments after -- go to Gradle unchanged
set -euo pipefail

usage() {
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

die() {
    echo "verify.sh: $*" >&2
    exit 2
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALL_TARGETS="desktop android ios screenshots lint sample"
# Every module verify.sh tests: the published libraries and the published test fixtures.
LIBRARY_MODULES="cameraK-core cameraK cameraK-testing ImageSaverPlugin qrScannerPlugin ocrPlugin analyzerPlugin"

mode=""
full_flag=false
record=false
module=""
only=""
gradle_extra=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --quick) [[ -z "$mode" ]] || die "choose one of --quick / --full"; mode=quick ;;
        --full) [[ -z "$mode" ]] || die "choose one of --quick / --full"; mode=full; full_flag=true ;;
        --record-screenshots) record=true ;;
        --module) [[ $# -ge 2 ]] || die "--module needs a name"; module="$2"; shift ;;
        --only) [[ $# -ge 2 ]] || die "--only needs a target"; only="$2"; shift ;;
        -h | --help) usage ;;
        --) shift; gradle_extra=("$@"); break ;;
        *) die "unknown argument '$1' (see --help)" ;;
    esac
    shift
done

if $record; then
    $full_flag && die "--record-screenshots rewrites goldens and refuses to run with --full"
    [[ "$mode" == quick ]] && die "--record-screenshots cannot be combined with --quick"
    [[ -z "$only" || "$only" == screenshots ]] || die "--record-screenshots only records screenshots"
    mode=record
    targets="screenshots"
elif [[ "$mode" == quick ]]; then
    [[ -z "$only" || "$only" == desktop ]] || die "--quick runs desktop tests only; use --only $only without --quick"
    targets="desktop"
else
    mode=full
    targets="$ALL_TARGETS"
fi

if [[ -n "$only" ]]; then
    [[ " $ALL_TARGETS " == *" $only "* ]] || die "--only must be one of: $ALL_TARGETS"
    targets="$only"
    [[ "$mode" == full ]] && mode="only-$only"
fi

modules="$LIBRARY_MODULES"
if [[ -n "$module" ]]; then
    [[ " $LIBRARY_MODULES " == *" $module "* ]] || die "--module must be one of: $LIBRARY_MODULES"
    modules="$module"
    mode="$mode-$module"
    # Screenshots exist only for cameraK and the Sample build needs every module. A full run drops
    # them; asking for them explicitly is refused rather than reported GREEN with nothing run.
    if [[ "$module" != cameraK && " $targets " == *" screenshots "* ]]; then
        [[ -z "$only" && $record == false ]] || die "screenshots exist only for cameraK, not $module"
        targets="${targets/screenshots/}"
    fi
    if [[ " $targets " == *" sample "* ]]; then
        [[ -z "$only" ]] || die "--only sample builds the Sample app, which needs every module; drop --module"
        targets="${targets/sample/}"
    fi
    targets="$(echo $targets)"
fi

# JDK 17: Gradle, the Kotlin toolchain and Robolectric's SDK pin all assume it.
java_major() {
    "$1/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}'
}
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" || "$(java_major "$JAVA_HOME")" != "17" ]]; then
    if [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)" || die "no JDK 17 found by /usr/libexec/java_home"
    else
        die "JAVA_HOME must point at a JDK 17 (currently '${JAVA_HOME:-unset}')"
    fi
fi
export JAVA_HOME

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ "$(uname)" == Darwin ]]; then ANDROID_HOME="$HOME/Library/Android/sdk"; else ANDROID_HOME="$HOME/Android/Sdk"; fi
fi
[[ -d "$ANDROID_HOME" ]] || die "ANDROID_HOME '$ANDROID_HOME' does not exist"
export ANDROID_HOME

out_dir="$ROOT/build/verify"
mkdir -p "$out_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
log="$out_dir/$mode-$stamp.log"
run_file="$out_dir/run.json"

avail_kb="$(df -Pk "$ROOT" | awk 'NR == 2 {print $4}')"
if [[ "$avail_kb" -lt $((2 * 1024 * 1024)) ]]; then
    echo "verify.sh: warning: only $((avail_kb / 1024)) MB free on the build disk; a full run needs about 2 GB" >&2
fi

csv() { tr ' ' ',' <<<"$1"; }

# Results written before this instant are stale and the report ignores them.
start_epoch="$(python3 -I -c 'import time; print(f"{time.time():.3f}")')"

# gitleaks is not a build dependency. A run without it says so rather than passing by omission.
secret_scan_status="not requested"
secret_scan_detail=""

write_run_file() {
    python3 -I - "$run_file" "$1" "$secret_scan_status" "$secret_scan_detail" <<EOF
import json, sys
path, exit_code, scan_status, scan_detail = sys.argv[1:5]
json.dump({
    "mode": "$mode",
    "record": $($record && echo True || echo False),
    "modules": "$modules".split(),
    "targets": "$targets".split(),
    "start_epoch": $start_epoch,
    "log": "$log",
    "gradle_exit": None if exit_code == "" else int(exit_code),
    "secret_scan": {"status": scan_status, "detail": scan_detail},
}, open(path, "w"), indent=2)
EOF
}
write_run_file ""

gradle_args=(
    --continue
    verifyAll
    "-Pcamerak.verify.rerunTests=true"
    "-Pcamerak.verify.targets=$(csv "$targets")"
    "-Pcamerak.verify.modules=$(csv "$modules")"
)
$record && gradle_args+=("-Pcamerak.recordScreenshots=true")
[[ ${#gradle_extra[@]} -gt 0 ]] && gradle_args+=("${gradle_extra[@]}")

echo "verify.sh: mode=$mode targets=[$targets] modules=[$modules]"
echo "verify.sh: JAVA_HOME=$JAVA_HOME"
echo "verify.sh: Gradle log -> $log"

set +e
(cd "$ROOT" && ./gradlew "${gradle_args[@]}") >"$log" 2>&1
gradle_exit=$?
set -e

# Full history, as CI's scheduled scan does; findings land in the JSON report with values redacted.
if [[ " $targets " == *" lint "* ]]; then
    if command -v gitleaks >/dev/null 2>&1; then
        scan_report="$out_dir/gitleaks-$stamp.json"
        scan_log="$out_dir/gitleaks-$stamp.log"
        echo "verify.sh: gitleaks over full history -> $scan_log"
        set +e
        (cd "$ROOT" && gitleaks git --no-banner --redact --log-opts=--all --exit-code 3 \
            --report-format json --report-path "$scan_report" .) >"$scan_log" 2>&1
        scan_exit=$?
        set -e
        case "$scan_exit" in
            0) secret_scan_status="clean"; secret_scan_detail="$scan_report" ;;
            3) secret_scan_status="leaks"; secret_scan_detail="$scan_report" ;;
            *) secret_scan_status="error"; secret_scan_detail="gitleaks exited $scan_exit; see $scan_log" ;;
        esac
    else
        secret_scan_status="not run"
        secret_scan_detail="gitleaks not on PATH"
    fi
fi
write_run_file "$gradle_exit"

python3 -I "$ROOT/scripts/verify_report.py" --run "$run_file"
