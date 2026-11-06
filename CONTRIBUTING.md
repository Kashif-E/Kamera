# Contributing

Thanks for your interest in contributing to CameraK!

## Before You Start

- Search existing issues to avoid duplicates.
- For significant changes, open an issue to discuss the approach first.

## Development Setup

1. Clone the repository.
2. Open the project in Android Studio or IntelliJ IDEA.
3. Run checks locally before submitting (see [Verifying changes](#verifying-changes)):

```bash
scripts/verify.sh
```

## Verifying changes

`scripts/verify.sh` runs the same checks as CI and prints a GREEN or RED summary. It needs JDK 17
(found through `/usr/libexec/java_home -v 17` when `JAVA_HOME` is not 17) and the Android SDK
(`ANDROID_HOME`, default `~/Library/Android/sdk`).

```bash
scripts/verify.sh                            # full run: spotless, every module's desktop, Android and iOS tests, Android lint, screenshots, the Sample's tests, build and iOS framework link, and the publish step
scripts/verify.sh --quick                    # desktop JVM tests only, the fastest loop
scripts/verify.sh --module qrScannerPlugin   # one module
scripts/verify.sh --only android             # one target: desktop, android, ios, screenshots, lint or sample
```

The Gradle log goes to `build/verify/<mode>-<timestamp>.log`, and the summary goes to
`build/verify/summary.md` and `summary.json`. The run is RED if Gradle fails, if a test fails, or
if a requested target ran zero tests. Results left over from earlier runs are ignored. The sample
target runs the Sample's own tests (`:Sample:desktopTest`) and counts them as `Sample / sample`.

**The publish step.** A `--full` run (and no narrower one) also publishes every published module, the
BOM and the relocation POMs, unsigned, to `build/verify/m2`, which it empties first; nothing goes to
`~/.m2` or Maven Central. The run is RED unless every coordinate a release uploads is there: each
artifact's root and its `android`, `desktop`, `iosarm64` and `iossimulatorarm64` variants (`.module`
and `.pom`), the BOM's root, and each relocation POM pointing at its 2.0 id. Compiling the tests never
builds the metadata compilations behind a root artifact, so only publishing shows they work.
`./gradlew verifyAll` runs the same tasks without the summary.

**Adding a lint check.** Register the task with `group = lintGroup` in the root build (in a
module, `rootProject.extra["lintGroup"]`, which is `"camerak lint"`). `verifyAll` runs every task
in that group when the lint target is selected, and writes their paths to
`build/verify/lint-checks.txt`, which `verify_report.py` requires to have run and lists in the
summary; no list is needed to add one. The checks that existed when the group became the only list
are named in the root build's `requiredLintChecks`, so moving one out of the group fails the lint
target instead of dropping it; remove a check from there only when deleting it.
`scripts/test_lint_self_registration.sh` registers a probe check that way and fails unless
`verify.sh --only lint` exits 0, runs the probe, writes the list in that run and names the probe in
the summary. `scripts/test_lint_self_registration_guards.sh` checks that test against stale or
failed evidence in a second, and `scripts/test_lint_minimum_set.sh` moves every required check out
of the group and requires the lint target to fail naming each.

**Test first.** A bug fix or behaviour change starts with a test that fails without the change.
Run the harness and see it go RED for that test, then make the change and see it go GREEN.
Do not loosen an assertion, widen a tolerance, delete a case or add an ignore to get a pass. If a
test is wrong, say why in the PR.

**Published classpath, and why there is no `buildHealth`.** Each library's
`checkPublishedClasspath` (run by `verify.sh` whenever the desktop, android or ios target is
selected, as `--full` and `--quick` do; `--only lint` does not run it) fails on a `*-platform`
umbrella or native classifier jar on `desktopRuntimeClasspath`, a bytedeco binding the library does
not import, a bytedeco `api` dependency no public signature exposes, and the specific libraries
listed in the root build's `ClasspathRule` and `ApiExportRule` lists on the published and
`*ApiElements` configurations. The dependency-analysis plugin (`com.autonomousapps.dependency-analysis`) is not
wired in. Its 3.x line supports Gradle 9.4.1 and AGP 8.13.2, but in a Kotlin Multiplatform module
it analyses only the `jvm` target and the `androidLibrary` target of
`com.android.kotlin.multiplatform.library`. These modules use `com.android.library` with
`androidTarget()`, and it does not analyse iOS, so it would check only the desktop target, which
`checkPublishedClasspath` already covers for bytedeco. What neither covers: an unused dependency,
or a used transitive one, that no rule names (for example `ocrPlugin` importing
`org.bytedeco.javacpp.BytePointer` while getting javacpp only through the core's `api`); an
`implementation` dependency that should be `api` because a public signature exposes it; and
anything on the Android and iOS targets beyond the named rules. Revisit when the modules move to
`com.android.kotlin.multiplatform.library`.

**Screenshots.** Desktop goldens live in `cameraK/src/desktopTest/resources/goldens`. Paparazzi
goldens live in `cameraKScreenshotTests/src/test/snapshots`. Both are compared pixel for pixel and
recorded on macOS. After an intended UI change, re-record them and commit the new PNGs:

```bash
scripts/verify.sh --record-screenshots
```

When a comparison fails, the actual render and a diff are written to
`cameraK/build/reports/screenshots-desktop/` and `cameraKScreenshotTests/build/paparazzi/failures/`.

**Public API and ABI dumps.** Every published module is compiled in explicit API mode, so each
declaration states its visibility, and checks its ABI against the dumps committed under its `api/`
directory (`api/android/*.api`, `api/desktop/*.api`, `api/*.klib.api`). Each `checkKotlinAbi` runs in
the lint target, so an API change the dumps do not record fails `verify.sh`. After reviewing a change
to the public API, rewrite the dumps and commit them with it:

```bash
./gradlew updateKotlinAbi
```

The lint target also rejects a public `data class`, a `Channel` or `MutableStateFlow`/`MutableSharedFlow`
in a public signature, a public sealed type or enum whose KDoc does not open with "Closed" or "Open",
and an ABI dump that names an atomicfu type (`checkBuildGateRules`). ERROR-level deprecation hints are
exempt.

**Dependency verification.** Gradle checks the sha256 of every dependency it downloads against
`gradle/verification-metadata.xml`. Any dependency change, a Dependabot update included, fails the build
until the file is regenerated, in the same commit, over everything CI and a release resolve:

```bash
./gradlew --write-verification-metadata sha256 --continue verifyAll -Pcamerak.verify.rerunTests=true \
    -Pcamerak.verify.publishRepo="$PWD/build/verify/m2" -PsignAllPublications=false
./gradlew --write-verification-metadata sha256 --dry-run publishAllPublicationsToMavenCentralRepository
mv gradle/verification-metadata.dryrun.xml gradle/verification-metadata.xml
```

The second command adds what signing a release resolves; with `--dry-run` it uploads nothing and
writes `verification-metadata.dryrun.xml`, the first file plus those entries. Review the new entries
before committing. CI runs on Apple silicon macOS, which is what the file covers; on another OS the
desktop tests' bytedeco natives and the Kotlin/Native toolchain differ, so run the first command there
and do not commit the result.

The release entries come from a `--dry-run`, which resolves what signing needs but runs no task, so
nothing has yet checked them against a real upload. The maintainer's first signed
`UploadLibToCentral` run is that check: if it fails dependency verification, regenerate the file from
that run's task set and commit it before tagging again.

## Releasing

A release is a pushed tag named `v` plus the `VERSION_NAME` in `gradle.properties` (for 2.0, `v2.0`).
`.github/workflows/UploadLibToCentral.yml` runs only for such tags: it first runs the whole of
`build.yml` (`scripts/verify.sh --full`) on the tagged commit, then `scripts/check_release_tag.sh`
refuses a tag that does not match `VERSION_NAME`, and the upload job waits in the `maven-central`
environment. Nothing can start a publish by hand or from a branch, and only one publish runs at a time.

The `maven-central` environment is configured in the repository settings, not in a file: it needs
required reviewers, a deployment rule allowing only `v*` tags, and the five publishing secrets
(`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, `SIGNING_KEY_ID`,
`SIGNING_PASSWORD`) as environment secrets, deleted from the repository-level secrets.
`scripts/verify.sh --only lint` runs `scripts/check_release_workflow.py`, which fails if the workflow loses any of these
gates.

## Publishing credentials

Credentials live only in `~/.gradle/gradle.properties` or in `ORG_GRADLE_PROJECT_*` environment
variables. Never put them in a file inside this repository. The root `gradle.properties` is tracked
and holds build settings only; `.gitignore` cannot protect a tracked file, so `git commit -a` would
pick up anything added to it.

The properties are `mavenCentralUsername` and `mavenCentralPassword` (a Central Portal user token),
and `signingInMemoryKey` (the ASCII-armored export of the signing subkey), `signingInMemoryKeyId`
and `signingInMemoryKeyPassword`. The publish workflow sets them from repository secrets through
`env:`; no secret appears in a `run:` line and no key file is written to disk.

Enable the pre-commit hook once per clone. It rejects a commit that adds a literal value for one of
those properties, an OSSRH credential or a signing passphrase, after `=`, `:` or whitespace, or a PGP
private key block. References such as `${{ secrets.X }}` or `System.getenv("X")` pass:

```bash
git config core.hooksPath scripts/git-hooks
```

`scripts/verify.sh --only lint` runs `scripts/check_credential_hygiene.py`, which fails if a
properties file in the repository holds a credential, a doc points credentials at one, a workflow
interpolates a secret into `run:`, a build script loads credentials from a file in the repository,
or the hook stops rejecting credentials. When `gitleaks` is on your `PATH` it also scans every
branch and tag (`--log-opts=--all`) with `.gitleaks.toml`; otherwise the summary says the scan did
not run. In CI, `.github/workflows/secret-scan.yml` runs the gitleaks action on the commits each push
to `main` and each pull request brings, and every week (or on demand) runs the gitleaks CLI, checked
against a pinned sha256, over every branch and tag with `--log-opts=--all` and values redacted.

The Maven Central token and the signing passphrase were committed to this repository's history
before `a626597`. Removing them from the tree did not revoke them. Until the token and signing
subkey are rotated, the full-history scan fails on those commits. After rotation, add their
gitleaks fingerprints to `.gitleaksignore` with a link to the rotation PR; any other finding still
fails the build.

## Branching and Pull Requests

- Create a feature branch from the default branch.
- Keep changes focused and minimal.
- Add or update tests when appropriate.
- Update documentation when you change public APIs.

## Commit Messages

Use concise, descriptive commit messages. Example:

```
Add QR scanner pause/resume support
```

## Code Style

- Follow Kotlin style guidelines.
- Prefer clear names and small, focused functions.
- Avoid breaking API changes unless discussed.

## Reporting Bugs

Please use the bug report template in .github/ISSUE_TEMPLATE.

## Requesting Features

Please use the feature request template in .github/ISSUE_TEMPLATE.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.