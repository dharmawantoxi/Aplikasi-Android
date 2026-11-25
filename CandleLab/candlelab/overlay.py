"""Overlay tebakan di atas chart: badge kecil, bukan jendela aplikasi.

Tujuan mode ini: IQ Option tetap menjadi layar utama. CandleLab berjalan
diam-diam di latar belakang dan hanya menampilkan satu badge ringkas berisi
arah candle berikutnya. Badge disembunyikan sebentar setiap kali piksel
diambil agar tidak ikut terbaca.
"""
from __future__ import annotations

import tkinter as tk
from typing import Callable

from .hud_layout import MARGIN, badge_positions
from .theme import DOJI, FORECAST_COLORS, format_percent

BADGE_WIDTH = 176
BADGE_HEIGHT = 46
FONT = "Segoe UI"


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
        self.window = tk.Toplevel(root)
        self.window.overrideredirect(True)
        self.window.configure(bg="#0E1F19")
        self.window.attributes("-topmost", True)
        try:
            self.window.attributes("-alpha", 0.94)
        except tk.TclError:
            pass
        self.window.attributes("-toolwindow", True)

        self.frame = tk.Frame(self.window, bg="#0E1F19", highlightthickness=1,
                              highlightbackground="#25372F")
        self.frame.pack(fill="both", expand=True)

        self.headline = tk.Label(
            self.frame, text="…", bg="#0E1F19", fg="#FFFFFF",
            font=(FONT, 14, "bold"), anchor="w",
        )
        self.headline.pack(side="left", padx=(10, 6), pady=(6, 6))
        self.detail = tk.Label(
            self.frame, text="", bg="#0E1F19", fg="#8FA49A",
            font=(FONT, 7), anchor="w", justify="left",
        )
        self.detail.pack(side="left", fill="x", expand=True, pady=(6, 6))

        self.buttons = tk.Frame(self.frame, bg="#0E1F19")
        self.buttons.pack(side="right", padx=(0, 6))
        for text, command, colour in (
            ("⚙", self._expand, "#8FA49A"),
            ("✕", self._stop, "#D45E62"),
        ):
            tk.Button(
                self.buttons, text=text, command=command, bg="#0E1F19", fg=colour,
                activebackground="#1C342C", activeforeground="#FFFFFF",
                borderwidth=0, highlightthickness=0, font=(FONT, 9, "bold"),
                cursor="hand2", padx=3, pady=0,
            ).pack(side="top")

        self._drag_origin = None
        for widget in (self.frame, self.headline, self.detail):
            widget.bind("<ButtonPress-1>", self._start_drag)
            widget.bind("<B1-Motion>", self._drag)
            widget.bind("<Button-3>", lambda _event: self.cycle_corner())
            widget.bind("<Double-Button-1>", lambda _event: self._expand())

        self.window.geometry(f"{BADGE_WIDTH}x{BADGE_HEIGHT}")
        self.window.update_idletasks()

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
        x = max(0, event.x_root - self._drag_origin[0])
        y = max(0, event.y_root - self._drag_origin[1])
        self.window.geometry(f"+{x}+{y}")

    def cycle_corner(self):
        from .hud_layout import BADGE_CORNERS
        try:
            index = BADGE_CORNERS.index(self.corner)
        except ValueError:
            index = 0
        self.place(BADGE_CORNERS[(index + 1) % len(BADGE_CORNERS)])

    def place(self, corner: str, margins=None):
        self.window.update_idletasks()
        screen_w = self.window.winfo_screenwidth()
        screen_h = self.window.winfo_screenheight()
        positions = badge_positions(screen_w, screen_h, BADGE_WIDTH, BADGE_HEIGHT,
                                    margins or (MARGIN, 90, MARGIN, 110))
        x, y = positions.get(corner, positions["top-right"])
        self.corner = corner if corner in positions else "top-right"
        self.window.geometry(f"{BADGE_WIDTH}x{BADGE_HEIGHT}+{max(0, x)}+{max(0, y)}")

    def set_hidden(self, hidden: bool):
        try:
            self.window.attributes("-alpha", 0.0 if hidden else 0.94)
        except tk.TclError:
            if hidden:
                self.window.withdraw()
            else:
                self.window.deiconify()

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
                    fg="#C9A227",
                )
            else:
                self.headline.configure(text=f"{label} {format_percent(confidence, 0)}", fg=colour)
                note = warning or f"{candle_count} candle"
                self.detail.configure(text=f"next\n{note}", fg="#8FA49A")
        else:
            reason = prediction.reason if prediction is not None else "Menunggu pembacaan"
            self.headline.configure(text="Belum cukup", fg="#C9D6CF")
            self.detail.configure(text=f"next\n{reason}")
