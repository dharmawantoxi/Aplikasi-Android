"""Perhitungan tata letak panel live, tanpa ketergantungan Tkinter.

Dipisah dari ``live_hud`` agar aturan "jangan menutupi area chart" dapat diuji
di lingkungan tanpa layar (CI headless).
"""
from __future__ import annotations

WIDTH = 300
HEIGHT = 132
MARGIN = 16
TASKBAR_ALLOWANCE = 80

CORNERS = ("bottom-right", "top-right", "top-left", "bottom-left")


def corner_positions(screen_w: int, screen_h: int) -> dict[str, tuple[int, int]]:
    right = max(0, screen_w - WIDTH - MARGIN)
    bottom = max(0, screen_h - HEIGHT - TASKBAR_ALLOWANCE)
    return {
        "top-left": (MARGIN, MARGIN),
        "top-right": (right, MARGIN),
        "bottom-left": (MARGIN, bottom),
        "bottom-right": (right, bottom),
    }


def overlaps(region, x: int, y: int, width: int = WIDTH, height: int = HEIGHT) -> bool:
    x1, y1, x2, y2 = region
    return not (x + width <= x1 or x >= x2 or y + height <= y1 or y >= y2)


def best_corner(region, screen_w: int, screen_h: int) -> str:
    """Sudut yang tidak menutupi chart; bila semua menutupi, pilih paling kecil."""
    positions = corner_positions(screen_w, screen_h)
    if not region:
        return "bottom-right"
    free = [name for name, (x, y) in positions.items() if not overlaps(region, x, y)]
    if free:
        # Di antara sudut yang bebas, pilih yang paling jauh dari chart agar
        # panel tidak menempel pada candle terdekat.
        return max(free, key=lambda name: (_distance(region, *positions[name]), name))
    return _least_overlap(region, positions)


def _distance(region, x: int, y: int) -> float:
    """Jarak antara pusat panel dan pusat area chart."""
    x1, y1, x2, y2 = region
    chart_x = (x1 + x2) / 2
    chart_y = (y1 + y2) / 2
    panel_x = x + WIDTH / 2
    panel_y = y + HEIGHT / 2
    return ((chart_x - panel_x) ** 2 + (chart_y - panel_y) ** 2) ** 0.5


def _overlap_area(region, x: int, y: int) -> int:
    x1, y1, x2, y2 = region
    width = max(0, min(x + WIDTH, x2) - max(x, x1))
    height = max(0, min(y + HEIGHT, y2) - max(y, y1))
    return width * height


def _least_overlap(region, positions: dict[str, tuple[int, int]]) -> str:
    return min(positions, key=lambda name: (_overlap_area(region, *positions[name]), name))


def next_corner(corner: str) -> str:
    try:
        return CORNERS[(CORNERS.index(corner) + 1) % len(CORNERS)]
    except ValueError:
        return CORNERS[0]


BADGE_CORNERS = ("top-right", "bottom-right", "top-left", "bottom-left")


def badge_positions(screen_w: int, screen_h: int, width: int, height: int,
                    margins=(MARGIN, 90, MARGIN, 110)) -> dict[str, tuple[int, int]]:
    """Empat sudut untuk badge kecil. ``margins`` = (kiri, atas, kanan, bawah).

    Margin atas/bawah lebih besar agar badge tidak menutupi toolbar chart atau
    baris tombol broker.
    """
    left_m, top_m, right_m, bottom_m = margins
    right = max(0, screen_w - width - right_m)
    bottom = max(0, screen_h - height - bottom_m)
    return {
        "top-left": (left_m, top_m),
        "top-right": (right, top_m),
        "bottom-left": (left_m, bottom),
        "bottom-right": (right, bottom),
    }


def best_badge_corner(region, screen_w: int, screen_h: int,
                      width: int, height: int) -> str:
    """Sudut badge yang tidak menutupi area chart; utamakan pojok atas."""
    positions = badge_positions(screen_w, screen_h, width, height)
    if not region:
        return "top-right"
    free = [name for name in BADGE_CORNERS
            if not overlaps(region, *positions[name], width, height)]
    if not free:
        return min(positions, key=lambda name: (_overlap_area(region, *positions[name]), name))
    return max(free, key=lambda name: (_distance(region, *positions[name]), name))


# -- badge overlay ---------------------------------------------------------

BADGE_PAD_X = 10
BADGE_BUTTON_W = 22

# Lebar karakter relatif terhadap tinggi font (Segoe UI).
_CHAR_WIDTH = 0.60
_CHAR_WIDTH_BOLD = 0.66


def estimate_badge_width(headline: str, detail: str, line_height: int,
                         button_width: int = BADGE_BUTTON_W) -> int:
    """Perkiraan lebar badge dari isi teksnya.

    Dihitung aditif seperti cara Tk memaketkan widget: headline (kiri) +
    detail (tengah) + tombol (kanan) + padding. Dipakai sebagai batas bawah
    sebelum jendela dipetakan; setelah itu lebar sebenarnya diambil dari
    ``winfo_reqwidth``.
    """
    headline_len = len(headline)
    detail_len = max((len(line) for line in detail.splitlines()), default=0)
    headline_w = headline_len * line_height * _CHAR_WIDTH_BOLD
    detail_w = detail_len * line_height * _CHAR_WIDTH
    pad_left, pad_mid, pad_right = 10, 8, 6
    return int(headline_w + detail_w + pad_left + pad_mid + pad_right
               + button_width + pad_right + 2)


def clamp_position(x: int, y: int, screen_w: int, screen_h: int,
                   width: int, height: int, margin: int = 4) -> tuple[int, int]:
    """Jepit posisi agar badge selalu utuh di dalam layar.

    Perlindungan terhadap monitor yang lebih kecil dari layar utama, taskbar,
    dan posisi hasil seret pengguna.
    """
    max_x = max(margin, screen_w - width - margin)
    max_y = max(margin, screen_h - height - margin)
    return min(max(margin, int(x)), max_x), min(max(margin, int(y)), max_y)


def fit_size(desired_w: int, desired_h: int, screen_w: int, screen_h: int,
             min_w: int = 120, min_h: int = 30) -> tuple[int, int]:
    """Batasi ukuran badge agar tidak melebihi layar.

    Isi teks diutamakan: bila layar dilaporkan sangat kecil (sesi remote atau
    runner CI), batas layar diabaikan supaya teks tetap utuh. Lebih baik badge
    sedikit keluar layar daripada menampilkan teks terpotong.
    """
    width, height = max(min_w, int(desired_w)), max(min_h, int(desired_h))
    if screen_w >= 640:
        width = max(min_w, min(width, screen_w - 8))
    if screen_h >= 480:
        height = max(min_h, min(height, screen_h - 8))
    return width, height
