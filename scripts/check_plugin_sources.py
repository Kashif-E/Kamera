#!/usr/bin/env python3
"""Checks the plugin modules' sources for dead text that the compiler cannot see.

- Commented-out code: a // line whose text is a Kotlin statement, declaration or block delimiter.
- Orphaned KDoc: a KDoc block followed directly by another KDoc block, so the first documents nothing.
- KDoc code samples: a fenced block in KDoc, which no compiler checks. Samples go in a compiled
  function referenced with @sample.

Exits non-zero and lists each finding as path:line: reason.
"""
import pathlib
import re
import sys

PLUGIN_MODULES = ["qrScannerPlugin", "ocrPlugin", "videoRecorderPlugin", "analyzerPlugin"]

COMMENTED_CODE = re.compile(
    r"^\s*//\s*("
    r"(import|package|val|var|fun|class|object|interface|private|internal|override|return)\s"
    r"|targets\.|compilations\["
    r"|[}{)]\s*$"
    r"|[A-Za-z_][A-Za-z0-9_.]*\(.*\)\s*$"
    r"|[A-Za-z_][A-Za-z0-9_.]*\s*=\s*\S"
    r")"
)
FENCE = re.compile(r"^\s*\*\s*```")


def kotlin_files(root):
    for module in PLUGIN_MODULES:
        base = root / module
        yield base / "build.gradle.kts"
        for path in sorted((base / "src").rglob("*.kt")):
            yield path


def check(path, root):
    findings = []
    lines = path.read_text(encoding="utf-8").splitlines()
    in_kdoc = False
    last_kdoc_end = None
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not in_kdoc and COMMENTED_CODE.match(line):
            findings.append((number, "commented-out code"))
        if stripped.startswith("/**"):
            if last_kdoc_end is not None:
                findings.append((last_kdoc_end, "KDoc followed by another KDoc documents nothing"))
            in_kdoc = not stripped.endswith("*/") or stripped == "/**"
            if not in_kdoc:
                last_kdoc_end = number
            continue
        if in_kdoc:
            if FENCE.match(line) and "src/" in str(path) and "Main/" in str(path):
                findings.append((number, "KDoc code sample: move it into a compiled @sample function"))
            if stripped.endswith("*/"):
                in_kdoc = False
                last_kdoc_end = number
            continue
        if stripped:
            last_kdoc_end = None
    rel = path.relative_to(root)
    return [f"{rel}:{number}: {reason}" for number, reason in findings]


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    findings = []
    for path in kotlin_files(root):
        if path.exists():
            findings += check(path, root)
    if findings:
        print("Plugin source check failed:")
        print("\n".join(findings))
        return 1
    print(f"Plugin source check passed ({', '.join(PLUGIN_MODULES)}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
