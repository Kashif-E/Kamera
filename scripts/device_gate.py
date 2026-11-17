#!/usr/bin/env python3
"""Judge the device jobs of device.yml: the Android emulator run (.github/ci/device-android.sh) and the iOS
simulator run (.github/ci/device-ios.sh), from the files they leave in ci-out/.

  python3 -I scripts/device_gate.py android ci-out/android
  python3 -I scripts/device_gate.py ios ci-out/ios
  python3 -I scripts/device_gate.py --self-test

A run fails when a step did not finish, a matrix case FAILed or was BLOCKED, the churn's memory or thread
count grew past what a healthy run shows, or, on iOS, the app crashed or `leaks` found a root leak of any
type but Skia's GrOp. Standard library only; exits 1 on a failure, 2 if the gate cannot run.
"""

from __future__ import annotations

import re
import statistics
import sys
import tempfile
from pathlib import Path
from typing import List, Optional

# The emulator's CameraX recordings end their audio up to 0.11 s after the video (2.94 s against 2.84 s,
# the only FAIL of the 17 Nov emulator matrix), where a Galaxy S23 stays within 0.017 s on every recording
# with audio. The matrix's own limit, 0.1 s, holds on phones; on the emulator a gap up to this passes.
EMULATOR_AV_GAP_S = 0.15
AV_GAP = re.compile(r"audio ([0-9.]+) s against video ([0-9.]+) s")

# Healthy churns, measured: the emulator's total PSS went from 82 to 96 MB over 3000 opens and closes and
# levelled off (threads 35 to 43); an S23 stayed flat. A leak of about 100 KB an open doubles the PSS of a
# 500-open churn, so growth past both bounds below is a leak, not the warm-up these runs show.
MEMORY_GROWTH_RATIO = 1.5
MEMORY_GROWTH_KB = 30 * 1024
THREAD_GROWTH = 20

# Skia's GPU operations (GrOp), which Compose's renderer leaves behind on the simulator; nothing of CameraK.
ALLOWED_LEAK_TYPES = {"GrOp"}
ROOT_LEAK = re.compile(r"ROOT LEAK: <([A-Za-z_][\w:.]*)")
APP_PROCESS = "Compose Multiplatform Camera App"


def status_lines(out: Path) -> List[str]:
    path = out / "status.txt"
    return path.read_text().splitlines() if path.is_file() else []


def step_problems(out: Path, steps: List[str], exits: List[str]) -> List[str]:
    lines = status_lines(out)
    problems = []
    for name in exits:
        if f"{name} exit 0" not in lines:
            found = next((line for line in lines if line.startswith(f"{name} exit")), f"{name}: no exit status")
            problems.append(f"{found}")
    for name in steps:
        line = next((line for line in lines if line.startswith(f"{name}:")), None)
        if line is None:
            problems.append(f"{name}: did not run")
        elif not line.startswith(f"{name}: done"):
            problems.append(line)
    return problems


def case_problems(report: Path, emulator: bool) -> List[str]:
    """The FAILed and BLOCKED cases of one matrix report, but an emulator recording within EMULATOR_AV_GAP_S."""
    if not report.is_file():
        return [f"{report.parent.name}: no report.md"]
    problems = []
    for row in report.read_text().splitlines():
        cells = [cell.strip() for cell in row.split("|")]
        if len(cells) < 6 or not cells[1].isdigit() or cells[2] not in ("FAIL", "BLOCKED"):
            continue
        gap = AV_GAP.search(cells[4])
        if emulator and cells[2] == "FAIL" and gap and abs(float(gap.group(1)) - float(gap.group(2))) <= EMULATOR_AV_GAP_S:
            continue
        problems.append(f"{report.parent.name} #{cells[1]} {cells[2]} {cells[3]}: {cells[4]}")
    return problems


def growth_problems(memory: Path, column: str, threads: Optional[str]) -> List[str]:
    if not memory.is_file():
        return [f"{memory.parent.name}: no memory.csv"]
    rows = memory.read_text().splitlines()
    header = rows[0].split(",") if rows else []
    if column not in header:
        return [f"{memory.parent.name}: memory.csv has no {column} column"]
    samples = []
    for row in rows[1:]:
        fields = row.split(",")
        try:
            samples.append((int(fields[header.index(column)]), int(fields[header.index(threads)]) if threads else 0))
        except (ValueError, IndexError):
            continue
    if len(samples) < 4:
        return [f"{memory.parent.name}: {len(samples)} memory samples, too few to tell growth"]
    base = statistics.median(kb for kb, _ in samples[:3])
    base_threads = statistics.median(t for _, t in samples[:3])
    last, last_threads = samples[-1]
    problems = []
    if last > base * MEMORY_GROWTH_RATIO and last - base > MEMORY_GROWTH_KB:
        problems.append(f"{memory.parent.name}: {column} grew from {base / 1024:.0f} to {last / 1024:.0f} MB")
    if threads and last_threads - base_threads > THREAD_GROWTH:
        problems.append(f"{memory.parent.name}: threads grew from {base_threads:.0f} to {last_threads}")
    return problems


def leak_problems(leaks: Path) -> List[str]:
    if not leaks.is_file():
        return [f"{leaks.parent.name}: leaks did not run"]
    found = [match.group(1) for match in ROOT_LEAK.finditer(leaks.read_text())]
    return [f"{leaks.parent.name}: leaks found a root {kind}" for kind in found if kind not in ALLOWED_LEAK_TYPES]


def crash_problems(out: Path) -> List[str]:
    reports = out / "crash-reports"
    if not reports.is_dir():
        return []
    return [f"crash report {path.name}" for path in sorted(reports.rglob("*.ips")) if APP_PROCESS in path.name]


def android_problems(out: Path) -> List[str]:
    problems = step_problems(out, ["matrix", "photo", "churn"], ["contract", "install"])
    for step in ("matrix", "photo"):
        problems += case_problems(out / step / "report.md", emulator=True)
    problems += growth_problems(out / "churn" / "memory.csv", "total_pss_kb", "threads")
    return problems


def ios_problems(out: Path) -> List[str]:
    problems = step_problems(out, ["matrix", "churn"], ["xcodebuild"])
    for step in ("matrix", "churn"):
        problems += case_problems(out / step / "report.md", emulator=False)
        problems += leak_problems(out / step / "leaks.txt")
    problems += growth_problems(out / "churn" / "memory.csv", "rss_kb", "threads")
    return problems + crash_problems(out)


def write(root: Path, files: dict) -> Path:
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    return root


REPORT_HEADER = "| # | verdict | case | reason | measures |\n|---|---|---|---|---|\n"


def report(*rows: str) -> str:
    return REPORT_HEADER + "".join(rows)


def memory(column: str, values: List[int], threads: List[int]) -> str:
    rows = [f"{index * 15},{kb},0,0,0,{t},CHURN" if column == "total_pss_kb" else f"{index * 10},{kb},{t},CHURN"
            for index, (kb, t) in enumerate(zip(values, threads))]
    header = "seconds,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,threads,progress" if column == "total_pss_kb" \
        else "seconds,rss_kb,threads,progress"
    return header + "\n" + "\n".join(rows) + "\n"


def self_test() -> None:
    """Raises AssertionError when a rule misjudges a fixture, so a rule that matches nothing cannot pass."""
    passed = "| 1 | PASS | video/BACK/RATIO_4_3/HD/false |  | readyMs=1 |\n"
    av_ok = "| 2 | FAIL | video/BACK/RATIO_4_3/HD/true | audio 2.94 s against video 2.84 s | tracks |\n"
    av_bad = "| 3 | FAIL | video/BACK/RATIO_4_3/HD/true | audio 3.10 s against video 2.84 s | tracks |\n"
    failed = "| 4 | FAIL | photo/FRONT/WIDE_ANGLE/RATIO_4_3/null/JPEG/SPEED/false/OFF | capture: Timeout(CAPTURE) | x |\n"
    blocked = "| 5 | BLOCKED | features/1/FRONT/WIDE_ANGLE/ZOOM_MIN | start: NotReady | x |\n"
    flat = memory("total_pss_kb", [82000, 86000, 84000, 90000, 96000], [35, 38, 38, 42, 43])
    good_android = {
        "status.txt": "contract exit 0\ninstall exit 0\nmatrix: done after 300 s; DONE\nphoto: done after 400 s; DONE\n"
        "churn: done after 140 s; DONE churn=500\n",
        "matrix/report.md": report(passed, av_ok),
        "photo/report.md": report(passed),
        "churn/report.md": report(),
        "churn/memory.csv": flat,
    }
    good_ios = {
        "status.txt": "xcodebuild exit 0\nmatrix: done after 41 s; DONE cases=0\nchurn: done after 50 s; DONE churn=500\n",
        "matrix/report.md": report(),
        "churn/report.md": report(),
        "matrix/leaks.txt": "Process 1: 1 leak for 128 total leaked bytes.\n    1 (128 bytes) ROOT LEAK: <GrOp 0x1> [128]\n",
        "churn/leaks.txt": "Process 2: 0 leaks for 0 total leaked bytes.\n",
        "churn/memory.csv": memory("rss_kb", [120000, 121000, 119000, 125000, 126000], [20, 21, 21, 22, 22]),
    }
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)

        def judged(kind: str, base: dict, **changes: str) -> List[str]:
            out = write(root / f"{kind}-{len(list(root.iterdir()))}", {**base, **changes})
            return android_problems(out) if kind == "android" else ios_problems(out)

        assert not judged("android", good_android), f"a good Android run fails: {judged('android', good_android)}"
        assert not judged("ios", good_ios), f"a good iOS run fails: {judged('ios', good_ios)}"
        broken_android = {
            "a FAILed case": {"photo/report.md": report(passed, failed)},
            "a BLOCKED case": {"matrix/report.md": report(blocked)},
            "an audio gap past the emulator's": {"matrix/report.md": report(av_bad)},
            "failed device tests": {"status.txt": good_android["status.txt"].replace("contract exit 0", "contract exit 1")},
            "a matrix that timed out": {
                "status.txt": good_android["status.txt"].replace("matrix: done after 300 s; DONE", "matrix: timeout after 1800 s;")
            },
            "a churn that never ran": {"status.txt": good_android["status.txt"].replace("churn: done", "nothing")},
            "memory that kept growing": {
                "churn/memory.csv": memory("total_pss_kb", [82000, 86000, 84000, 150000, 210000], [35, 38, 38, 40, 41])
            },
            "threads that kept growing": {
                "churn/memory.csv": memory("total_pss_kb", [82000, 86000, 84000, 85000, 86000], [35, 38, 38, 60, 80])
            },
            "a missing report": {},
        }
        for label, changes in broken_android.items():
            files = dict(good_android, **changes)
            if label == "a missing report":
                files.pop("photo/report.md")
            out = write(root / f"android-broken-{len(list(root.iterdir()))}", files)
            assert android_problems(out), f"the Android gate accepts {label}"
        broken_ios = {
            "a leak of CameraK's": {"churn/leaks.txt": "    1 (64 bytes) ROOT LEAK: <AVCaptureSession 0x2> [64]\n"},
            "a Kotlin object leaked": {"matrix/leaks.txt": "    1 (32 bytes) ROOT LEAK: <kotlin.Any 0x3> [32]\n"},
            "a failed build": {"status.txt": good_ios["status.txt"].replace("xcodebuild exit 0", "xcodebuild exit 65")},
            "an app that exited": {"status.txt": good_ios["status.txt"].replace("churn: done", "churn: exited")},
            "a crash report": {f"crash-reports/{APP_PROCESS}-2026-11-18.ips": "{}"},
        }
        for label, changes in broken_ios.items():
            assert judged("ios", good_ios, **changes), f"the iOS gate accepts {label}"
        missing_leaks = dict(good_ios)
        missing_leaks.pop("churn/leaks.txt")
        out = write(root / "ios-no-leaks", missing_leaks)
        assert ios_problems(out), "the iOS gate accepts a run whose leaks did not run"


def main(argv: List[str]) -> int:
    if argv[1:] == ["--self-test"]:
        try:
            self_test()
        except AssertionError as error:
            print(f"device_gate self-test: {error}", file=sys.stderr)
            return 2
        print("device_gate self-test: OK")
        return 0
    if len(argv) != 3 or argv[1] not in ("android", "ios"):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        self_test()
    except AssertionError as error:
        print(f"device_gate cannot run: {error}", file=sys.stderr)
        return 2
    out = Path(argv[2])
    problems = android_problems(out) if argv[1] == "android" else ios_problems(out)
    for line in status_lines(out):
        print(line)
    if problems:
        print(f"\n{argv[1]} device gate: {len(problems)} problem(s)")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print(f"\n{argv[1]} device gate: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
