#!/usr/bin/env python3
"""Repository policies that live in tracked files rather than in the Gradle model.

Usage: check_repo_policy.py <policy> [<policy> ...]

  agent-permissions  Committed agent configuration (.claude/) pre-approves nothing that reads or
                     changes secrets, signs, publishes, commits or rewrites history, nothing that
                     edits files, and no path outside the repository; personal approvals stay in
                     the gitignored .claude/settings.local.json.

Each policy also runs its rule against a fixture it must reject, so a rule that matches nothing
cannot pass. Standard library only; run with `python3 -I`. Exits 1 on any violation, 2 if a check
cannot run.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, Dict, List

ROOT = Path(__file__).resolve().parent.parent


class CheckError(Exception):
    """The check could not run, which must not read as a pass."""


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def tracked(pattern: str) -> List[Path]:
    result = git("ls-files", "-z", "--", pattern)
    if result.returncode != 0:
        raise CheckError(f"git ls-files exited {result.returncode}: {result.stderr.strip()}")
    return [ROOT / name for name in result.stdout.split("\0") if name]


# ─── agent-permissions ──────────────────────────────────────────────────────────────────────────

SHARED_AGENT_SETTINGS = ".claude/settings.json"
LOCAL_AGENT_SETTINGS = ".claude/settings.local.json"

# A rule is Tool or Tool(specifier). Commands that touch secrets, signing, publishing or history,
# and anything that writes files, never get project-wide approval.
FORBIDDEN_COMMANDS = [
    (re.compile(r"\bgh\s+(secret|auth|variable|release|api)\b"), "GitHub secrets, auth, variables, releases or API"),
    (re.compile(r"\bgit\s+(commit|push|tag|rebase|reset|filter-branch|filter-repo|update-ref|config)\b"),
     "history- or remote-mutating git"),
    (re.compile(r"\b(gpg|signing)\b", re.IGNORECASE), "signing"),
    (re.compile(r"publish", re.IGNORECASE), "publishing"),
    (re.compile(r"\bsed\s+(-\S*\s+)*-i\b|\brm\b|\bmv\b|\bchmod\b|>"), "file-mutating commands"),
]
PERSONAL_PATH = re.compile(r"(^|[(\s:=])(//|~|/Users/|/home/|[A-Za-z]:\\)")
RULE = re.compile(r"^(?P<tool>[A-Za-z]+)(\((?P<specifier>.*)\))?$")
FILE_WRITING_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}


def agent_rule_problems(rules: List[str]) -> List[str]:
    problems = []
    for rule in rules:
        match = RULE.match(rule.strip())
        if not match:
            problems.append(f"'{rule}' is not a Tool(specifier) rule")
            continue
        tool, specifier = match.group("tool"), match.group("specifier")
        if tool == "Bash" and specifier in (None, "", "*", ":*"):
            problems.append(f"'{rule}' approves every shell command")
        if tool in FILE_WRITING_TOOLS:
            problems.append(f"'{rule}' approves writing files")
        if specifier and PERSONAL_PATH.search(specifier):
            problems.append(f"'{rule}' names a path outside the repository")
        if tool == "Bash" and specifier:
            problems += [f"'{rule}' approves {what}" for pattern, what in FORBIDDEN_COMMANDS if pattern.search(specifier)]
    return problems


def agent_settings_problems(settings: object, name: str) -> List[str]:
    if not isinstance(settings, dict):
        return [f"{name} is not a JSON object"]
    permissions = settings.get("permissions", {})
    if not isinstance(permissions, dict):
        return [f"{name}: permissions is not an object"]
    if permissions.get("defaultMode") not in (None, "default", "plan"):
        return [f"{name}: defaultMode {permissions.get('defaultMode')} skips prompts for every contributor"]
    rules = permissions.get("allow", [])
    if not isinstance(rules, list) or not all(isinstance(rule, str) for rule in rules):
        return [f"{name}: permissions.allow is not a list of strings"]
    return [f"{name}: {problem}" for problem in agent_rule_problems(rules)]


def check_agent_permissions() -> List[str]:
    fixture = {
        "permissions": {
            "allow": ["Read(//Users/someone/**)", "Bash(gh secret:*)", "Bash(git commit:*)", "Bash(sed -i '' s/a/b/ x)"]
        }
    }
    rejected = agent_settings_problems(fixture, "fixture")
    if len(rejected) < 4:
        raise CheckError(f"the agent-permissions rule accepts a fixture it must reject: {rejected}")

    problems = []
    for path in tracked(".claude/*"):
        name = path.relative_to(ROOT).as_posix()
        if name == LOCAL_AGENT_SETTINGS:
            problems.append(f"{name} is tracked; personal approvals belong in the gitignored local file")
            continue
        if path.suffix != ".json":
            continue
        try:
            settings = json.loads(path.read_text())
        except ValueError as error:
            problems.append(f"{name} is not valid JSON: {error}")
            continue
        problems += agent_settings_problems(settings, name)
    # Only the repository's own .gitignore counts; a contributor's global excludes file does not.
    if git("-c", "core.excludesFile=/dev/null", "check-ignore", "-q", "--no-index", LOCAL_AGENT_SETTINGS).returncode != 0:
        problems.append(f"{LOCAL_AGENT_SETTINGS} is not gitignored, so personal approvals can be committed")
    return problems


POLICIES: Dict[str, Callable[[], List[str]]] = {
    "agent-permissions": check_agent_permissions,
}


def main(argv: List[str]) -> int:
    unknown = [name for name in argv if name not in POLICIES]
    if not argv or unknown:
        print(f"usage: check_repo_policy.py <{'|'.join(POLICIES)}> ...", file=sys.stderr)
        return 2
    failed = False
    for name in argv:
        try:
            problems = POLICIES[name]()
        except (CheckError, OSError) as error:
            print(f"check_repo_policy {name}: cannot run: {error}", file=sys.stderr)
            return 2
        if problems:
            failed = True
            print(f"{name}:")
            for problem in problems:
                print(f"  {problem}")
        else:
            print(f"{name}: OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
