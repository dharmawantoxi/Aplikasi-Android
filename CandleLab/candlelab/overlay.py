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

import sys
import time
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
# Tombol klik otomatis: redup saat mati, jingga menyala saat aktif.
CLICK_ON_FG = "#FF6B35"
CLICK_OFF_FG = "#5E736B"


def _short(text: str, limit: int = 34) -> str:
    """Potong alasan panjang agar badge tetap kecil; penjelasan ada di jendela."""
    clean = " ".join(text.split())
    clean = clean.replace("KLIK DITOLAK • ", "")
    if len(clean) <= limit:
        return clean
    return clean[: limit - 1].rstrip() + "…"


class OverlayBadge:
    """Badge always-on-top: 'HIJAU 67%' beserta opsi mini."""

    WIN32_EX_NOACTIVATE = 0x08000000

    def __init__(
        self,
        root: tk.Tk,
        on_expand: Callable[[], None],
        on_stop: Callable[[], None],
        on_auto_click: Callable[[], None] | None = None,
    ):
        self.root = root
        self.on_expand = on_expand
        self.on_stop = on_stop
        self.on_auto_click = on_auto_click
        self.auto_click_on = False
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
        # Badge tidak boleh merebut fokus. Tanpa ini, jendela broker kehilangan
        # fokus setiap badge tampil, dan pengaman klik menolak kliknya sendiri.
        try:
            self.window.attributes("-disabled", False)
            self.window.wm_attributes("-topmost", True)
            self._make_no_activate(self.window)
        except tk.TclError:
            pass

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
            # ⚡ mulai tanpa warna: warna hanya menandakan "sedang aktif".
            ("⚡", self._toggle_click, CLICK_OFF_FG),
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
    def _make_no_activate(self, window):
        """Tambahkan WS_EX_NOACTIVATE agar klik pada badge tidak membajak fokus.

        Windows: badge tetap menerima klik, tetapi jendela broker tetap menjadi
        jendela aktif. Di sistem lain fungsi ini tidak melakukan apa pun.
        """
        if sys.platform != "win32":
            return
        try:
            import ctypes
            window.update_idletasks()
            hwnd = ctypes.windll.user32.GetParent(window.winfo_id())
            if not hwnd:
                hwnd = window.winfo_id()
            GWL_EXSTYLE = -20
            style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            ctypes.windll.user32.SetWindowLongW(
                hwnd, GWL_EXSTYLE, style | self.WIN32_EX_NOACTIVATE)
        except Exception:
            # Fitur ini menambah kenyamanan, bukan syarat keamanan; bila gagal,
            # badge tetap berfungsi dan pengaman klik tetap menolak dengan jujur.
            pass

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
    def _toggle_click(self):
        if self.on_auto_click is not None:
            self.on_auto_click()

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
    def set_auto_click_state(self, enabled: bool):
        """Tandai status klik otomatis pada tombol ⚡."""
        self.auto_click_on = bool(enabled)
        try:
            self.buttons.winfo_children()[0].configure(
                fg=CLICK_ON_FG if enabled else CLICK_OFF_FG)
        except (IndexError, tk.TclError):
            pass

    def show_round(self, round_obj, stats, candle_count: int, pending: bool = True,
                   seconds_left=None, next_in=None, timeframe="?",
                   click_note: str = "", waiting_note: str = ""):
        """Tampilkan tebakan yang sedang berjalan beserta skor benar/salah.

        ``round_obj`` adalah ``rounds.Round``; ``stats`` adalah
        ``rounds.RoundStats``. Bila ``round_obj`` punya hasil, badge menampilkan
        apakah tebakan itu benar atau salah.
        """
        if round_obj is None:
            kurang = max(0, 5 - candle_count)
            self.headline.configure(text="Belum cukup" if kurang else "Menunggu sinyal", fg="#C9D6CF")
            pesan = f"perlu {kurang} candle lagi" if kurang else waiting_note or "menunggu awal menit"
            self.detail.configure(text=pesan, fg=DETAIL_FG)
            self._apply_geometry()
            return

        direction = round_obj.direction
        label = "HIJAU" if direction == 1 else "MERAH" if direction == -1 else "SEIMBANG"
        colour = FORECAST_COLORS.get(direction, DOJI)

        if round_obj.resolved:
            # Candle sudah selesai: ini bukti, bukan lagi tebakan.
            if round_obj.correct is None:
                detail, tint = "tebakan tadi\ntidak dinilai", DETAIL_FG
            elif round_obj.correct:
                detail, tint = f"tebakan tadi BENAR\nskor {stats.wins}/{stats.played}", "#3FE08A"
            else:
                detail, tint = f"tebakan tadi SALAH\nskor {stats.wins}/{stats.played}", "#FF8A7A"
        else:
            # Tanpa penanda waktu, badge tampak diam selama satu candle penuh
            # dan pengguna mengira aplikasi berhenti.
            tick = "." * (1 + int(time.monotonic() * 2) % 3)
            # Sisa waktu ke candle berikutnya adalah saat entry 1 menit masuk
            # akal; tanpa itu badge hanya diam tanpa memberi waktu berguna.
            if next_in is not None and next_in >= 0:
                ketiga = f"candle baru {int(next_in)} dtk"
            elif seconds_left is not None and seconds_left >= 0:
                ketiga = f"menunggu {int(seconds_left)} dtk"
            else:
                ketiga = ""
            baris = f"skor {stats.wins}/{stats.played}{tick}"
            if ketiga:
                baris = ketiga + "\n" + baris
            detail = f"tebakan {timeframe}\n{baris}"
            tint = DETAIL_FG
        # Status klik otomatis menimpa baris skor: tanpa ini badge identik
        # antara "klik berjalan" dan "klik terus ditolak", dan pengguna tidak
        # punya cara mengetahui alasannya.
        if click_note:
            rejected = "DITOLAK" in click_note
            detail = f"{'klik ditolak' if rejected else 'klik'}\n{_short(click_note)}"
            tint = "#FF8A7A" if rejected else "#3FE08A"
        self.headline.configure(text=label, fg=colour)
        self.detail.configure(text=detail, fg=tint)
        self._apply_geometry()

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
