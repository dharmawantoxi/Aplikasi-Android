"""Tangkapan layar lokal. Pillow hanya dimuat saat fitur dipakai."""
from __future__ import annotations

import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from PIL import Image


def capture_region(box: tuple[int, int, int, int]) -> "Image.Image":
    try:
        from PIL import ImageGrab
    except ImportError as exc:
        raise RuntimeError(
            "Fitur baca layar memerlukan Pillow. Jalankan: python -m pip install -r requirements.txt"
        ) from exc
    x1, y1, x2, y2 = (int(value) for value in box)
    if x2 - x1 < 20 or y2 - y1 < 20:
        raise ValueError("Area tangkapan minimal 20 × 20 piksel.")
    try:
        # all_screens penting untuk susunan multi-monitor di Windows.
        return ImageGrab.grab(bbox=(x1, y1, x2, y2), all_screens=True)
    except Exception as exc:
        raise RuntimeError(
            "Layar tidak dapat ditangkap. Di macOS, berikan izin Screen Recording "
            "ke Python/CandleLab. Di Linux Wayland, pembatasan sistem dapat mencegah tangkapan."
        ) from exc


def capture_after_overlay_closed(box: tuple[int, int, int, int]) -> "Image.Image":
    # Beri window manager waktu menghapus overlay dari layar.
    time.sleep(0.22)
    return capture_region(box)
