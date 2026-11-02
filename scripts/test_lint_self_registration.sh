#!/usr/bin/env bash
# A check task registered in the "camerak lint" group runs under `scripts/verify.sh --only lint`, and
# the report requires it, without being listed in the root build or in scripts/verify_report.py.
#
# Registers :probeLintCheck through a Gradle init script, runs the lint target, and fails unless
# verify.sh exits 0, the probe ran, this run wrote the lint check list and it names the probe, and the
# summary names it. Takes as long as `verify.sh --only lint`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cat >"$work/probe.init.gradle" <<'EOF'
rootProject {
    tasks.register("probeLintCheck") {
        group = "camerak lint"
        description = "A lint check that nothing lists by name"
        doLast { println("probeLintCheck ran") }
    }
}
EOF

set +e
"$ROOT/scripts/verify.sh" --only lint -- -I "$work/probe.init.gradle" >"$work/verify.out" 2>&1
verify_exit=$?
set -e

run_json="$ROOT/build/verify/run.json"
log="$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["log"])' "$run_json")"
problems=()
[[ $verify_exit -eq 0 ]] || problems+=("verify.sh --only lint exited $verify_exit")
grep -q '^> Task :probeLintCheck$' "$log" || problems+=("verify.sh --only lint did not run :probeLintCheck")
evidence="$ROOT/build/verify/lint-checks.txt"
if [[ ! -f "$evidence" ]]; then
    problems+=("the build wrote no lint check list at build/verify/lint-checks.txt for verify_report.py")
elif python3 -I -c 'import json, os, sys; sys.exit(os.stat(sys.argv[1]).st_mtime < float(json.load(open(sys.argv[2]))["start_epoch"]))' \
    "$evidence" "$run_json"; then
    grep -qx ':probeLintCheck' "$evidence" || problems+=("the lint check list verify_report.py reads does not name :probeLintCheck")
else
    problems+=("build/verify/lint-checks.txt is older than this run's start, so it is an earlier run's list")
fi
python3 -I -c 'import json, sys; sys.exit(":probeLintCheck" not in json.load(open(sys.argv[1])).get("lint_checks", []))' \
    "$ROOT/build/verify/summary.json" 2>/dev/null ||
    problems+=("build/verify/summary.json does not name :probeLintCheck among the lint checks it required")

if [[ ${#problems[@]} -gt 0 ]]; then
    echo "test_lint_self_registration: FAILED (verify.sh exited $verify_exit; log $log)"
    printf '  %s\n' "${problems[@]}"
    exit 1
fi
echo "test_lint_self_registration: OK (verify.sh exited $verify_exit; log $log)"
