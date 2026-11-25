"""Mengenali tombol Higher/Lower pada layar broker.

Tujuan modul ini bukan sekadar mencari kotak hijau/merah: klik otomatis yang
salah sasaran bisa menekan tombol yang tidak diinginkan. Karena itu tombol
hanya dikenali bila memenuhi syarat bentuk yang ketat, dan area pencarian harus
ditunjuk sendiri oleh pengguna.

Modul ini murni (tanpa tkinter maupun pengambilan layar) agar aturannya dapat
diuji tanpa GUI.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

# Tombol broker jauh lebih besar daripada candle: pada skala 125% di IQ Option
# sekitar 90 x 120 piksel. Batas bawah dibuat longgar agar skala lain tetap
# terbaca, tetapi tetap jauh di atas ukuran body candle biasa.
MIN_BUTTON_WIDTH = 40
MIN_BUTTON_HEIGHT = 40
# Tombol hampir selalu lebih lebar daripada tinggi, sedangkan candle sebaliknya.
MIN_ASPECT = 0.55
MAX_ASPECT = 2.5
# Proporsi piksel berwarna di dalam kotak; tombol padat, candle berongga.
MIN_FILL_RATIO = 0.55


@dataclass(frozen=True)
class ButtonCandidate:
    direction: int          # 1 = Higher/hijau, -1 = Lower/merah
    box: tuple[int, int, int, int]
    fill_ratio: float

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.box
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def area(self) -> int:
        x1, y1, x2, y2 = self.box
        return max(0, x2 - x1) * max(0, y2 - y1)


def _largest_components(mask: bytearray, width: int, height: int, direction: int,
                        limit: int = 6) -> list[ButtonCandidate]:
    """Cari blok warna besar (bukan candle) dengan flood-fill sederhana."""
    visited = bytearray(mask)
    found: list[ButtonCandidate] = []
    for start in range(width * height):
        if not visited[start]:
            continue
        visited[start] = 0
        stack = [start]
        min_x = max_x = start % width
        min_y = max_y = start // width
        count = 0
        while stack:
            index = stack.pop()
            x, y = index % width, index // width
            count += 1
            if x < min_x:
                min_x = x
            elif x > max_x:
                max_x = x
            if y < min_y:
                min_y = y
            elif y > max_y:
                max_y = y
            for dy in (-1, 0, 1):
                ny = y + dy
                if ny < 0 or ny >= height:
                    continue
                base = ny * width
                for dx in (-1, 0, 1):
                    nx = x + dx
                    if nx < 0 or nx >= width or (dx == 0 and dy == 0):
                        continue
                    neighbour = base + nx
                    if visited[neighbour]:
                        visited[neighbour] = 0
                        stack.append(neighbour)
        box_width = max_x - min_x + 1
        box_height = max_y - min_y + 1
        area = box_width * box_height
        if area <= 0:
            continue
        found.append(ButtonCandidate(direction, (min_x, min_y, max_x, max_y), count / area))
    found.sort(key=lambda item: item.area, reverse=True)
    return found[:limit]


def find_buttons(image) -> list[ButtonCandidate]:
    """Kembalikan kandidat tombol Higher (hijau) dan Lower (merah)."""
    from .vision import colour_masks

    width, height = image.size
    if width < MIN_BUTTON_WIDTH or height < MIN_BUTTON_HEIGHT:
        return []
    green, red = colour_masks(image)
    candidates: list[ButtonCandidate] = []
    for mask, direction in ((green, 1), (red, -1)):
        for item in _largest_components(mask, width, height, direction):
            x1, y1, x2, y2 = item.box
            box_width = x2 - x1 + 1
            box_height = y2 - y1 + 1
            if box_width < MIN_BUTTON_WIDTH or box_height < MIN_BUTTON_HEIGHT:
                continue
            aspect = box_width / box_height
            if not MIN_ASPECT <= aspect <= MAX_ASPECT:
                continue
            if item.fill_ratio < MIN_FILL_RATIO:
                continue
            candidates.append(item)
    return candidates


@dataclass(frozen=True)
class ButtonPair:
    """Pasangan tombol Higher dan Lower yang bisa dipakai klik otomatis."""

    higher: tuple[int, int]
    lower: tuple[int, int]
    spread: int  # jarak vertikal antar-pusat, untuk pemeriksaan kewajaran


def find_button_pair(image) -> tuple[Optional[ButtonPair], str]:
    """Cari satu pasang tombol Higher/Lower; kembalikan (pasangan, alasan gagal)."""
    candidates = find_buttons(image)
    greens = [item for item in candidates if item.direction == 1]
    reds = [item for item in candidates if item.direction == -1]
    if not greens or not reds:
        return None, ("Tombol Higher/Lower tidak dikenali. Pilih area yang memuat "
                      "kedua tombol broker dengan jelas.")
    # Ambil kandidat terbesar dari masing-masing warna.
    higher = max(greens, key=lambda item: item.area)
    lower = max(reds, key=lambda item: item.area)
    hx, hy = higher.center
    lx, ly = lower.center
    # Higher dan Lower pada broker selalu bertumpuk: sejajar secara horizontal.
    if abs(hx - lx) > max(higher.box[2] - higher.box[0], 1):
        return None, "Tombol Higher dan Lower tidak sejajar; area kemungkinan salah."
    spread = abs(ly - hy)
    if spread < MIN_BUTTON_HEIGHT // 2:
        return None, "Tombol Higher dan Lower terlalu berdekatan; area kemungkinan salah."
    return ButtonPair((hx, hy), (lx, ly), spread), ""


def click_point(image_size: tuple[int, int], offset: tuple[int, int],
                pair: ButtonPair, direction: int) -> tuple[int, int]:
    """Titik klik di layar (koordinat absolut) untuk arah tertentu."""
    x, y = pair.higher if direction == 1 else pair.lower
    return offset[0] + x, offset[1] + y
