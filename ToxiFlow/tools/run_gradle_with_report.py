#!/usr/bin/env python3
"""Jalankan Gradle, unggah log sebagai anotasi, dan ulangi bila gangguan jaringan.

Contoh:
    python3 tools/run_gradle_with_report.py \
        --log "$RUNNER_TEMP/toxiflow-assemble.log" -- assembleDebug

Mengapa ada retry: unduhan wrapper/distribusi Gradle serta dependensi Maven
kadang terputus di runner (``EOF``/timeout), sehingga build yang sama bisa
gagal lalu sukses tanpa perubahan kode. Retry hanya dilakukan untuk penanda
jaringan tersebut; kegagalan kompilasi tetap langsung menggagalkan job.

Exit code akhir: 0 bila salah satu percobaan berhasil, selain itu exit code
Gradle terakhir (non-nol).
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import gradle_log_report as report  # noqa: E402

TRANSIENT_EXIT = 3


def run_once(args: list[str], cwd: Path, log: Path) -> int:
    """Jalankan Gradle sambil menyalin output ke log (streaming, bukan buffered)."""
    print(f"+ {' '.join(args)}", flush=True)
    with log.open("a", encoding="utf-8", errors="replace") as out:
        with subprocess.Popen(
            args, cwd=str(cwd), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            errors="replace",
        ) as process:
            assert process.stdout is not None
            for line in process.stdout:
                out.write(line)
                out.flush()
                print(line, end="", flush=True)
            return process.wait()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True, help="berkas tujuan log Gradle")
    parser.add_argument("--cwd", type=Path, default=Path.cwd(), help="folder proyek Gradle")
    parser.add_argument("--attempts", type=int, default=2, help="jumlah percobaan maksimum")
    parser.add_argument("gradle_args", nargs=argparse.REMAINDER, help="argumen setelah --")
    settings = parser.parse_args(argv)

    gradle_args = [arg for arg in settings.gradle_args if arg != "--"]
    if not gradle_args:
        parser.error("berikan tugas Gradle, contoh: -- assembleDebug")
    attempts = max(1, settings.attempts)
    gradlew = settings.cwd / "gradlew"
    command = ["bash", str(gradlew), *gradle_args, "--no-daemon", "--stacktrace", "--console=plain"]

    settings.log.parent.mkdir(parents=True, exist_ok=True)
    # Log bersifat append: keluaran setiap percobaan tetap tersimpan untuk diagnosis.
    settings.log.write_text("", encoding="utf-8")
    last_code = 1
    for attempt in range(1, attempts + 1):
        with settings.log.open("a", encoding="utf-8") as out:
            out.write(f"=== Percobaan {attempt}/{attempts}: {' '.join(command)} ===\n")
        exit_code = run_once(command, settings.cwd, settings.log)
        if exit_code == 0:
            print(f"Gradle berhasil pada percobaan {attempt}.")
            return 0

        try:
            output = settings.log.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            print(f"::error::Tidak dapat membaca log Gradle: {error}")
            return exit_code or 1

        code, lines = report.report(output)
        last_code = exit_code
        if code == TRANSIENT_EXIT and attempt < attempts:
            # Hanya peringatan singkat; rincian tetap ada di log percobaan ini.
            detail = next((line for line in lines if line.startswith("::warning::")), "")
            print(detail or f"::warning::Percobaan {attempt} gagal karena jaringan.")
            print(f"::warning::Mengulang Gradle ({attempt + 1}/{attempts}).")
            continue
        for line in lines:
            print(line)
        if code == 0:
            print("::error::Gradle gagal tanpa pesan yang dapat dikenali. Lihat artifact log.")
        return exit_code

    return last_code


if __name__ == "__main__":
    sys.exit(main())
