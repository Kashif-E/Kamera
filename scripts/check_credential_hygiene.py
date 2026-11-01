#!/usr/bin/env python3
"""Fail if the repository could leak publishing credentials again.

The Maven Central token and the GPG signing passphrase were once committed in the tracked root
gradle.properties. This checks the things that let that happen, and the things that would catch
it next time:

- no gradle.properties inside the repository assigns a publishing credential;
- no tracked Markdown file points credentials at a gradle.properties other than ~/.gradle's;
- .gitignore ignores local key files and does not pretend to ignore gradle.properties;
- workflows pass secrets through env:, never into a run: line, a -P flag or a key file on disk;
- the publish workflow hands Gradle its credentials as ORG_GRADLE_PROJECT_* variables;
- no tracked build script loads credentials from a file in the repository or a keyring file;
- CI runs gitleaks: its official action pinned to a commit SHA on new commits, and on a schedule a
  checksum-verified gitleaks CLI over every branch and tag (`--log-opts=--all`), values redacted;
- scripts/git-hooks/pre-commit rejects a commit that adds a credential (any separator
  java.util.Properties accepts) or a PGP private key block, and accepts references to secrets.

Violations name the file, line and key, never a value. Standard library only; run with
`python3 -I`. Exits 1 on any violation, 2 if the check itself cannot run.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable, Iterator, List, Tuple

ROOT = Path(__file__).resolve().parent.parent
HOOK = ROOT / "scripts" / "git-hooks" / "pre-commit"
PRUNED_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "node_modules", "Pods", "site"}

# Gradle properties the publishing setup reads. signing.keyId and signingInMemoryKeyId are
# identifiers, not secrets, but they only ever travel with the passphrase.
CREDENTIAL_KEY = re.compile(
    r"^\s*(?P<key>mavenCentral(?:Username|Password)|ossrh(?:Username|Password)"
    r"|signing\.(?:keyId|password|secretKeyRingFile)|signingInMemoryKey(?:Id|Password)?)(?:\s*[=:]|\s+\S)"
)
CREDENTIAL_WORD = re.compile(r"credential|password|passphrase|secret|token|signing", re.IGNORECASE)
USER_HOME_PROPERTIES = re.compile(r"(?:~|\$HOME|\$\{HOME\}|GRADLE_USER_HOME)/(?:\.gradle/)?gradle\.properties")
SECRET_EXPRESSION = re.compile(r"\$\{\{\s*secrets\.")
CREDENTIAL_FLAG = re.compile(r"-P\s*(?:mavenCentral|signing|ossrh)")
KEY_FILE = re.compile(r"secring\.gpg|secretKeyRingFile|SECRET_KEY_RING_FILE")
LOCAL_PROPERTIES = re.compile(r"local\.properties")
BUILD_SCRIPT_CREDENTIAL = re.compile(r"signing\.(?:keyId|password)|ossrh(?:Username|Password)|mavenCentral(?:Username|Password)")
RUN_KEY = re.compile(r"^\s*(?:-\s+)?run:(?P<rest>.*)$")
PUBLISH_TASK = re.compile(r"publish\w*MavenCentral", re.IGNORECASE)
GITLEAKS_ACTION = re.compile(r"uses:\s*gitleaks/gitleaks-action@(?P<ref>\S+)")
# The action runs `gitleaks detect` with no --log-opts on scheduled runs, which covers only the
# checked-out branch. Every branch and tag needs the CLI with --log-opts=--all.
FULL_HISTORY_SCAN = re.compile(r"gitleaks\S*\s+(?:git|detect)\b.*--log-opts[= ]\s*['\"]?--all\b")
REDACT = re.compile(r"--redact\b")
CHECKSUM_VERIFY = re.compile(r"\b(?:sha256sum|shasum\s+-a\s*256)\b.*\s(?:-c|--check)\b")
SHA256 = re.compile(r"\b[0-9a-f]{64}\b")
PUBLISH_ENV = (
    "ORG_GRADLE_PROJECT_mavenCentralUsername",
    "ORG_GRADLE_PROJECT_mavenCentralPassword",
    "ORG_GRADLE_PROJECT_signingInMemoryKey",
    "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword",
)

Violation = str


class CheckError(Exception):
    """The check could not run, which must not read as a pass."""


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT))


def isolated_git_env(home: str) -> dict:
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(
        GIT_CONFIG_GLOBAL=os.devnull,
        GIT_CONFIG_NOSYSTEM="1",
        HOME=home,
        GIT_AUTHOR_NAME="check",
        GIT_AUTHOR_EMAIL="check@example.invalid",
        GIT_COMMITTER_NAME="check",
        GIT_COMMITTER_EMAIL="check@example.invalid",
    )
    return env


def git(args: List[str], cwd: Path, env: dict | None = None) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True, text=True)
    except OSError as error:
        raise CheckError(f"cannot run git {' '.join(args)}: {error}") from error


def tracked_files(pattern: str) -> List[Path]:
    result = git(["ls-files", "-z", "--", pattern], ROOT)
    if result.returncode != 0:
        raise CheckError(f"git ls-files failed: {result.stderr.strip()}")
    return [ROOT / name for name in result.stdout.split("\0") if name]


def repository_files(name: str) -> Iterator[Path]:
    for directory, subdirs, files in os.walk(ROOT):
        subdirs[:] = [d for d in subdirs if d not in PRUNED_DIRS]
        if name in files:
            yield Path(directory) / name


def check_gradle_properties() -> List[Violation]:
    """Any gradle.properties under the repository can be committed; none may hold a credential."""
    violations = []
    for path in repository_files("gradle.properties"):
        for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            match = CREDENTIAL_KEY.match(line)
            if match:
                violations.append(
                    f"{rel(path)}:{number}: assigns `{match.group('key')}`; credentials belong in "
                    "~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* variables"
                )
    return violations


def check_docs() -> List[Violation]:
    """A doc that sends credentials to the project's gradle.properties produces the next leak."""
    violations = []
    for path in tracked_files("*.md"):
        for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            elsewhere = USER_HOME_PROPERTIES.sub("", line)
            if "gradle.properties" in elsewhere and CREDENTIAL_WORD.search(line):
                at = elsewhere.find("gradle.properties")
                excerpt = elsewhere[max(0, at - 90) : at + 50].strip()
                violations.append(
                    f"{rel(path)}:{number}: points credentials at a gradle.properties other than "
                    f"~/.gradle/gradle.properties: ...{excerpt}..."
                )
    return violations


def check_gitignore() -> List[Violation]:
    """Asks git, in a scratch repository holding only .gitignore, what the patterns really do."""
    gitignore = ROOT / ".gitignore"
    if not gitignore.is_file():
        return [".gitignore is missing"]
    must_ignore = ["secring.gpg", "keys/signing.gpg", "signing-key.asc", "local.properties", "cameraK/local.properties"]
    # Ignoring a tracked file has no effect, so a broad pattern only hides untracked module files
    # and suggests the root one is safe to fill in.
    must_track = ["gradle.properties", "cameraK/gradle.properties"]
    violations = []
    with tempfile.TemporaryDirectory() as scratch:
        env = isolated_git_env(scratch)
        repo = Path(scratch) / "repo"
        repo.mkdir()
        if git(["init", "-q"], repo, env).returncode != 0:
            raise CheckError("git init failed in a scratch directory")
        (repo / ".gitignore").write_text(gitignore.read_text())
        for path in must_ignore + must_track:
            result = git(["-c", f"core.excludesFile={os.devnull}", "check-ignore", "-q", "--no-index", path], repo, env)
            if result.returncode not in (0, 1):
                raise CheckError(f"git check-ignore failed for {path}: {result.stderr.strip()}")
            ignored = result.returncode == 0
            if path in must_ignore and not ignored:
                violations.append(f".gitignore: does not ignore `{path}` (local signing keys and SDK paths)")
            if path in must_track and ignored:
                violations.append(
                    f".gitignore: ignores `{path}`; that has no effect on the tracked root file and hides "
                    "module-level ones"
                )
    return violations


def run_scripts(lines: List[str]) -> Iterator[List[Tuple[int, str]]]:
    """Yields each `run:` value in a workflow as its (line number, text) lines."""
    index = 0
    while index < len(lines):
        match = RUN_KEY.match(lines[index])
        if not match:
            index += 1
            continue
        column = lines[index].index("run:")
        script = [(index + 1, match.group("rest"))]
        index += 1
        while index < len(lines):
            line = lines[index]
            if line.strip() and len(line) - len(line.lstrip()) <= column:
                break
            script.append((index + 1, line))
            index += 1
        yield script


def run_blocks(lines: List[str]) -> Iterator[Tuple[int, str]]:
    """Yields (line number, text) for every line of every `run:` value in a workflow."""
    for script in run_scripts(lines):
        yield from script


def shell_commands(script: List[Tuple[int, str]]) -> Iterator[str]:
    """Joins backslash-continued lines, so a flag on a continuation line counts."""
    command = ""
    for _, line in script:
        stripped = line.rstrip()
        if stripped.endswith("\\"):
            command += stripped[:-1] + " "
            continue
        yield command + stripped
        command = ""
    if command:
        yield command


def check_workflows() -> List[Violation]:
    workflows = sorted((ROOT / ".github" / "workflows").glob("*.y*ml"))
    if not workflows:
        raise CheckError("no workflows found under .github/workflows")
    violations = []
    gitleaks_refs: List[Tuple[Path, str]] = []
    for path in workflows:
        text = path.read_text()
        lines = text.splitlines()
        publishes = False
        for number, line in run_blocks(lines):
            if SECRET_EXPRESSION.search(line):
                violations.append(
                    f"{rel(path)}:{number}: interpolates a secret into a run: line; pass it through env:"
                )
            if CREDENTIAL_FLAG.search(line):
                violations.append(
                    f"{rel(path)}:{number}: passes a credential as a -P flag; use ORG_GRADLE_PROJECT_* env vars"
                )
            if PUBLISH_TASK.search(line):
                publishes = True
        for number, line in enumerate(lines, 1):
            if KEY_FILE.search(line):
                violations.append(
                    f"{rel(path)}:{number}: writes or reads a signing keyring file; use "
                    "ORG_GRADLE_PROJECT_signingInMemoryKey"
                )
            match = GITLEAKS_ACTION.search(line)
            if match:
                gitleaks_refs.append((path, match.group("ref")))
        if publishes:
            for name in PUBLISH_ENV:
                if not re.search(rf"^\s*{name}:\s*\$\{{\{{\s*secrets\.\w+\s*\}}\}}\s*$", text, re.MULTILINE):
                    violations.append(f"{rel(path)}: publishes without `{name}` set from a secret in env:")
    if not gitleaks_refs:
        violations.append(".github/workflows: no job runs gitleaks/gitleaks-action")
    violations += check_full_history_scan(workflows)
    for path, ref in gitleaks_refs:
        text = path.read_text()
        if not re.fullmatch(r"[0-9a-f]{40}", ref):
            violations.append(f"{rel(path)}: gitleaks/gitleaks-action@{ref} is not pinned to a full commit SHA")
        if not re.search(r"^\s*fetch-depth:\s*0\s*$", text, re.MULTILINE):
            violations.append(f"{rel(path)}: checkout lacks `fetch-depth: 0`, so gitleaks cannot see history")
        if not re.search(r"^\s*schedule:\s*$", text, re.MULTILINE):
            violations.append(
                f"{rel(path)}: no schedule: trigger; push and pull_request runs scan only new commits, "
                "so nothing scans full history"
            )
    return violations


def check_full_history_scan(workflows: List[Path]) -> List[Violation]:
    """A scheduled job must run a checksum-verified gitleaks over every ref, with values redacted."""
    scheduled = [path for path in workflows if re.search(r"^\s*schedule:\s*$", path.read_text(), re.MULTILINE)]
    for path in scheduled:
        text = path.read_text()
        commands = [command for script in run_scripts(text.splitlines()) for command in shell_commands(script)]
        scans = [command for command in commands if FULL_HISTORY_SCAN.search(command)]
        if not scans:
            continue
        violations = []
        if not all(REDACT.search(command) for command in scans):
            violations.append(f"{rel(path)}: the full-history gitleaks scan does not pass --redact, so findings print secrets to the log")
        if not any(CHECKSUM_VERIFY.search(command) for command in commands) or not SHA256.search(text):
            violations.append(
                f"{rel(path)}: runs a gitleaks binary without checking it against a pinned sha256 "
                "(sha256sum --check)"
            )
        return violations
    return [
        ".github/workflows: no scheduled job runs `gitleaks git --log-opts=--all`; the gitleaks action "
        "scans only the checked-out branch, so other branches and tags are never scanned"
    ]


def check_build_scripts() -> List[Violation]:
    """Build logic that reads credentials from a repository file sends the next maintainer there."""
    violations = []
    for path in tracked_files("*.gradle.kts") + tracked_files("*.gradle"):
        text = path.read_text(errors="replace")
        reads_local_file = LOCAL_PROPERTIES.search(text) and BUILD_SCRIPT_CREDENTIAL.search(text)
        for number, line in enumerate(text.splitlines(), 1):
            if KEY_FILE.search(line):
                violations.append(f"{rel(path)}:{number}: reads a signing keyring file; use signingInMemoryKey")
            if reads_local_file and LOCAL_PROPERTIES.search(line):
                violations.append(
                    f"{rel(path)}:{number}: loads publishing credentials from local.properties in the "
                    "repository; Gradle reads them from ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_*"
                )
    return violations


def check_pre_commit_hook() -> List[Violation]:
    """Runs the hook in a scratch repository: forbidden additions fail, clean ones commit."""
    if not HOOK.is_file():
        return [f"{rel(HOOK)} is missing (it must reject added credentials and PGP key blocks)"]
    if not os.access(HOOK, os.X_OK):
        return [f"{rel(HOOK)} is not executable"]
    # Assembled at runtime so this file never contains the strings the hook and gitleaks reject.
    value = "placeholder-" + "value"
    password = "mavenCentral" + "Password"
    forbidden = {
        "Central Portal password": password + "=" + value,
        "Central Portal password after `:`": password + ": " + value,
        "Central Portal password after whitespace": password + " " + value,
        "quoted Central Portal password after `:`": "  ORG_GRADLE_PROJECT_" + password + ": '" + value + "'",
        "Central Portal password in a shell export": "export ORG_GRADLE_PROJECT_" + password + "=" + value,
        "Central Portal username": "mavenCentral" + "Username=" + value,
        "signing passphrase": "signing." + "password=" + value,
        "signing passphrase after `:`": "signing." + "password:" + value,
        "in-memory signing key": "signingInMemory" + "Key=" + value,
        "OSSRH password": "ossrh" + "Password=" + value,
        "PGP key block": "-----BEGIN " + "PGP PRIVATE KEY BLOCK-----",
    }
    # None of these holds a secret; rejecting them would push people to --no-verify.
    allowed = {
        "PGP public key block": "-----BEGIN " + "PGP PUBLIC KEY BLOCK-----",
        "Gradle reference to an environment variable": "signingInMemoryKey" + 'Password=${System.getenv("X")}',
        "Kotlin read of an environment variable": "val " + password + ' = System.getenv("X")',
        "workflow env set from a secret": "  ORG_GRADLE_PROJECT_" + password + ": ${{ secrets.X }}",
        "signing key id": "signingInMemory" + "KeyId=ABCDEF12",
        "credential key with no value": password + "=",
    }
    violations = []
    with tempfile.TemporaryDirectory() as scratch:
        env = isolated_git_env(scratch)
        repo = Path(scratch) / "repo"
        repo.mkdir()
        for args in (["init", "-q"], ["config", "core.hooksPath", str(HOOK.parent)]):
            if git(args, repo, env).returncode != 0:
                raise CheckError(f"git {' '.join(args)} failed in a scratch directory")

        def commit(content: str, message: str, verify: bool = True) -> subprocess.CompletedProcess:
            (repo / "build.properties").write_text(content)
            if git(["add", "build.properties"], repo, env).returncode != 0:
                raise CheckError("git add failed in a scratch directory")
            return git(["commit", "-q", "-m", message] + ([] if verify else ["--no-verify"]), repo, env)

        clean = commit("org.gradle.jvmargs=-Xmx4G\n", "clean")
        if clean.returncode != 0:
            violations.append(f"{rel(HOOK)}: rejected a commit with no credentials: {clean.stderr.strip()[:200]}")
        for label, line in forbidden.items():
            result = commit(f"org.gradle.jvmargs=-Xmx4G\n{line}\n", label)
            if result.returncode == 0:
                violations.append(f"{rel(HOOK)}: accepted a commit that adds a {label}")
                git(["reset", "-q", "--hard", "HEAD~1"], repo, env)
            elif value in result.stdout + result.stderr:
                violations.append(f"{rel(HOOK)}: printed the rejected value for a {label}")
        for label, line in allowed.items():
            result = commit(f"org.gradle.jvmargs=-Xmx4G\n{line}\n", label)
            if result.returncode != 0:
                violations.append(f"{rel(HOOK)}: rejected a commit that adds a {label}, which is not a secret")
                git(["reset", "-q", "--hard", "HEAD"], repo, env)
        # Removing a leaked line must stay possible.
        leaked = commit(f"{forbidden['signing passphrase']}\n", "already leaked", verify=False)
        if leaked.returncode != 0:
            raise CheckError(f"git commit --no-verify failed in a scratch directory: {leaked.stderr.strip()}")
        removal = commit("org.gradle.jvmargs=-Xmx4G\n", "remove the leak")
        if removal.returncode != 0:
            violations.append(f"{rel(HOOK)}: rejected a commit that only removes a credential line")
    return violations


CHECKS: List[Tuple[str, Callable[[], List[Violation]]]] = [
    ("gradle.properties holds no credentials", check_gradle_properties),
    ("docs keep credentials out of tracked files", check_docs),
    (".gitignore covers local key files", check_gitignore),
    ("workflows pass secrets through env and scan full history", check_workflows),
    ("build scripts read credentials only from Gradle properties", check_build_scripts),
    ("pre-commit hook rejects credentials", check_pre_commit_hook),
]


def main() -> int:
    failed = 0
    for name, check in CHECKS:
        try:
            violations = check()
        except CheckError as error:
            print(f"ERROR {name}: {error}")
            return 2
        if violations:
            failed += 1
            print(f"FAIL  {name}")
            for violation in violations:
                print(f"      {violation}")
        else:
            print(f"ok    {name}")
    print(f"credential hygiene: {len(CHECKS) - failed}/{len(CHECKS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
