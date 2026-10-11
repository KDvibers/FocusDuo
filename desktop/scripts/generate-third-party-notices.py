#!/usr/bin/env python3
"""Regenerate the checked-in Windows dependency notices from resolved runtime JARs.

First run ./gradlew.bat writeRuntimeLicenseInputs from desktop, then:
  python scripts/generate-third-party-notices.py
Python 3 stdlib only. Regeneration downloads version-pinned upstream license
texts and the Skia build archive; ordinary Gradle/MSI builds use committed files.
Unknown dependency families fail explicitly instead of assigning a license.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import urllib.request
import xml.etree.ElementTree as ET
from zipfile import ZipFile


DESKTOP = Path(__file__).resolve().parents[1]
SKIA_TAG = "m132-a00c390e98-1"
SKIA_ARCHIVE = f"https://github.com/JetBrains/skia-pack/releases/download/{SKIA_TAG}/Skia-{SKIA_TAG}-windows-Release-x64.zip"
SKIA_BASE = "https://raw.githubusercontent.com/JetBrains/skia/a00c390e98/"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    return base64.b64decode(data) if url.endswith("?format=TEXT") else data


def family(artifact: dict) -> str:
    group, name = artifact["group"], artifact["name"]
    if group == "org.slf4j":
        return "slf4j"
    if group.startswith("androidx.") or group.startswith("org.jetbrains.compose.") or group.startswith("org.jetbrains.androidx."):
        return "androidx-compose"
    if group == "io.ktor":
        return "ktor"
    if group == "org.jetbrains.kotlin":
        return "kotlin-" + artifact["version"]
    if group == "org.jetbrains.kotlinx":
        for prefix in ("atomicfu", "kotlinx-coroutines", "kotlinx-serialization", "kotlinx-io"):
            if name.startswith(prefix):
                return prefix
    if group == "org.jetbrains.skiko":
        return "skiko"
    if group == "org.jetbrains.runtime" and name == "jbr-api":
        return "jbr-api"
    if group == "org.jetbrains" and name == "annotations":
        return "jetbrains-annotations"
    if group == "org.jspecify":
        return "jspecify"
    raise ValueError(f"Review the license of the new dependency: {group}:{name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, default=DESKTOP / "build/license-input/runtime-artifacts.json")
    parser.add_argument("--output-dir", type=Path, default=DESKTOP / "src/main/distribution-resources/common/licenses")
    args = parser.parse_args()
    artifacts = json.loads(args.classpath_file.read_text(encoding="utf-8-sig"))
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    written: dict[str, dict] = {}

    def save(relative: str, data: bytes, source: str) -> str:
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        written[relative] = {"path": relative, "sha256": digest(data), "source": source}
        return relative

    def download(relative: str, url: str) -> str:
        return save(relative, fetch(url), url)

    apache = download("shared/Apache-2.0.txt", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    family_notices: dict[str, list[str]] = {}
    sources = {
        "androidx-compose": [("LICENSE.txt", "https://raw.githubusercontent.com/JetBrains/compose-multiplatform-core/v1.9.1/LICENSE.txt")],
        "ktor": [("LICENSE", "https://raw.githubusercontent.com/ktorio/ktor/3.3.1/LICENSE")],
        "atomicfu": [("LICENSE.txt", "https://raw.githubusercontent.com/Kotlin/kotlinx-atomicfu/0.23.2/LICENSE.txt")],
        "kotlinx-coroutines": [("LICENSE.txt", "https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/LICENSE.txt"),
                               ("NOTICE.txt", "https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/license/NOTICE.txt")],
        "kotlinx-serialization": [("LICENSE.txt", "https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.9.0/LICENSE.txt"),
                                  ("NOTICE.txt", "https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.9.0/license/NOTICE.txt")],
        "kotlinx-io": [("LICENSE", "https://raw.githubusercontent.com/Kotlin/kotlinx-io/0.8.0/LICENSE"),
                       ("NOTICE", "https://raw.githubusercontent.com/Kotlin/kotlinx-io/0.8.0/NOTICE")],
        "jetbrains-annotations": [("LICENSE.txt", "https://raw.githubusercontent.com/JetBrains/java-annotations/23.0.0/LICENSE.txt")],
        "jspecify": [("LICENSE", "https://raw.githubusercontent.com/jspecify/jspecify/v1.0.0/LICENSE")],
        "skiko": [("LICENSE", "https://raw.githubusercontent.com/JetBrains/skiko/v0.9.22.2/LICENSE"),
                  ("NOTICE", "https://raw.githubusercontent.com/JetBrains/skiko/v0.9.22.2/NOTICE")],
        # jbr-api is an Apache-2.0 Java API artifact, distinct from the bundled JDK.
        # Its exact published POM declaration is retained below. The runtime's
        # GPL/classpath-exception and native notices remain in runtime/legal.
        "jbr-api": [],
        "slf4j": [],
    }
    for version in sorted({a["version"] for a in artifacts if a["group"] == "org.jetbrains.kotlin"}):
        sources["kotlin-" + version] = [(name, f"https://raw.githubusercontent.com/JetBrains/kotlin/v{version}/license/{name}")
                                          for name in ("LICENSE.txt", "NOTICE.txt", "COPYRIGHT.txt")]
    families = {family(a) for a in artifacts}
    for key in sorted(families):
        family_notices[key] = [download(f"upstream/{key}/{name}", url) for name, url in sources[key]]
        if key != "slf4j":
            family_notices[key].insert(0, apache)

    # Skiko v0.9.22.2 declares this exact Skia build in skiko/gradle.properties.
    # Preserve the archive's native dependency license texts and additional
    # linked-library notices pinned by that Skia revision's DEPS.
    native = [download("native/skia/LICENSE", SKIA_BASE + "LICENSE")]
    native.append(download("native/skia/DEPS.txt", SKIA_BASE + "DEPS"))
    native.append(download("native/skiko/skia-build.properties", "https://raw.githubusercontent.com/JetBrains/skiko/v0.9.22.2/skiko/gradle.properties"))
    archive = DESKTOP / "build/license-input/skia-release.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        archive.write_bytes(fetch(SKIA_ARCHIVE))
    archive_hash = digest(archive.read_bytes())
    with ZipFile(archive) as zipped:
        for entry in sorted(zipped.namelist()):
            if not entry.endswith("/") and re.search(r"(^|/)(LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*|COPYRIGHT[^/]*)$", entry, re.I):
                native.append(save("native/skia-pack/" + entry, zipped.read(entry), SKIA_ARCHIVE + "!/" + entry))
    native_sources = {
        "expat/COPYING": "https://raw.githubusercontent.com/libexpat/libexpat/624da0f593bb8d7e146b9f42b06d8e6c80d032a3/expat/COPYING",
        "d3d12allocator/LICENSE.txt": "https://raw.githubusercontent.com/GPUOpen-LibrariesAndSDKs/D3D12MemoryAllocator/169895d529dfce00390a20e69c2f516066fe7a3b/LICENSE.txt",
        "icu/LICENSE": "https://chromium.googlesource.com/chromium/deps/icu/+/364118a1d9da24bb5b770ac3d762ac144d6da5a4/LICENSE?format=TEXT",
        "libjpeg-turbo/LICENSE.md": "https://chromium.googlesource.com/chromium/deps/libjpeg_turbo/+/ccfbe1c82a3b6dbe8647ceb36a3f9ee711fba3cf/LICENSE.md?format=TEXT",
        "libjpeg-turbo/README.ijg": "https://chromium.googlesource.com/chromium/deps/libjpeg_turbo/+/ccfbe1c82a3b6dbe8647ceb36a3f9ee711fba3cf/README.ijg?format=TEXT",
        "spirv-cross/LICENSE": "https://raw.githubusercontent.com/KhronosGroup/SPIRV-Cross/b8fcf307f1f347089e3c46eb4451d27f32ebc8d3/LICENSE",
        "wuffs/LICENSE": "https://raw.githubusercontent.com/google/wuffs-mirror-release-c/e3f919ccfe3ef542cfc983a82146070258fb57f8/LICENSE",
    }
    native.extend(download("native/" + name, url) for name, url in native_sources.items())
    family_notices["skiko"].extend(native)
    records = []
    for artifact in sorted(artifacts, key=lambda a: (a["group"], a["name"], a["version"])):
        jar = Path(artifact["file"])
        coordinate = f"{artifact['group']}:{artifact['name']}:{artifact['version']}"
        # Keep install/source paths below Windows MAX_PATH even in a worktree.
        # Full original coordinates and JAR entry paths remain in the manifest.
        suffix = hashlib.sha256(coordinate.encode()).hexdigest()[:8]
        folder = f"artifacts/{artifact['name'][:32]}-{artifact['version']}-{suffix}"
        notices = list(family_notices[family(artifact)])
        with ZipFile(jar) as zipped:
            for entry in sorted(zipped.namelist()):
                if not entry.endswith("/") and re.search(r"(^|/)(LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*|COPYRIGHT[^/]*)$", entry, re.I):
                    safe = PurePosixPath(entry)
                    if safe.is_absolute() or ".." in safe.parts:
                        raise ValueError("Unsafe JAR notice entry: " + entry)
                    entry_id = hashlib.sha256(entry.encode()).hexdigest()[:8]
                    notices.append(save(folder + "/" + entry_id + "-" + safe.name, zipped.read(entry), coordinate + "!/" + entry))
        poms = sorted(jar.parent.parent.glob("*/*.pom"))
        if poms:
            notices.append(save(folder + "/published.pom", poms[0].read_bytes(), coordinate + ":pom"))
        if not notices:
            raise ValueError("No license text available for " + coordinate)
        records.append({**{key: artifact[key] for key in ("group", "name", "version")}, "jar": jar.name,
                        "sha256": digest(jar.read_bytes()), "license": "MIT" if family(artifact) == "slf4j" else "Apache-2.0",
                        "notices": sorted(set(notices))})
    ofl = save("fonts/Manrope-OFL.txt", (DESKTOP / "src/main/resources/fonts/OFL.txt").read_bytes(), "desktop/src/main/resources/fonts/OFL.txt")
    manifest = {"schemaVersion": 1, "artifacts": records, "fontNotices": [ofl],
                "nativeBuild": {"skikoVersion": "0.9.22.2", "skiaBuild": SKIA_TAG, "archive": SKIA_ARCHIVE, "archiveSha256": archive_hash},
                "files": sorted(written.values(), key=lambda record: record["path"])}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    lines = ["FocusDuo third-party notices", "", "This directory describes the dependency binaries in this Windows client.",
             "It does not assign a license to FocusDuo. The embedded JDK's original", "license and copyright notices are additionally preserved in runtime/legal.",
             "Manrope fonts are covered by fonts/Manrope-OFL.txt.", "", "Resolved runtime artifacts:"]
    lines.extend(f"  {a['group']}:{a['name']}:{a['version']} — {a['license']}" for a in records)
    lines.extend(["", "All upstream/JAR notices are copied byte-for-byte; their sources and SHA-256", "are in manifest.json. Native Skia/Skiko dependency notices are under native/.",
                  "The Skia archive may contain optional build dependencies; retaining their", "notices does not assert that every optional library is linked into skiko.dll.",
                  "", "To regenerate after reviewing a dependency change:", "  .\\gradlew.bat writeRuntimeLicenseInputs", "  python scripts/generate-third-party-notices.py", "", "Normal packaging needs no Python/network license fetch; Gradle verifies the", "committed manifest against the exact runtime JARs before building the MSI."])
    (output / "THIRD_PARTY_NOTICES.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"Preserved {len(records)} runtime JARs, {len(written)} notice/POM files, and Manrope OFL in {output}")


if __name__ == "__main__":
    main()
