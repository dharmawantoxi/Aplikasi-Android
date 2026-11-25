#!/usr/bin/env python3
"""Cek konsistensi versi Gradle/AGP sebelum build dijalankan.

Kegagalan unduh plugin sering tampil sebagai error Gradle yang panjang dan
ambigu. Pemeriksaan murah ini memberi pesan jelas lebih awal, tanpa perlu
Java atau Android SDK.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_PATTERN = re.compile(
    r'id\(\s*"com\.android\.application"\s*\)\s*version\s*"([^"]+)"'
)
VERSION_PATTERN = re.compile(r'^\s*androidGradlePlugin\s*=\s*"([^"]+)"', re.MULTILINE)
DISTRIBUTION_PATTERN = re.compile(r"^distributionUrl=.*gradle-(\d+(?:\.\d+)+)-", re.MULTILINE)
CHECKSUM_PATTERN = re.compile(r"^distributionSha256Sum=([0-9a-f]{64})$", re.MULTILINE)
# AGP 8.13 memerlukan Gradle 8.13+; batas bawah ini cukup untuk mencegah salah tarik.
MIN_GRADLE = (8, 13)


def check(root: Path) -> list[str]:
    """Kembalikan daftar masalah; list kosong berarti konfigurasi konsisten."""
    problems: list[str] = []
    build = (root / "build.gradle.kts")
    if not build.is_file():
        return [f"Tidak menemukan {build}"]
    try:
        build_text = build.read_text(encoding="utf-8")
    except OSError as error:
        return [f"Tidak dapat membaca {build}: {error}"]

    plugin = PLUGIN_PATTERN.search(build_text)
    if not plugin:
        problems.append("Versi plugin com.android.application tidak ditemukan di build.gradle.kts.")
        plugin_version = None
    else:
        plugin_version = plugin.group(1)

    catalog = root / "gradle" / "libs.versions.toml"
    catalog_version = None
    if not catalog.is_file():
        problems.append(f"Tidak menemukan {catalog}.")
    else:
        match = VERSION_PATTERN.search(catalog.read_text(encoding="utf-8"))
        catalog_version = match.group(1) if match else None
        if not catalog_version:
            problems.append("androidGradlePlugin tidak ada di gradle/libs.versions.toml.")

    if plugin_version and catalog_version and plugin_version != catalog_version:
        problems.append(
            f"Versi AGP tidak sinkron: build.gradle.kts={plugin_version}, "
            f"libs.versions.toml={catalog_version}."
        )

    wrapper = root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not wrapper.is_file():
        problems.append(f"Tidak menemukan {wrapper}.")
    else:
        wrapper_text = wrapper.read_text(encoding="utf-8")
        distribution = DISTRIBUTION_PATTERN.search(wrapper_text)
        if not distribution:
            problems.append("distributionUrl Gradle tidak dapat dibaca.")
        else:
            version = tuple(int(part) for part in distribution.group(1).split("."))
            if version < MIN_GRADLE:
                pretty = ".".join(str(part) for part in version)
                minimum = ".".join(str(part) for part in MIN_GRADLE)
                problems.append(f"Gradle {pretty} terlalu lama untuk AGP ini; minimal {minimum}.")
        if not CHECKSUM_PATTERN.search(wrapper_text):
            problems.append("distributionSha256Sum tidak ada; unduhan wrapper tidak terverifikasi.")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="folder proyek ToxiFlow")
    args = parser.parse_args(argv)

    problems = check(args.root)
    for problem in problems:
        print("::error::" + problem.replace("%", "%25"))
    if problems:
        return 1
    print("Konfigurasi Gradle/AGP konsisten.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
