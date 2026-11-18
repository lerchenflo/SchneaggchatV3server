#!/usr/bin/env python3
"""Check build.gradle.kts, the Gradle wrapper, the JVM toolchain and the Dockerfile for newer versions.

Usage: python3 scripts/check_updates.py [--pre]

  --pre   also consider alpha/beta/RC/milestone versions as "latest"

Only reads files and queries public version endpoints (Maven Central, Gradle Plugin Portal,
services.gradle.org, Adoptium). Changes nothing. Standard library only.
"""

import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_FILE = ROOT / "build.gradle.kts"
WRAPPER_FILE = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
DOCKERFILE = ROOT / "Dockerfile"

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
USER_AGENT = "gradle-update-check"
# Artifacts that stopped publishing under their old coordinates: old -> (new, note).
RELOCATED = {
    "com.bucket4j:bucket4j-core": ("com.bucket4j:bucket4j_jdk17-core", ""),
    "com.bucket4j:bucket4j-redis": ("com.bucket4j:bucket4j_jdk17-lettuce",
                                    "redis module split into -lettuce/-jedis/-redisson; this project uses Lettuce"),
}
PRE_RELEASE = re.compile(r"(?i)(alpha|beta|[.-]rc|[.-]cr|[.-]m\d|milestone|snapshot|preview|[.-]dev|[.-]ea\b)")

INCLUDE_PRE = "--pre" in sys.argv


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


def version_key(version):
    return tuple(int(n) for n in re.findall(r"\d+", version))


def is_stable(version):
    return INCLUDE_PRE or not PRE_RELEASE.search(version)


def latest_of(versions, current):
    candidates = [v for v in versions if is_stable(v)]
    # Drop date-style versions (e.g. 20030203) that would otherwise sort above real ones.
    if version_key(current) and version_key(current)[0] < 1000:
        candidates = [v for v in candidates if version_key(v) and version_key(v)[0] < 1000]
    return max(candidates, key=version_key) if candidates else None


def maven_latest(repo, group, artifact, current):
    url = "{}/{}/{}/maven-metadata.xml".format(repo, group.replace(".", "/"), artifact)
    root = ET.fromstring(fetch(url))
    return latest_of([v.text for v in root.iter("version")], current)


def plugin_latest(plugin_id, current):
    return maven_latest(PLUGIN_PORTAL, plugin_id, plugin_id + ".gradle.plugin", current)


def gradle_latest(current):
    if INCLUDE_PRE:
        versions = [v["version"] for v in json.loads(fetch("https://services.gradle.org/versions/all"))
                    if not v.get("snapshot") and not v.get("nightly") and not v.get("broken")]
        return latest_of(versions, current)
    return json.loads(fetch("https://services.gradle.org/versions/current"))["version"]


def java_latest(current):
    info = json.loads(fetch("https://api.adoptium.net/v3/info/available_releases"))
    return "{} (LTS {})".format(info["most_recent_feature_release"], info["most_recent_lts"])


def java_status(current, latest_text):
    feature, lts = (int(n) for n in re.findall(r"\d+", latest_text))
    if int(current) < lts:
        return "UPDATE (major, new LTS)"
    if int(current) < feature:
        return "up to date (LTS); newer non-LTS"
    return "up to date"


# ---------------------------------------------------------------- parsing

def parse_build_file(text):
    """Return (checks, managed): checks are dicts with kind/name/current/lookup, managed are names without a version."""
    checks, managed = [], []

    plugins_block = re.search(r"plugins\s*\{(.*?)\n\}", text, re.S)
    if plugins_block:
        for line in plugins_block.group(1).splitlines():
            m = re.search(r'kotlin\("([^"]+)"\)\s+version\s+"([^"]+)"', line)
            if m:
                plugin_id = "org.jetbrains.kotlin." + m.group(1)
                checks.append(dict(kind="plugin", name="kotlin(\"{}\")".format(m.group(1)), current=m.group(2),
                                   lookup=lambda c, p=plugin_id: plugin_latest(p, c)))
                continue
            m = re.search(r'id\("([^"]+)"\)\s+version\s+"([^"]+)"', line)
            if m:
                checks.append(dict(kind="plugin", name=m.group(1), current=m.group(2),
                                   lookup=lambda c, p=m.group(1): plugin_latest(p, c)))

    deps_block = re.search(r"dependencies\s*\{(.*?)\n\}", text, re.S)
    if deps_block:
        for line in deps_block.group(1).splitlines():
            line = line.split("//")[0]
            m = re.search(r'\w+\("([^":]+):([^":]+):([^"]+)"\)', line)
            if m:
                group, artifact, current = m.groups()
                checks.append(dict(kind="library", name="{}:{}".format(group, artifact), current=current,
                                   lookup=lambda c, g=group, a=artifact: maven_latest(MAVEN_CENTRAL, g, a, c)))
                continue
            m = re.search(r'\w+\("([^":]+:[^":]+)"\)', line) or re.search(r'\w+\((kotlin\("[^"]+"\))\)', line)
            if m:
                managed.append(m.group(1))

    m = re.search(r"JavaLanguageVersion\.of\((\d+)\)", text)
    if m:
        checks.append(dict(kind="jvm", name="Java toolchain", current=m.group(1), lookup=java_latest,
                           status=java_status))
    return checks, managed


def parse_wrapper(text):
    m = re.search(r"gradle-([\w.\-]+?)-(?:bin|all)\.zip", text)
    return [dict(kind="gradle", name="Gradle wrapper", current=m.group(1), lookup=gradle_latest)] if m else []


def parse_dockerfile(text):
    return sorted(set(re.findall(r"FROM\s+eclipse-temurin:(\d+)", text)))


# ---------------------------------------------------------------- report

def classify(current, latest):
    if latest is None:
        return "no stable version found"
    cur, new = version_key(current), version_key(latest)
    if new <= cur:
        return "up to date"
    return "UPDATE (major)" if new[:1] != cur[:1] else "update"


def run_check(check):
    try:
        latest = check["lookup"](check["current"])
        status = check.get("status", classify)(check["current"], latest)
        moved = RELOCATED.get(check["name"])
        if moved:
            new_name, note = moved
            group, artifact = new_name.split(":")
            new_latest = maven_latest(MAVEN_CENTRAL, group, artifact, check["current"])
            status = "MOVED to {} {}".format(new_name, new_latest)
            if note:
                check["note"] = "{}: {}.".format(check["name"], note)
        return latest, status
    except Exception as error:  # network error, missing metadata, etc.
        return "-", "ERROR: {}".format(error)


def main():
    build_text = BUILD_FILE.read_text()
    checks, managed = parse_build_file(build_text)
    if WRAPPER_FILE.exists():
        checks += parse_wrapper(WRAPPER_FILE.read_text())

    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(run_check, checks))

    rows = [(c["kind"], c["name"], c["current"], latest, status) for c, (latest, status) in zip(checks, results)]
    headers = ("kind", "name", "current", "latest", "status")
    widths = [max(len(str(r[i])) for r in rows + [headers]) for i in range(len(headers))]
    line = "  ".join("{:<" + str(w) + "}" for w in widths)
    print(line.format(*headers))
    print(line.format(*("-" * w for w in widths)))
    order = {"jvm": 0, "gradle": 1, "plugin": 2, "library": 3}
    for row in sorted(rows, key=lambda r: (order.get(r[0], 9), r[4] == "up to date", r[1])):
        print(line.format(*row))

    notes = [c["note"] for c in checks if c.get("note")]
    kotlin_versions = {c["current"] for c in checks if c["name"].startswith("kotlin(")}
    if len(kotlin_versions) > 1:
        notes.append("Kotlin plugins use different versions ({}); keep them on one version."
                     .format(", ".join(sorted(kotlin_versions, key=version_key))))

    toolchain = next((c["current"] for c in checks if c["kind"] == "jvm"), None)
    if DOCKERFILE.exists():
        for image_version in parse_dockerfile(DOCKERFILE.read_text()):
            if toolchain and image_version != toolchain:
                notes.append("Dockerfile uses eclipse-temurin:{} but the toolchain is Java {}."
                             .format(image_version, toolchain))

    if managed:
        notes.append("{} dependencies have no version (managed by the Spring Boot BOM / Kotlin plugin); "
                     "they move with the Spring Boot and Kotlin plugin versions.".format(len(managed)))

    updates = sum(1 for r in rows if r[4].startswith(("update", "UPDATE", "MOVED")))
    errors = sum(1 for r in rows if r[4].startswith("ERROR"))
    print()
    print("{} checked, {} with updates, {} errors.".format(len(rows), updates, errors))
    for note in notes:
        print("note: " + note)


if __name__ == "__main__":
    main()
