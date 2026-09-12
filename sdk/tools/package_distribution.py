#!/usr/bin/env python3
"""Assemble already-built StableAR artifacts into a traceable customer/evaluation bundle.

This script packages binaries; it does not grant a licence, publish a release or mark the
bundle legally/commercially cleared. The manifest intentionally records clearance as false.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_sha(repo: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True
        ).strip()
    except Exception:
        return "unknown"


def copy_file(source: Path, destination: Path, logical_kind: str, artifacts: list[dict]) -> None:
    if not source.is_file():
        raise FileNotFoundError(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    artifacts.append(
        {
            "kind": logical_kind,
            "path": destination.as_posix(),
            "size_bytes": destination.stat().st_size,
            "sha256": sha256(destination),
        }
    )


def copy_tree(source: Path, destination: Path) -> None:
    if not source.is_dir():
        raise FileNotFoundError(source)
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(
        source,
        destination,
        ignore=shutil.ignore_patterns(
            "build", ".gradle", ".DS_Store", "*.pem", "*.key", "*.p12", "*.keystore"
        ),
    )


def record_tree_files(root: Path, bundle_root: Path, logical_kind: str, artifacts: list[dict]) -> None:
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        artifacts.append(
            {
                "kind": logical_kind,
                "path": path.relative_to(bundle_root).as_posix(),
                "size_bytes": path.stat().st_size,
                "sha256": sha256(path),
            }
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="package/product version, e.g. 0.2.0-preview.7")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--source-sha")
    parser.add_argument(
        "--platform",
        action="append",
        choices=("android", "native", "apple", "unity"),
        default=[],
        help="platform payload to require; repeat as needed. With none, package every discovered payload.",
    )
    parser.add_argument("--native-install-dir", type=Path, default=Path("build/install"))
    parser.add_argument("--apple-build-dir", type=Path, default=Path("build/apple"))
    args = parser.parse_args()

    script = Path(__file__).resolve()
    repo = (args.repo_root or script.parents[2]).resolve()
    sdk = repo / "sdk"
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("output directory must be empty or absent")
    output.mkdir(parents=True, exist_ok=True)

    requested = set(args.platform)
    auto = not requested
    artifacts: list[dict] = []
    packaged_platforms: list[str] = []

    android_candidates = [
        (sdk / "core/build/libs/core.jar", "core.jar"),
        (sdk / "arcore/build/outputs/aar/arcore-release.aar", "arcore-release.aar"),
        (sdk / "vision/build/outputs/aar/vision-release.aar", "vision-release.aar"),
        (sdk / "native-android/build/outputs/aar/native-android-release.aar", "native-android-release.aar"),
        (
            sdk / "native-vision-android/build/outputs/aar/native-vision-android-release.aar",
            "native-vision-android-release.aar",
        ),
    ]
    android_available = all(path.is_file() for path, _ in android_candidates)
    if "android" in requested and not android_available:
        missing = [str(path) for path, _ in android_candidates if not path.is_file()]
        raise SystemExit("required Android artifacts are missing: " + ", ".join(missing))
    if android_available and (auto or "android" in requested):
        for source, name in android_candidates:
            copy_file(source, output / "android" / name, "android", artifacts)
        packaged_platforms.append("android")

    native_source = (repo / args.native_install_dir).resolve()
    native_available = native_source.is_dir() and any(native_source.rglob("*"))
    if "native" in requested and not native_available:
        raise SystemExit(f"required native install tree is missing: {native_source}")
    if native_available and (auto or "native" in requested):
        destination = output / "native"
        copy_tree(native_source, destination)
        record_tree_files(destination, output, "native", artifacts)
        packaged_platforms.append("native")

    apple_source = (repo / args.apple_build_dir).resolve()
    apple_frameworks = [
        apple_source / "StableARNative.xcframework",
        apple_source / "StableARXFeatNative.xcframework",
    ]
    apple_available = all(path.is_dir() for path in apple_frameworks)
    if "apple" in requested and not apple_available:
        raise SystemExit("required Apple XCFrameworks are missing from " + str(apple_source))
    if apple_available and (auto or "apple" in requested):
        apple_destination = output / "apple"
        for source in apple_frameworks:
            copy_tree(source, apple_destination / source.name)
        record_tree_files(apple_destination, output, "apple", artifacts)
        packaged_platforms.append("apple")

    unity_source = sdk / "unity"
    unity_available = unity_source.is_dir()
    if "unity" in requested and not unity_available:
        raise SystemExit(f"required Unity package is missing: {unity_source}")
    if unity_available and (auto or "unity" in requested):
        unity_destination = output / "unity"
        copy_tree(unity_source, unity_destination)
        record_tree_files(unity_destination, output, "unity", artifacts)
        packaged_platforms.append("unity")

    if requested - set(packaged_platforms):
        raise SystemExit("one or more requested platform payloads were not packaged")
    if not packaged_platforms:
        raise SystemExit("no built/distributable platform payloads were discovered")

    docs = output / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    for relative in (
        "README.md",
        "MULTIPLATFORM.md",
        "VERSIONING.md",
        "VALIDATION.md",
        "COMMERCIALIZATION.md",
        "benchmark/README.md",
        "licensing/README.md",
    ):
        source = sdk / relative
        destination = docs / relative.replace("/", "-")
        if source.is_file():
            shutil.copy2(source, destination)

    manifest = {
        "schema": "stablear-distribution-v1",
        "product": "StableAR SDK",
        "version": args.version,
        "native_c_abi": 1,
        "source_sha": args.source_sha or git_sha(repo),
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "platforms": packaged_platforms,
        "commercial_release_cleared": False,
        "clearance_note": (
            "Packaging is not a legal/commercial approval. See docs/COMMERCIALIZATION.md and attach "
            "the customer licence/entitlement separately."
        ),
        "artifacts": sorted(artifacts, key=lambda value: value["path"]),
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    checksums = output / "SHA256SUMS"
    checksum_lines = [
        f"{entry['sha256']}  {entry['path']}" for entry in manifest["artifacts"]
    ]
    checksum_lines.append(f"{sha256(manifest_path)}  manifest.json")
    checksums.write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")

    print(output)
    print(f"packaged platforms: {', '.join(packaged_platforms)}")
    print("commercial_release_cleared=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
