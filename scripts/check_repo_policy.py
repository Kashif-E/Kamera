#!/usr/bin/env python3
"""Repository policies that live in tracked files rather than in the Gradle model.

Usage: check_repo_policy.py <policy> [<policy> ...]

  agent-permissions  Committed agent configuration (.claude/) pre-approves nothing that reads or
                     changes secrets, signs, publishes, commits or rewrites history, nothing that
                     edits files, and no path outside the repository; personal approvals stay in
                     the gitignored .claude/settings.local.json.
  security-policy    .github/SECURITY.md supports the release line gradle.properties publishes and
                     routes reports through GitHub private vulnerability reporting on the repository
                     the POMs name, with no email address nobody has verified.
  docs-workflow      Pull requests build the docs with mkdocs --strict and check their links; the
                     site is deployed with actions/deploy-pages only for a v* tag, after that
                     strict build, and never force-pushed with mkdocs gh-deploy.
  build-integrity    The Gradle wrapper pins its distribution's sha256 and every workflow that runs
                     Gradle validates the wrapper jar; every action is pinned by commit SHA;
                     Dependabot updates Gradle, GitHub Actions and the docs' pip requirements; and the
                     docs install only hash-pinned requirements.
  catalog            gradle/libs.versions.toml has no unreferenced version, library or plugin entry,
                     one version key per release train (no two keys give one Maven group the same
                     version), and every plugin it declares is applied somewhere; build scripts
                     apply third-party plugins only through [plugins] aliases (no buildscript
                     classpath, no versionless id("...")), and libraries declare Compose by explicit
                     coordinates rather than the compose.* accessors.
  native-settings    A module with no native code (no cpp/jni sources, CMake or ndk-build file, or
                     .so) sets no NDK version and no jniLibs packaging option, which would have no
                     effect on its artifact and would make contributors install an NDK.

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


# ─── security-policy ────────────────────────────────────────────────────────────────────────────

SECURITY_POLICY = ".github/SECURITY.md"
EMAIL = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+")
SUPPORTED = re.compile(r"✅|\byes\b", re.IGNORECASE)


def gradle_property(name: str) -> str:
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    raise CheckError(f"gradle.properties has no {name}")


def security_policy_problems(text: str, version: str, repository: str) -> List[str]:
    line = ".".join(version.split(".")[:2]) + ".x"
    rows = [
        [cell.strip() for cell in row.strip().strip("|").split("|")]
        for row in text.splitlines()
        if row.strip().startswith("|") and not re.match(r"^\|[\s:|-]+\|$", row.strip())
    ]
    supported = [cells[0] for cells in rows[1:] if len(cells) >= 2 and SUPPORTED.search(cells[1])]
    problems = []
    if line not in supported:
        problems.append(f"the supported-versions table marks {supported or 'nothing'} supported, not {line}")
    reporting = f"{repository}/security/advisories/new"
    if reporting not in text:
        problems.append(f"reports are not routed to GitHub private vulnerability reporting ({reporting})")
    problems += [f"names the unverified address {address.group(0)}" for address in EMAIL.finditer(text)]
    return problems


def check_security_policy() -> List[str]:
    version = gradle_property("VERSION_NAME")
    repository = gradle_property("POM_URL").rstrip("/")
    stale = "| Version | Supported |\n|---|---|\n| 0.1.x | ✅ Yes |\nEmail: security@example.invalid\n"
    if len(security_policy_problems(stale, version, repository)) < 3:
        raise CheckError("the security-policy rule accepts a stale fixture it must reject")
    path = ROOT / SECURITY_POLICY
    if not path.is_file():
        return [f"{SECURITY_POLICY} does not exist"]
    return [f"{SECURITY_POLICY}: {problem}" for problem in security_policy_problems(path.read_text(), version, repository)]


# ─── docs-workflow ──────────────────────────────────────────────────────────────────────────────

WORKFLOWS = ".github/workflows"
STRICT_DOCS_BUILD = re.compile(r"\bmkdocs\s+build\b[^\n]*--strict")
GH_DEPLOY = re.compile(r"\bmkdocs\s+gh-deploy\b")
TAG_CONDITION = re.compile(r"startsWith\(\s*github\.ref\s*,\s*'refs/tags/v'\s*\)")


def workflow_yaml():
    sys.path.insert(0, str(ROOT / "scripts"))
    import check_release_workflow  # noqa: E402  (shares its YAML subset reader)

    return check_release_workflow


def docs_workflow_problems(workflows: Dict[str, dict]) -> List[str]:
    reader = workflow_yaml()
    strict_on_pr, links_on_pr, problems = False, False, []
    for name, workflow in workflows.items():
        on = reader.triggers(workflow)
        push = on.get("push") if isinstance(on.get("push"), dict) else {}
        tag_only = set(on) == {"push"} and "branches" not in push and all(
            str(tag).startswith("v") for tag in reader.as_list(push.get("tags"))
        ) and bool(push.get("tags"))
        jobs = workflow.get("jobs") if isinstance(workflow.get("jobs"), dict) else {}
        strict_jobs = set()
        for job_id, job in jobs.items():
            steps = [step for step in reader.as_list(job.get("steps")) if isinstance(step, dict)]
            runs = reader.run_text(job)
            uses = [str(step.get("uses", "")) for step in steps]
            if STRICT_DOCS_BUILD.search(runs):
                strict_jobs.add(job_id)
                strict_on_pr = strict_on_pr or "pull_request" in on
            if any(use.startswith("lycheeverse/lychee-action@") for use in uses) or re.search(r"\blychee\b", runs):
                links_on_pr = links_on_pr or "pull_request" in on
            if GH_DEPLOY.search(runs):
                problems.append(f"{name}: job {job_id} force-pushes the site with mkdocs gh-deploy")
            if any(use.startswith("actions/deploy-pages@") for use in uses):
                if not tag_only and not TAG_CONDITION.search(str(job.get("if", ""))):
                    problems.append(
                        f"{name}: job {job_id} deploys the site on {sorted(on)}; deploy only for a v* tag"
                    )
                needs = set(reader.as_list(job.get("needs")))
                if not needs & strict_jobs and not any(
                    STRICT_DOCS_BUILD.search(reader.run_text(jobs.get(need, {}))) for need in needs
                ):
                    problems.append(f"{name}: job {job_id} deploys without needing the mkdocs --strict build")
    if not strict_on_pr:
        problems.append("no pull_request workflow builds the docs with mkdocs build --strict")
    if not links_on_pr:
        problems.append("no pull_request workflow checks the docs' links")
    return problems


def check_docs_workflow() -> List[str]:
    reader = workflow_yaml()
    stale = reader.parse_yaml(
        "on:\n  push:\n    branches:\n      - main\njobs:\n  deploy:\n    steps:\n"
        "      - run: mkdocs gh-deploy --force\n"
    )
    if len(docs_workflow_problems({"fixture.yml": stale})) < 3:
        raise CheckError("the docs-workflow rule accepts a force-deploying fixture it must reject")
    try:
        workflows = {
            path.relative_to(ROOT).as_posix(): reader.load(path)
            for path in sorted((ROOT / WORKFLOWS).glob("*.y*ml"))
        }
    except reader.CheckError as error:
        raise CheckError(str(error)) from error
    return docs_workflow_problems(workflows)


# ─── build-integrity ────────────────────────────────────────────────────────────────────────────

WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"
DEPENDABOT = ".github/dependabot.yml"
DEPENDABOT_ECOSYSTEMS = {"gradle", "github-actions", "pip"}
RUNS_GRADLE = re.compile(r"(\./gradlew|scripts/verify\.sh)\b")
PINNED_ACTION = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
PIP_INSTALL = re.compile(r"\bpip3?\s+install\b([^\n]*)")
HASHED_REQUIREMENTS = re.compile(r"--require-hashes\b.*\s-r\s+(\S+)|\s-r\s+(\S+).*--require-hashes\b")


def requirements_problems(path: Path) -> List[str]:
    name = path.relative_to(ROOT).as_posix()
    if not path.is_file():
        return [f"{name} does not exist"]
    requirements, current = [], ""
    for line in path.read_text().splitlines():
        line = line.split(" #", 1)[0].strip() if not line.lstrip().startswith("#") else ""
        current = f"{current} {line}".strip()
        if current.endswith("\\"):
            current = current[:-1].strip()
            continue
        if current:
            requirements.append(current)
        current = ""
    unhashed = [req.split()[0] for req in requirements if not req.startswith("-") and "--hash=sha256:" not in req]
    unpinned = [req.split()[0] for req in requirements if not req.startswith("-") and "==" not in req.split()[0]]
    return [f"{name}: {req} has no sha256 hash" for req in unhashed] + [
        f"{name}: {req} is not pinned with ==" for req in unpinned
    ]


def build_integrity_problems(wrapper: str, workflows: Dict[str, dict], dependabot: object) -> List[str]:
    reader = workflow_yaml()
    problems = []
    checksum = re.search(r"^distributionSha256Sum=([0-9a-f]{64})\s*$", wrapper, re.MULTILINE)
    if not checksum:
        problems.append(f"{WRAPPER_PROPERTIES} has no distributionSha256Sum, so the downloaded Gradle is not verified")
    for name, workflow in workflows.items():
        jobs = workflow.get("jobs") if isinstance(workflow.get("jobs"), dict) else {}
        for job_id, job in jobs.items():
            steps = [step for step in reader.as_list(job.get("steps")) if isinstance(step, dict)]
            uses = [str(step.get("uses", "")).strip() for step in steps if step.get("uses")]
            if job.get("uses"):
                uses.append(str(job.get("uses")).strip())
            for use in uses:
                if not use.startswith("./") and not use.startswith("docker://") and not PINNED_ACTION.match(use):
                    problems.append(f"{name}: job {job_id} uses {use}, not pinned by commit SHA")
            if RUNS_GRADLE.search(reader.run_text(job)) and not any(
                use.startswith("gradle/actions/wrapper-validation@") for use in uses
            ):
                problems.append(f"{name}: job {job_id} runs Gradle without gradle/actions/wrapper-validation")
            for match in PIP_INSTALL.finditer(reader.run_text(job)):
                hashed = HASHED_REQUIREMENTS.search(match.group(0))
                if not hashed:
                    problems.append(f"{name}: job {job_id} runs '{match.group(0).strip()}' without --require-hashes -r")
                else:
                    problems += requirements_problems(ROOT / (hashed.group(1) or hashed.group(2)))
    updates = dependabot.get("updates") if isinstance(dependabot, dict) else None
    ecosystems = {str(update.get("package-ecosystem")) for update in reader.as_list(updates) if isinstance(update, dict)}
    missing = DEPENDABOT_ECOSYSTEMS - ecosystems
    if missing:
        problems.append(f"{DEPENDABOT} does not update {sorted(missing)}")
    return problems


def check_build_integrity() -> List[str]:
    reader = workflow_yaml()
    stale = reader.parse_yaml(
        "jobs:\n  build:\n    steps:\n      - uses: actions/checkout@v4\n"
        "      - run: ./gradlew check\n      - run: pip install mkdocs-material==9.5.18\n"
    )
    if len(build_integrity_problems("distributionUrl=x\n", {"fixture.yml": stale}, None)) < 5:
        raise CheckError("the build-integrity rule accepts an unpinned fixture it must reject")
    try:
        workflows = {
            path.relative_to(ROOT).as_posix(): reader.load(path)
            for path in sorted((ROOT / WORKFLOWS).glob("*.y*ml"))
        }
        dependabot_path = ROOT / DEPENDABOT
        dependabot = reader.parse_yaml(dependabot_path.read_text()) if dependabot_path.is_file() else None
    except reader.CheckError as error:
        raise CheckError(str(error)) from error
    return build_integrity_problems((ROOT / WRAPPER_PROPERTIES).read_text(), workflows, dependabot)


# ─── catalog ────────────────────────────────────────────────────────────────────────────────────

CATALOG = "gradle/libs.versions.toml"
LIBRARY_MODULES = ["cameraK", "ImageSaverPlugin", "qrScannerPlugin", "ocrPlugin", "videoRecorderPlugin", "analyzerPlugin"]
CATALOG_ENTRY = re.compile(r"^([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$")
INLINE_FIELD = re.compile(r'([A-Za-z.]+)\s*=\s*"([^"]*)"')
ACCESSOR = re.compile(r"\blibs\.((?:[A-Za-z0-9]+\.)*[A-Za-z0-9]+)")
PLUGIN_ALIAS = re.compile(r"\balias\(libs\.plugins\.([A-Za-z0-9.]+)\)(\s*\.?\s*apply\s*\(?\s*false)?")
VERSIONLESS_PLUGIN_ID = re.compile(r'\bid\("([A-Za-z0-9_-]+\.[A-Za-z0-9_.-]+)"\)(?!\s*version)')
COMPOSE_ACCESSOR = re.compile(
    r"\b(?:api|implementation|compileOnly|runtimeOnly)\(compose\.(?!desktop\.currentOs\b)([A-Za-z0-9.]+)\)"
)


def parse_catalog(text: str) -> Dict[str, Dict[str, Dict[str, str]]]:
    sections: Dict[str, Dict[str, Dict[str, str]]] = {"versions": {}, "libraries": {}, "plugins": {}}
    section = None
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("["):
            section = line.strip("[]").strip()
            continue
        match = CATALOG_ENTRY.match(line)
        if section not in sections or not match:
            continue
        key, value = match.groups()
        if value.startswith("{"):
            sections[section][key] = dict(INLINE_FIELD.findall(value))
        else:
            sections[section][key] = {"version": value.strip('"')}
    return sections


def accessor(alias: str) -> str:
    return re.sub(r"[-_.]", ".", alias)


def catalog_problems(catalog_text: str, scripts: Dict[str, str]) -> List[str]:
    catalog = parse_catalog(catalog_text)
    problems = []
    used = {"versions": set(), "libraries": set(), "plugins": set()}
    applied = set()
    accessors = {
        "versions": {accessor(key): key for key in catalog["versions"]},
        "plugins": {accessor(key): key for key in catalog["plugins"]},
        "libraries": {accessor(key): key for key in catalog["libraries"]},
    }
    for name, text in scripts.items():
        for match in ACCESSOR.finditer(text):
            path = match.group(1)
            section = "libraries"
            for prefix in ("versions", "plugins"):
                if path.startswith(prefix + "."):
                    section, path = prefix, path[len(prefix) + 1:]
            # The longest alias whose accessor the reference starts with, at a segment boundary.
            candidates = [a for a in accessors[section] if path == a or path.startswith(a + ".")]
            if candidates:
                used[section].add(accessors[section][max(candidates, key=len)])
        for match in PLUGIN_ALIAS.finditer(text):
            if not match.group(2):
                applied.add(accessors["plugins"].get(match.group(1), match.group(1)))
        if "classpath(" in text:
            problems.append(f"{name} puts a plugin on a buildscript classpath; declare it in [plugins] and use alias(...)")
        for plugin_id in VERSIONLESS_PLUGIN_ID.findall(text):
            problems.append(f"{name} applies id(\"{plugin_id}\") without the catalog; use a [plugins] alias")
        module = name.split("/", 1)[0]
        if module in LIBRARY_MODULES:
            for coordinate in COMPOSE_ACCESSOR.findall(text):
                problems.append(f"{name} declares compose.{coordinate}; use explicit org.jetbrains.compose coordinates")
    for entries in (catalog["libraries"], catalog["plugins"]):
        for fields in entries.values():
            if "version.ref" in fields:
                used["versions"].add(fields["version.ref"])
    for section in ("versions", "libraries", "plugins"):
        for key in sorted(set(catalog[section]) - used[section]):
            problems.append(f"{CATALOG} [{section}] {key} is not referenced by any build script")
    for key in sorted(set(catalog["plugins"]) - applied):
        if key in used["plugins"]:
            problems.append(f"{CATALOG} [plugins] {key} is only ever declared with apply false")
    trains: Dict[tuple, set] = {}
    for fields in catalog["libraries"].values():
        if "module" in fields and "version.ref" in fields:
            version = catalog["versions"].get(fields["version.ref"], {}).get("version")
            trains.setdefault((fields["module"].split(":")[0], version), set()).add(fields["version.ref"])
    for (group, version), keys in sorted(trains.items()):
        if len(keys) > 1:
            problems.append(f"{CATALOG} gives {group} version {version} through {sorted(keys)}; use one key")
    return problems


def check_catalog() -> List[str]:
    fixture_catalog = (
        '[versions]\na = "1"\nb = "1"\ndead = "2"\n[libraries]\n'
        'x = { module = "g:x", version.ref = "a" }\ny = { module = "g:y", version.ref = "b" }\n'
        'z = { module = "g:z", version.ref = "a" }\n[plugins]\np = { id = "p", version.ref = "a" }\n'
    )
    fixture_scripts = {
        "build.gradle.kts": "plugins { alias(libs.plugins.p) apply false }\nbuildscript { dependencies { classpath(libs.z) } }",
        "cameraK/build.gradle.kts": 'plugins { id("org.example.plugin") }\napi(libs.x)\napi(libs.y.get())\napi(compose.ui)',
    }
    if len(catalog_problems(fixture_catalog, fixture_scripts)) < 6:
        raise CheckError("the catalog rule accepts a fixture it must reject")
    scripts = {
        path.relative_to(ROOT).as_posix(): path.read_text()
        for path in tracked("*.gradle.kts")
    }
    return catalog_problems((ROOT / CATALOG).read_text(), scripts)


# ─── native-settings ────────────────────────────────────────────────────────────────────────────

NATIVE_SOURCE = re.compile(r"(^|/)(cpp|jni|jniLibs)/|\.so$|(^|/)(CMakeLists\.txt|Android\.mk)$")
NATIVE_SETTING = re.compile(r"^\s*(ndkVersion\s*=|ndkPath\s*=|jniLibs\s*\{|useLegacyPackaging\s*=)", re.MULTILINE)


def native_settings_problems(scripts: Dict[str, str], files: List[str]) -> List[str]:
    problems = []
    for name, text in scripts.items():
        module = name.rsplit("/", 1)[0] if "/" in name else ""
        if any(NATIVE_SOURCE.search(f) for f in files if f.startswith(f"{module}/src/")):
            continue
        for match in NATIVE_SETTING.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            problems.append(f"{name}:{line}: {match.group(1).rstrip(' ={')} has no effect, {module or 'the root'} has no native code")
    return problems


def check_native_settings() -> List[str]:
    fixture = {"lib/build.gradle.kts": 'android {\n    ndkVersion = "26"\n    packaging {\n        jniLibs {\n'}
    if len(native_settings_problems(fixture, ["lib/src/main/kotlin/A.kt"])) < 2 or native_settings_problems(
        fixture, ["lib/src/main/cpp/a.cpp"]
    ):
        raise CheckError("the native-settings rule misjudges its fixtures")
    files = [path.relative_to(ROOT).as_posix() for path in tracked("*")]
    scripts = {name: (ROOT / name).read_text() for name in files if name.endswith("build.gradle.kts")}
    return native_settings_problems(scripts, files)


POLICIES: Dict[str, Callable[[], List[str]]] = {
    "agent-permissions": check_agent_permissions,
    "security-policy": check_security_policy,
    "docs-workflow": check_docs_workflow,
    "build-integrity": check_build_integrity,
    "catalog": check_catalog,
    "native-settings": check_native_settings,
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
