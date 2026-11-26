"""Antarmuka desktop CandleLab (Python/Tkinter)."""
from __future__ import annotations

from datetime import datetime
import queue
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import traceback

from . import APP_NAME, __version__
from .applog import CLICK, ERROR, INFO, REJECT, AppLog
from .charts import CandleChart, LogoMark, ProbabilityBar
from .csv_io import export_csv, load_csv
from .experiments import summarise
from .hud_layout import best_badge_corner
from .live_hud import LiveHud
from .overlay import BADGE_HEIGHT, BADGE_WIDTH, OverlayBadge
from .auto_click import (
    click_point,
    find_button_pair,
    find_new_option,
    new_option_point,
)
from .hypotheses import HYPOTHESES, run_hypothesis
from .hypothesis_stats import evaluate, evaluate_many
from .auto_click_win import (
    ClickLimiter,
    click_safety,
    restore_chart_focus,
    foreground_title,
    foreground_window,
    is_point_inside,
    left_click,
    same_window,
    platform_supported,
    wait_for_new_option,
    window_caption,
    window_root_at,
)
from .rounds import (
    MIN_ROUNDS_FOR_SIGNAL,
    CandleClock,
    RoundTracker,
    accuracy_is_meaningful,
)
from .live_monitor import LiveScreenMonitor, MonitorEvent
from .model import (
    MIN_CANDLES,
    MIN_DIRECTIONAL,
    DOJI, GREEN as GREEN_DIRECTION, PRIOR_STRENGTH, RED as RED_DIRECTION,
    Candle, CandleModel, make_demo_candles,
)
from .storage import JsonStore
from .screen_capture import capture_after_overlay_closed, capture_region
from .screen_ui import select_and_capture
from .vision import VisionResult, detect_candles
from .theme import (
    ACCENT, BG, DOJI as DOJI_COLOR, FONT, GREEN, GREEN_BRIGHT, GREEN_PALE,
    INK, LINE, MUTED, RED, SIDEBAR, SIDEBAR_SOFT, SURFACE,
    WARNING, WARNING_PALE, button, card, direction_name, format_percent,
    format_price, text,
)
from .widgets import ScrollFrame, metric, page_heading


class CandleLabApp(tk.Tk):
    def __init__(self):
        self._enable_dpi_awareness()
        super().__init__()
        self.title(f"{APP_NAME} — Eksperimen Probabilitas Candlestick")
        self.geometry("1180x800")
        self.minsize(1000, 700)
        self.configure(bg=BG)
        self.store = JsonStore()
        self.state = self.store.load()
        # Putaran tebak otomatis: tebakan dikunci per candle lalu dinilai
        # oleh candle itu sendiri saat selesai.
        self.rounds = RoundTracker()
        self.entry_window = 0.5
        self.clock = CandleClock()
        self._round_status = ""
        # Klik otomatis: nonaktif secara bawaan.
        self.lab_results = []
        self.lab_payout_var = tk.StringVar(value="82")
        self.auto_click_enabled = False
        self.click_guard = ClickLimiter()
        self._clicked_candle = -1
        # Alasan klik terakhir ditolak/berhasil. Disimpan terpisah supaya tidak
        # tertimpa status deteksi live yang berganti setiap 2 detik.
        self.click_status = ""
        self._clicking = False
        # Log dipakai untuk menelusuri masalah klik; isinya bisa disalin utuh.
        self.log = AppLog()
        self.screen_region = self.state.screen_region
        self.live_monitor = None
        self.live_events = queue.Queue(maxsize=3)
        self.live_generation = 0
        self.live_active = False
        self.live_hidden = False
        self.live_hidden_mode = ""
        self.live_hud = None
        self.hud_mode = False
        self.overlay = None
        self.overlay_mode = False
        self.quiet_live = False
        self.live_candles = []
        self.live_sequence = ""
        self.live_pending_sequence = None
        self.exclude_forming_var = tk.BooleanVar(value=True)
        self.prediction_chart = None
        self.forecast_panel = None
        self.backtest_panel = None
        self.data_badge_frame = None
        self.data_badge_label = None
        self.current_page = "prediction"
        self.order_var = tk.StringVar(value="1 candle terakhir")
        self.window_var = tk.StringVar(value="60 candle")
        self.status_var = tk.StringVar(value="Siap untuk eksperimen")
        self.count_var = tk.StringVar()
        self.nav_buttons = {}
        self._configure_style()
        self._set_icon()
        self._build_shell()
        self._bind_shortcuts()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.show_page("prediction")
        if self.store.warning:
            self.after(200, lambda: messagebox.showwarning("Data dipulihkan", self.store.warning, parent=self))

    @staticmethod
    def _enable_dpi_awareness():
        # Koordinat ImageGrab dan overlay harus sama-sama memakai piksel fisik.
        import sys
        if sys.platform == "win32":
            try:
                import ctypes
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception:
                try:
                    ctypes.windll.user32.SetProcessDPIAware()
                except Exception:
                    pass

    def _configure_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Candle.Treeview", background=SURFACE, fieldbackground=SURFACE,
            foreground=INK, rowheight=34, borderwidth=0, font=(FONT, 9),
        )
        style.configure(
            "Candle.Treeview.Heading", background=BG, foreground=MUTED,
            relief="flat", font=(FONT, 8, "bold"), padding=(8, 10),
        )
        style.map("Candle.Treeview", background=[("selected", GREEN_PALE)],
                  foreground=[("selected", INK)])
        style.configure("TCombobox", padding=8, fieldbackground=SURFACE,
                        background=SURFACE, foreground=INK, arrowcolor=MUTED)
        style.configure("Vertical.TScrollbar", background=LINE, troughcolor=BG,
                        bordercolor=BG, arrowcolor=MUTED)
        style.configure("Candle.TCheckbutton", background=SURFACE, foreground=INK,
                        font=(FONT, 8), focuscolor=SURFACE)

    def _set_icon(self):
        try:
            icon = tk.PhotoImage(width=32, height=32)
            icon.put(SIDEBAR, to=(0, 0, 32, 32))
            icon.put(GREEN_BRIGHT, to=(7, 6, 13, 25))
            icon.put(RED, to=(19, 12, 25, 28))
            self.iconphoto(True, icon)
            self._icon = icon
        except tk.TclError:
            pass

    def _build_shell(self):
        self.grid_rowconfigure(0, weight=1)
        self.grid_columnconfigure(1, weight=1)
        sidebar = tk.Frame(self, bg=SIDEBAR, width=218)
        sidebar.grid(row=0, column=0, sticky="ns")
        sidebar.grid_propagate(False)

        brand = tk.Frame(sidebar, bg=SIDEBAR)
        brand.pack(fill="x", padx=22, pady=(25, 32))
        LogoMark(brand, 38, SIDEBAR).pack(side="left")
        names = tk.Frame(brand, bg=SIDEBAR)
        names.pack(side="left", padx=(10, 0))
        text(names, "CandleLab", 16, "#FFFFFF", "bold", SIDEBAR).pack(anchor="w")
        text(names, "PROBABILITY LAB", 7, GREEN_BRIGHT, "bold", SIDEBAR).pack(anchor="w", pady=(2, 0))

        text(sidebar, "RUANG KERJA", 8, "#789188", "bold", SIDEBAR).pack(anchor="w", padx=24, pady=(0, 8))
        for key, label in (
            ("prediction", "◈   Prediksi"),
            ("data", "▤   Data candle"),
            ("experiments", "✓   Eksperimen"),
            ("lab", "⚗   Lab hipotesis"),
            ("guide", "?   Panduan"),
        ):
            nav = tk.Button(
                sidebar, text=label, command=lambda name=key: self.show_page(name),
                anchor="w", font=(FONT, 10, "bold"), relief="flat", bd=0,
                padx=22, pady=13, cursor="hand2", highlightthickness=0,
            )
            nav.pack(fill="x", padx=12, pady=2)
            self.nav_buttons[key] = nav

        footer = tk.Frame(sidebar, bg=SIDEBAR_SOFT, padx=15, pady=15)
        footer.pack(side="bottom", fill="x", padx=14, pady=18)
        text(footer, "EKSPERIMEN, BUKAN SINYAL", 8, ACCENT, "bold", SIDEBAR_SOFT,
             wraplength=160).pack(anchor="w")
        text(footer, "Tidak ada prediksi yang pasti.\nJangan gunakan sebagai satu-satunya\ndasar keputusan finansial.",
             8, "#B5C5BE", bg=SIDEBAR_SOFT, justify="left").pack(anchor="w", pady=(7, 0))
        text(sidebar, f"v{__version__}  •  Offline", 8, "#789188", bg=SIDEBAR).pack(side="bottom", pady=(0, 2))

        shell = tk.Frame(self, bg=BG)
        shell.grid(row=0, column=1, sticky="nsew")
        shell.grid_rowconfigure(0, weight=1)
        shell.grid_columnconfigure(0, weight=1)
        self.page_host = tk.Frame(shell, bg=BG)
        self.page_host.grid(row=0, column=0, sticky="nsew")
        status = tk.Frame(shell, bg=SURFACE, highlightbackground=LINE, highlightthickness=1)
        status.grid(row=1, column=0, sticky="ew")
        text(status, "●", 8, GREEN, bg=SURFACE).pack(side="left", padx=(15, 6), pady=8)
        tk.Label(status, textvariable=self.status_var, font=(FONT, 8), fg=MUTED,
                 bg=SURFACE, anchor="w").pack(side="left", fill="x", expand=True)
        tk.Label(status, textvariable=self.count_var, font=(FONT, 8, "bold"), fg=MUTED,
                 bg=SURFACE).pack(side="right", padx=15)

    def _bind_shortcuts(self):
        self.bind("<Control-n>", lambda _event: self.add_candle_dialog())
        self.bind("<Control-i>", lambda _event: self.import_csv())
        self.bind("<Control-s>", lambda _event: self.export_data())
        self.bind("<F5>", lambda _event: self.refresh())
        self.bind("<F8>", lambda _event: self.read_screen())
        self.bind("<Control-F8>", lambda _event: self.read_screen(reuse_region=True))
        self.bind("<F7>", lambda _event: None if self.live_active else self.start_overlay_live(select_new=True))
        self.bind("<Control-F7>", lambda _event: None if self.live_active else self.start_overlay_live_saved())
        self.bind("<F9>", lambda _event: self.toggle_live())
        self.bind("<F10>", lambda _event: self.expand_from_hud() if self.live_hud else None)
        self.bind("<Control-F9>", lambda _event: self.toggle_live(select_new=True))

    def _set_icon_buttons(self):
        for key, nav in self.nav_buttons.items():
            selected = key == self.current_page
            nav.configure(
                bg=SIDEBAR_SOFT if selected else SIDEBAR,
                fg="#FFFFFF" if selected else "#AFC0B9",
                activebackground=SIDEBAR_SOFT, activeforeground="#FFFFFF",
            )

    def _update_status(self):
        if self.live_active:
            self.count_var.set(f"LIVE  •  {len(self.live_candles)} candle  •  data tersimpan tidak berubah")
        else:
            kind = "simulasi" if self.state.is_demo else "data saya"
            self.count_var.set(f"{len(self.state.candles)} candle  •  {kind}  •  tersimpan lokal")

    def set_status(self, message):
        self.status_var.set(message)
        self._update_status()

    def show_page(self, name):
        self.current_page = name
        self.prediction_chart = None
        self.forecast_panel = None
        self.backtest_panel = None
        self.data_badge_frame = None
        self.data_badge_label = None
        for child in self.page_host.winfo_children():
            child.destroy()
        self._set_icon_buttons()
        self._update_status()
        if name == "prediction":
            self._prediction_page()
        elif name == "data":
            self._data_page()
        elif name == "experiments":
            self._experiments_page()
        elif name == "lab":
            self._lab_page()
        else:
            self._guide_page()

    def refresh(self):
        """Bangun ulang halaman aktif.

        Saat mode overlay aktif, jendela utama sedang disembunyikan. Membangun
        ulang di sana membuat overlay macet: jendela tidak pernah tampil lagi
        dan pengguna terjebak di badge tanpa jalan kembali. Karena itu halaman
        hanya dibangun ulang bila jendela memang terlihat.
        """
        if self.overlay_mode or self.hud_mode:
            return
        self.show_page(self.current_page)

    def _order(self):
        return int(self.order_var.get().split()[0])

    def _window(self):
        return 0 if self.window_var.get().lower().startswith("semua") else int(self.window_var.get().split()[0])

    def _chart_candles(self):
        return self.live_candles if self.live_active else self.state.candles

    def _source_candles(self):
        candles = self._chart_candles()
        if self.live_active and self.exclude_forming_var.get() and candles:
            return candles[:-1]
        return candles

    def _prediction(self):
        return CandleModel.predict(self._source_candles(), self._order(), self._window())

    def _save(self):
        try:
            self.store.save(self.state)
        except OSError as exc:
            messagebox.showerror("Gagal menyimpan", f"Perubahan belum tersimpan:\n{exc}", parent=self)
            return False
        self._update_status()
        return True

    def _prediction_page(self):
        scroller = ScrollFrame(self.page_host)
        scroller.pack(fill="both", expand=True)
        page = tk.Frame(scroller.interior, bg=BG, padx=28, pady=25)
        page.pack(fill="both", expand=True)
        page_heading(
            page, "Candle berikutnya, tanpa janji pasti.",
            "Estimasi probabilistik dari pola warna historis. Uji dahulu, nilai hasilnya, dan tetap skeptis.",
        ).pack(fill="x")

        chart_card = card(page, padding=18)
        chart_card.pack(fill="x", pady=(20, 14))
        chart_header = tk.Frame(chart_card, bg=SURFACE)
        chart_header.pack(fill="x", pady=(0, 8))
        chart_title = tk.Frame(chart_header, bg=SURFACE)
        chart_title.pack(side="left")
        text(chart_title, "Pergerakan harga", 12, INK, "bold").pack(anchor="w")
        text(chart_title, "64 candle terakhir  •  Arahkan kursor untuk melihat OHLC", 8, MUTED).pack(anchor="w", pady=(3, 0))
        badge_text = f"LIVE • {len(self.live_candles)} CANDLE" if self.live_active else "DATA SIMULASI" if self.state.is_demo else "DATA SAYA"
        badge_bg = GREEN_PALE if self.live_active or not self.state.is_demo else WARNING_PALE
        badge_fg = GREEN if self.live_active or not self.state.is_demo else WARNING
        badge = tk.Frame(chart_header, bg=badge_bg, padx=10, pady=6)
        badge.pack(side="right")
        self.data_badge_frame = badge
        self.data_badge_label = text(badge, badge_text, 8, badge_fg, "bold", badge_bg)
        self.data_badge_label.pack()
        button(chart_header, "▣  Baca layar", self.read_screen, "secondary").pack(side="right", padx=(0, 8))
        if self.live_active:
            live_button = button(chart_header, "■ Stop live", self.toggle_live, "danger")
        else:
            overlay_button = button(chart_header, "◱ Live di atas chart",
                                    lambda: self.start_overlay_live(select_new=True),
                                    "primary")
            overlay_button.pack(side="right", padx=(0, 8))
            live_button = button(chart_header, "● Live (panel)", self.toggle_live, "secondary")
        live_button.pack(side="right", padx=(0, 8))
        chart = CandleChart(chart_card)
        self.prediction_chart = chart
        chart.pack(fill="x", expand=True)
        chart.set_data(self._chart_candles())

        columns = tk.Frame(page, bg=BG)
        columns.pack(fill="x", pady=(0, 14))
        columns.grid_columnconfigure(0, weight=3, uniform="forecast")
        columns.grid_columnconfigure(1, weight=2, uniform="forecast")
        forecast = card(columns, bg=SIDEBAR, padding=22)
        self.forecast_panel = forecast
        forecast.grid(row=0, column=0, sticky="nsew", padx=(0, 7))
        settings = card(columns, padding=20)
        settings.grid(row=0, column=1, sticky="nsew", padx=(7, 0))
        self._fill_forecast(forecast)
        self._fill_settings(settings)
        self._fill_backtest(page)

        note = tk.Frame(page, bg=WARNING_PALE, padx=16, pady=13)
        note.pack(fill="x")
        text(note, "!", 12, WARNING, "bold", WARNING_PALE).pack(side="left", padx=(0, 10))
        text(note, "Probabilitas bukan kepastian dan akurasi historis bukan jaminan hasil berikutnya. "
                   "Data simulasi awal bukan harga pasar. Aplikasi tidak terhubung ke broker atau bursa.",
             9, WARNING, bg=WARNING_PALE, wraplength=650).pack(side="left", fill="x", expand=True)

    def _fill_forecast(self, parent):
        prediction = self._prediction()
        text(parent, "ESTIMASI CANDLE BERIKUTNYA", 8, GREEN_BRIGHT, "bold", SIDEBAR).pack(anchor="w")
        if not prediction.available:
            text(parent, "Data belum cukup", 22, "#FFFFFF", "bold", SIDEBAR).pack(anchor="w", pady=(14, 7))
            text(parent, prediction.reason, 10, "#B8C9C1", bg=SIDEBAR, wraplength=340).pack(anchor="w")
            text(parent, f"Minimal {MIN_CANDLES} candle ({MIN_DIRECTIONAL} non-doji). "
                         f"Saat ini {len(self._source_candles())} candle yang dihitung.",
                 9, "#82998F", bg=SIDEBAR).pack(anchor="w", pady=(16, 0))
            if self.live_active:
                text(parent, f"Mode live menebak candle yang sedang berjalan, jadi candle itu tidak dipakai "
                             f"sebagai dasar: tampilkan minimal {MIN_CANDLES + 1} candle di area chart.",
                     8, ACCENT, bg=SIDEBAR, wraplength=340).pack(anchor="w", pady=(8, 0))
                button(parent, "Stop live", self.toggle_live, "accent").pack(anchor="w", pady=(18, 0))
            else:
                button(parent, "Tambah candle", self.add_candle_dialog, "accent").pack(anchor="w", pady=(18, 0))
            return

        direction = prediction.predicted_direction
        headline = "Hijau lebih mungkin" if direction == GREEN_DIRECTION else "Merah lebih mungkin" if direction == RED_DIRECTION else "Masih seimbang"
        headline_color = GREEN_BRIGHT if direction == GREEN_DIRECTION else "#FF9295" if direction == RED_DIRECTION else "#FFFFFF"
        text(parent, headline, 21, headline_color, "bold", SIDEBAR).pack(anchor="w", pady=(12, 2))
        text(parent, f"Pola {self._pattern_text(prediction.pattern)}  •  {prediction.evidence_label}",
             9, "#AFC3BA", bg=SIDEBAR).pack(anchor="w", pady=(0, 12))
        gauge = ProbabilityBar(parent, SIDEBAR)
        gauge.pack(fill="x")
        gauge.set_probability(prediction.green_probability)
        probabilities = tk.Frame(parent, bg=SIDEBAR)
        probabilities.pack(fill="x", pady=(3, 12))
        probabilities.grid_columnconfigure(0, weight=1)
        probabilities.grid_columnconfigure(1, weight=1)
        text(probabilities, format_percent(prediction.green_probability), 25, GREEN_BRIGHT, "bold", SIDEBAR).grid(row=0, column=0, sticky="w")
        text(probabilities, format_percent(prediction.red_probability), 25, "#FF9295", "bold", SIDEBAR).grid(row=0, column=1, sticky="e")
        text(probabilities, "P(Hijau)", 8, "#AFC3BA", bg=SIDEBAR).grid(row=1, column=0, sticky="w")
        text(probabilities, "P(Merah)", 8, "#AFC3BA", bg=SIDEBAR).grid(row=1, column=1, sticky="e")
        observed = (f"Ditemukan {prediction.matches} pola serupa: {prediction.green_matches} hijau, "
                    f"{prediction.red_matches} merah. Hasil akhir telah dihaluskan menuju frekuensi dasar "
                    f"{format_percent(prediction.base_green_probability)} hijau.")
        text(parent, observed, 8, "#AFC3BA", bg=SIDEBAR, wraplength=340, justify="left").pack(anchor="w", fill="x")
        if self.live_active:
            self._fill_round_panel(parent)
        pending = self.state.pending
        if pending:
            info = tk.Frame(parent, bg=SIDEBAR_SOFT, padx=12, pady=10)
            info.pack(fill="x", pady=(13, 0))
            text(info, "✓ 1 prediksi terkunci", 9, ACCENT, "bold", SIDEBAR_SOFT).pack(anchor="w")
            text(info, "Tambahkan candle aktual berikutnya untuk menilai hasilnya.",
                 8, "#B8C9C1", bg=SIDEBAR_SOFT).pack(anchor="w", pady=(3, 0))
            button(parent, "Tambah candle aktual", self.add_candle_dialog, "accent").pack(fill="x", pady=(10, 0))
        else:
            if getattr(prediction, "thin_data", True):
                tk.Frame(parent, bg="#4A3A12", height=1).pack(fill="x", pady=(12, 9))
                text(parent, "DATA SEDIKIT", 8, "#F0C36B", "bold", SIDEBAR).pack(anchor="w")
                text(parent,
                     f"Angka di atas sebagian besar berasal dari prior, bukan pola. "
                     f"Hanya {prediction.directional_count} candle non-doji. "
                     f"Perlakukan sebagai perkiraan lemah, bukan dasar keputusan.",
                     8, "#D8C79A", bg=SIDEBAR, wraplength=340, justify="left").pack(anchor="w", pady=(4, 0))
            lock_text = "Kunci prediksi live & berhenti" if self.live_active else "Kunci prediksi untuk eksperimen"
            button(parent, lock_text, self.lock_prediction, "accent").pack(fill="x", pady=(14, 0))

    def _fill_round_panel(self, parent):
        """Penjelasan singkat + skor: apa yang ditebak, dan apakah benar."""
        round_obj = self.rounds.current
        stats = self.rounds.stats
        box = tk.Frame(parent, bg=SIDEBAR_SOFT, padx=12, pady=10)
        box.pack(fill="x", pady=(13, 0))
        text(box, "TEBAK OTOMATIS", 8, ACCENT, "bold", SIDEBAR_SOFT).pack(anchor="w")
        text(box, "Aplikasi menebak warna candle yang sedang berjalan. Candle itu "
                  "sendiri yang menentukan tebakan benar atau salah.",
             7, "#8FA49A", bg=SIDEBAR_SOFT, wraplength=320, justify="left").pack(
            anchor="w", pady=(3, 0))

        if round_obj is None:
            text(box, "Menunggu candle terbaca", 9, "#B8C9C1", bg=SIDEBAR_SOFT).pack(
                anchor="w", pady=(6, 0))
            return

        arah = direction_name(round_obj.direction)
        if round_obj.resolved:
            if round_obj.correct is None:
                hasil, warna = "tidak dinilai", "#B8C9C1"
            elif round_obj.correct:
                hasil, warna = "BENAR", "#3FE08A"
            else:
                hasil, warna = "SALAH", "#FF8A7A"
            text(box, f"Tebakan tadi: {arah} → {hasil}", 10, warna, "bold",
                 SIDEBAR_SOFT).pack(anchor="w", pady=(6, 0))
        else:
            text(box, f"Tebakan: {arah} (untuk candle yang sedang berjalan)", 9,
                 "#FFFFFF", "bold", SIDEBAR_SOFT).pack(anchor="w", pady=(6, 0))

        played = stats.played
        if played:
            akurasi = format_percent(stats.accuracy, 0)
            streak = stats.streak
            rentetan = (f"{streak} benar beruntun" if streak > 0
                        else f"{-streak} salah beruntun" if streak < 0 else "")
            text(box, f"Skor {stats.wins}/{played} benar ({akurasi})"
                      + (f" • {rentetan}" if rentetan else ""),
                 8, "#B8C9C1", bg=SIDEBAR_SOFT, wraplength=320, justify="left").pack(
                anchor="w", pady=(3, 0))
            if not accuracy_is_meaningful(played):
                sisa = MIN_ROUNDS_FOR_SIGNAL - played
                text(box, f"Belum bisa disimpulkan: butuh ~{sisa} putaran lagi. "
                          f"Dengan sedikit putaran, hasil bagus maupun buruk "
                          f"bisa terjadi karena kebetulan.",
                     7, "#C9A227", bg=SIDEBAR_SOFT, wraplength=320, justify="left").pack(
                    anchor="w", pady=(3, 0))
        else:
            text(box, "Skor muncul setelah candle pertama selesai — tunggu "
                      "beberapa saat sampai candle menutup.",
                 8, "#B8C9C1", bg=SIDEBAR_SOFT, wraplength=320, justify="left").pack(
                anchor="w", pady=(3, 0))

        if stats.skipped:
            text(box, f"{stats.skipped} candle dilewati (doji/seimbang).",
                 8, "#8FA49A", bg=SIDEBAR_SOFT).pack(anchor="w", pady=(2, 0))

        # Verifikasi mandiri: bandingkan urutan yang dibaca aplikasi dengan
        # chart aslinya sebelum menyimpulkan bahwa prediksinya buruk.
        dilihat = self.rounds.observed_text(12)
        if dilihat != "-":
            text(box, "Yang dibaca: " + dilihat, 8, "#8FA49A", bg=SIDEBAR_SOFT,
                 wraplength=320, justify="left").pack(anchor="w", pady=(6, 0))
            text(box, "Cocokkan dengan 12 candle terakhir di chart. H=hijau, M=merah, D=doji.",
                 7, "#6F8377", bg=SIDEBAR_SOFT, wraplength=320, justify="left").pack(anchor="w")
        # Klik otomatis: sengaja diletakkan setelah penjelasan, bukan di atas.
        status_klik = "MATI" if not self.auto_click_enabled else f"AKTIF ({self.click_guard.count} klik)"
        text(box, f"KLIK OTOMATIS: {status_klik}", 7,
             "#FF8A7A" if self.auto_click_enabled else "#6F8377",
             "bold", SIDEBAR_SOFT).pack(anchor="w", pady=(8, 0))
        button(box, "Matikan klik otomatis" if self.auto_click_enabled
               else "Aktifkan klik otomatis",
               self.toggle_auto_click, "danger" if self.auto_click_enabled else "secondary").pack(
            fill="x", pady=(3, 0))
        button(box, "Uji klik sekarang (tanpa klik)", self.test_click_now,
               "secondary").pack(fill="x", pady=(3, 0))
        button(box, self._entry_window_label(), self.cycle_entry_window,
               "secondary").pack(fill="x", pady=(3, 0))
        button(box, "Buka log (bisa disalin)", self.open_log_window,
               "secondary").pack(fill="x", pady=(3, 0))
        text(box, self.log.summary(), 7, "#6F8377",
             bg=SIDEBAR_SOFT).pack(anchor="w", pady=(2, 0))
        if not self.state.click_region:
            text(box, "Belum dikalibrasi. Atur di halaman Data.",
                 7, "#C9A227", bg=SIDEBAR_SOFT).pack(anchor="w", pady=(2, 0))
        else:
            x1, y1, x2, y2 = self.state.click_region
            text(box, f"Area tombol: {x1},{y1} → {x2},{y2}",
                 7, "#6F8377", bg=SIDEBAR_SOFT).pack(anchor="w", pady=(2, 0))

        if self.rounds.log:
            button(box, "Salin riwayat tebakan", self.copy_round_log, "secondary").pack(
                fill="x", pady=(8, 0))
        riwayat = self.rounds.history_text(6)
        if riwayat:
            text(box, "Riwayat: " + riwayat, 7, "#8FA49A", bg=SIDEBAR_SOFT,
                 wraplength=320, justify="left").pack(anchor="w", pady=(4, 0))

    # -- klik otomatis ---------------------------------------------------
    def toggle_auto_click(self):
        """Aktifkan/matikan klik otomatis dari jendela penuh."""
        self.set_auto_click(not self.auto_click_enabled)

    def set_auto_click(self, enable: bool) -> bool:
        """Nyalakan/matikan klik otomatis. Aman dipanggil dari badge overlay.

        Mengembalikan True bila keadaan berubah. Tidak memanggil ``refresh()``
        agar tidak mengganggu mode overlay.
        """
        if not enable:
            self.auto_click_enabled = False
            self._log(INFO, "klik otomatis dimatikan")
            self.set_status("Klik otomatis dimatikan.")
            self._refresh_overlay()
            return True
        if not platform_supported():
            messagebox.showinfo(
                "Klik otomatis hanya di Windows",
                "Fitur ini memakai SendInput Windows. Di sistem lain, gunakan "
                "mode latihan (catat sinyal tanpa klik).", parent=self._dialog_parent())
            return False
        if not self.state.click_region:
            messagebox.showinfo(
                "Kalibrasi tombol belum ada",
                "Klik otomatis perlu tahu posisi tombol Higher/Lower.\n\n"
                "Gunakan ‘Kalibrasi tombol broker’ di halaman Data, lalu pilih "
                "area yang memuat kedua tombol tersebut.", parent=self._dialog_parent())
            return False
        if not messagebox.askyesno(
            "Aktifkan klik otomatis?",
            "CandleLab akan MENGKLIK tombol Higher/Lower di layar Anda setiap "
            "kali tebakan baru muncul.\n\n"
            "Pengaman yang berlaku:\n"
            "• hanya bila jendela broker sedang aktif\n"
            "• hanya bila titik klik berada di area yang dikalibrasi\n"
            "• maksimal satu klik per candle\n"
            "• berhenti otomatis setelah 50 klik\n\n"
            "Gunakan hanya pada akun demo. Lanjutkan?",
            parent=self._dialog_parent(),
        ):
            return False
        self.auto_click_enabled = True
        self._log(INFO, f"klik otomatis AKTIF • area {self.state.click_region}")
        self.click_guard = ClickLimiter()
        self.click_status = ""
        self._clicked_candle = -1
        self.set_status("Klik otomatis AKTIF — hanya pada akun demo.")
        self._refresh_overlay()
        return True

    def _dialog_parent(self):
        """Induk dialog: badge saat overlay aktif, jika tidak jendela utama."""
        if self.overlay is not None:
            return self.overlay.window
        return self

    def _entry_window_label(self) -> str:
        if self.entry_window >= 0.99:
            return "Entry: kapan saja (klik untuk ubah)"
        return f"Entry: bagian awal candle ≤{self.entry_window * 100:.0f}% (klik untuk ubah)"

    def cycle_entry_window(self):
        """Ubah batas waktu entry: 50% -> 80% -> kapan saja -> 50%."""
        urutan = [0.5, 0.8, 1.0]
        try:
            posisi = urutan.index(self.entry_window)
        except ValueError:
            posisi = 0
        self.entry_window = urutan[(posisi + 1) % len(urutan)]
        pesan = {
            0.5: "Entry hanya di paruh awal candle. Paling cocok dengan tebakan.",
            0.8: "Entry di 80% pertama candle. Lebih longgar, hasil lebih banyak.",
            1.0: "Entry kapan saja. Hasil akan tercampur candle yang sedang berjalan.",
        }[self.entry_window]
        self._log(INFO, f"batas waktu entry diubah ke {self.entry_window * 100:.0f}%")
        self.set_status(pesan)
        self.refresh()

    def open_log_window(self):
        """Tampilkan log untuk diperiksa dan disalin."""
        window = tk.Toplevel(self)
        window.title("Log CandleLab")
        window.configure(bg=BG)
        window.geometry("900x560")
        window.transient(self)
        header = tk.Frame(window, bg=BG, padx=16, pady=10)
        header.pack(fill="x")
        text(header, "Log kegiatan", 13, INK, "bold", bg=BG).pack(side="left")
        text(header, "  salin seluruhnya lalu kirim bila perlu bantuan", 8, MUTED,
             bg=BG).pack(side="left", padx=(8, 0))

        body = tk.Text(window, bg="#0E1F19", fg="#C9D6CF", insertbackground="#C9D6CF",
                       font=(FONT, 8), wrap="none", padx=10, pady=8)
        scroll = tk.Scrollbar(window, command=body.yview)
        body.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        body.pack(fill="both", expand=True, padx=16)

        def _isi():
            body.delete("1.0", "end")
            isi = self.log.text() or "Belum ada catatan."
            body.insert("1.0", isi)
            body.see("end")

        def _salin():
            isi = self.log.text()
            self.clipboard_clear()
            self.clipboard_append(isi)
            self.update_idletasks()
            self.set_status(f"{len(self.log.entries)} baris log disalin.")

        bar = tk.Frame(window, bg=BG, padx=16, pady=10)
        bar.pack(fill="x")
        button(bar, "Salin semua", _salin, "primary").pack(side="left")
        button(bar, "Muat ulang", _isi, "secondary").pack(side="left", padx=6)
        button(bar, "Bersihkan", lambda: (self.log.clear(), _isi()), "secondary").pack(
            side="left")
        button(bar, "Tutup", window.destroy, "secondary").pack(side="right")
        _isi()
        return window

    def test_click_now(self):
        """Periksa seluruh rantai klik tanpa benar-benar menekan tombol.

        Tanpa ini, penolakan klik tidak terlihat: status live berganti setiap
        2 detik, jadi alasan penolakan tertimpa sebelum sempat dibaca.
        """
        lines = []
        ok = True
        if not platform_supported():
            messagebox.showinfo(
                "Klik otomatis hanya di Windows",
                "Fitur ini memakai SendInput Windows, jadi tidak bisa diuji di sini.",
                parent=self._dialog_parent())
            return
        if not self.state.click_region:
            messagebox.showinfo(
                "Area tombol belum dikalibrasi",
                "Klik ‘Kalibrasi tombol broker’ di halaman Data lebih dulu, "
                "lalu pilih kotak yang memuat tombol HIGHER, LOWER, dan "
                "tombol oranye NEW OPTION bila terlihat.",
                parent=self._dialog_parent())
            return
        box = self.state.click_region
        lines.append(f"✓ Area tombol: {box[0]},{box[1]} → {box[2]},{box[3]}")
        try:
            image = capture_region(box)
            lines.append(f"✓ Area terbaca ({image.size[0]}×{image.size[1]} piksel)")
        except (RuntimeError, ValueError) as exc:
            lines.append(f"✗ Area tidak terbaca: {exc}")
            ok = False
            image = None
        if image is not None:
            option_box, _ = find_new_option(image)
            if option_box is not None:
                titik = new_option_point(image.size, box[:2], option_box)
                lines.append(f"• Tombol NEW OPTION terlihat di {titik[0]},{titik[1]}")
                lines.append("  → akan ditekan otomatis lebih dulu, lalu Higher/Lower.")
        pair = None
        if image is not None:
            pair, reason = find_button_pair(image)
            if pair is None:
                if option_box is not None:
                    lines.append("• Tombol Higher/Lower belum muncul (form order tertutup)")
                    lines.append("  → Normal selama NEW OPTION tampil; keduanya ditekan berurutan.")
                else:
                    lines.append(f"✗ Tombol tidak dikenali: {reason}")
                    lines.append("  → Kalibrasi ulang dengan kotak yang lebih rapat.")
                    ok = False
            else:
                lines.append(f"✓ Tombol dikenali (Higher {pair.higher}, Lower {pair.lower})")
        judul_aktif = foreground_title()
        if not judul_aktif:
            lines.append("✗ Tidak ada jendela aktif yang terbaca")
            ok = False
        elif self._needs_own_window_exception():
            lines.append(f"✗ Yang aktif justru CandleLab sendiri: '{judul_aktif}'")
            lines.append("  → Klik ke chart broker dulu; klik otomatis akan mencoba")
            lines.append("    mengembalikan fokus secara otomatis, tetapi tidak selalu bisa.")
            ok = False
        else:
            lines.append(f"✓ Jendela aktif: '{judul_aktif}'")
        if image is not None and pair is not None:
            for arah, nilai, nama in ((1, pair.higher, "HIGHER"), (-1, pair.lower, "LOWER")):
                point = click_point(image.size, box[:2], pair, arah)
                inside = is_point_inside(point, box)
                lines.append(("✓" if inside else "✗") + f" Titik {nama}: {point[0]},{point[1]}")
                if inside:
                    # Tanpa baris ini, penolakan "titik bukan milik jendela
                    # aktif" tidak bisa ditelusuri: pengguna hanya melihat
                    # pesan tanpa tahu jendela mana yang terbaca di titik itu.
                    bawah = window_root_at(point)
                    judul_bawah = window_caption(bawah) if bawah else ""
                    aktif_hwnd = foreground_window()
                    cocok = same_window(bawah, aktif_hwnd) if bawah else None
                    if bawah is None:
                        lines.append("  • Jendela di titik ini tidak terbaca (dianggap aman)")
                    elif cocok:
                        lines.append(f"  ✓ Jendela di titik: '{judul_bawah or '?'}' (sama dengan yang aktif)")
                    else:
                        lines.append(f"  ✗ Jendela di titik: '{judul_bawah or '?'}'")
                        lines.append(f"    berbeda dari jendela aktif: '{foreground_title() or '?'}'")
                        ok = False
        judul = "Siap mengklik" if ok else "Belum siap"
        pesan = "\n".join(lines) + "\n\n" + (
            "Semua pengaman lolos. Klik otomatis akan menekan tombol pada candle "
            "berikutnya." if ok else
            "Perbaiki baris bertanda ✗ di atas; klik otomatis tidak akan bekerja "
            "sebelum itu beres.")
        (messagebox.showinfo if ok else messagebox.showwarning)(judul, pesan,
                                                              parent=self._dialog_parent())

    def calibrate_click_region(self):
        """Pilih area panel tombol Higher/Lower."""
        if self.live_active:
            messagebox.showinfo(
                "Hentikan live dulu",
                "Kalibrasi tombol dilakukan saat live berhenti agar area chart "
                "tidak tertukar.", parent=self)
            return
        captured = select_and_capture(self)
        if captured is None:
            return
        image, box = captured
        pair, reason = find_button_pair(image)
        option_box, _ = find_new_option(image)
        if pair is None and option_box is None:
            messagebox.showwarning("Tombol tidak dikenali", reason, parent=self)
            return
        if pair is None and option_box is not None:
            # Kalibrasi saat form order tertutup sah: tombol NEW OPTION yang
            # tampil, dan Higher/Lower akan muncul setelahnya.
            pesan_tambahan = ("\n\nSaat dikalibrasi hanya tombol NEW OPTION yang tampak. "
                              "Itu tidak masalah: setelah NEW OPTION ditekan, Higher/Lower "
                              "muncul di area yang sama.")
        else:
            pesan_tambahan = ""
        self.state.click_region = box
        if self._save():
            messagebox.showinfo(
                "Tombol dikenali",
                f"Higher di {pair.higher}, Lower di {pair.lower} (relatif area).\n\n"
                f"Area disimpan: {box[0]}, {box[1]} → {box[2]}, {box[3]}\n\n"
                f"{pesan_tambahan}\n\n"
                "KAPAN KLIK TERJADI:\n"
                "Hanya saat mode live berjalan, ketika muncul tebakan BARU untuk "
                "candle yang sedang berjalan. Maksimal satu klik per candle.\n\n"
                "YANG HARUS MASUK AREA:\n"
                "• tombol HIGHER dan LOWER (hijau & merah), dan\n"
                "• tombol oranye + NEW OPTION bila terlihat.\n"
                "Area harus memuat keduanya sekaligus, karena posisinya bertumpuk.\n\n"
                "Urutan yang disarankan: mulai live dulu → atur durasi → baru "
                "aktifkan klik otomatis.",
                parent=self)

    def _maybe_auto_click(self, direction: int, candle_index: int):
        """Klik sekali per candle, hanya bila semua pengaman lolos."""
        if not self.auto_click_enabled or not self.state.click_region:
            return
        if direction not in (1, -1):
            return
        if candle_index == self._clicked_candle:
            return
        allowed, reason = self.click_guard.allow(time.monotonic())
        if not allowed:
            self._click_problem(reason)
            return
        try:
            image = capture_region(self.state.click_region)
        except (RuntimeError, ValueError) as exc:
            self._click_problem(f"area tombol tidak terbaca ({exc}).")
            return
        # Tombol NEW OPTION harus ditekan lebih dulu: selama tombol itu tampil,
        # tombol Higher/Lower belum ada di layar.
        option_box, _ = find_new_option(image)
        if option_box is not None:
            point = new_option_point(image.size, self.state.click_region[:2], option_box)
            guard = self._entry_guard(point)
            if not guard.allowed:
                self._click_problem(guard.reason)
                return
            left_click(point)
            self.click_status = "NEW OPTION ditekan • menunggu Higher/Lower"
            self._log(CLICK, f"NEW OPTION di {point[0]},{point[1]}")
            self.set_status(self.click_status)
            self._refresh_overlay()

            def _still_there() -> bool:
                try:
                    lagi = capture_region(self.state.click_region)
                except (RuntimeError, ValueError):
                    return False
                kotak, _ = find_new_option(lagi)
                return kotak is not None

            wait_for_new_option(_still_there, timeout=1.5)
            try:
                image = capture_region(self.state.click_region)
            except (RuntimeError, ValueError) as exc:
                self._click_problem(f"layar tidak terbaca ulang setelah NEW OPTION ({exc}).")
                return

        pair, reason = find_button_pair(image)
        if pair is None:
            self._click_problem(reason)
            return
        point = click_point(image.size, self.state.click_region[:2], pair, direction)
        guard = self._entry_guard(point)
        if not guard.allowed:
            self._click_problem(guard.reason)
            return
        left_click(point)
        self.click_guard.record(time.monotonic())
        self._clicked_candle = candle_index
        arah = "Higher" if direction == 1 else "Lower"
        self.click_status = (f"KLIK {arah} di {point[0]},{point[1]} • "
                             f"{self.click_guard.count} klik sesi ini")
        self._last_skip = ""
        self._log(CLICK, f"{arah} di {point[0]},{point[1]} "
                         f"(klik ke-{self.click_guard.count} sesi ini)")
        self.set_status(self.click_status)

    def _entry_guard(self, point):
        region = self.state.click_region
        guard = click_safety(point, region, True)
        if not guard.allowed and self.screen_region and is_point_inside(point, region):
            x1, y1, x2, y2 = self.screen_region
            if restore_chart_focus(point, ((x1 + x2) // 2, (y1 + y2) // 2)):
                self._log(INFO, "fokus dikembalikan ke jendela chart")
                guard = click_safety(point, region, True)
        return guard

    def _log(self, level: str, message: str):
        try:
            self.log.add(level, message)
        except Exception:
            # Pencatatan tidak boleh menghentikan apa pun yang sedang berjalan.
            pass

    def _click_problem(self, reason: str):
        """Catat penolakan klik agar terlihat di badge, bukan hanya status live."""
        self.click_status = "KLIK DITOLAK • " + reason
        self._log(REJECT, reason)
        self.set_status(self.click_status)
        self._refresh_overlay()

    def copy_round_log(self):
        """Salin riwayat putaran ke clipboard untuk diperiksa di luar aplikasi."""
        import json as _json
        payload = _json.dumps(
            {"skor": self.rounds.status_text(),
             "putaran": [item.to_dict() for item in self.rounds.log]},
            ensure_ascii=False, indent=1,
        )
        self.clipboard_clear()
        self.clipboard_append(payload)
        self.set_status(f"Riwayat {len(self.rounds.log)} putaran disalin ke clipboard.")

    def _fill_settings(self, parent):
        text(parent, "Pengaturan model", 12, INK, "bold").pack(anchor="w")
        text(parent, "Ubah asumsi, lalu bandingkan hasil backtest.", 8, MUTED).pack(anchor="w", pady=(4, 16))
        text(parent, "PANJANG POLA", 8, MUTED, "bold").pack(anchor="w")
        order = ttk.Combobox(parent, textvariable=self.order_var, state="readonly",
                             values=("1 candle terakhir", "2 candle terakhir", "3 candle terakhir"),
                             font=(FONT, 10))
        order.pack(fill="x", pady=(5, 13))
        order.bind("<<ComboboxSelected>>", self._model_changed)
        text(parent, "JENDELA DATA", 8, MUTED, "bold").pack(anchor="w")
        window = ttk.Combobox(parent, textvariable=self.window_var, state="readonly",
                              values=("30 candle", "60 candle", "100 candle", "Semua data"),
                              font=(FONT, 10))
        window.pack(fill="x", pady=(5, 13))
        window.bind("<<ComboboxSelected>>", self._model_changed)
        separator = tk.Frame(parent, bg=LINE, height=1)
        separator.pack(fill="x", pady=(1, 13))
        text(parent, f"Prior Bayesian: {PRIOR_STRENGTH:g} observasi", 9, INK, "bold").pack(anchor="w")
        text(parent, "Mencegah probabilitas 0% atau 100% hanya karena pola jarang muncul. Doji diabaikan dalam pola.",
             8, MUTED, wraplength=230, justify="left").pack(anchor="w", pady=(5, 0))
        live_separator = tk.Frame(parent, bg=LINE, height=1)
        live_separator.pack(fill="x", pady=(13, 11))
        text(parent, "MODE LIVE • 2 DETIK", 8, GREEN if self.live_active else MUTED, "bold").pack(anchor="w")
        check = ttk.Checkbutton(
            parent, text="Abaikan candle paling kanan\n(masih terbentuk)",
            variable=self.exclude_forming_var, command=self._live_setting_changed,
            style="Candle.TCheckbutton",
        )
        check.pack(anchor="w", pady=(7, 5))
        text(parent, "Perubahan urutan diterima setelah 2 pembacaan konsisten." if self.live_active else "Pilih area sekali, lalu biarkan CandleLab membaca otomatis.",
             8, MUTED, wraplength=240).pack(anchor="w", pady=(0, 8))
        if self.live_active:
            button(parent, "■ Stop live", self.toggle_live, "danger").pack(fill="x")
        else:
            button(parent, "◱ Live di atas chart (F7)", lambda: self.start_overlay_live(select_new=True),
                   "primary").pack(fill="x")
            button(parent, "● Live dengan panel (F9)", self.toggle_live,
                   "secondary").pack(fill="x", pady=(6, 0))

    def _model_changed(self, _event=None):
        self.refresh()

    def _live_setting_changed(self):
        self._refresh_live_panels(redraw_chart=False)

    def _fill_backtest(self, page):
        self.backtest_panel = card(page, padding=20)
        self.backtest_panel.pack(fill="x", pady=(0, 14))
        self._render_backtest()

    def _render_backtest(self):
        if self.backtest_panel is None:
            return
        panel = self.backtest_panel
        for child in panel.winfo_children():
            child.destroy()
        result = CandleModel.backtest(self._source_candles(), self._order(), self._window())
        header = tk.Frame(panel, bg=SURFACE)
        header.pack(fill="x", pady=(0, 14))
        titles = tk.Frame(header, bg=SURFACE)
        titles.pack(side="left")
        text(titles, "Backtest walk-forward", 12, INK, "bold").pack(anchor="w")
        text(titles, "Setiap prediksi hanya memakai candle sebelum target; doji tidak dinilai.", 8, MUTED).pack(anchor="w", pady=(3, 0))
        text(header, "DATA HISTORIS, BUKAN JAMINAN", 8, WARNING, "bold", SURFACE).pack(side="right")
        grid = tk.Frame(panel, bg=SURFACE)
        grid.pack(fill="x")
        for column in range(4):
            grid.grid_columnconfigure(column, weight=1, uniform="metrics")
        metric(grid, "Sampel uji", str(result.samples), "Candle non-doji").grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        metric(grid, "Akurasi model", format_percent(result.accuracy), "Arah dengan probabilitas >50%", GREEN_PALE, GREEN).grid(row=0, column=1, sticky="nsew", padx=6)
        metric(grid, "Baseline", format_percent(result.baseline_accuracy), "Tebak arah frekuensi dasar").grid(row=0, column=2, sticky="nsew", padx=6)
        brier_note = "Lebih rendah lebih baik" if result.brier_score is not None else "Belum cukup data"
        metric(grid, "Brier score", "—" if result.brier_score is None else f"{result.brier_score:.3f}".replace(".", ","),
               brier_note).grid(row=0, column=3, sticky="nsew", padx=(6, 0))

    @staticmethod
    def _pattern_text(pattern):
        return " → ".join("H" if value == GREEN_DIRECTION else "M" if value == RED_DIRECTION else "D" for value in pattern)

    def lock_prediction(self):
        try:
            prediction = self._prediction()
            live_snapshot = list(self._source_candles())
            if self.live_active:
                if not messagebox.askyesno(
                    "Kunci prediksi live?",
                    "Mode live akan berhenti dan candle yang sedang dihitung akan disimpan sebagai data eksperimen. "
                    "Candle paling kanan tidak ikut jika opsi abaikan aktif. Lanjutkan?", parent=self,
                ):
                    return
                self.stop_live_monitor(refresh=False)
                self.state.replace_candles(live_snapshot)
            self.state.lock_prediction(prediction)
        except ValueError as exc:
            messagebox.showwarning("Belum dapat dikunci", str(exc), parent=self)
            return
        if self._save():
            self.set_status("Prediksi dikunci. Tambahkan satu candle aktual untuk menilainya.")
            self.refresh()

    def _data_page(self):
        page = tk.Frame(self.page_host, bg=BG, padx=28, pady=25)
        page.pack(fill="both", expand=True)
        heading = tk.Frame(page, bg=BG)
        heading.pack(fill="x")
        titles = page_heading(heading, "Data candle", "Satu baris = satu OHLC, berurutan dari yang paling lama ke terbaru.")
        titles.pack(side="left", fill="x", expand=True)
        actions = tk.Frame(heading, bg=BG)
        actions.pack(side="right", padx=(15, 0))
        button(actions, "Baca layar", self.read_screen, "secondary").pack(side="left", padx=3)
        button(actions, "Kalibrasi tombol broker", self.calibrate_click_region,
               "secondary").pack(side="left", padx=3)
        button(actions, "Impor CSV", self.import_csv, "secondary").pack(side="left", padx=3)
        button(actions, "+ Tambah candle", self.add_candle_dialog, "primary").pack(side="left", padx=3)

        summary = tk.Frame(page, bg=GREEN_PALE if not self.state.is_demo else WARNING_PALE, padx=15, pady=12)
        summary.pack(fill="x", pady=(20, 12))
        info_color = GREEN if not self.state.is_demo else WARNING
        label = ("Data simulasi aktif — cocok untuk mencoba aplikasi, bukan data pasar."
                 if self.state.is_demo else f"Data Anda aktif — {len(self.state.candles)} candle tersimpan di perangkat ini.")
        text(summary, label, 9, info_color, "bold", summary.cget("bg")).pack(side="left")
        text(summary, "Maksimal 2.000 candle", 8, info_color, bg=summary.cget("bg")).pack(side="right")

        table_card = tk.Frame(page, bg=SURFACE, highlightbackground=LINE, highlightthickness=1)
        table_card.pack(fill="both", expand=True, pady=(0, 12))
        columns = ("index", "time", "open", "high", "low", "close", "direction")
        tree = ttk.Treeview(table_card, columns=columns, show="headings", style="Candle.Treeview")
        headings = ("NO.", "WAKTU", "OPEN", "HIGH", "LOW", "CLOSE", "WARNA")
        widths = (60, 145, 110, 110, 110, 110, 90)
        for name, heading_text, width in zip(columns, headings, widths):
            tree.heading(name, text=heading_text)
            tree.column(name, width=width, minwidth=60, anchor="center" if name in ("index", "direction") else "e")
        tree.column("index", anchor="center")
        tree.column("time", anchor="center")
        scrollbar = ttk.Scrollbar(table_card, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        tree.pack(side="left", fill="both", expand=True)
        tree.tag_configure("green", foreground=GREEN)
        tree.tag_configure("red", foreground=RED)
        tree.tag_configure("doji", foreground=DOJI_COLOR)
        total = len(self.state.candles)
        start = max(0, total - 500)
        for index in range(total - 1, start - 1, -1):
            candle = self.state.candles[index]
            when = self._format_time(candle.timestamp)
            values = (index + 1, when, format_price(candle.open), format_price(candle.high),
                      format_price(candle.low), format_price(candle.close), direction_name(candle.direction))
            tag = "green" if candle.direction == GREEN_DIRECTION else "red" if candle.direction == RED_DIRECTION else "doji"
            tree.insert("", "end", values=values, tags=(tag,))
        if not self.state.candles:
            tree.insert("", "end", values=("", "", "Belum ada data", "", "", "", ""))

        footer = tk.Frame(page, bg=BG)
        footer.pack(fill="x")
        button(footer, "Ekspor CSV", self.export_data, "ghost").pack(side="left", padx=(0, 6))
        button(footer, "Muat data simulasi", self.reset_demo, "ghost").pack(side="left", padx=6)
        button(footer, "Hapus candle terakhir", self.delete_last, "danger").pack(side="right")
        text(page, "Hingga 500 candle terbaru  •  F8 baca layar  •  Ctrl+F8 ulangi area  •  Ctrl+N tambah  •  Ctrl+I impor  •  Ctrl+S ekspor",
             8, MUTED, bg=BG).pack(anchor="w", pady=(10, 0))

    @staticmethod
    def _format_time(timestamp):
        if not timestamp:
            return "—"
        try:
            return datetime.fromtimestamp(timestamp).strftime("%d/%m/%Y %H:%M")
        except (ValueError, OverflowError, OSError):
            return "—"

    def add_candle_dialog(self):
        dialog = tk.Toplevel(self)
        dialog.title("Tambah candle aktual")
        dialog.configure(bg=SURFACE, padx=24, pady=22)
        dialog.resizable(False, False)
        dialog.transient(self)
        dialog.grab_set()
        text(dialog, "Tambah candle berikutnya", 16, INK, "bold").pack(anchor="w")
        text(dialog, "Urutan high/low akan divalidasi. Candle baru otomatis menilai prediksi yang terkunci.",
             9, MUTED, wraplength=400).pack(anchor="w", pady=(5, 18))
        previous = self.state.candles[-1].close if self.state.candles else 100.0
        defaults = (previous, previous * 1.01, previous * 0.99, previous)
        entries = {}
        form = tk.Frame(dialog, bg=SURFACE)
        form.pack(fill="x")
        for row, (field, label, default) in enumerate(zip(
            ("open", "high", "low", "close"), ("Open", "High", "Low", "Close"), defaults,
        )):
            text(form, label.upper(), 8, MUTED, "bold").grid(row=row * 2, column=0, sticky="w", pady=(6, 3))
            variable = tk.StringVar(value=f"{default:.6f}".rstrip("0").rstrip("."))
            entry = tk.Entry(form, textvariable=variable, font=(FONT, 11), bg=BG, fg=INK,
                             relief="flat", highlightbackground=LINE, highlightthickness=1)
            entry.grid(row=row * 2 + 1, column=0, sticky="ew", ipady=8, ipadx=8)
            entries[field] = variable
        form.grid_columnconfigure(0, weight=1)
        error_var = tk.StringVar()
        tk.Label(dialog, textvariable=error_var, font=(FONT, 9), fg=RED, bg=SURFACE,
                 wraplength=390, justify="left").pack(fill="x", pady=(10, 0))
        actions = tk.Frame(dialog, bg=SURFACE)
        actions.pack(fill="x", pady=(18, 0))
        button(actions, "Batal", dialog.destroy, "ghost").pack(side="right", padx=(6, 0))

        def save_candle():
            try:
                values = {field: float(variable.get().strip().replace(",", "."))
                          for field, variable in entries.items()}
                timestamp = datetime.now().timestamp()
                if self.state.candles and self.state.candles[-1].timestamp:
                    timestamp = max(timestamp, self.state.candles[-1].timestamp + 1)
                candle = Candle(**values, timestamp=timestamp)
                resolved = self.state.append_candle(candle)
            except ValueError as exc:
                error_var.set(str(exc))
                return
            if not self._save():
                return
            dialog.destroy()
            self.set_status("Candle ditambahkan dan disimpan.")
            self.refresh()
            if resolved:
                if resolved.actual_direction == DOJI:
                    outcome = "Candle aktual adalah doji, sehingga prediksi tidak dinilai."
                elif resolved.score == 0.5:
                    outcome = "Probabilitas sebelumnya 50%–50%; eksperimen diberi skor 0,5."
                else:
                    outcome = "Prediksi tepat." if resolved.score == 1.0 else "Prediksi meleset."
                messagebox.showinfo(
                    "Eksperimen dinilai",
                    f"Aktual: {direction_name(resolved.actual_direction)}.\n{outcome}\n\n"
                    f"Brier score: {'—' if resolved.brier_score is None else f'{resolved.brier_score:.3f}'}",
                    parent=self,
                )

        button(actions, "Simpan & nilai", save_candle, "primary").pack(side="right")
        dialog.bind("<Return>", lambda _event: save_candle())
        dialog.bind("<Escape>", lambda _event: dialog.destroy())
        dialog.update_idletasks()
        x = self.winfo_rootx() + (self.winfo_width() - dialog.winfo_width()) // 2
        y = self.winfo_rooty() + (self.winfo_height() - dialog.winfo_height()) // 2
        dialog.geometry(f"+{max(x, 0)}+{max(y, 0)}")
        first_entry = form.grid_slaves(row=1, column=0)[0]
        first_entry.focus_set()
        dialog.wait_window()

    def toggle_live(self, select_new=False):
        if self.live_active:
            self.stop_live_monitor(refresh=False)
            if select_new:
                self.start_live_monitor(select_new=True)
            else:
                self.refresh()
        else:
            self.start_live_monitor(select_new=select_new)

    def start_live_monitor(self, select_new=False, overlay=False):
        pending = self.state.pending
        if pending:
            if not messagebox.askyesno(
                "Mulai mode live?",
                "Prediksi yang masih menunggu akan dibatalkan agar tidak dinilai pada data yang berubah. Lanjutkan?",
                parent=self,
            ):
                return
            pending.cancel("Dibatalkan saat mode live dimulai")
            if not self._save():
                return
        if overlay:
            self.quiet_live = True
        # Putaran baru dimulai: tebakan lama tidak boleh dinilai dengan candle
        # dari area pembacaan yang berbeda.
        self.rounds.reset()
        self.clock.reset()
        try:
            self.configure(cursor="watch")
            if select_new or not self.screen_region:
                captured = select_and_capture(self)
                if captured is None:
                    return
                image, box = captured
            else:
                box = self.screen_region
                self.withdraw()
                self.update()
                try:
                    image = capture_after_overlay_closed(box)
                finally:
                    self.deiconify()
                    self.update()
            initial = detect_candles(image)
            self.screen_region = box
            self.state.screen_region = box
            self._save()
        except (RuntimeError, ValueError, OSError) as exc:
            messagebox.showerror("Mode live gagal", str(exc), parent=self)
            return
        finally:
            self.configure(cursor="")
            self.deiconify()

        self._log(INFO, f"live dimulai • area {box} • {len(initial.candles)} candle")
        self.live_generation += 1
        self.live_active = True
        self.live_candles = list(initial.candles)
        self.live_sequence = initial.sequence
        self.live_pending_sequence = None
        self.live_events = queue.Queue(maxsize=3)
        self.live_monitor = LiveScreenMonitor(
            self.live_events, self.screen_region, self.live_generation, interval=2.0,
        )
        covered = self.live_window_overlaps_region()
        # Wilayah ini bisa tertutup badge sendiri saat mode overlay; masalah
        # tersebut tidak nyata karena badge ikut disembunyikan saat memotret.
        if overlay:
            covered = False
        self.live_monitor.start()
        self.show_page("prediction")
        if overlay:
            # Mode diam: IQ Option tetap layar utama, hanya badge tebakan tampil.
            self.show_overlay()
        else:
            # Jendela utama hilang dan diganti panel kecil.
            self.show_live_hud()
        if covered:
            messagebox.showwarning(
                "Jendela sempat menutupi area chart",
                "Sebagian area chart tadi tertutup jendela CandleLab, jadi pembacaan "
                "pertama bisa salah.\n\nJendela penuh kini disembunyikan; panel kecil "
                "dapat digeser agar tidak menutupi candle. Jika hasilnya masih aneh, "
                "pilih area baru dengan Ctrl+F9.", parent=self.live_hud.window,
            )
        self.set_status(
            f"LIVE aktif • {len(self.live_candles)} candle • panel live dapat digeser."
        )
        if overlay:
            # Pengguna perlu tahu area mana yang sedang dibaca, karena memilih
            # ulang area adalah perbaikan untuk sebagian besar masalah live.
            messagebox.showinfo(
                "Live di atas chart",
                f"Badge aktif di sudut kanan atas.\n\n"
                f"Area yang dibaca: {box[0]}, {box[1]} → {box[2]}, {box[3]} "
                f"({len(self.live_candles)} candle terdeteksi).\n\n"
                "• Klik kanan badge untuk memindah sudut\n"
                "• Dobel-klik badge untuk membuka jendela penuh\n"
                "• Ctrl+F7 atau Ctrl+F9 untuk memilih area baru\n\n"
                "Area harus mencakup ruang kosong di kanan candle terakhir, "
                "supaya candle baru ikut terbaca.",
                parent=self.overlay.window,
            )
        self.after(120, self._poll_live_events)

    def live_window_overlaps_region(self) -> bool:
        """True bila jendela utama menutupi sebagian besar area yang dipilih."""
        if not self.screen_region:
            return False
        try:
            if self.wm_state() != "normal":
                return False
            left = self.winfo_rootx()
            top = self.winfo_rooty()
            right = left + self.winfo_width()
            bottom = top + self.winfo_height()
        except tk.TclError:
            return False
        x1, y1, x2, y2 = self.screen_region
        overlap_w = max(0, min(right, x2) - max(left, x1))
        overlap_h = max(0, min(bottom, y2) - max(top, y1))
        if overlap_w <= 0 or overlap_h <= 0:
            return False
        region_area = max(1, (x2 - x1) * (y2 - y1))
        return (overlap_w * overlap_h) / region_area >= 0.30

    def stop_live_monitor(self, refresh=True):
        if self.live_monitor:
            self.live_monitor.stop()
        self.live_monitor = None
        self.live_generation += 1
        self.live_active = False
        self.live_candles = []
        self.live_sequence = ""
        self.live_pending_sequence = None
        self.live_events = queue.Queue(maxsize=3)
        self.hud_mode = False
        self.quiet_live = False
        self.close_overlay()
        self.close_live_hud()
        self._show_after_capture()
        self.wm_state("normal")
        self.deiconify()
        self.lift()
        self.set_status("Mode live berhenti. Data tersimpan tidak berubah.")
        if refresh:
            self.refresh()

    def _poll_live_events(self):
        if not self.live_active:
            return
        latest = None
        error = ""
        ready = False
        while True:
            try:
                event = self.live_events.get_nowait()
            except queue.Empty:
                break
            if event.generation != self.live_generation:
                continue
            if event.ready:
                ready = True
            elif event.error:
                error = event.error
            else:
                latest = event
        if ready and self.live_monitor is not None:
            # Jendela harus hilang dari layar sebelum worker memotret, kalau tidak
            # CandleLab membaca grafiknya sendiri.
            self._hide_for_capture()
            self.live_monitor.release()
        if error:
            self.stop_live_monitor(refresh=False)
            self.refresh()
            messagebox.showerror(
                "Mode live berhenti",
                "Pembacaan layar gagal. Pastikan area masih terlihat dan izin tangkapan layar tersedia.\n\n" + error,
                parent=self,
            )
            return
        if latest is not None:
            self._process_live_result(latest)
        self._show_after_capture()
        if self.live_active:
            self.after(120, self._poll_live_events)

    def _hide_for_capture(self):
        """Buat semua jendela CandleLab tidak terlihat saat piksel diambil.

        HUD ikut disembunyikan sebentar karena panel itu juga berada di atas
        chart. Keduanya memakai transparansi agar fokus/taskbar tidak berubah.
        """
        if self.overlay is not None:
            self.overlay.set_hidden(True)
        if self.live_hud is not None:
            self.live_hud.set_hidden(True)
        if self.hud_mode or self.overlay_mode:
            # Jendela utama memang sudah tidak terlihat selama HUD aktif.
            return
        if self.live_hidden:
            return
        self.live_hidden = True
        self.live_hidden_mode = "alpha"
        try:
            self.attributes("-alpha", 0.0)
            self.update_idletasks()
            return
        except tk.TclError:
            self.live_hidden_mode = "withdraw"
        self.withdraw()

    def _show_after_capture(self):
        if self.overlay is not None:
            self.overlay.set_hidden(False)
        if self.live_hud is not None:
            self.live_hud.set_hidden(False)
        if self.hud_mode or self.overlay_mode:
            # Jangan munculkan jendela penuh; chart harus tetap terlihat.
            return
        if not self.live_hidden:
            return
        self.live_hidden = False
        mode, self.live_hidden_mode = self.live_hidden_mode, ""
        try:
            if mode == "withdraw":
                self.deiconify()
            else:
                self.attributes("-alpha", 1.0)
                self.deiconify()
            self.update_idletasks()
        except tk.TclError:
            # Jangan biarkan jendela tetap tak terlihat bila pemulihan gagal.
            try:
                self.attributes("-alpha", 1.0)
            except tk.TclError:
                pass
            self.deiconify()

    def show_live_hud(self, corner=None):
        """Sembunyikan jendela utama dan tampilkan panel kecil di atas chart."""
        if self.live_hud is None:
            self.live_hud = LiveHud(self, on_stop=self.stop_live_monitor,
                                    on_expand=self.expand_from_hud)
        if corner is None:
            corner = self.live_hud.best_corner(
                self.screen_region, self.winfo_screenwidth(), self.winfo_screenheight(),
            )
        self.live_hud.move_to_corner(corner)
        self.withdraw()
        self.hud_mode = True
        self.live_hud.window.deiconify()
        self.live_hud.window.lift()
        self._refresh_live_hud()

    def expand_from_hud(self):
        """Kembali ke jendela penuh; live tetap berjalan."""
        self.hud_mode = False
        if self.live_hud is not None:
            self.live_hud.set_hidden(False)
        self.deiconify()
        self.wm_state("normal")
        self.lift()
        self.refresh()

    def close_live_hud(self):
        if self.live_hud is not None:
            self.live_hud.close()
            self.live_hud = None

    # -- overlay di atas chart (IQ Option tetap layar utama) ---------------
    def start_overlay_live(self, select_new=False):
        """Mode diam: tanpa jendela CandleLab, hanya badge tebakan di layar.

        Tombol dan F7 selalu meminta pemilihan area. Area lama memang diingat,
        tetapi memakainya tanpa bertanya membuat pengguna tidak sadar sedang
        membaca area yang salah (mis. area yang memotong candle terakhir).
        Gunakan Ctrl+F7 untuk memakai area yang diingat.
        """
        if self.overlay_mode:
            return
        self.quiet_live = True
        self.start_live_monitor(select_new=select_new, overlay=True)

    def start_overlay_live_saved(self):
        """Live di atas chart memakai area yang diingat (Ctrl+F7)."""
        if self.overlay_mode:
            return
        if not self.screen_region:
            messagebox.showinfo(
                "Belum ada area tersimpan",
                "Pilih area chart sekali dulu dengan tombol "
                "‘Live di atas chart’ atau F7.", parent=self,
            )
            return
        self.quiet_live = True
        self.start_live_monitor(select_new=False, overlay=True)

    def show_overlay(self):
        """Sembunyikan semua jendela CandleLab dan tampilkan badge saja."""
        if self.overlay is None:
            self.overlay = OverlayBadge(
                self, on_expand=self.expand_from_overlay,
                on_stop=self.stop_live_monitor,
                on_auto_click=self.toggle_auto_click_from_badge,
            )
        corner = best_badge_corner(
            self.screen_region, self.winfo_screenwidth(), self.winfo_screenheight(),
            BADGE_WIDTH, BADGE_HEIGHT,
        )
        # Badge diukur saat jendela sudah dipetakan; kalau belum, lebar teks
        # belum bisa dihitung dan teks akan terpotong.
        self.overlay.window.deiconify()
        self.overlay.place(corner)
        self.close_live_hud()
        self.overlay_mode = True
        self.withdraw()
        self.overlay.window.deiconify()
        self.overlay.window.lift()
        self._refresh_overlay()

    def toggle_auto_click_from_badge(self):
        """Nyalakan/matikan klik otomatis langsung dari badge, tanpa buka jendela."""
        if self.set_auto_click(not self.auto_click_enabled):
            self.set_status(
                "Klik otomatis AKTIF (dari badge)."
                if self.auto_click_enabled else "Klik otomatis dimatikan (dari badge).")
            self._refresh_overlay()

    def expand_from_overlay(self):
        """Buka jendela penuh untuk melihat detail; live tetap berjalan."""
        self.overlay_mode = False
        if self.overlay is not None:
            self.overlay.close()
            self.overlay = None
        self.deiconify()
        self.wm_state("normal")
        self.lift()
        self.refresh()

    def close_overlay(self):
        if self.overlay is not None:
            self.overlay.close()
            self.overlay = None
        self.overlay_mode = False

    def _refresh_overlay(self):
        if self.overlay is None:
            return
        self.overlay.set_auto_click_state(self.auto_click_enabled)
        if self.live_active:
            self._try_auto_click_for_current()
            self.overlay.show_round(
                self.rounds.current, self.rounds.stats, len(self.live_candles),
                seconds_left=self.rounds.waiting_seconds(),
                next_in=self.clock.seconds_to_next(time.monotonic()),
                timeframe=self.clock.label(),
                click_note=self.click_status if self.auto_click_enabled else "",
            )
        else:
            self.overlay.show_prediction(
                self._prediction(), len(self._source_candles()),
            )

    def _refresh_live_hud(self):
        if self.live_hud is None:
            return
        self.live_hud.update(
            self._prediction(), len(self._source_candles()),
            bool(self.exclude_forming_var.get()),
        )

    def _round_inputs(self):
        """Candle historis + prediksi untuk candle yang sedang berjalan.

        Candle terakhir dianggap masih terbentuk, jadi tidak dipakai sebagai
        dasar tebakan dan tidak ikut membentuk pola.
        """
        candles = self.live_candles if self.live_active else self._chart_candles()
        history = list(candles[:-1]) if candles else []
        prediction = CandleModel.predict(history, self._order(), self._window())
        return history, prediction

    def _try_auto_click_for_current(self):
        """Coba klik untuk tebakan yang sedang berjalan.

        Jaring pengaman: bila percobaan pertama ditolak karena jendela broker
        belum fokus, pengguna masih punya beberapa detik untuk memfokuskannya
        tanpa kehilangan candle itu.
        """
        current = self.rounds.current
        if current is None or current.resolved:
            # Dicatat sekali saja agar log tidak banjir setiap 2 detik.
            alasan = ("belum ada tebakan" if current is None else "candle sudah dinilai")
            if self.auto_click_enabled and getattr(self, "_last_skip", "") != alasan:
                self._last_skip = alasan
                self._log(INFO, f"klik belum dilakukan • {alasan}")
            return
        if not self.auto_click_enabled or not self.state.click_region:
            return
        if self._clicked_candle == current.seq:
            return
        bagian = self.clock.elapsed_fraction(time.monotonic())
        if self.entry_window < 1 and (bagian is None or bagian > self.entry_window):
            alasan = ("menunggu pengukuran durasi candle" if bagian is None
                      else "di luar batas waktu entry")
            if getattr(self, "_last_entry_skip", None) != (current.seq, alasan):
                self._last_entry_skip = (current.seq, alasan)
                self._log(INFO, f"klik ditunda • {alasan}")
            self.click_status = alasan
            return
        if self._clicking:
            # Menolak klik memperbarui badge, dan badge memicu percobaan klik
            # lagi; tanpa penjaga ini keduanya saling memanggil tanpa henti.
            return
        self._clicking = True
        try:
            self._maybe_auto_click(current.direction, current.seq)
        finally:
            self._clicking = False

    def _advance_round(self, candles):
        """Kunci atau nilai satu putaran tebak otomatis."""
        history = list(candles[:-1]) if candles else []
        prediction = CandleModel.predict(history, self._order(), self._window())
        finished = self.rounds.observe(history, prediction)
        self._round_status = ""
        if finished is not None and finished.correct is not None:
            self.state.record_live_round(finished.correct)
            self._save_quietly()
            self._round_status = (
                f"LIVE • tebakan {direction_name(finished.direction)} "
                + ("BENAR" if finished.correct else "SALAH")
                + f" • skor {self.rounds.status_text()}"
            )
        elif finished is not None:
            self._round_status = "LIVE • candle doji/seimbang; tidak dinilai."
        elif self.rounds.reset_reason:
            self._round_status = "LIVE • " + self.rounds.reset_reason
        self.rounds.reset_reason = ""

    def _save_quietly(self):
        """Simpan tanpa dialog; kegagalan tidak menghentikan mode live."""
        try:
            self.store.save(self.state)
        except OSError:
            pass

    def _process_live_result(self, event: MonitorEvent):
        result = event.result
        if result is None:
            return
        if not result.candles:
            self.set_status("LIVE • candle belum terbaca; periksa ukuran/warna area grafik.")
            return
        sequence = result.sequence
        if self.live_sequence and sequence != self.live_sequence:
            if sequence != self.live_pending_sequence:
                self.live_pending_sequence = sequence
                self.set_status("LIVE • perubahan candle terdeteksi; menunggu pembacaan kedua untuk konfirmasi.")
                return
        self.live_pending_sequence = None
        changed = sequence != self.live_sequence or not self.live_candles
        if changed:
            # Setiap perubahan urutan berarti satu candle baru muncul; selisih
            # waktunya adalah durasi candle (TF) yang bisa diukur otomatis.
            self.clock.mark_new_candle(time.monotonic())
        self.live_candles = list(result.candles)
        self.live_sequence = sequence
        previous_status = self._round_status or ""
        self._advance_round(result.candles)
        if self._round_status and self._round_status != previous_status:
            # Hasil putaran lebih penting daripada statistik deteksi, tetapi
            # hanya ditampilkan sekali agar status tidak berkedip.
            self.set_status(self._round_status)
            self._round_status = ""
        elif changed and result.warning:
            # Peringatan deteksi penting (mis. objek terlalu kecil) didahulukan,
            # tetapi hanya saat urutan berubah agar status tidak berkedip.
            self.set_status("LIVE • " + result.warning)
        else:
            self.set_status(
                f"LIVE • {len(self.live_candles)} candle • konsistensi {format_percent(result.confidence, 0)}"
                f" • {event.elapsed:.1f} detik/baca • {datetime.now():%H:%M:%S}"
            )
        self._refresh_live_panels(redraw_chart=True, redraw_model=changed)
        self._refresh_live_hud()
        self._refresh_overlay()

    def _refresh_live_panels(self, redraw_chart=True, redraw_model=True):
        if self.current_page != "prediction":
            return
        if redraw_chart and self.prediction_chart is not None:
            self.prediction_chart.set_data(self._chart_candles())
        if redraw_model and self.forecast_panel is not None:
            for child in self.forecast_panel.winfo_children():
                child.destroy()
            self._fill_forecast(self.forecast_panel)
            self._render_backtest()
        if self.data_badge_label is not None:
            if self.live_active:
                badge_text, badge_bg, badge_fg = f"LIVE • {len(self.live_candles)} CANDLE", GREEN_PALE, GREEN
            elif self.state.is_demo:
                badge_text, badge_bg, badge_fg = "DATA SIMULASI", WARNING_PALE, WARNING
            else:
                badge_text, badge_bg, badge_fg = "DATA SAYA", GREEN_PALE, GREEN
            self.data_badge_frame.configure(bg=badge_bg)
            self.data_badge_label.configure(text=badge_text, bg=badge_bg, fg=badge_fg)

    def read_screen(self, reuse_region=False):
        """Tangkap area grafik dan selalu minta verifikasi sebelum impor."""
        if self.live_active:
            self.stop_live_monitor(refresh=False)
        try:
            self.configure(cursor="watch")
            if reuse_region and self.screen_region:
                self.withdraw()
                self.update()
                try:
                    image = capture_after_overlay_closed(self.screen_region)
                finally:
                    self.deiconify()
                    self.update()
                box = self.screen_region
            else:
                captured = select_and_capture(self)
                if captured is None:
                    return
                image, box = captured
            self.screen_region = box
            self.state.screen_region = box
            self._save()
            self.set_status("Menganalisis warna candle pada tangkapan layar…")
            self.update_idletasks()
            result = detect_candles(image)
        except (RuntimeError, ValueError, OSError) as exc:
            messagebox.showerror("Baca layar gagal", str(exc), parent=self)
            return
        finally:
            self.configure(cursor="")
            self.deiconify()
        self.set_status(f"{len(result.candles)} candle terdeteksi — menunggu verifikasi Anda.")
        self._show_screen_result(result)

    def _show_screen_result(self, result: VisionResult):
        from PIL import ImageTk

        dialog = tk.Toplevel(self)
        dialog.title("Verifikasi hasil baca layar")
        dialog.configure(bg=BG, padx=22, pady=20)
        dialog.minsize(760, 620)
        dialog.transient(self)
        heading = tk.Frame(dialog, bg=BG)
        heading.pack(fill="x")
        titles = tk.Frame(heading, bg=BG)
        titles.pack(side="left")
        text(titles, "Periksa deteksi sebelum digunakan", 17, INK, "bold", BG).pack(anchor="w")
        text(titles, "Kotak berwarna menandai candle yang terbaca. Screenshot tidak disimpan atau dikirim.",
             9, MUTED, bg=BG).pack(anchor="w", pady=(4, 0))
        confidence_color = GREEN if result.confidence >= 0.65 else WARNING
        text(heading, f"Konsistensi {format_percent(result.confidence, 0)}", 11,
             confidence_color, "bold", BG).pack(side="right")

        preview = tk.Frame(dialog, bg=SIDEBAR, highlightbackground=LINE, highlightthickness=1)
        preview.pack(fill="both", expand=True, pady=(16, 12))
        image = result.annotated_image.copy()
        image.thumbnail((900, 390))
        preview_image = ImageTk.PhotoImage(image)
        image_label = tk.Label(preview, image=preview_image, bg=SIDEBAR)
        image_label.image = preview_image
        image_label.pack(expand=True, padx=8, pady=8)

        summary = tk.Frame(dialog, bg=BG)
        summary.pack(fill="x")
        text(summary, f"{len(result.candles)} candle digunakan", 12, INK, "bold", BG).pack(side="left")
        text(summary, f"{result.candidate_count} kandidat warna  •  H=hijau, M=merah, D=doji",
             8, MUTED, bg=BG).pack(side="right")
        sequence = result.sequence or "Tidak ada candle yang dapat dikenali."
        text(dialog, sequence[-120:], 10, GREEN if result.candles else RED, "bold", BG,
             wraplength=820).pack(anchor="w", pady=(9, 4))
        notice = (result.warning + " " if result.warning else "") + \
                 "Harga hasil baca layar hanya skala piksel relatif, bukan harga aset sebenarnya. " \
                 "Potong hanya area price chart; hindari volume, indikator, toolbar, dan teks berwarna."
        text(dialog, notice, 9, WARNING, bg=WARNING_PALE, wraplength=820,
             padx=12, pady=10).pack(fill="x", pady=(5, 12))

        actions = tk.Frame(dialog, bg=BG)
        actions.pack(fill="x")
        button(actions, "Batal", dialog.destroy, "ghost").pack(side="left")
        repeat = button(actions, "Tangkap ulang area", lambda: None, "secondary")
        # Argumen command dipasang terpisah agar dialog ditutup lebih dahulu.
        repeat.configure(command=lambda: (dialog.destroy(), self.read_screen(reuse_region=True)))
        repeat.pack(side="left", padx=(8, 0))
        append = button(actions, "Tambah candle terakhir", lambda: None, "secondary")
        append.configure(command=lambda: self._import_screen_last(dialog, result))
        append.pack(side="right", padx=(8, 0))
        replace = button(actions, "Ganti data dengan hasil", lambda: None, "primary")
        replace.configure(command=lambda: self._import_screen_replace(dialog, result))
        replace.pack(side="right")
        if not result.candles:
            append.configure(state="disabled")
            replace.configure(state="disabled")
        dialog.bind("<Escape>", lambda _event: dialog.destroy())
        dialog.grab_set()
        dialog.wait_window()

    def _import_screen_replace(self, dialog, result):
        if not result.candles:
            return
        if not messagebox.askyesno(
            "Gunakan hasil baca layar?",
            f"Data set akan diganti dengan {len(result.candles)} candle hasil deteksi. "
            "Prediksi tertunda akan dibatalkan. Pastikan kotak deteksi sudah benar.",
            parent=dialog,
        ):
            return
        try:
            self.state.replace_candles(result.candles)
        except ValueError as exc:
            messagebox.showerror("Hasil tidak dapat digunakan", str(exc), parent=dialog)
            return
        if self._save():
            dialog.destroy()
            self.set_status(f"{len(result.candles)} candle dibaca dari layar dan disimpan.")
            self.show_page("prediction")

    def _import_screen_last(self, dialog, result):
        if not result.candles:
            return
        last = result.candles[-1]
        timestamp = datetime.now().timestamp()
        if self.state.candles and self.state.candles[-1].timestamp:
            timestamp = max(timestamp, self.state.candles[-1].timestamp + 1)
        try:
            resolved = self.state.append_candle(Candle(last.open, last.high, last.low, last.close, timestamp))
        except ValueError as exc:
            messagebox.showerror("Candle tidak dapat ditambahkan", str(exc), parent=dialog)
            return
        if not self._save():
            return
        dialog.destroy()
        self.set_status("Candle terakhir dari layar ditambahkan.")
        self.refresh()
        if resolved:
            outcome = ("Doji tidak dinilai." if resolved.actual_direction == DOJI else
                       "Probabilitas seimbang: skor 0,5." if resolved.score == 0.5 else
                       "Prediksi tepat." if resolved.score == 1.0 else "Prediksi meleset.")
            messagebox.showinfo("Eksperimen dinilai",
                                f"Aktual: {direction_name(resolved.actual_direction)}.\n{outcome}", parent=self)

    def import_csv(self):
        path = filedialog.askopenfilename(
            parent=self, title="Impor data OHLC",
            filetypes=(("File CSV", "*.csv"), ("Semua file", "*.*")),
        )
        if not path:
            return
        try:
            candles = load_csv(path)
        except (OSError, ValueError) as exc:
            messagebox.showerror("Impor gagal", str(exc), parent=self)
            return
        if not messagebox.askyesno(
            "Ganti data set?",
            f"File berisi {len(candles)} candle valid. Data set saat ini akan diganti. "
            "Prediksi yang masih tertunda akan dibatalkan.\n\nLanjutkan?", parent=self,
        ):
            return
        try:
            self.state.replace_candles(candles)
        except ValueError as exc:
            messagebox.showerror("Impor gagal", str(exc), parent=self)
            return
        if self._save():
            self.set_status(f"Berhasil mengimpor {len(candles)} candle.")
            self.show_page("data")

    def export_data(self):
        if not self.state.candles:
            messagebox.showinfo("Tidak ada data", "Tambahkan candle sebelum mengekspor.", parent=self)
            return
        path = filedialog.asksaveasfilename(
            parent=self, title="Ekspor data candle", defaultextension=".csv",
            initialfile="candlelab-data.csv", filetypes=(("File CSV", "*.csv"),),
        )
        if not path:
            return
        try:
            export_csv(self.state.candles, path)
        except OSError as exc:
            messagebox.showerror("Ekspor gagal", str(exc), parent=self)
            return
        self.set_status(f"Data diekspor ke {path}")

    def reset_demo(self):
        if not messagebox.askyesno(
            "Muat data simulasi?",
            "Data set saat ini akan diganti dengan 96 candle sintetis. Ekspor data terlebih dahulu jika masih diperlukan.",
            parent=self,
        ):
            return
        self.state.replace_candles(make_demo_candles(), demo=True)
        if self._save():
            self.set_status("Data simulasi dimuat.")
            self.refresh()

    def delete_last(self):
        if not self.state.candles:
            return
        if not messagebox.askyesno("Hapus candle terakhir?", "Tindakan ini mengubah data set dan membatalkan prediksi tertunda.", parent=self):
            return
        self.state.delete_last()
        if self._save():
            self.set_status("Candle terakhir dihapus.")
            self.refresh()

    def _lab_page(self):
        """Lab hipotesis: satu hipotesis, diuji sekali, dengan nilai-p jujur."""
        scroller = ScrollFrame(self.page_host)
        scroller.pack(fill="both", expand=True)
        page = tk.Frame(scroller.interior, bg=BG, padx=28, pady=25)
        page.pack(fill="both", expand=True)
        page_heading(page, "Lab hipotesis",
                     "Bedakan pola nyata dari kebetulan. Pilih satu hipotesis, "
                     "jalankan sekali, lalu lihat nilai-p-nya.").pack(fill="x")

        candles = self._chart_candles()
        info = tk.Frame(page, bg=GREEN_PALE, padx=15, pady=12)
        info.pack(fill="x", pady=(18, 12))
        text(info, f"Data: {len(candles)} candle"
                   + (" (simulasi)" if self.state.is_demo else " (data Anda)"),
             9, GREEN, "bold", GREEN_PALE).pack(anchor="w")
        text(info, "Uji pada data historis TIDAK sama dengan hasil live. Pola yang "
                   "lolos di sini tetap harus dibuktikan dengan putaran baru.",
             8, "#2F6B4F", bg=GREEN_PALE, wraplength=880, justify="left").pack(anchor="w", pady=(3, 0))

        controls = card(page, padding=16)
        controls.pack(fill="x", pady=(0, 12))
        row = tk.Frame(controls, bg=SURFACE)
        row.pack(fill="x")
        text(row, "Hipotesis", 9, INK, "bold").pack(side="left")
        self.lab_choice = tk.StringVar(value=HYPOTHESES[1].key)
        combo = ttk.Combobox(row, textvariable=self.lab_choice, state="readonly",
                             values=[f"{item.label} — {item.question}" for item in HYPOTHESES],
                             font=(FONT, 10), width=60)
        combo.pack(side="left", padx=(10, 10))
        text(row, "Payout %", 9, INK, "bold").pack(side="left", padx=(6, 4))
        ttk.Entry(row, textvariable=self.lab_payout_var, width=6,
                  font=(FONT, 10)).pack(side="left")
        button(row, "Uji hipotesis ini", self.run_lab_hypothesis, "primary").pack(
            side="left", padx=(12, 0))
        button(row, "Uji semua (dikoreksi)", self.run_lab_all, "secondary").pack(
            side="left", padx=(6, 0))

        self.lab_output = tk.Frame(page, bg=BG)
        self.lab_output.pack(fill="both", expand=True)
        self._render_lab_results()

    def _lab_payout(self) -> float:
        try:
            value = float(self.lab_payout_var.get().replace(",", "."))
        except (TypeError, ValueError):
            value = 82.0
        value = max(1.0, min(value, 500.0))
        return value / 100.0

    def run_lab_hypothesis(self):
        index = [f"{item.label} — {item.question}" for item in HYPOTHESES].index(
            self.lab_choice.get()) if self.lab_choice.get() in [
            f"{item.label} — {item.question}" for item in HYPOTHESES] else 1
        hypothesis = HYPOTHESES[index]
        candles = self._chart_candles()
        try:
            scores = run_hypothesis(hypothesis.key, candles)
        except Exception as exc:  # pragma: no cover - pertahanan GUI
            messagebox.showerror("Uji gagal", str(exc), parent=self)
            return
        if not scores:
            messagebox.showinfo(
                "Tidak ada sampel",
                "Hipotesis ini tidak menemukan satu pun kejadian yang cocok pada "
                "data saat ini. Impor CSV yang lebih panjang atau pilih hipotesis lain.",
                parent=self)
            return
        verdict = evaluate(hypothesis.label, sum(scores), len(scores), self._lab_payout())
        self.lab_results = [(hypothesis, verdict)]
        self._render_lab_results()

    def run_lab_all(self):
        candles = self._chart_candles()
        raw = []
        for hypothesis in HYPOTHESES:
            try:
                scores = run_hypothesis(hypothesis.key, candles)
            except Exception:  # pragma: no cover - pertahanan GUI
                scores = []
            raw.append((hypothesis, sum(scores), len(scores)))
        played = [(hypothesis, wins, total) for hypothesis, wins, total in raw if total]
        if not played:
            messagebox.showinfo("Tidak ada sampel", "Belum ada data untuk diuji.", parent=self)
            return
        verdicts = evaluate_many([(h.label, w, t) for h, w, t in played],
                                 self._lab_payout())
        by_label = {verdict.label: verdict for verdict in verdicts}
        self.lab_results = [(hypothesis, by_label[hypothesis.label])
                            for hypothesis, _, _ in played]
        self._render_lab_results()

    def _render_lab_results(self):
        for child in self.lab_output.winfo_children():
            child.destroy()
        if not self.lab_results:
            hint = card(self.lab_output, padding=18)
            hint.pack(fill="x")
            text(hint, "Belum ada yang diuji", 11, INK, "bold").pack(anchor="w")
            text(hint, "Mulai dari satu hipotesis. Menguji sepuluh sekaligus lalu "
                       "memilih yang terbaik adalah cara paling umum menghasilkan "
                       "penemuan palsu.",
                 8, MUTED, wraplength=820, justify="left").pack(anchor="w", pady=(4, 0))
            return

        panel = card(self.lab_output, padding=16)
        panel.pack(fill="x", pady=(0, 12))
        text(panel, "HASIL", 8, MUTED, "bold").pack(anchor="w", pady=(0, 10))
        for hypothesis, verdict in self.lab_results:
            self._render_lab_row(panel, hypothesis, verdict)

        footnote = card(self.lab_output, padding=16, bg=WARNING_PALE)
        footnote.pack(fill="x")
        text(footnote, "Cara membaca hasil ini", 10, WARNING, "bold", WARNING_PALE).pack(anchor="w")
        for line in (
            "Nilai-p = peluang melihat hasil seekstrem ini bila model sebenarnya "
            "tidak lebih baik dari koin. Di bawah 0,05 baru layak dicurigai.",
            "Bila banyak hipotesis diuji sekaligus, nilai-p dikoreksi "
            "(Benjamini-Hochberg) agar penemuan palsu tidak muncul dari jumlah percobaan.",
            "Akurasi di atas ambang impas belum berarti bila nilai-p masih besar. "
            "Dua syarat itu harus terpenuhi bersamaan.",
        ):
            text(footnote, "• " + line, 8, "#5A4A1F", bg=WARNING_PALE,
                 wraplength=820, justify="left").pack(anchor="w", pady=(3, 0))

    def _render_lab_row(self, parent, hypothesis, verdict):
        row = tk.Frame(parent, bg=SURFACE, pady=8)
        row.pack(fill="x")
        left = tk.Frame(row, bg=SURFACE)
        left.pack(side="left", fill="x", expand=True)
        text(left, hypothesis.label, 10, INK, "bold", SURFACE).pack(anchor="w")
        text(left, hypothesis.question, 8, MUTED, SURFACE, wraplength=560,
             justify="left").pack(anchor="w")
        text(left, verdict.summary(), 8,
             GREEN if verdict.profitable else (WARNING if verdict.significant else MUTED),
             bg=SURFACE, wraplength=560, justify="left").pack(anchor="w", pady=(3, 0))
        right = tk.Frame(row, bg=SURFACE)
        right.pack(side="right")
        acc = "—" if verdict.accuracy is None else format_percent(verdict.accuracy, 1)
        text(right, acc, 13, INK, "bold", SURFACE).pack(anchor="e")
        text(right, f"n={verdict.total}  p={verdict.adjusted_p:.3f}", 8, MUTED, SURFACE).pack(anchor="e")

    def _experiments_page(self):
        scroller = ScrollFrame(self.page_host)
        scroller.pack(fill="both", expand=True)
        page = tk.Frame(scroller.interior, bg=BG, padx=28, pady=25)
        page.pack(fill="both", expand=True)
        heading = tk.Frame(page, bg=BG)
        heading.pack(fill="x")
        page_heading(heading, "Eksperimen prediksi", "Bandingkan prediksi yang benar-benar dikunci dengan candle aktual berikutnya.").pack(side="left", fill="x", expand=True)
        button(heading, "Bersihkan riwayat", self.clear_history, "ghost").pack(side="right")
        summary = summarise(self.state.experiments)
        metrics = tk.Frame(page, bg=BG)
        metrics.pack(fill="x", pady=(20, 14))
        for column in range(4):
            metrics.grid_columnconfigure(column, weight=1, uniform="experiment_metrics")
        metric(metrics, "Dinilai", str(summary.resolved), "Doji & batal dikecualikan").grid(row=0, column=0, sticky="nsew", padx=(0, 5))
        metric(metrics, "Akurasi tercatat", format_percent(summary.accuracy), "Tie 50% dihitung 0,5", GREEN_PALE, GREEN).grid(row=0, column=1, sticky="nsew", padx=5)
        metric(metrics, "Brier rata-rata", "—" if summary.brier_score is None else f"{summary.brier_score:.3f}".replace(".", ","),
               "Lebih rendah lebih baik").grid(row=0, column=2, sticky="nsew", padx=5)
        metric(metrics, "Menunggu", str(summary.pending), "Prediksi terkunci").grid(row=0, column=3, sticky="nsew", padx=(5, 0))

        pending = self.state.pending
        if pending:
            panel = card(page, bg=SIDEBAR, padding=18)
            panel.pack(fill="x", pady=(0, 14))
            text(panel, "PREDIKSI MENUNGGU HASIL", 8, GREEN_BRIGHT, "bold", SIDEBAR).pack(anchor="w")
            direction = direction_name(pending.predicted_direction) if pending.predicted_direction != DOJI else "Seimbang"
            text(panel, f"{direction}  •  P(Hijau) {format_percent(pending.green_probability)}",
                 18, "#FFFFFF", "bold", SIDEBAR).pack(anchor="w", pady=(8, 3))
            text(panel, f"Dikunci setelah {pending.candle_count} candle  •  Pola {self._pattern_text(pending.pattern)}",
                 9, "#B5C8BE", bg=SIDEBAR).pack(anchor="w")
            button(panel, "Tambah candle aktual", self.add_candle_dialog, "accent").pack(anchor="w", pady=(13, 0))

        panel = card(page, padding=16)
        panel.pack(fill="x")
        text(panel, "Riwayat prediksi terkunci", 12, INK, "bold").pack(anchor="w", pady=(0, 12))
        columns = ("created", "pattern", "probability", "prediction", "actual", "score", "brier")
        tree = ttk.Treeview(panel, columns=columns, show="headings", height=12, style="Candle.Treeview")
        labels = ("WAKTU KUNCI", "POLA", "P(HIJAU)", "PREDIKSI", "AKTUAL", "SKOR", "BRIER")
        widths = (135, 85, 90, 100, 100, 65, 75)
        for name, label, width in zip(columns, labels, widths):
            tree.heading(name, text=label)
            tree.column(name, width=width, minwidth=60, anchor="center")
        scrollbar = ttk.Scrollbar(panel, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        for item in reversed(self.state.experiments):
            if item.pending:
                actual, score, brier = "Menunggu", "—", "—"
            elif item.status == "cancelled":
                actual, score, brier = "Dibatalkan", "—", "—"
            else:
                actual = direction_name(item.actual_direction)
                score = "—" if item.score is None else f"{item.score:g}".replace(".", ",")
                brier = "—" if item.brier_score is None else f"{item.brier_score:.3f}".replace(".", ",")
            prediction = direction_name(item.predicted_direction) if item.predicted_direction != DOJI else "Seimbang"
            tree.insert("", "end", values=(
                self._format_iso(item.created_at), self._pattern_text(item.pattern),
                format_percent(item.green_probability), prediction, actual, score, brier,
            ))
        if not self.state.experiments:
            tree.insert("", "end", values=("Belum ada eksperimen", "", "", "", "", "", ""))
        text(page, "Akurasi hanya menghitung arah. Brier score menilai kualitas probabilitas: 0 sempurna, 1 paling buruk. "
                   "Sampel kecil sangat mudah menyesatkan.", 8, MUTED, bg=BG, wraplength=800).pack(anchor="w", pady=(12, 0))

    @staticmethod
    def _format_iso(value):
        try:
            return datetime.fromisoformat(value).astimezone().strftime("%d/%m/%Y %H:%M")
        except (ValueError, TypeError):
            return value

    def clear_history(self):
        if not self.state.experiments:
            return
        if not messagebox.askyesno("Bersihkan riwayat?", "Riwayat selesai dan dibatalkan akan dihapus. Prediksi tertunda tetap disimpan.", parent=self):
            return
        self.state.clear_history()
        if self._save():
            self.set_status("Riwayat eksperimen dibersihkan.")
            self.refresh()

    def _guide_page(self):
        scroller = ScrollFrame(self.page_host)
        scroller.pack(fill="both", expand=True)
        page = tk.Frame(scroller.interior, bg=BG, padx=28, pady=25)
        page.pack(fill="both", expand=True)
        page_heading(page, "Panduan & metode", "Pahami apa yang dihitung sebelum menafsirkan angka probabilitas.").pack(fill="x")
        sections = (
            ("01  Cara menjalankan eksperimen", [
                "1. Mulai dengan data simulasi atau impor CSV OHLC milik Anda. Urutkan data dari candle paling lama ke terbaru.",
                "2. Pilih panjang pola dan jendela data di halaman Prediksi. Periksa hasil backtest, bukan hanya persentase terbesar.",
                "3. Klik ‘Kunci prediksi untuk eksperimen’. Prediksi tersimpan beserta pengaturan dan jumlah candle saat itu.",
                "4. Setelah candle berikutnya selesai, masukkan OHLC aktual melalui Tambah candle. Aplikasi langsung memberi skor.",
                "5. Ulangi pada banyak sampel. Satu atau dua tebakan benar tidak menunjukkan bahwa model memiliki keunggulan.",
            ]),
            ("02  Membaca candle dari layar desktop", [
                "Klik ‘Baca layar’ atau tekan F8. Aplikasi diminimalkan dan sebuah overlay transparan ditampilkan. Drag hanya pada area price chart, lalu lepaskan tombol mouse. Tekan Esc untuk membatalkan.",
                "CandleLab membaca piksel hijau/teal dan merah, mengelompokkan body serta sumbu, lalu memilih deret dengan lebar dan spasi yang konsisten. Ini bukan OCR harga dan bukan koneksi ke platform trading.",
                "Periksa kotak penanda pada preview. Pilih ‘Ganti data dengan hasil’ untuk memuat seluruh urutan, atau ‘Tambah candle terakhir’ untuk menilai prediksi terkunci. Jangan gunakan hasil jika volume, indikator, atau teks ikut terkotak.",
                "Ctrl+F8 menangkap ulang area yang sama. Area terakhir diingat di data.json (koordinat saja) agar mode live bisa dimulai tanpa memilih ulang. Screenshot tidak disimpan ke disk dan tidak dikirim ke mana pun.",
                "Skala OHLC hasil deteksi berasal dari posisi piksel, bukan harga aset sesungguhnya. Warna custom, candle yang saling menempel, chart kecil, atau anti-alias ekstrem dapat menurunkan akurasi deteksi. Grafik broker bertema terang seperti IQ Option dan candle hollow didukung; atur zoom agar minimal 6 candle terlihat dan tidak menempel. Di macOS diperlukan izin Screen Recording; Wayland dapat membatasi tangkapan layar.",
            ]),
            ("03  Mode live tanpa screenshot berulang", [
                "Tekan F9 atau klik ‘Mulai live’. Jika belum ada area, pilih area price chart sekali. Setelah itu worker membaca area yang sama setiap 2 detik dan probabilitas diperbarui otomatis; Anda tidak perlu mengambil screenshot manual.",
                "Ctrl+F9 memilih area baru. Selama live, jangan menutupi area chart dengan CandleLab atau jendela lain dan jangan mengubah zoom/warna chart. Tekan ‘Stop live’ atau F9 untuk berhenti.",
                "Perubahan urutan warna harus muncul pada dua pembacaan berturut-turut sebelum diterima. Ini meredam satu deteksi yang keliru, tetapi menambah jeda sekitar satu interval.",
                "Model dapat mulai dari 5 candle, tetapi di bawah 20 candle non-doji hasilnya ditandai DATA SEDIKIT: angkanya sebagian besar berasal dari prior, bukan dari pola. Perlakukan sebagai perkiraan lemah.",
                "Opsi ‘Abaikan candle paling kanan’ aktif secara default karena candle tersebut biasanya masih terbentuk. Grafik tetap menampilkannya, tetapi prediksi memakai candle sebelumnya. Saat candle baru muncul, candle yang sudah selesai otomatis masuk perhitungan.",
                "Data live dan screenshot hanya berada di memori; aplikasi tidak menulis data set setiap interval. ‘Kunci prediksi live & berhenti’ menyimpan snapshot yang sedang dihitung, menghentikan monitor, dan membuat prediksi eksperimen. Jika live dihentikan tanpa dikunci, data tersimpan sebelumnya tetap dipakai.",
            ]),
            ("03b  Tebak otomatis: benar atau salah", [
                "Di mode live, CandleLab menebak candle yang SEDANG berjalan, lalu candle itu sendiri yang menilai hasilnya. Badge menampilkan tebakannya; setelah candle selesai, badge berubah menjadi BENAR atau SALAH beserta skor seperti 12/20.",
                "Tebakan tidak diperbarui selama candle berjalan. Kalau diperbarui, tebakan akan selalu mengikuti warna candle yang sedang bergerak dan hasilnya selalu benar — skor seperti itu tidak berarti.",
                "Satu putaran selesai ketika jumlah candle di layar bertambah, atau ketika seluruh urutan bergeser satu langkah (umum pada chart broker: candle baru masuk di kanan, candle lama keluar di kiri).",
                "AREA HARUS MENCAKUP CANDLE BARU. Jika kotak berhenti tepat di candle terakhir, candle berikutnya berada di luar area, jumlah candle tidak pernah berubah, dan badge akan terus menampilkan 0/0. Sertakan ruang kosong di kanan candle terakhir.",
                "Butuh minimal 6 candle terlihat: satu candle yang sedang berjalan tidak dipakai sebagai dasar tebakan. Doji dan tebakan 50/50 dilewati, bukan dihitung sebagai kalah.",
                "Badge menampilkan titik berkedip dan lama menunggu (mis. 42s). Angka yang terus naik tanpa pernah dinilai berarti area tangkapan tidak pernah melihat candle baru — ulangi pemilihan area.",
            ]),
            ("04  Rumus probabilitas", [
                "Candle hijau berarti close > open, merah berarti close < open, dan doji berarti close = open. Doji ditampilkan, tetapi diabaikan saat membentuk pola dan tidak dinilai.",
                "Model mencari pola 1–3 arah terakhir di dalam jendela 30/60/100 atau semua data. Frekuensi dasar adalah proporsi hijau pada seluruh candle non-doji di jendela tersebut.",
                "P(Hijau) = (jumlah hijau setelah pola + 6 × frekuensi dasar hijau) ÷ (jumlah pola + 6). Angka 6 adalah prior Bayesian agar pola langka tidak menghasilkan kepastian semu.",
                "Contoh: pola muncul 4 kali dan 3 diikuti hijau bukan berarti peluang pasti 75%. Frekuensi dasar ikut menarik estimasi agar lebih hati-hati.",
            ]),
            ("05  Backtest dan penilaian", [
                "Backtest walk-forward memprediksi setiap target hanya dari candle sebelum target. Model tidak melatih memakai candle masa depan.",
                "Baseline menebak arah berdasarkan frekuensi dasar pada data pelatihan yang sama. Bandingkan akurasi model dengan baseline untuk melihat apakah pola menambah informasi.",
                "Brier score adalah rata-rata (P(Hijau) − aktual hijau)², dengan aktual hijau bernilai 1 dan merah 0. Nilai mendekati 0 lebih baik. Probabilitas 50%–50% memperoleh skor arah 0,5.",
                "Backtest pada data yang dipakai berulang untuk memilih pengaturan tetap dapat overfit. Idealnya gunakan data baru yang belum pernah dilihat untuk eksperimen terkunci.",
            ]),
            ("06  Format CSV", [
                "Header yang disarankan: timestamp,open,high,low,close. Kolom timestamp bersifat opsional; open, high, low, close wajib. Header bahasa Inggris dan Indonesia umum dikenali.",
                "Pemisah koma, titik-koma, atau tab didukung. File tanpa header dapat berisi open,high,low,close; jika ada lima kolom, kolom pertama dianggap waktu.",
                "Encoding harus UTF-8, ukuran maksimal 4 MiB, dan maksimal 2.000 candle. Semua baris divalidasi sebelum data set diganti; impor yang gagal tidak mengubah data.",
            ]),
            ("07  Batasan & privasi", [
                "Ini adalah model statistik sederhana untuk eksperimen, bukan kecerdasan buatan yang mengetahui masa depan. Harga, volume, berita, spread, likuiditas, dan kondisi pasar lain tidak dianalisis.",
                "Aplikasi tidak memiliki koneksi bursa, tidak mengambil harga live, dan tidak memberi rekomendasi beli/jual. Probabilitas tinggi tetap dapat menghasilkan prediksi salah.",
                "Semua data dan riwayat disimpan lokal sebagai JSON tanpa enkripsi. Tidak ada akun atau sinkronisasi. Ekspor CSV secara berkala jika perlu cadangan.",
            ]),
        )
        for title, paragraphs in sections:
            panel = card(page, padding=20)
            panel.pack(fill="x", pady=(18 if title.startswith("01") else 0, 12))
            text(panel, title, 12, GREEN, "bold").pack(anchor="w", pady=(0, 10))
            for paragraph in paragraphs:
                text(panel, paragraph, 9, INK, wraplength=650, justify="left").pack(anchor="w", fill="x", pady=4)

    def _close(self):
        if self.live_active:
            self.stop_live_monitor(refresh=False)
        self.close_live_hud()
        self._save()
        self.destroy()

    def report_callback_exception(self, exc, val, tb):
        traceback.print_exception(exc, val, tb)
        # Kegagalan pada fitur klik tidak boleh menghentikan live: callback Tk
        # yang melempar akan memutus rantai pembaruan badge dan tebakan.
        if self.live_active:
            self.click_status = f"KLIK DITOLAK • kesalahan internal: {val}"
            self._log(ERROR, f"kesalahan internal: {val}")
            self.set_status("Fitur klik otomatis gagal, tetapi live tetap berjalan.")
            return
        messagebox.showerror("Terjadi kesalahan", f"{val}\n\nData Anda tidak dikirim ke mana pun.", parent=self)


def main():
    app = CandleLabApp()
    app.mainloop()
