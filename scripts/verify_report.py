#!/usr/bin/env python3
"""Summarise one scripts/verify.sh run.

Reads build/verify/run.json, then only the JUnit XML, Paparazzi failure images and desktop
screenshot diffs written after the run started. Prints a per-module, per-target table and every
failure, writes build/verify/summary.md and summary.json, and exits 1 (RED) when Gradle failed, a
test failed or errored, a test let an exception escape on another thread, a requested target ran
zero tests (skipped tests do not count), a requested target has nothing to check, a --full run's
publish step left a coordinate out of its scratch repository, or the local gitleaks scan found or
failed. A scan that could not run (no gitleaks on PATH) is shown, not passed.

Standard library only; run with `python3 -I`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCREENSHOT_MODULE = "cameraKScreenshotTests"
# The sample target runs the Sample app's own tests; they count as that target, not as desktop.
SAMPLE_MODULE = "Sample"
TASK_TARGETS = {
    "desktopTest": "desktop",
    "testDebugUnitTest": "android",
    "iosSimulatorArm64Test": "ios",
}
TEST_TARGETS = ("desktop", "android", "ios", "screenshots", "sample")
# The lint checks are every task in the build's "camerak lint" group. verifyAll writes their paths
# here once its task graph is known, so neither this script nor the build lists them by name.
LINT_CHECKS_FILE = ROOT / "build" / "verify" / "lint-checks.txt"
# Log lines proving a non-test target executed; lint's come from LINT_CHECKS_FILE.
TASK_EVIDENCE = {
    "sample": [
        ":Sample:compileKotlinDesktop",
        ":Sample:assembleDebug",
        ":Sample:compileKotlinIosSimulatorArm64",
        ":Sample:compileKotlinIosArm64",
    ],
}
# Per-module compile tasks for the variants that ship but have no tests.
COMPILE_EVIDENCE = {
    "android": ["compileReleaseKotlinAndroid"],
    "ios": ["compileKotlinIosArm64"],
}
# The JVM's default uncaught-exception handler prints this when a thread other than the test's dies.
# JUnit never sees that exception, so the test passes; the line only reaches the XML's system-err.
UNCAUGHT = re.compile(r'^Exception in thread "[^"]*" (.*)$', re.MULTILINE)


@dataclass
class Cell:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0


@dataclass
class Failure:
    module: str
    target: str
    test: str
    kind: str
    first_line: str


@dataclass
class Report:
    cells: dict[tuple[str, str], Cell] = field(default_factory=dict)
    failures: list[Failure] = field(default_factory=list)
    stale_files: int = 0
    unreadable: list[str] = field(default_factory=list)
    uncaught: list[Failure] = field(default_factory=list)

    def cell(self, module: str, target: str) -> Cell:
        return self.cells.setdefault((module, target), Cell())


def first_line(text: str | None) -> str:
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()[:300]
    return "(no message)"


def target_for(module: str, task_dir: str, classname: str) -> str | None:
    if module == SCREENSHOT_MODULE:
        return "screenshots"
    if module == SAMPLE_MODULE:
        return "sample" if task_dir in TASK_TARGETS else None
    target = TASK_TARGETS.get(task_dir)
    if target == "desktop" and ".screenshots." in f".{classname}.":
        return "screenshots"
    return target


def parse_results(modules: list[str], start: float, report: Report) -> None:
    for module in modules:
        results_dir = ROOT / module / "build" / "test-results"
        if not results_dir.is_dir():
            continue
        for xml_path in sorted(results_dir.rglob("*.xml")):
            task_dir = xml_path.relative_to(results_dir).parts[0]
            if xml_path.stat().st_mtime < start:
                report.stale_files += 1
                continue
            try:
                root = ET.parse(xml_path).getroot()
            except ET.ParseError as error:
                report.unreadable.append(f"{xml_path.relative_to(ROOT)}: {error}")
                continue
            suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
            for suite in suites:
                suite_target = target_for(module, task_dir, suite.get("name", "")) or task_dir
                for err in suite.iter("system-err"):
                    for match in UNCAUGHT.finditer(err.text or ""):
                        report.uncaught.append(
                            Failure(module, suite_target, suite.get("name", "?"), "uncaught", match.group(0)[:300])
                        )
                for case in suite.iter("testcase"):
                    classname = case.get("classname", "")
                    target = target_for(module, task_dir, classname)
                    if target is None:
                        continue
                    cell = report.cell(module, target)
                    cell.tests += 1
                    name = f"{classname}.{case.get('name', '?')}"
                    for kind in ("failure", "error"):
                        node = case.find(kind)
                        if node is not None:
                            if kind == "failure":
                                cell.failures += 1
                            else:
                                cell.errors += 1
                            report.failures.append(
                                Failure(module, target, name, kind, first_line(node.get("message") or node.text))
                            )
                    if case.find("skipped") is not None:
                        cell.skipped += 1


def fresh_files(directory: Path, start: float, pattern: str = "*.png") -> list[str]:
    if not directory.is_dir():
        return []
    return sorted(str(p.relative_to(ROOT)) for p in directory.rglob(pattern) if p.stat().st_mtime >= start)


def gradle_error_excerpt(log: Path) -> list[str]:
    if not log.is_file():
        return [f"Gradle log {log} is missing"]
    lines = log.read_text(errors="replace").splitlines()
    # Kotlin compiler diagnostics ("e: file:///...kt:180:32 message"); Gradle's own failure block
    # only says "Compilation error. See log for more details".
    excerpt: list[str] = [line for line in lines if line.startswith("e: ")][:20]
    for index, line in enumerate(lines):
        if line.startswith("* What went wrong:"):
            block = []
            for follow in lines[index + 1 : index + 12]:
                if follow.startswith("* Try:"):
                    break
                block.append(follow)
            excerpt.extend(line for line in block if line.strip())
    return excerpt[:40] or [line for line in lines[-15:] if line.strip()]


def credential_hygiene_excerpt(log: Path) -> list[str]:
    """The check's own report from the Gradle failure, so the summary names each violation."""
    if not log.is_file():
        return []
    lines = log.read_text(errors="replace").splitlines()
    for index, line in enumerate(lines):
        if "Credential hygiene check failed" in line:
            block: list[str] = []
            for follow in lines[index + 1 : index + 200]:
                text = follow.strip()
                if not text:
                    continue
                block.append(text if text.startswith(("ok ", "FAIL", "ERROR", "credential hygiene:")) else "  " + text)
                if text.startswith("credential hygiene:"):
                    break
            return block
    return []


def secret_scan_status(scan: dict) -> tuple[str, str | None]:
    """(line for the summary, problem or None) for the local gitleaks run."""
    status, detail = scan.get("status", "not requested"), scan.get("detail", "")
    if status == "not run":
        return f"not run ({detail})", None
    if status == "clean":
        return "ran over full history: no findings", None
    if status == "leaks":
        try:
            count = len(json.loads(Path(detail).read_text()))
        except (OSError, ValueError) as error:
            return f"found leaks; report {detail} unreadable: {error}", f"gitleaks found leaks; report unreadable: {error}"
        report = os.path.relpath(detail, ROOT)
        return f"{count} finding(s), values redacted, in `{report}`", f"gitleaks found {count} secret(s) in history"
    return f"failed: {detail}", f"gitleaks failed: {detail}"


def lint_check_list(start: float) -> tuple[list[str], str | None]:
    """The lint checks this run's build listed, or why there is no usable list."""
    try:
        if LINT_CHECKS_FILE.stat().st_mtime < start:
            return [], f"the build did not write {LINT_CHECKS_FILE.relative_to(ROOT)} in this run"
        checks = [line.strip() for line in LINT_CHECKS_FILE.read_text().splitlines() if line.strip()]
    except OSError as error:
        return [], f"cannot read the build's lint check list: {error.strerror}"
    if not checks:
        return [], f"{LINT_CHECKS_FILE.relative_to(ROOT)} lists no lint check"
    return checks, None


PUBLISHED_COORDINATES_FILE = ROOT / "build" / "verify" / "published-coordinates.json"


def publish_problems(repo: Path, expected: dict) -> list[str]:
    """The coordinates the publish step should have written to [repo] and did not: each module's root
    artifact and one per Kotlin target (.module and .pom), the BOM's root, and each relocation POM,
    which must relocate to its 2.0 id."""
    group_dir = repo.joinpath(*expected["group"].split("."))
    version = expected["version"]
    problems: list[str] = []

    def require(artifact: str, extensions: tuple[str, ...]) -> None:
        for extension in extensions:
            path = group_dir / artifact / version / f"{artifact}-{version}.{extension}"
            if not path.is_file():
                problems.append(f"{expected['group']}:{artifact}:{version} has no .{extension}")

    for artifact, targets in sorted(expected["modules"].items()):
        require(artifact, ("module", "pom"))
        for target in targets:
            require(f"{artifact}-{target}", ("module", "pom"))
    require(expected["bom"], ("module", "pom"))
    for old_id, new_id in sorted(expected["relocations"].items()):
        pom = group_dir / old_id / version / f"{old_id}-{version}.pom"
        if not pom.is_file():
            problems.append(f"{expected['group']}:{old_id}:{version} has no relocation .pom")
            continue
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError as error:
            problems.append(f"{pom.relative_to(ROOT)} is unreadable: {error}")
            continue
        ns = {"m": root.tag[1:].split("}")[0]} if root.tag.startswith("{") else {}
        prefix = "m:" if ns else ""
        relocated = root.find(f"{prefix}distributionManagement/{prefix}relocation/{prefix}artifactId", ns)
        if relocated is None or (relocated.text or "").strip() != new_id:
            found = "nothing" if relocated is None else (relocated.text or "").strip()
            problems.append(f"{old_id}'s POM relocates to {found}, not {new_id}")
    return problems


def executed_tasks(log: Path) -> set[str]:
    if not log.is_file():
        return set()
    pattern = re.compile(r"^> Task (\S+)")
    return {m.group(1) for line in log.read_text(errors="replace").splitlines() if (m := pattern.match(line))}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run", default=str(ROOT / "build" / "verify" / "run.json"))
    args = parser.parse_args()

    run = json.loads(Path(args.run).read_text())
    start = float(run["start_epoch"])
    modules: list[str] = run["modules"]
    targets: list[str] = run["targets"]
    log = Path(run["log"])
    gradle_exit = run.get("gradle_exit")
    record = bool(run.get("record"))

    scanned = (
        modules
        + ([SCREENSHOT_MODULE] if "cameraK" in modules else [])
        + ([SAMPLE_MODULE] if "sample" in targets else [])
    )
    report = Report()
    parse_results(scanned, start, report)

    # Every (module, target) the run asked for must have run at least one test.
    expected: list[tuple[str, str]] = []
    for module in modules:
        for target in ("desktop", "android", "ios"):
            if target in targets:
                expected.append((module, target))
    if "screenshots" in targets and "cameraK" in modules:
        expected += [("cameraK", "screenshots"), (SCREENSHOT_MODULE, "screenshots")]
    if "sample" in targets:
        expected.append((SAMPLE_MODULE, "sample"))
    for key in expected:
        report.cell(*key)

    problems: list[str] = []
    if gradle_exit is None:
        problems.append("Gradle did not finish (no exit code recorded)")
    elif gradle_exit != 0:
        problems.append(f"Gradle exited with {gradle_exit}")
    # A requested target that maps to nothing would otherwise pass with nothing run.
    checked = {target for _, target in expected} | {t for t in [*TASK_EVIDENCE, "lint"] if t in targets}
    for target in targets:
        if target not in checked:
            problems.append(f"{target}: requested but nothing to run for modules {', '.join(modules)}")
    for module, target in expected:
        cell = report.cells[(module, target)]
        if cell.tests - cell.skipped == 0:
            skipped = f" ({cell.skipped} skipped)" if cell.skipped else ""
            problems.append(f"{module} / {target}: requested but ran 0 tests{skipped}")
    total = Cell()
    for cell in report.cells.values():
        total.tests += cell.tests
        total.failures += cell.failures
        total.errors += cell.errors
        total.skipped += cell.skipped
    if total.failures or total.errors:
        problems.append(f"{total.failures} test failure(s), {total.errors} error(s)")
    if report.uncaught:
        problems.append(f"{len(report.uncaught)} exception(s) escaped on a background thread while tests passed")
    for path in report.unreadable:
        problems.append(f"unreadable result file {path}")

    tasks = executed_tasks(log)
    task_checks: dict[str, str] = {}
    evidence_by_target = dict(TASK_EVIDENCE)
    lint_checks: list[str] = []
    if "lint" in targets:
        lint_checks, lint_problem = lint_check_list(start)
        if lint_problem:
            task_checks["lint"] = lint_problem
            problems.append(f"lint: {lint_problem}")
        else:
            evidence_by_target["lint"] = lint_checks
    for target, evidence in evidence_by_target.items():
        if target not in targets:
            continue
        missing = [task for task in evidence if task not in tasks]
        task_checks[target] = f"ran ({len(evidence)} tasks)" if not missing else "did not run: " + ", ".join(missing)
        if missing:
            problems.append(f"{target}: requested but {task_checks[target]}")
    for target, names in COMPILE_EVIDENCE.items():
        if target not in targets:
            continue
        evidence = [f":{module}:{name}" for module in modules for name in names]
        key = f"{target} compile (release/device)"
        missing = [task for task in evidence if task not in tasks]
        task_checks[key] = "ran" if not missing else "did not run: " + ", ".join(missing)
        if missing:
            problems.append(f"{key}: requested but {task_checks[key]}")

    publish_repo = run.get("publish_repo")
    publish_missing: list[str] = []
    if publish_repo:
        try:
            if PUBLISHED_COORDINATES_FILE.stat().st_mtime < start:
                raise OSError(0, f"the build did not write {PUBLISHED_COORDINATES_FILE.relative_to(ROOT)} in this run")
            expected_coordinates = json.loads(PUBLISHED_COORDINATES_FILE.read_text())
        except (OSError, ValueError) as error:
            reason = getattr(error, "strerror", None) or str(error)
            task_checks["publish"] = f"cannot check: {reason}"
            problems.append(f"publish: {task_checks['publish']}")
        else:
            publish_missing = publish_problems(Path(publish_repo), expected_coordinates)
            count = len(expected_coordinates["modules"]) + 1 + len(expected_coordinates["relocations"])
            task_checks["publish"] = (
                f"every coordinate of {count} artifacts published to `{os.path.relpath(publish_repo, ROOT)}`"
                if not publish_missing
                else f"{len(publish_missing)} coordinate(s) missing"
            )
            if publish_missing:
                problems.append(f"publish: {task_checks['publish']}")

    credential_excerpt = credential_hygiene_excerpt(log) if "lint" in targets else []
    summary_line = next((line for line in credential_excerpt if line.startswith("credential hygiene:")), None)
    if credential_excerpt:
        problems.append(summary_line or "credential hygiene check failed")
    if "lint" in targets:
        scan_line, scan_problem = secret_scan_status(run.get("secret_scan") or {})
        task_checks["secret scan (gitleaks, full history)"] = scan_line
        if scan_problem:
            problems.append(scan_problem)

    paparazzi_failures = fresh_files(ROOT / SCREENSHOT_MODULE / "build" / "paparazzi" / "failures", start)
    desktop_diffs = fresh_files(ROOT / "cameraK" / "build" / "reports" / "screenshots-desktop", start)

    green = not problems
    banner = "GREEN" if green else "RED"
    gradle_excerpt = gradle_error_excerpt(log) if gradle_exit not in (0, None) else []

    rows = sorted(report.cells.items(), key=lambda item: (item[0][0].lower(), TEST_TARGETS.index(item[0][1])))
    header = f"| Module | Target | Tests | Failures | Errors | Skipped |\n|---|---|---:|---:|---:|---:|"
    table = [header] + [
        f"| {module} | {target} | {c.tests} | {c.failures} | {c.errors} | {c.skipped} |" for (module, target), c in rows
    ]
    table.append(f"| **total** | | **{total.tests}** | **{total.failures}** | **{total.errors}** | **{total.skipped}** |")

    md: list[str] = [f"# Verify: {banner}", ""]
    md.append(f"- Mode: `{run['mode']}`{' (recording goldens)' if record else ''}")
    md.append(f"- Targets: {', '.join(targets)}")
    md.append(f"- Modules: {', '.join(modules)}")
    md.append(f"- Gradle exit: {gradle_exit}")
    md.append(f"- Gradle log: `{os.path.relpath(log, ROOT)}`")
    md.append(f"- Stale result files ignored: {report.stale_files}")
    for target, status in task_checks.items():
        md.append(f"- {target}: {status}")
    md += ["", *table, ""]
    if problems:
        md += ["## Why it is RED", ""] + [f"- {p}" for p in problems] + [""]
    if report.failures or report.uncaught:
        md += ["## Failures", ""]
        md += [
            f"- `{f.test}` ({f.module} / {f.target}, {f.kind}): {f.first_line}"
            for f in report.failures + report.uncaught
        ]
        md.append("")
    if credential_excerpt:
        md += ["## Credential hygiene", "", "```", *credential_excerpt, "```", ""]
    if gradle_excerpt:
        md += ["## Gradle errors", "", "```", *gradle_excerpt, "```", ""]
    if paparazzi_failures or desktop_diffs:
        md += ["## Screenshot diffs", ""] + [f"- `{p}`" for p in paparazzi_failures + desktop_diffs] + [""]
    if publish_missing:
        md += ["## Publish step", ""] + [f"- {p}" for p in publish_missing] + [""]
    if lint_checks:
        md += ["## Lint checks required", ""] + [f"- `{check}`" for check in lint_checks] + [""]

    out_dir = ROOT / "build" / "verify"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.md").write_text("\n".join(md))
    summary = {
        "result": banner,
        "mode": run["mode"],
        "record": record,
        "targets": targets,
        "modules": modules,
        "gradle_exit": gradle_exit,
        "log": str(log),
        "stale_result_files_ignored": report.stale_files,
        "task_checks": task_checks,
        "lint_checks": lint_checks,
        "totals": vars(total),
        "cells": [{"module": m, "target": t, **vars(c)} for (m, t), c in rows],
        "failures": [vars(f) for f in report.failures + report.uncaught],
        "publish_missing": publish_missing,
        "problems": problems,
        "gradle_errors": gradle_excerpt,
        "screenshot_diffs": paparazzi_failures + desktop_diffs,
    }
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")

    bar = "=" * 64
    print(bar)
    print(f"  {banner}  ({run['mode']})")
    print(bar)
    print("\n".join(md[1:]))
    print(f"Summary: {out_dir / 'summary.md'}")
    return 0 if green else 1


if __name__ == "__main__":
    sys.exit(main())
