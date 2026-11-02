#!/usr/bin/env bash
# scripts/test_lint_self_registration.sh must not pass on evidence this run did not produce: a
# lint-checks.txt left over from an earlier run, a verify.sh run that failed, or a summary that does
# not name the probe.
#
# Runs a copy of that test in a sandbox whose scripts/verify.sh is a stub, once per case, so it needs
# no Gradle and takes a second.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT

# Leaves the run's evidence behind as verify.sh would, varied by STUB_CASE:
#   fresh     the probe ran, this run wrote lint-checks.txt naming it, the summary names it, exit 0
#   stale     as fresh, but this run wrote no lint-checks.txt; an earlier run's copy names the probe
#   failed    as fresh, but verify.sh exits 1
#   unnamed   as fresh, but the summary's lint checks do not name the probe
cat >"$sandbox/verify.stub.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/verify"
mkdir -p "$out"
start="$(python3 -I -c 'import time; print(f"{time.time():.3f}")')"
log="$out/only-lint-stub.log"
printf '> Task :checkAgentPermissions\n> Task :probeLintCheck\nprobeLintCheck ran\n' >"$log"
printf '{"start_epoch": %s, "log": "%s"}\n' "$start" "$log" >"$out/run.json"
[[ "$STUB_CASE" == stale ]] || printf ':checkAgentPermissions\n:probeLintCheck\n' >"$out/lint-checks.txt"
named='":checkAgentPermissions", ":probeLintCheck"'
[[ "$STUB_CASE" == unnamed ]] && named='":checkAgentPermissions"'
result=GREEN
[[ "$STUB_CASE" == failed ]] && result=RED
printf '{"result": "%s", "lint_checks": [%s]}\n' "$result" "$named" >"$out/summary.json"
[[ "$STUB_CASE" == failed ]] && exit 1
exit 0
EOF

failures=()
run_case() {
    local name="$1" expected="$2" root="$sandbox/$1"
    mkdir -p "$root/scripts" "$root/build/verify"
    cp "$ROOT/scripts/test_lint_self_registration.sh" "$root/scripts/"
    cp "$sandbox/verify.stub.sh" "$root/scripts/verify.sh"
    chmod +x "$root/scripts/verify.sh" "$root/scripts/test_lint_self_registration.sh"
    # An earlier run's list, which names the probe.
    printf ':checkAgentPermissions\n:probeLintCheck\n' >"$root/build/verify/lint-checks.txt"
    touch -t 202001010000 "$root/build/verify/lint-checks.txt"
    STUB_CASE="$name" "$root/scripts/test_lint_self_registration.sh" >"$root/out.txt" 2>&1
    local exit=$?
    local actual=pass
    [[ $exit -eq 0 ]] || actual=fail
    if [[ "$actual" != "$expected" ]]; then
        failures+=("$name: expected the test to $expected, it did $actual (exit $exit): $(tr '\n' ' ' <"$root/out.txt")")
    else
        echo "  ok $name: test_lint_self_registration.sh did $actual"
    fi
}

run_case fresh pass
run_case stale fail
run_case failed fail
run_case unnamed fail

if [[ ${#failures[@]} -gt 0 ]]; then
    echo "test_lint_self_registration_guards: FAILED"
    printf '  %s\n' "${failures[@]}"
    exit 1
fi
echo "test_lint_self_registration_guards: OK"
