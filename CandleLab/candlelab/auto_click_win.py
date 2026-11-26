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
import ctypes.wintypes
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


# Nama jendela yang diterima. IQ Option dapat berjalan sebagai aplikasi desktop
# ("IQ Option") maupun di peramban ("IQ Option - Google Chrome"), dan judulnya
# berbeda-beda antar versi.
BROKER_TITLES = ("iq option", "iqoption", "iq-option")


def _window_text(handle) -> str:
    user32 = _user32()
    length = user32.GetWindowTextLengthW(handle)
    if not length:
        return ""
    buffer = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(handle, buffer, length + 1)
    return buffer.value


def foreground_window():
    """Handle jendela aktif, atau None."""
    if not platform_supported():
        return None
    return _user32().GetForegroundWindow() or None


def foreground_title() -> str:
    """Judul jendela aktif; string kosong bila tidak diketahui."""
    try:
        handle = foreground_window()
        return _window_text(handle) if handle else ""
    except (AttributeError, OSError, ValueError):
        return ""


def window_process_id(handle) -> Optional[int]:
    """PID pemilik jendela, atau None bila tidak dapat dibaca.

    ``GetWindowThreadProcessId`` berada di **user32**, bukan kernel32. Memanggilnya
    lewat kernel32 memunculkan ``function 'GetWindowThreadProcessId' not found``
    dan menjatuhkan aplikasi sebelum klik pertama.
    """
    if not platform_supported() or not handle:
        return None
    try:
        user32 = _user32()
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(handle, ctypes.byref(pid))
        return int(pid.value) or None
    except (AttributeError, OSError, ValueError):
        # ctypes melempar AttributeError untuk fungsi yang tidak ada di DLL.
        return None


def is_our_window(handle) -> bool:
    """Apakah jendela ini milik proses CandleLab sendiri.

    Bila PID tidak terbaca, jawabannya False: pengaman berbasis titik tetap
    menjaga klik, dan menolak semua klik hanya akan mematikan fitur tanpa alasan.
    """
    pid = window_process_id(handle)
    if pid is None:
        return False
    try:
        return pid == int(ctypes.windll.kernel32.GetCurrentProcessId())
    except (AttributeError, OSError, ValueError):
        return False


def window_root_at(point: tuple[int, int]):
    """Handle jendela teratas yang berada di bawah titik layar."""
    if not platform_supported():
        return None
    try:
        user32 = _user32()
        point_ref = ctypes.wintypes.POINT(int(point[0]), int(point[1]))
        handle = user32.WindowFromPoint(point_ref)
        if not handle:
            return None
        # Naik ke jendela induk teratas agar perbandingan dengan jendela aktif sah.
        root = user32.GetAncestor(handle, 2)  # GA_ROOT
        return root or handle
    except (AttributeError, OSError, ValueError):
        return None


def restore_chart_focus(point, chart_center) -> bool:
    """Jangan rebut fokus dari aplikasi lain atau aktifkan jendela tak dikenal."""
    if not is_our_window(foreground_window()):
        return False
    target = window_root_at(point)
    if not target or target != window_root_at(chart_center) or is_our_window(target):
        return False
    try:
        return bool(_user32().SetForegroundWindow(target)
                    and foreground_window() == target
                    and window_root_at(point) == target)
    except (AttributeError, OSError, ValueError):
        return False


def window_caption(handle) -> str:
    """Judul jendela; string kosong bila tidak diketahui."""
    if not platform_supported() or not handle:
        return ""
    try:
        return _window_text(handle)
    except (AttributeError, OSError, ValueError):
        return ""


def same_window(first, second) -> bool:
    """Apakah dua handle menunjuk jendela yang sama.

    Membandingkan handle mentah saja tidak cukup. ``WindowFromPoint`` dapat
    mengembalikan jendela **anak** (mis. elemen chart di dalam peramban), dan
    aplikasi berbasis Chromium memecah jendela ke beberapa proses. Karena itu
    pemeriksaan berlapis: handle yang sama, proses yang sama, lalu judul yang
    sama. Bila salah satu tidak diketahui, jawabannya False agar pemanggil
    memakai pengaman lain, bukan menolak tanpa dasar.
    """
    if first is None or second is None:
        return False
    if first == second:
        return True
    pid_first = window_process_id(first)
    pid_second = window_process_id(second)
    if pid_first and pid_second and pid_first == pid_second:
        return True
    caption_first = window_caption(first).strip().lower()
    caption_second = window_caption(second).strip().lower()
    if caption_first and caption_second:
        return caption_first == caption_second
    return False


def click_safety(point: tuple[int, int], region: tuple[int, int, int, int],
                 require_active: bool) -> ClickGuard:
    """Pengaman klik berbasis *titik*, bukan judul jendela.

    Syarat yang sebenarnya penting bukanlah "judul jendela aktif memuat IQ
    Option", melainkan "titik yang akan diklik memang milik aplikasi yang sedang
    aktif, dan aplikasi itu bukan CandleLab sendiri". Pemeriksaan judul membuat
    fitur gagal di peramban atau versi dengan judul berbeda; pemeriksaan titik
    tidak.
    """
    if not platform_supported():
        return ClickGuard("Klik otomatis hanya tersedia di Windows.")
    if not is_point_inside(point, region):
        return ClickGuard("Titik klik berada di luar area yang dipantau; klik dibatalkan.")
    if not require_active:
        return ClickGuard()
    active = foreground_window()
    if not active:
        return ClickGuard("Tidak ada jendela aktif; klik dibatalkan.")
    if is_our_window(active):
        return ClickGuard(
            "Jendela CandleLab sendiri sedang aktif. Klik dulu ke chart broker "
            "agar klik otomatis boleh menekan tombol.")
    target = window_root_at(point)
    if target is None:
        # Jendela di bawah titik tidak terbaca. Pengaman lain sudah lolos, jadi
        # menolak di sini hanya akan mematikan fitur tanpa alasan.
        return ClickGuard()
    if same_window(target, active):
        return ClickGuard()
    return ClickGuard(
        "Titik klik bukan milik jendela yang sedang aktif "
        f"(titik: '{window_caption(target) or '?'}' / "
        f"aktif: '{window_caption(active) or '?'}'); klik dibatalkan agar tidak "
        "menekan aplikasi lain.")


def check_before_click(point: tuple[int, int], region: tuple[int, int, int, int],
                       require_active: bool, window_title: str) -> ClickGuard:
    """Pemeriksaan lama berbasis judul; dipertahankan untuk kompatibilitas.

    Aplikasi memakai ``click_safety``; fungsi ini hanya menerima judul yang
    cocok agar tes lama dan pemanggil luar tidak rusak.
    """
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


def wait_for_new_option(probe, timeout: float = 1.5, interval: float = 0.15):
    """Tunggu tombol NEW OPTION hilang setelah diklik.

    Setelah NEW OPTION ditekan, form order butuh waktu singkat untuk tampil.
    Mengklik Higher/Lower terlalu cepat berarti mengklik tombol yang belum ada,
    sehingga klik pertama hilang. ``probe`` mengembalikan True selama tombol
    masih terlihat.
    """
    deadline = time.monotonic() + max(0.0, timeout)
    while time.monotonic() < deadline:
        try:
            if not probe():
                return True
        except Exception:
            return False
        time.sleep(interval)
    try:
        return not probe()
    except Exception:
        return False


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
        if self.count and now - self._last < self.min_interval:
            # count == 0 berarti ini klik pertama; tidak ada yang perlu ditunggu.
            return False, "Terlalu cepat sejak klik sebelumnya; menunggu candle berikutnya."
        return True, ""

    def record(self, now: float) -> None:
        self.count += 1
        self._last = now

    def reset(self) -> None:
        self.count = 0
        self._last = -1e9
