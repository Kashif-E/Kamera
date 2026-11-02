#!/usr/bin/env bash
# A lint check moved out of the "camerak lint" group must fail the lint target, not silently stop
# running. Moves every lint check in the root build's requiredLintChecks out of it, keeps the group
# non-empty with a probe, and requires a lint-target build to fail naming each one.
#
# Plans the build with --dry-run, so it executes no task; takes one Gradle configuration.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

checks=(
    :Sample:checkMacOsCameraPackaging
    :cameraK:checkComposableStability
    :checkAgentPermissions
    :checkAndroidSdkFloor
    :checkAndroidSdkFloorRules
    :checkBuildGateRules
    :checkBuildIntegrity
    :checkCatalogHygiene
    :checkComposeBaseline
    :checkCredentialHygiene
    :checkDocsWorkflow
    :checkLicenseMetadata
    :checkNativeSettings
    :checkNoIntelSimulatorTarget
    :checkNoLibraryFrameworks
    :checkPluginSources
    :checkPublishedVariants
    :checkReleaseMetadata
    :checkReleaseWorkflow
    :checkRepositoryLinks
    :checkSecurityPolicy
    :checkVerifyCoverage
    :spotlessCheck
)

{
    echo 'gradle.projectsEvaluated {'
    echo '    rootProject.tasks.register("probeLintCheck") { group = "camerak lint" }'
    for check in "${checks[@]}"; do
        echo "    rootProject.tasks.getByPath(\"$check\").group = \"moved out of camerak lint\""
    done
    echo '}'
} >"$work/moved.init.gradle"

set +e
(cd "$ROOT" && ./gradlew --dry-run verifyAll -Pcamerak.verify.targets=lint -I "$work/moved.init.gradle") \
    >"$work/gradle.out" 2>&1
gradle_exit=$?
set -e

problems=()
if [[ $gradle_exit -eq 0 ]]; then
    problems+=("the lint target still plans with every lint check moved out of the group")
fi
# Only the failure's own text counts, so a task listed elsewhere in the output is no evidence.
sed -n '/What went wrong/,/\* Try:/p' "$work/gradle.out" >"$work/failure.txt"
for check in "${checks[@]}"; do
    grep -qE "(^|[^A-Za-z0-9:])${check}([^A-Za-z0-9]|$)" "$work/failure.txt" || problems+=("the failure does not name $check")
done

if [[ ${#problems[@]} -gt 0 ]]; then
    echo "test_lint_minimum_set: FAILED (Gradle exited $gradle_exit)"
    printf '  %s\n' "${problems[@]}"
    sed 's/^/  | /' "$work/failure.txt"
    exit 1
fi
echo "test_lint_minimum_set: OK (Gradle exited $gradle_exit and named all ${#checks[@]} checks)"
