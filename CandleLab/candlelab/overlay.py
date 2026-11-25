"""Overlay tebakan di atas chart: badge kecil, bukan jendela aplikasi.

Tujuan mode ini: IQ Option tetap menjadi layar utama. CandleLab berjalan
diam-diam di latar belakang dan hanya menampilkan satu badge ringkas berisi
arah candle berikutnya. Badge disembunyikan sebentar setiap kali piksel
diambil agar tidak ikut terbaca.

Lebar badge dihitung dari isi teksnya (``fit_size``), bukan dipaku pada angka
tetap. Versi sebelumnya memakai lebar tetap sehingga teks seperti
"perkiraan lemah" terpotong di tengah.
"""
from __future__ import annotations

import tkinter as tk
from typing import Callable

from .hud_layout import (
    MARGIN,
    BADGE_PAD_X,
    badge_positions,
    clamp_position,
    estimate_badge_width,
    fit_size,
)
from .model import MIN_CANDLES
from .theme import DOJI, FORECAST_COLORS, format_percent

BADGE_WIDTH = 176  # lebar minimum; ukuran sebenarnya mengikuti isi
BADGE_HEIGHT = 46
FONT = "Segoe UI"
BG = "#0E1F19"
BORDER = "#25372F"
DETAIL_FG = "#8FA49A"
WARN_FG = "#E6B84C"


class OverlayBadge:
    """Badge always-on-top: 'HIJAU 67%' beserta opsi mini."""

    def __init__(
        self,
        root: tk.Tk,
        on_expand: Callable[[], None],
        on_stop: Callable[[], None],
    ):
        self.root = root
        self.on_expand = on_expand
        self.on_stop = on_stop
        self.corner = "top-right"
        self.width = BADGE_WIDTH
        self.height = BADGE_HEIGHT
        self._alpha = 0.94

        self.window = tk.Toplevel(root)
        self.window.overrideredirect(True)
        self.window.configure(bg=BG)
        self.window.attributes("-topmost", True)
        self._apply_alpha(self._alpha)
        self.window.attributes("-toolwindow", True)

        self.frame = tk.Frame(self.window, bg=BG, highlightthickness=1,
                              highlightbackground=BORDER)
        self.frame.pack(fill="both", expand=True)

        self.headline = tk.Label(
            self.frame, text="…", bg=BG, fg="#FFFFFF",
            font=(FONT, 14, "bold"), anchor="w",
        )
        self.headline.pack(side="left", padx=(10, 8), pady=(4, 4))

        self.detail = tk.Label(
            self.frame, text="", bg=BG, fg=DETAIL_FG,
            font=(FONT, 7), anchor="w", justify="left",
        )
        self.detail.pack(side="left", pady=(4, 4))

        # Tombol diletakkan di kanan dan tidak boleh ikut terdesak: tanpa
        # bingkai tetap, tombol gear sempat hilang saat badge terlalu sempit.
        self.buttons = tk.Frame(self.frame, bg=BG, width=BADGE_PAD_X * 2)
        self.buttons.pack(side="right", padx=(6, 6), pady=(2, 2))
        for text, command, colour in (
            ("⚙", self._expand, DETAIL_FG),
            ("✕", self._stop, "#D45E62"),
        ):
            tk.Button(
                self.buttons, text=text, command=command, bg=BG, fg=colour,
                activebackground="#1C342C", activeforeground="#FFFFFF",
                borderwidth=0, highlightthickness=0, font=(FONT, 8, "bold"),
                cursor="hand2", padx=2, pady=0,
            ).pack(side="left")

        self._drag_origin = None
        for widget in (self.frame, self.headline, self.detail, self.buttons):
            widget.bind("<ButtonPress-1>", self._start_drag)
            widget.bind("<B1-Motion>", self._drag)
            widget.bind("<Button-3>", lambda _event: self.cycle_corner())
            widget.bind("<Double-Button-1>", lambda _event: self._expand())

        self._apply_geometry()

    # -- ukuran & posisi ---------------------------------------------------
    def _apply_alpha(self, value: float):
        self._alpha = value
        try:
            self.window.attributes("-alpha", value)
        except tk.TclError:
            # Sebagian lingkungan Windows tidak mendukung alpha; sembunyikan
            # jendela sepenuhnya saat piksel dibaca.
            if value <= 0:
                self.window.withdraw()
            else:
                self.window.deiconify()

    def _screen(self) -> tuple[int, int]:
        return self.window.winfo_screenwidth(), self.window.winfo_screenheight()

    def _fit_to_content(self):
        """Sesuaikan ukuran jendela dengan teks yang sedang ditampilkan."""
        self.window.update_idletasks()
        screen_w, screen_h = self._screen()
        wanted_w = max(self.frame.winfo_reqwidth(), self._estimated_width() + 8)
        wanted_h = max(self.frame.winfo_reqheight(), BADGE_HEIGHT)
        self.width, self.height = fit_size(wanted_w, wanted_h, screen_w, screen_h)

    def _estimated_width(self) -> int:
        return estimate_badge_width(
            self.headline.cget("text"),
            self.detail.cget("text"),
            line_height=14,
        )

    def _apply_geometry(self):
        self._fit_to_content()
        x, y = self.window.winfo_x(), self.window.winfo_y()
        if x <= 1 and y <= 1:
            # Belum pernah ditempatkan: pakai sudut default.
            self.place(self.corner, update=False)
            return
        self._move(x, y)

    def _move(self, x: int, y: int):
        screen_w, screen_h = self._screen()
        x, y = clamp_position(x, y, screen_w, screen_h, self.width, self.height)
        self.window.geometry(f"{self.width}x{self.height}+{x}+{y}")

    # -- aksi -------------------------------------------------------------
    def _expand(self):
        self.on_expand()

    def _stop(self):
        self.on_stop()

    def _start_drag(self, event):
        self._drag_origin = (event.x_root - self.window.winfo_x(),
                             event.y_root - self.window.winfo_y())

    def _drag(self, event):
        if not self._drag_origin:
            return
        self._move(event.x_root - self._drag_origin[0],
                   event.y_root - self._drag_origin[1])

    def cycle_corner(self):
        from .hud_layout import BADGE_CORNERS
        try:
            index = BADGE_CORNERS.index(self.corner)
        except ValueError:
            index = 0
        self.place(BADGE_CORNERS[(index + 1) % len(BADGE_CORNERS)])

    def place(self, corner: str, margins=None, update: bool = True):
        if update:
            self._fit_to_content()
        screen_w, screen_h = self._screen()
        positions = badge_positions(screen_w, screen_h, self.width, self.height,
                                    margins or (MARGIN, 90, MARGIN, 110))
        self.corner = corner if corner in positions else "top-right"
        x, y = positions[self.corner]
        x, y = clamp_position(x, y, screen_w, screen_h, self.width, self.height)
        self.window.geometry(f"{self.width}x{self.height}+{x}+{y}")

    def set_hidden(self, hidden: bool):
        self._apply_alpha(0.0 if hidden else 0.94)

    def close(self):
        try:
            self.window.destroy()
        except tk.TclError:
            pass

    # -- isi --------------------------------------------------------------
    def show_prediction(self, prediction, candle_count: int, warning: str = ""):
        if prediction is not None and prediction.available:
            direction = prediction.predicted_direction
            if direction == 1:  # model.GREEN
                label, confidence = "HIJAU", prediction.green_probability
            elif direction == -1:  # model.RED
                label, confidence = "MERAH", prediction.red_probability
            else:
                label, confidence = "SEIMBANG", 0.5
            colour = FORECAST_COLORS.get(direction, DOJI)
            if getattr(prediction, "thin_data", False):
                # Data sedikit: arah saja, tanpa persen yang menyesatkan.
                self.headline.configure(text=label, fg=colour)
                self.detail.configure(
                    text=f"next · data {candle_count}\nperkiraan lemah",
                    fg=WARN_FG,
                )
            else:
                self.headline.configure(text=f"{label} {format_percent(confidence, 0)}", fg=colour)
                note = warning or f"{candle_count} candle"
                self.detail.configure(text=f"next\n{note}", fg=DETAIL_FG)
        else:
            # Alasan lengkap tidak muat di badge; rinciannya ada di jendela
            # penuh. Badge cukup menyebut apa yang kurang.
            if prediction is not None and prediction.reason:
                detail = f"next\nbutuh {MIN_CANDLES} candle"
            else:
                detail = "next\nmenunggu pembacaan"
            self.headline.configure(text="Belum cukup", fg="#C9D6CF")
            self.detail.configure(text=detail, fg=DETAIL_FG)
        self._apply_geometry()
