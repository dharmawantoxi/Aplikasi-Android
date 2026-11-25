"""Eksekusi klik pada layar Windows, dengan pengaman berlapis.

Klik otomatis adalah fitur paling berisiko di aplikasi ini: satu klik yang
salah sasaran dapat menekan tombol lain di broker. Karena itu semua pengaman
di bawah bersifat wajib, bukan opsional.

Pengaman:
1. Hanya berjalan di Windows (butuh ``SendInput`` lewat ctypes).
2. Menolak bila posisi kursor tidak berada di area yang dipantau.
3. Menolak bila jendela broker tidak sedang aktif (fokus).
4. Memakai event klik sungguhan (turun + naik) di satu titik, bukan gerakan.
"""
from __future__ import annotations

import ctypes
import sys
import time
from dataclasses import dataclass
from typing import Optional

MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004


@dataclass
class ClickGuard:
    """Alasan klik ditolak, atau string kosong bila aman."""

    reason: str = ""

    @property
    def allowed(self) -> bool:
        return not self.reason


def platform_supported() -> bool:
    return sys.platform == "win32"


def _user32():
    return ctypes.windll.user32


def is_window_active(title_part: str) -> bool:
    """Apakah jendela aktif memuat judul tertentu (mis. 'IQ Option')."""
    if not platform_supported():
        return False
    user32 = _user32()
    handle = user32.GetForegroundWindow()
    if not handle:
        return False
    length = user32.GetWindowTextLengthW(handle)
    if not length:
        return False
    buffer = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(handle, buffer, length + 1)
    return title_part.lower() in buffer.value.lower()


def is_point_inside(point: tuple[int, int], region: tuple[int, int, int, int]) -> bool:
    x, y = point
    x1, y1, x2, y2 = region
    return x1 <= x <= x2 and y1 <= y <= y2


def cursor_position() -> Optional[tuple[int, int]]:
    if not platform_supported():
        return None
    point = ctypes.wintypes.POINT()
    if not _user32().GetCursorPos(ctypes.byref(point)):
        return None
    return point.x, point.y


def check_before_click(point: tuple[int, int], region: tuple[int, int, int, int],
                       require_active: bool, window_title: str) -> ClickGuard:
    """Semua pemeriksaan sebelum klik. Mengembalikan ClickGuard."""
    if not platform_supported():
        return ClickGuard("Klik otomatis hanya tersedia di Windows.")
    if not is_point_inside(point, region):
        return ClickGuard("Titik klik berada di luar area yang dipantau; klik dibatalkan.")
    if require_active and not is_window_active(window_title):
        return ClickGuard(
            f"Jendela '{window_title}' tidak sedang aktif; klik dibatalkan agar tidak "
            "menekan aplikasi lain.")
    return ClickGuard()


def left_click(point: tuple[int, int], settle: float = 0.02) -> None:
    """Klik kiri di satu titik. Tidak menggerakkan kursor lebih dari perlu."""
    if not platform_supported():
        raise RuntimeError("Klik otomatis hanya tersedia di Windows.")
    user32 = _user32()
    user32.SetCursorPos(int(point[0]), int(point[1]))
    time.sleep(settle)
    user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    time.sleep(settle)
    user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)


class ClickLimiter:
    """Batas jumlah klik agar kesalahan deteksi tidak berulang-ulang.

    ``min_interval`` mencegah dua klik dalam satu candle; ``max_per_session``
    menghentikan sesi sebelum kerugian menumpuk tanpa disadari.
    """

    def __init__(self, min_interval: float = 20.0, max_per_session: int = 50) -> None:
        self.min_interval = min_interval
        self.max_per_session = max_per_session
        self.count = 0
        self._last = -1e9

    def allow(self, now: float) -> tuple[bool, str]:
        if self.count >= self.max_per_session:
            return False, (f"Batas {self.max_per_session} klik per sesi tercapai. "
                           "Mulai sesi baru untuk melanjutkan.")
        if now - self._last < self.min_interval:
            return False, "Terlalu cepat sejak klik sebelumnya; menunggu candle berikutnya."
        return True, ""

    def record(self, now: float) -> None:
        self.count += 1
        self._last = now

    def reset(self) -> None:
        self.count = 0
        self._last = -1e9
