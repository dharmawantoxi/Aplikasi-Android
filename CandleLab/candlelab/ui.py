"""Antarmuka desktop CandleLab (Python/Tkinter)."""
from __future__ import annotations

from datetime import datetime
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import traceback

from . import APP_NAME, __version__
from .charts import CandleChart, LogoMark, ProbabilityBar
from .csv_io import export_csv, load_csv
from .experiments import summarise
from .model import (
    DOJI, GREEN as GREEN_DIRECTION, PRIOR_STRENGTH, RED as RED_DIRECTION,
    Candle, CandleModel, make_demo_candles,
)
from .storage import JsonStore
from .theme import (
    ACCENT, BG, DOJI as DOJI_COLOR, FONT, GREEN, GREEN_BRIGHT, GREEN_PALE,
    INK, LINE, MUTED, RED, SIDEBAR, SIDEBAR_SOFT, SURFACE,
    WARNING, WARNING_PALE, button, card, direction_name, format_percent,
    format_price, text,
)
from .widgets import ScrollFrame, metric, page_heading


class CandleLabApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP_NAME} — Eksperimen Probabilitas Candlestick")
        self.geometry("1180x800")
        self.minsize(1000, 700)
        self.configure(bg=BG)
        self.store = JsonStore()
        self.state = self.store.load()
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

    def _set_icon_buttons(self):
        for key, nav in self.nav_buttons.items():
            selected = key == self.current_page
            nav.configure(
                bg=SIDEBAR_SOFT if selected else SIDEBAR,
                fg="#FFFFFF" if selected else "#AFC0B9",
                activebackground=SIDEBAR_SOFT, activeforeground="#FFFFFF",
            )

    def _update_status(self):
        kind = "simulasi" if self.state.is_demo else "data saya"
        self.count_var.set(f"{len(self.state.candles)} candle  •  {kind}  •  tersimpan lokal")

    def set_status(self, message):
        self.status_var.set(message)
        self._update_status()

    def show_page(self, name):
        self.current_page = name
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
        else:
            self._guide_page()

    def refresh(self):
        self.show_page(self.current_page)

    def _order(self):
        return int(self.order_var.get().split()[0])

    def _window(self):
        return 0 if self.window_var.get().lower().startswith("semua") else int(self.window_var.get().split()[0])

    def _prediction(self):
        return CandleModel.predict(self.state.candles, self._order(), self._window())

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
        badge_text = "DATA SIMULASI" if self.state.is_demo else "DATA SAYA"
        badge_bg = WARNING_PALE if self.state.is_demo else GREEN_PALE
        badge_fg = WARNING if self.state.is_demo else GREEN
        badge = tk.Frame(chart_header, bg=badge_bg, padx=10, pady=6)
        badge.pack(side="right")
        text(badge, badge_text, 8, badge_fg, "bold", badge_bg).pack()
        chart = CandleChart(chart_card)
        chart.pack(fill="x", expand=True)
        chart.set_data(self.state.candles)

        columns = tk.Frame(page, bg=BG)
        columns.pack(fill="x", pady=(0, 14))
        columns.grid_columnconfigure(0, weight=3, uniform="forecast")
        columns.grid_columnconfigure(1, weight=2, uniform="forecast")
        forecast = card(columns, bg=SIDEBAR, padding=22)
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
            text(parent, f"Minimal 20 candle dan 12 non-doji. Saat ini {len(self.state.candles)} candle.",
                 9, "#82998F", bg=SIDEBAR).pack(anchor="w", pady=(16, 0))
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
        pending = self.state.pending
        if pending:
            info = tk.Frame(parent, bg=SIDEBAR_SOFT, padx=12, pady=10)
            info.pack(fill="x", pady=(13, 0))
            text(info, "✓ 1 prediksi terkunci", 9, ACCENT, "bold", SIDEBAR_SOFT).pack(anchor="w")
            text(info, "Tambahkan candle aktual berikutnya untuk menilai hasilnya.",
                 8, "#B8C9C1", bg=SIDEBAR_SOFT).pack(anchor="w", pady=(3, 0))
            button(parent, "Tambah candle aktual", self.add_candle_dialog, "accent").pack(fill="x", pady=(10, 0))
        else:
            button(parent, "Kunci prediksi untuk eksperimen", self.lock_prediction, "accent").pack(fill="x", pady=(14, 0))

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

    def _model_changed(self, _event=None):
        self.refresh()

    def _fill_backtest(self, page):
        result = CandleModel.backtest(self.state.candles, self._order(), self._window())
        panel = card(page, padding=20)
        panel.pack(fill="x", pady=(0, 14))
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
            self.state.lock_prediction(self._prediction())
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
        text(page, "Menampilkan hingga 500 candle terbaru  •  Ctrl+N tambah  •  Ctrl+I impor  •  Ctrl+S ekspor",
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
            ("02  Rumus probabilitas", [
                "Candle hijau berarti close > open, merah berarti close < open, dan doji berarti close = open. Doji ditampilkan, tetapi diabaikan saat membentuk pola dan tidak dinilai.",
                "Model mencari pola 1–3 arah terakhir di dalam jendela 30/60/100 atau semua data. Frekuensi dasar adalah proporsi hijau pada seluruh candle non-doji di jendela tersebut.",
                "P(Hijau) = (jumlah hijau setelah pola + 6 × frekuensi dasar hijau) ÷ (jumlah pola + 6). Angka 6 adalah prior Bayesian agar pola langka tidak menghasilkan kepastian semu.",
                "Contoh: pola muncul 4 kali dan 3 diikuti hijau bukan berarti peluang pasti 75%. Frekuensi dasar ikut menarik estimasi agar lebih hati-hati.",
            ]),
            ("03  Backtest dan penilaian", [
                "Backtest walk-forward memprediksi setiap target hanya dari candle sebelum target. Model tidak melatih memakai candle masa depan.",
                "Baseline menebak arah berdasarkan frekuensi dasar pada data pelatihan yang sama. Bandingkan akurasi model dengan baseline untuk melihat apakah pola menambah informasi.",
                "Brier score adalah rata-rata (P(Hijau) − aktual hijau)², dengan aktual hijau bernilai 1 dan merah 0. Nilai mendekati 0 lebih baik. Probabilitas 50%–50% memperoleh skor arah 0,5.",
                "Backtest pada data yang dipakai berulang untuk memilih pengaturan tetap dapat overfit. Idealnya gunakan data baru yang belum pernah dilihat untuk eksperimen terkunci.",
            ]),
            ("04  Format CSV", [
                "Header yang disarankan: timestamp,open,high,low,close. Kolom timestamp bersifat opsional; open, high, low, close wajib. Header bahasa Inggris dan Indonesia umum dikenali.",
                "Pemisah koma, titik-koma, atau tab didukung. File tanpa header dapat berisi open,high,low,close; jika ada lima kolom, kolom pertama dianggap waktu.",
                "Encoding harus UTF-8, ukuran maksimal 4 MiB, dan maksimal 2.000 candle. Semua baris divalidasi sebelum data set diganti; impor yang gagal tidak mengubah data.",
            ]),
            ("05  Batasan & privasi", [
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
        self._save()
        self.destroy()

    def report_callback_exception(self, exc, val, tb):
        traceback.print_exception(exc, val, tb)
        messagebox.showerror("Terjadi kesalahan", f"{val}\n\nData Anda tidak dikirim ke mana pun.", parent=self)


def main():
    app = CandleLabApp()
    app.mainloop()
