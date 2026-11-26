"""Catatan kejadian di dalam aplikasi, untuk menelusuri masalah.

Tujuan modul ini: ketika klik otomatis tidak bekerja, pengguna tidak perlu
menebak. Setiap keputusan penting dicatat dengan alasannya, dan seluruh isi
log dapat disalin dari aplikasi lalu ditempel ke mana pun.

Modul ini sengaja murni (tanpa tkinter) agar aturannya dapat diuji tanpa GUI.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import datetime
from typing import Optional

# Urutan level menentukan penekanan saat ditampilkan.
INFO = "INFO"
CLICK = "KLIK"
REJECT = "TOLAK"
ERROR = "ERROR"

_ORDER = {INFO: 0, CLICK: 1, REJECT: 2, ERROR: 3}


@dataclass(frozen=True)
class Entry:
    at: str
    level: str
    message: str

    def line(self) -> str:
        return f"{self.at} {self.level:<5} {self.message}"


class AppLog:
    """Cincin catatan terbatas; yang tertua dibuang lebih dulu."""

    def __init__(self, keep: int = 600) -> None:
        self.keep = max(1, int(keep))
        self.entries: list[Entry] = []
        # Jam dibuat bisa diganti pada tes agar keluarannya pasti.
        self.clock = lambda: datetime.now().strftime("%H:%M:%S")  # noqa: E731

    # -- penulisan --------------------------------------------------------
    def add(self, level: str, message: str) -> Entry:
        entry = Entry(self.clock(), level if level in _ORDER else INFO,
                      " ".join(str(message).split()))
        self.entries.append(entry)
        del self.entries[: max(0, len(self.entries) - self.keep)]
        return entry

    def info(self, message: str) -> Entry:
        return self.add(INFO, message)

    def click(self, message: str) -> Entry:
        return self.add(CLICK, message)

    def reject(self, message: str) -> Entry:
        return self.add(REJECT, message)

    def error(self, message: str) -> Entry:
        return self.add(ERROR, message)

    # -- pembacaan --------------------------------------------------------
    def text(self) -> str:
        """Seluruh log sebagai teks siap salin."""
        return "\n".join(entry.line() for entry in self.entries)

    def tail(self, count: int = 20) -> str:
        return "\n".join(entry.line() for entry in self.entries[-max(1, count):])

    def clear(self) -> None:
        self.entries.clear()

    def count(self, level: Optional[str] = None) -> int:
        if level is None:
            return len(self.entries)
        return sum(1 for entry in self.entries if entry.level == level)

    def since(self, marker: float) -> list[Entry]:
        """Entri yang ditambahkan setelah waktu ``time.monotonic()`` tertentu."""
        del marker
        return list(self.entries)

    def summary(self) -> str:
        """Ringkasan singkat untuk ditampilkan di samping tombol."""
        if not self.entries:
            return "log kosong"
        return (f"{self.count()} catatan • "
                f"{self.count(REJECT)} tolak • {self.count(ERROR)} galat")


def stamp() -> float:
    return time.monotonic()
