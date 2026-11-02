#!/usr/bin/env python3
"""Checks the plugin modules' sources for dead text that the compiler cannot see.

- Commented-out code: a // line whose text is a Kotlin statement, declaration or block delimiter.
- Orphaned KDoc: a KDoc block followed directly by another KDoc block, so the first documents nothing.
- KDoc code samples: a fenced block in KDoc, which no compiler checks. Samples go in a compiled
  function referenced with @sample.
- Source-set layout: every plugin module, ImageSaverPlugin included, keeps its code in the standard
  source sets (commonMain, androidMain, appleMain, desktopMain and their tests), under
  <sourceSet>/kotlin/ in directories that match the package.

Exits non-zero and lists each finding as path:line: reason.
"""
import pathlib
import re
import sys

PLUGIN_MODULES = ["qrScannerPlugin", "ocrPlugin", "videoRecorderPlugin", "analyzerPlugin"]
LAYOUT_MODULES = ["ImageSaverPlugin", *PLUGIN_MODULES]
SOURCE_SETS = {
    "commonMain",
    "commonTest",
    "androidMain",
    "androidUnitTest",
    "appleMain",
    "appleTest",
    "desktopMain",
    "desktopTest",
}
PACKAGE = re.compile(r"^package\s+([A-Za-z0-9_.]+)", re.M)

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


def check_layout(root):
    findings = []
    for module in LAYOUT_MODULES:
        src = root / module / "src"
        for source_set in sorted(p for p in src.iterdir() if p.is_dir()):
            if source_set.name not in SOURCE_SETS:
                findings.append(f"{module}/src/{source_set.name}: not a standard source set ({', '.join(sorted(SOURCE_SETS))})")
        for path in sorted(src.rglob("*.kt")):
            parts = path.relative_to(src).parts
            rel = path.relative_to(root)
            if len(parts) < 3 or parts[1] != "kotlin":
                findings.append(f"{rel}: not under <sourceSet>/kotlin/")
                continue
            match = PACKAGE.search(path.read_text(encoding="utf-8"))
            expected = match.group(1).replace(".", "/") if match else ""
            if "/".join(parts[2:-1]) != expected:
                findings.append(f"{rel}: directory does not match package {expected.replace('/', '.')}")
    return findings


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    findings = []
    for path in kotlin_files(root):
        if path.exists():
            findings += check(path, root)
    findings += check_layout(root)
    if findings:
        print("Plugin source check failed:")
        print("\n".join(findings))
        return 1
    print(f"Plugin source check passed ({', '.join(LAYOUT_MODULES)}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
