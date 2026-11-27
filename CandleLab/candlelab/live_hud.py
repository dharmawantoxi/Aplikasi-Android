"""Panel ringkas mode live: jendela utama hilang, chart tetap terlihat.

Panel ini bisa digeser bebas ke sudut lain. Selama satu kali pembacaan layar,
panel ikut disembunyikan sebentar agar tidak menutupi area chart, lalu muncul
lagi dengan angka terbaru.
"""
from __future__ import annotations

import tkinter as tk
from typing import Callable

from .hud_layout import HEIGHT, MARGIN, WIDTH, best_corner, corner_positions, next_corner
from .theme import DOJI, GREEN, GREEN_BRIGHT, LINE, MUTED, RED, SIDEBAR, format_percent


class LiveHud:
    """Jendela kecil always-on-top berisi probabilitas candle berikutnya."""

    def __init__(
        self,
        root: tk.Tk,
        on_stop: Callable[[], None],
        on_expand: Callable[[], None],
    ):
        self.root = root
        self.on_stop = on_stop
        self.on_expand = on_expand
        self.window = tk.Toplevel(root)
        self.window.title("CandleLab — Live")
        self.window.configure(bg=SIDEBAR)
        self.window.attributes("-topmost", True)
        self.window.resizable(False, False)
        self.window.protocol("WM_DELETE_WINDOW", self._stop)
        self.window.overrideredirect(True)

        self.header = tk.Frame(self.window, bg=SIDEBAR)
        self.header.pack(fill="x", padx=12, pady=(9, 0))
        self.title_label = tk.Label(
            self.header, text="● LIVE", bg=SIDEBAR, fg=GREEN_BRIGHT,
            font=("Segoe UI", 9, "bold"),
        )
        self.title_label.pack(side="left")
        self.status_label = tk.Label(
            self.header, text="menunggu…", bg=SIDEBAR, fg="#82998F",
            font=("Segoe UI", 8),
        )
        self.status_label.pack(side="left", padx=(8, 0))
        tk.Button(
            self.header, text="✕", command=self._stop, bg=SIDEBAR, fg=MUTED,
            activebackground=SIDEBAR, activeforeground="#FFFFFF", borderwidth=0,
            highlightthickness=0, font=("Segoe UI", 10, "bold"), cursor="hand2",
        ).pack(side="right")

        self.body = tk.Frame(self.window, bg=SIDEBAR)
        self.body.pack(fill="both", expand=True, padx=12, pady=(2, 8))
        self.headline = tk.Label(
            self.body, text="Data belum cukup", bg=SIDEBAR, fg="#FFFFFF",
            font=("Segoe UI", 17, "bold"), anchor="w",
        )
        self.headline.pack(fill="x")
        self.detail = tk.Label(
            self.body, text="", bg=SIDEBAR, fg="#B8C9C1", font=("Segoe UI", 8),
            anchor="w", justify="left", wraplength=WIDTH - 24,
        )
        self.detail.pack(fill="x", pady=(2, 0))

        self.actions = tk.Frame(self.window, bg=SIDEBAR)
        self.actions.pack(fill="x", padx=12, pady=(0, 9))
        self.corner_button = tk.Button(
            self.actions, text="⇄ Pindah sudut", command=self.cycle_corner, bg=SIDEBAR,
            fg="#B8C9C1", activebackground="#1C342C", activeforeground="#FFFFFF",
            borderwidth=0, highlightthickness=1, highlightbackground=LINE,
            font=("Segoe UI", 8), cursor="hand2", padx=8, pady=3,
        )
        self.corner_button.pack(side="right")
        self.expand_button = tk.Button(
            self.actions, text="Buka jendela", command=self._expand, bg=SIDEBAR,
            fg="#FFFFFF", activebackground="#1C342C", activeforeground="#FFFFFF",
            borderwidth=0, highlightthickness=1, highlightbackground=LINE,
            font=("Segoe UI", 8), cursor="hand2", padx=8, pady=3,
        )
        self.expand_button.pack(side="left")
        self.stop_button = tk.Button(
            self.actions, text="Stop live", command=self._stop, bg=SIDEBAR,
            fg=RED, activebackground="#1C342C", activeforeground=RED,
            borderwidth=0, highlightthickness=1, highlightbackground="#3A2B2C",
            font=("Segoe UI", 8), cursor="hand2", padx=8, pady=3,
        )
        self.stop_button.pack(side="left", padx=(8, 0))

        self._drag_origin = None
        for widget in (self.window, self.header, self.title_label, self.status_label,
                       self.body, self.headline, self.detail):
            widget.bind("<ButtonPress-1>", self._start_drag)
            widget.bind("<B1-Motion>", self._drag)
            widget.bind("<Double-Button-1>", lambda _event: self._expand())

        self.window.geometry(f"{WIDTH}x{HEIGHT}+{MARGIN}+{MARGIN}")
        self.window.update_idletasks()
        self.window.attributes("-toolwindow", True)

    # -- perilaku ---------------------------------------------------------
    def _stop(self):
        self.on_stop()

    def _expand(self):
        self.on_expand()

    def _start_drag(self, event):
        self._drag_origin = (event.x_root - self.window.winfo_x(),
                             event.y_root - self.window.winfo_y())

    def _drag(self, event):
        if not self._drag_origin:
            return
        x = max(0, event.x_root - self._drag_origin[0])
        y = max(0, event.y_root - self._drag_origin[1])
        self.window.geometry(f"+{x}+{y}")

    @staticmethod
    def best_corner(region, screen_w: int, screen_h: int) -> str:
        return best_corner(region, screen_w, screen_h)

    def move_to_corner(self, corner: str = "bottom-right"):
        """Tempatkan panel di sudut layar utama agar tidak menutupi chart."""
        self.window.update_idletasks()
        screen_w = self.window.winfo_screenwidth()
        screen_h = self.window.winfo_screenheight()
        positions = corner_positions(screen_w, screen_h)
        x, y = positions.get(corner, positions["bottom-right"])
        self._corner = corner if corner in positions else "bottom-right"
        self.window.geometry(f"+{max(0, x)}+{max(0, y)}")

    def cycle_corner(self):
        """Pindah ke sudut berikutnya agar candle tidak tertutup panel."""
        self._corner = next_corner(getattr(self, "_corner", "bottom-right"))
        self.move_to_corner(self._corner)

    def set_hidden(self, hidden: bool):
        """Sembunyikan sesaat selama piksel diambil."""
        try:
            self.window.attributes("-alpha", 0.0 if hidden else 1.0)
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
    def update(self, prediction, candle_count: int, forming_excluded: bool, warning: str = ""):
        if prediction is not None and prediction.available and prediction.green_probability is None:
            up = prediction.predicted_direction == GREEN
            self.headline.configure(text="HIJAU" if up else "MERAH", fg=GREEN_BRIGHT if up else RED)
            self.detail.configure(text=prediction.reason)
        elif prediction is not None and prediction.available:
            green = prediction.green_probability
            red = prediction.red_probability
            direction = prediction.predicted_direction
            label = "HIJAU" if direction == GREEN else "MERAH" if direction == RED else "SEIMBANG"
            colour = GREEN_BRIGHT if direction == GREEN else RED if direction == RED else DOJI
            self.headline.configure(
                text=f"{label}  {format_percent(green if direction == GREEN else red, 0)}",
                fg=colour,
            )
            thin = getattr(prediction, "thin_data", False)
            self.detail.configure(
                text=(
                    f"Hijau {format_percent(green, 1)}  •  Merah {format_percent(red, 1)}\n"
                    f"{candle_count} candle  •  pola {prediction.order}  •  "
                    f"{prediction.matches} kemunculan"
                    + ("\nDATA SEDIKIT — anggap perkiraan lemah" if thin else "")
                ),
                fg="#C9A227" if thin else "#B8C9C1",
            )
        else:
            reason = prediction.reason if prediction is not None else "Menunggu pembacaan."
            self.headline.configure(text="Data belum cukup", fg="#FFFFFF")
            self.detail.configure(text=f"{reason}\n{candle_count} candle dihitung", fg="#B8C9C1")
        if warning:
            self.status_label.configure(text="periksa area", fg="#F0C36B")
        else:
            self.status_label.configure(
                text="candle terbentuk diabaikan" if forming_excluded else "semua candle dipakai",
                fg="#82998F",
            )
