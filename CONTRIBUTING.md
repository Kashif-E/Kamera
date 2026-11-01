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
scripts/verify.sh                            # full run: spotless, every module's desktop, Android and iOS tests, screenshots, Sample build
scripts/verify.sh --quick                    # desktop JVM tests only, the fastest loop
scripts/verify.sh --module qrScannerPlugin   # one module
scripts/verify.sh --only android             # one target: desktop, android, ios, screenshots, lint or sample
```

The Gradle log goes to `build/verify/<mode>-<timestamp>.log`, and the summary goes to
`build/verify/summary.md` and `summary.json`. The run is RED if Gradle fails, if a test fails, or
if a requested target ran zero tests. Results left over from earlier runs are ignored.
`./gradlew verifyAll` runs the same tasks without the summary.

**Test first.** A bug fix or behaviour change starts with a test that fails without the change.
Run the harness and see it go RED for that test, then make the change and see it go GREEN.
Do not loosen an assertion, widen a tolerance, delete a case or add an ignore to get a pass. If a
test is wrong, say why in the PR.

**Screenshots.** Desktop goldens live in `cameraK/src/desktopTest/resources/goldens`. Paparazzi
goldens live in `cameraKScreenshotTests/src/test/snapshots`. Both are compared pixel for pixel and
recorded on macOS. After an intended UI change, re-record them and commit the new PNGs:

```bash
scripts/verify.sh --record-screenshots
```

When a comparison fails, the actual render and a diff are written to
`cameraK/build/reports/screenshots-desktop/` and `cameraKScreenshotTests/build/paparazzi/failures/`.

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