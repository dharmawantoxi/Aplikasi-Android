#!/usr/bin/env python3
"""Ringkas kegagalan Gradle menjadi anotasi GitHub Actions.

Dipakai setelah langkah Gradle gagal. Exit code memberi tahu pemanggil apakah
kegagalan tampak sementara (jaringan) sehingga layak dicoba ulang:

* ``0`` — tidak ada kegagalan yang bisa dibaca.
* ``1`` — kegagalan nyata; pesan sudah dicetak sebagai anotasi ``::error::``.
* ``3`` — kegagalan sementara (unduhan putus/timeout) sehingga aman diulang.

Log asli tidak pernah tertelan: anotasi hanya menyalin baris terpenting,
sedangkan berkas log tetap diunggah sebagai artifact.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

TRANSIENT_MARKERS = (
    "could not get",
    "could not download",
    "could not resolve",
    "could not install",
    "connection reset",
    "connection timed out",
    "read timed out",
    "broken pipe",
    "unexpected end of file",
    "eof",
    "network is unreachable",
    "ssl handshake",
    "temporary failure in name resolution",
    "service unavailable",
    "too many requests",
    "gateway timeout",
    "received status code 5",
)

ERROR_PREFIXES = (
    "e: ",
    "error:",
    "agpbi: ",
    "execution failed",
    "a problem occurred",
    "caused by:",
    "failure:",
)

ERROR_CONTAINS = (
    " error:",
    " failed",
    "could not",
    "what went wrong",
)

MAX_ANNOTATIONS = 10
MAX_LINE = 2000


def escape(text: str) -> str:
    """Escape data untuk workflow command GitHub Actions."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def is_transient(text: str) -> bool:
    """True bila log menunjukkan gangguan jaringan yang mungkin pulih saat diulang."""
    lowered = text.lower()
    return any(marker in lowered for marker in TRANSIENT_MARKERS)


def error_lines(text: str) -> list[str]:
    """Ambil baris yang menjelaskan sebab kegagalan, tanpa duplikat."""
    wanted: list[str] = []
    seen: set[str] = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        lowered = line.lower()
        transient = any(marker in lowered for marker in TRANSIENT_MARKERS)
        if transient or lowered.startswith(ERROR_PREFIXES) or any(part in lowered for part in ERROR_CONTAINS):
            if line not in seen:
                seen.add(line)
                wanted.append(line[:MAX_LINE])
            if len(wanted) >= MAX_ANNOTATIONS:
                break
    return wanted


def report(text: str) -> tuple[int, list[str]]:
    """Kembalikan (exit code, baris anotasi) untuk isi log tertentu."""
    lines = error_lines(text)
    if not lines:
        return 0, []
    if is_transient(text):
        reason = next(
            (line for line in lines if any(marker in line.lower() for marker in TRANSIENT_MARKERS)),
            "",
        )
        note = f"Kegagalan sementara terdeteksi ({reason or 'gangguan jaringan'}); percobaan ulang dijalankan."
        return 3, ["::warning::" + escape(note)] + ["::error::" + escape(line) for line in lines]
    return 1, ["::error::" + escape(line) for line in lines]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="berkas log Gradle")
    parser.add_argument(
        "--require-failure",
        action="store_true",
        help="anggap tidak adanya pesan sebagai kegagalan tak dikenal (exit 1)",
    )
    args = parser.parse_args(argv)

    try:
        text = args.log.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"::error::Tidak dapat membaca log Gradle {args.log}: {error}")
        return 1

    code, lines = report(text)
    for line in lines:
        print(line)
    if code == 0:
        if args.require_failure:
            print("::error::Gradle gagal tanpa pesan yang dapat dikenali. Lihat artifact log.")
            return 1
        return 0
    return code


if __name__ == "__main__":
    sys.exit(main())
