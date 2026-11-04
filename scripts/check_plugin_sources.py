#!/usr/bin/env python3
"""Checks the plugin modules' sources for dead text that the compiler cannot see.

- Commented-out code: a // line whose text is a Kotlin statement, declaration or block delimiter.
- Orphaned KDoc: a KDoc block followed directly by another KDoc block, so the first documents nothing.
- KDoc code samples: a fenced block in KDoc, which no compiler checks. Samples go in a compiled
  function referenced with @sample.
- Source-set layout: every plugin module, ImageSaverPlugin included, keeps its code in the standard
  source sets (commonMain, androidMain, appleMain, desktopMain and their tests), under
  <sourceSet>/kotlin/ in directories that match the package.
- The Sample's App.kt names no plugin package: each plugin's code lives in that plugin's screen file,
  so a plugin slice or a package rename edits one Sample file and never App.kt. The plugin packages
  are read from the plugin modules' main sources, and the rule is first run against a fixture it
  must reject.

Exits non-zero and lists each finding as path:line: reason.
"""
import pathlib
import re
import sys

PLUGIN_MODULES = ["qrScannerPlugin", "ocrPlugin", "analyzerPlugin"]
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
SAMPLE_APP = pathlib.Path("Sample/src/commonMain/kotlin/org/company/app/App.kt")


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


def plugin_packages(root):
    packages = {}
    for module in LAYOUT_MODULES:
        for path in sorted((root / module / "src").glob("*Main/**/*.kt")):
            match = PACKAGE.search(path.read_text(encoding="utf-8"))
            if match:
                packages.setdefault(module, set()).add(match.group(1))
    return packages


def plugin_package_references(text, packages):
    """(line, package) for each line of text that imports or names a declaration in one of packages."""
    alternatives = "|".join(re.escape(package) for package in sorted(packages, key=len, reverse=True))
    pattern = re.compile(r"(?<![A-Za-z0-9_.])(" + alternatives + r")\.(\*|[A-Za-z_])")
    references = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = pattern.search(line)
        if match:
            references.append((number, match.group(1)))
    return references


def sample_app_kt_imports_no_plugin_package(root):
    fixture_packages = ["com.kashif.qrscannerplugin", "com.kashif.ocrPlugin"]
    fixture = (
        "import com.kashif.cameraK.state.CameraKState\n"
        "import com.kashif.qrscannerpluginx.Other\n"
        "import com.kashif.qrscannerplugin.QRScannerPlugin\n"
        "val ocr = com.kashif.ocrPlugin.rememberOcrPlugin()\n"
        "import com.kashif.ocrPlugin.*\n"
    )
    if [number for number, _ in plugin_package_references(fixture, fixture_packages)] != [3, 4, 5]:
        return ["scripts/check_plugin_sources.py: the App.kt plugin-package rule misjudges its fixture"]
    packages = plugin_packages(root)
    findings = [f"{module}: no package found in its main sources" for module in LAYOUT_MODULES if module not in packages]
    app = root / SAMPLE_APP
    if not app.exists():
        return findings + [f"{SAMPLE_APP}: missing, so the plugin-package rule checks nothing"]
    names = set().union(*packages.values()) if packages else set()
    for number, package in plugin_package_references(app.read_text(encoding="utf-8"), names):
        findings.append(f"{SAMPLE_APP}:{number}: names plugin package {package}; move it to that plugin's screen file")
    return findings


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    findings = []
    for path in kotlin_files(root):
        if path.exists():
            findings += check(path, root)
    findings += check_layout(root)
    findings += sample_app_kt_imports_no_plugin_package(root)
    if findings:
        print("Plugin source check failed:")
        print("\n".join(findings))
        return 1
    print(f"Plugin source check passed ({', '.join(LAYOUT_MODULES)}; Sample App.kt).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
