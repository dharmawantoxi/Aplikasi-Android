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
