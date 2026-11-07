#!/usr/bin/env python3
"""Fail if the release workflow could publish a commit that was not tested, tagged and approved.

A Maven Central upload cannot be undone. The publish workflow must therefore:

- run only for a pushed `v*` tag, never by hand or for a branch;
- check that the tag names the version gradle.properties publishes (scripts/check_release_tag.sh,
  which this script also runs against a matching and a mismatching tag);
- wait for the full verification harness, reused from build.yml rather than copied;
- publish from the protected `maven-central` environment, on the runner image CI uses;
- publish in two steps of that one job, `publishLibraries` then `publishLegacyRelocations`, after the tag
  check, neither conditional, so each namespace is its own deployment and a failed first step stops
  the second;
- hold only `contents: read`, and never run two publishes at once (`publish` concurrency group).

Credentials travelling as env variables only is checked by check_credential_hygiene.py.

Standard library only; run with `python3 -I`. Exits 1 on any violation, 2 if the check cannot run.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from typing import Any, List, Tuple

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
RELEASE_WORKFLOW = WORKFLOWS / "UploadLibToCentral.yml"
BUILD_WORKFLOW = WORKFLOWS / "build.yml"
TAG_GUARD = ROOT / "scripts" / "check_release_tag.sh"
ENVIRONMENT = "maven-central"
CONCURRENCY_GROUP = "publish"
# The release publishes in two builds, one Central Portal deployment per namespace: the libraries under
# GROUP, then the relocation POMs under LEGACY_GROUP. PUBLISH_TASK sees any step that publishes.
RELEASE_TASKS = ("publishLibraries", "publishLegacyRelocations")
PUBLISH_TASK = re.compile(r"\bpublish(?:\w*MavenCentral\w*|Libraries|LegacyRelocations)\b", re.IGNORECASE)
FULL_HARNESS = re.compile(r"scripts/verify\.sh\s+--full\b")


class CheckError(Exception):
    """The check could not run, which must not read as a pass."""


# ─── A YAML subset: block mappings, block sequences, scalars and `|`/`>` block scalars ──────────
# Workflow files use nothing else. Flow collections ([a, b]) are read as lists of plain scalars.


def _strip_comment(line: str) -> str:
    quote = None
    for index, char in enumerate(line):
        if quote:
            if char == quote:
                quote = None
        elif char in "'\"":
            quote = char
        elif char == "#" and (index == 0 or line[index - 1] in " \t"):
            return line[:index].rstrip()
    return line.rstrip()


def _scalar(text: str) -> Any:
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        return [_scalar(part) for part in text[1:-1].split(",") if part.strip()]
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "'\"":
        return text[1:-1]
    return {"true": True, "false": False}.get(text, text)


def parse_yaml(text: str) -> Any:
    raw = text.splitlines()
    lines: List[Tuple[int, str, int]] = []  # (indent, content, raw index)
    for number, line in enumerate(raw):
        content = _strip_comment(line)
        if content.strip():
            lines.append((len(content) - len(content.lstrip(" ")), content.strip(), number))
    position = 0

    def block_scalar(parent_indent: int, start: int) -> str:
        nonlocal position
        collected = []
        for number in range(start + 1, len(raw)):
            line = raw[number]
            if line.strip() and len(line) - len(line.lstrip(" ")) <= parent_indent:
                break
            collected.append(line.strip())
        while position < len(lines) and lines[position][2] < start + 1 + len(collected):
            position += 1
        return "\n".join(collected).strip() + "\n"

    def value_after(rest: str, indent: int, raw_index: int) -> Any:
        nonlocal position
        if rest in ("|", "|-", ">", ">-"):
            return block_scalar(indent, raw_index)
        if rest:
            return _scalar(rest)
        if position < len(lines) and (
            lines[position][0] > indent or (lines[position][0] == indent and lines[position][1].startswith("- "))
        ):
            return node(lines[position][0])
        return None

    def mapping_entry(content: str, indent: int, raw_index: int, target: dict) -> None:
        key, _, rest = content.partition(":")
        target[_scalar(key)] = value_after(rest.strip(), indent, raw_index)

    def node(indent: int) -> Any:
        nonlocal position
        if lines[position][1].startswith("- ") or lines[position][1] == "-":
            items: List[Any] = []
            while position < len(lines) and lines[position][0] == indent and lines[position][1].startswith("-"):
                _, content, raw_index = lines[position]
                position += 1
                item = content[1:].strip()
                if re.match(r"^[^'\"\s][^:]*:(\s|$)", item):
                    entry: dict = {}
                    mapping_entry(item, indent + 1, raw_index, entry)
                    child_indent = indent + 2
                    while position < len(lines) and lines[position][0] > indent:
                        child_indent = lines[position][0]
                        _, child, child_raw = lines[position]
                        position += 1
                        mapping_entry(child, child_indent, child_raw, entry)
                    items.append(entry)
                else:
                    items.append(_scalar(item) if item else value_after("", indent, raw_index))
            return items
        result: dict = {}
        while position < len(lines) and lines[position][0] == indent and not lines[position][1].startswith("- "):
            _, content, raw_index = lines[position]
            position += 1
            mapping_entry(content, indent, raw_index, result)
        return result

    return node(lines[0][0]) if lines else {}


def load(path: Path) -> dict:
    if not path.is_file():
        raise CheckError(f"{path.relative_to(ROOT)} does not exist")
    document = parse_yaml(path.read_text())
    if not isinstance(document, dict):
        raise CheckError(f"{path.relative_to(ROOT)} is not a YAML mapping")
    # YAML 1.1 reads a bare `on` key as boolean true.
    if True in document and "on" not in document:
        document["on"] = document.pop(True)
    return document


def as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def triggers(workflow: dict) -> dict:
    on = workflow.get("on")
    if isinstance(on, dict):
        return on
    return {name: None for name in as_list(on)}


def run_text(job: dict) -> str:
    return "\n".join(str(step.get("run", "")) for step in as_list(job.get("steps")) if isinstance(step, dict))


def version_name() -> str:
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith("VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    raise CheckError("gradle.properties has no VERSION_NAME")


def check_tag_guard() -> List[str]:
    if not TAG_GUARD.is_file():
        return [f"{TAG_GUARD.relative_to(ROOT)} does not exist, so nothing ties the release tag to VERSION_NAME"]
    version = version_name()
    cases = [
        (f"v{version}", True),
        (version, False),
        (f"v{version}.1", False),
        (f"v{version}-rc1", False),
        (f"x{version}", False),
        ("", False),
    ]
    problems = []
    for tag, accepted in cases:
        result = subprocess.run(["bash", str(TAG_GUARD), tag], cwd=ROOT, capture_output=True, text=True)
        if (result.returncode == 0) != accepted:
            verdict = "accepted" if result.returncode == 0 else f"rejected (exit {result.returncode})"
            problems.append(
                f"{TAG_GUARD.relative_to(ROOT)} {verdict} tag '{tag}' for VERSION_NAME {version}"
            )
    return problems


def release_step_problems(steps: List[dict], uploads: List[int]) -> List[str]:
    """What is wrong with the publishing steps [uploads] (indexes into [steps]) of the one publishing job."""
    problems = []
    found = {task: [] for task in RELEASE_TASKS}
    for index in uploads:
        step = steps[index]
        run = str(step.get("run", ""))
        tasks = PUBLISH_TASK.findall(run)
        if len(tasks) != 1 or tasks[0] not in RELEASE_TASKS:
            problems.append(f"step {index + 1} runs {tasks}; each publishing step runs one of {list(RELEASE_TASKS)}")
        for task in RELEASE_TASKS:
            if re.search(rf"\b{task}\b", run):
                found[task].append(index)
        for key in ("if", "continue-on-error"):
            if key in step:
                problems.append(f"step {index + 1} sets {key}, so a failed publish need not stop the release")
    for task, indexes in found.items():
        if len(indexes) != 1:
            problems.append(f"runs {task} in {len(indexes)} steps, not one")
    libraries, relocations = (found[task] for task in RELEASE_TASKS)
    if len(libraries) == 1 and len(relocations) == 1 and libraries[0] > relocations[0]:
        problems.append(f"runs {RELEASE_TASKS[1]} before {RELEASE_TASKS[0]}")
    return problems


def release_workflow_problems(release: dict, build: dict) -> List[str]:
    """What lets [release], the publish workflow, publish a commit [build]'s harness did not test, a tag
    other than VERSION_NAME, or an upload nobody approved."""
    name = RELEASE_WORKFLOW.relative_to(ROOT)
    problems: List[str] = []

    on = triggers(release)
    if set(on) != {"push"}:
        problems.append(f"{name}: triggers on {sorted(on)}; a release runs only for a pushed v* tag")
    push = on.get("push") if isinstance(on.get("push"), dict) else {}
    tags = [str(tag) for tag in as_list(push.get("tags"))]
    if not tags or any(not tag.startswith("v") for tag in tags) or "branches" in push:
        problems.append(f"{name}: push trigger is {push}; it must list only v* tags")

    if release.get("permissions") != {"contents": "read"}:
        problems.append(f"{name}: permissions are {release.get('permissions')}; set exactly contents: read")

    concurrency = release.get("concurrency")
    group = concurrency.get("group") if isinstance(concurrency, dict) else concurrency
    cancel = concurrency.get("cancel-in-progress", False) if isinstance(concurrency, dict) else False
    if group != CONCURRENCY_GROUP or cancel not in (False, "false"):
        problems.append(
            f"{name}: concurrency is {concurrency}; use group {CONCURRENCY_GROUP} without cancel-in-progress"
        )

    jobs = release.get("jobs") if isinstance(release.get("jobs"), dict) else {}
    publishing = {job_id: job for job_id, job in jobs.items() if PUBLISH_TASK.search(run_text(job))}
    harness = {
        job_id
        for job_id, job in jobs.items()
        if str(job.get("uses", "")).strip() == "./.github/workflows/build.yml"
    }
    if not harness:
        problems.append(f"{name}: no job calls ./.github/workflows/build.yml, so nothing tests the tagged commit")
    if "workflow_call" not in triggers(build):
        problems.append(f"{BUILD_WORKFLOW.relative_to(ROOT)}: no workflow_call trigger, so a release cannot reuse it")
    build_jobs = build.get("jobs") if isinstance(build.get("jobs"), dict) else {}
    harness_runners = {str(job.get("runs-on")) for job in build_jobs.values() if FULL_HARNESS.search(run_text(job))}
    if not harness_runners:
        problems.append(f"{BUILD_WORKFLOW.relative_to(ROOT)}: no job runs scripts/verify.sh --full")

    if len(publishing) != 1:
        problems.append(f"{name}: {len(publishing)} jobs publish to Maven Central; expected exactly one")
    for job_id, job in publishing.items():
        needs = set(as_list(job.get("needs")))
        if not harness or not harness <= needs:
            problems.append(f"{name}: job {job_id} needs {sorted(needs)}, not the harness job(s) {sorted(harness)}")
        environment = job.get("environment")
        environment_name = environment.get("name") if isinstance(environment, dict) else environment
        if environment_name != ENVIRONMENT:
            problems.append(f"{name}: job {job_id} runs in environment {environment_name}, not {ENVIRONMENT}")
        runner = str(job.get("runs-on"))
        if "latest" in runner or runner not in harness_runners:
            problems.append(
                f"{name}: job {job_id} runs on {runner}; publish on the pinned image the harness uses "
                f"({', '.join(sorted(harness_runners)) or 'none'})"
            )
        steps = [step for step in as_list(job.get("steps")) if isinstance(step, dict)]
        guard = next(
            (index for index, step in enumerate(steps) if "scripts/check_release_tag.sh" in str(step.get("run", ""))),
            None,
        )
        uploads = [index for index, step in enumerate(steps) if PUBLISH_TASK.search(str(step.get("run", "")))]
        upload = uploads[0] if uploads else None
        problems += [f"{name}: job {job_id} {problem}" for problem in release_step_problems(steps, uploads)]
        if guard is None or upload is None or guard > upload:
            problems.append(f"{name}: job {job_id} does not run scripts/check_release_tag.sh before publishing")
        elif "${{" in str(steps[guard].get("run", "")):
            problems.append(
                f"{name}: job {job_id} interpolates an expression into the tag check; pass \"$GITHUB_REF_NAME\""
            )
    return problems


# Fixture workflows the rules must judge before they judge the real ones: the split release, and each way
# of breaking it (design §12.4 W13, the release-workflow gate).
FIXTURE_BUILD = {
    "on": {"workflow_call": None},
    "jobs": {"full": {"runs-on": "macos-15", "steps": [{"run": "scripts/verify.sh --full"}]}},
}
FIXTURE_TAG_GUARD = '      - run: scripts/check_release_tag.sh "$GITHUB_REF_NAME"\n'
FIXTURE_LIBRARIES = "      - run: ./gradlew publishLibraries --no-configuration-cache\n"
FIXTURE_RELOCATIONS = "      - run: ./gradlew publishLegacyRelocations --no-configuration-cache\n"


def fixture_release(steps: List[str], extra_jobs: str = "", job_settings: str = "") -> dict:
    head = (
        "on:\n  push:\n    tags:\n      - 'v*'\n"
        "permissions:\n  contents: read\n"
        "concurrency:\n  group: publish\n  cancel-in-progress: false\n"
        "jobs:\n  verify:\n    uses: ./.github/workflows/build.yml\n"
        "  publish:\n    needs: verify\n    runs-on: macos-15\n    environment: maven-central\n"
        + job_settings
        + "    steps:\n"
    )
    document = parse_yaml(head + "".join(steps) + extra_jobs)
    if True in document:
        document["on"] = document.pop(True)
    return document


def self_test() -> None:
    """Raises CheckError when the rules misjudge a fixture, so a rule that matches nothing cannot pass."""
    correct = release_workflow_problems(
        fixture_release([FIXTURE_TAG_GUARD, FIXTURE_LIBRARIES, FIXTURE_RELOCATIONS]), FIXTURE_BUILD
    )
    if correct:
        raise CheckError(f"the release rules reject the split release: {correct}")
    broken = {
        "the two publish steps swapped": [FIXTURE_TAG_GUARD, FIXTURE_RELOCATIONS, FIXTURE_LIBRARIES],
        "publishLegacyRelocations before the tag guard": [FIXTURE_RELOCATIONS, FIXTURE_TAG_GUARD, FIXTURE_LIBRARIES],
        "publishLibraries alone": [FIXTURE_TAG_GUARD, FIXTURE_LIBRARIES],
        "one publishAllPublicationsToMavenCentralRepository step": [
            FIXTURE_TAG_GUARD,
            "      - run: ./gradlew publishAllPublicationsToMavenCentralRepository\n",
        ],
        "both tasks in one step": [
            FIXTURE_TAG_GUARD,
            "      - run: ./gradlew publishLibraries publishLegacyRelocations\n",
        ],
        "the second step with if: always()": [
            FIXTURE_TAG_GUARD,
            FIXTURE_LIBRARIES,
            FIXTURE_RELOCATIONS + "        if: always()\n",
        ],
        "the second step with continue-on-error: true": [
            FIXTURE_TAG_GUARD,
            FIXTURE_LIBRARIES,
            FIXTURE_RELOCATIONS + "        continue-on-error: true\n",
        ],
    }
    # Each way a publish step's script can exit 0 after its upload failed, so the next step runs anyway.
    masked = {
        "|| true": "      - run: ./gradlew publishLibraries --no-configuration-cache || true\n",
        "|| :": "      - run: ./gradlew publishLibraries --no-configuration-cache || :\n",
        "; true": "      - run: ./gradlew publishLibraries --no-configuration-cache; true\n",
        "set +e": "      - run: |\n          set +e\n          ./gradlew publishLibraries --no-configuration-cache\n",
        "set +o errexit": (
            "      - run: |\n          set +o errexit\n          ./gradlew publishLibraries --no-configuration-cache\n"
        ),
        "a trailing || echo": (
            '      - run: ./gradlew publishLibraries --no-configuration-cache || echo "upload failed"\n'
        ),
        "|| exit 0": "      - run: ./gradlew publishLibraries --no-configuration-cache || exit 0\n",
    }
    for form, step in masked.items():
        broken[f"the first publish step masking its exit status with {form}"] = [
            FIXTURE_TAG_GUARD,
            step,
            FIXTURE_RELOCATIONS,
        ]
    accepted = [
        name
        for name, steps in broken.items()
        if not release_workflow_problems(fixture_release(steps), FIXTURE_BUILD)
    ]
    second_job = (
        "  relocations:\n    needs: [verify, publish]\n    runs-on: macos-15\n    environment: maven-central\n"
        "    steps:\n" + FIXTURE_TAG_GUARD + FIXTURE_RELOCATIONS
    )
    if not release_workflow_problems(
        fixture_release([FIXTURE_TAG_GUARD, FIXTURE_LIBRARIES], second_job), FIXTURE_BUILD
    ):
        accepted.append("the second step in a second job")
    if not release_workflow_problems(
        fixture_release(
            [FIXTURE_TAG_GUARD, FIXTURE_LIBRARIES, FIXTURE_RELOCATIONS], job_settings="    continue-on-error: true\n"
        ),
        FIXTURE_BUILD,
    ):
        accepted.append("the publishing job with continue-on-error: true")
    if accepted:
        raise CheckError(f"the release rules accept a broken split release: {', '.join(accepted)}")


def check_release_workflow() -> List[str]:
    return release_workflow_problems(load(RELEASE_WORKFLOW), load(BUILD_WORKFLOW))


def main() -> int:
    try:
        self_test()
        problems = check_release_workflow() + check_tag_guard()
    except (CheckError, OSError) as error:
        print(f"check_release_workflow: cannot run: {error}", file=sys.stderr)
        return 2
    if problems:
        print("The release workflow can publish an untested, untagged or unapproved commit:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("Release workflow: v* tags only, tag matches VERSION_NAME, full harness, protected environment.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
