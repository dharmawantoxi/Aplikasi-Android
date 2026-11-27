"""Smoke test GUI untuk lingkungan desktop; tidak dijalankan pada runner headless."""
import os
from pathlib import Path
import sys
import tempfile
import tkinter as tk

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Cegah smoke test menyentuh data pengguna atau data runner yang sesungguhnya.
with tempfile.TemporaryDirectory() as directory:
    os.environ["APPDATA"] = str(Path(directory) / "appdata")
    os.environ["XDG_DATA_HOME"] = str(Path(directory) / "xdg")

    from PIL import Image, ImageDraw

    from candlelab.live_monitor import MonitorEvent
    from candlelab.model import GREEN, RED, Candle, make_demo_candles
    from candlelab.screen_capture import capture_region
    from candlelab.ui import CandleLabApp
    from candlelab.vision import detect_candles

    # Verifikasi backend ImageGrab tanpa menyimpan isi layar runner.
    capture = capture_region((0, 0, 40, 40))
    assert capture.width >= 40 and capture.height >= 40

    app = CandleLabApp()
    try:
        app.geometry("1180x800")
        for page in ("prediction", "data", "experiments", "guide"):
            app.show_page(page)
            app.update_idletasks()
            app.update()
        prediction = app._prediction()
        assert prediction.available
        app.lock_prediction()
        assert app.state.pending is not None
        app.show_page("experiments")
        app.update()

        # Render preview visi sungguhan, lalu tutup otomatis agar test tidak block.
        image = Image.new("RGB", (460, 220), "#101513")
        draw = ImageDraw.Draw(image)
        for index in range(24):
            x = 15 + index * 18
            colour = "#26A69A" if index % 2 else "#EF5350"
            draw.line((x + 4, 25, x + 4, 195), fill=colour)
            draw.rectangle((x, 55, x + 8, 155), fill=colour)
        result = detect_candles(image)
        assert len(result.candles) == 24
        assert {item.direction for item in result.detections} == {GREEN, RED}

        def close_preview():
            for child in app.winfo_children():
                if isinstance(child, tk.Toplevel):
                    child.destroy()

        app.after(400, close_preview)
        app._show_screen_result(result)

        # Simulasikan event worker live tanpa memulai tangkapan kedua.
        app.live_active = True
        app.live_generation = 99
        app.live_candles = []
        app.live_sequence = ""
        app.show_page("prediction")
        app._process_live_result(MonitorEvent(99, result=result, elapsed=0.01))
        app.update()
        assert len(app._chart_candles()) == 24
        assert len(app._source_candles()) == 23  # candle forming dikecualikan

        # Jendela harus benar-benar tidak terlihat selama penangkapan live,
        # kalau tidak CandleLab akan membaca grafiknya sendiri.
        app._hide_for_capture()
        assert app.live_hidden, "jendela belum ditandai tersembunyi"
        assert float(app.attributes("-alpha")) < 0.01
        app._show_after_capture()
        assert not app.live_hidden
        assert float(app.attributes("-alpha")) > 0.99

        # HUD live: jendela utama hilang, panel kecil tampil dan bisa digeser.
        app.screen_region = (100, 250, 1430, 800)
        app.show_live_hud()
        app.update()
        assert app.live_hud is not None, "HUD tidak dibuat"
        hud = app.live_hud.window
        assert hud.winfo_exists()
        assert app.wm_state() == "withdrawn", "jendela utama masih terlihat"
        assert int(hud.attributes("-topmost")) == 1, "HUD tidak always-on-top"
        before = (hud.winfo_x(), hud.winfo_y())
        app.live_hud.cycle_corner()
        app.update()
        assert (hud.winfo_x(), hud.winfo_y()) != before, "HUD tidak berpindah sudut"

        # Siklus tangkapan: HUD hilang sebentar, jendela PENUH tidak boleh muncul.
        app._hide_for_capture()
        assert float(hud.attributes("-alpha")) < 0.01, "HUD tidak ikut disembunyikan"
        app._show_after_capture()
        app.update()
        assert float(hud.attributes("-alpha")) > 0.99, "HUD tidak dipulihkan"
        assert app.wm_state() == "withdrawn", "jendela penuh muncul lagi saat HUD aktif"

        app.expand_from_hud()
        app.update()
        assert app.wm_state() == "normal", "jendela utama tidak kembali"

        app.show_live_hud()
        app.stop_live_monitor(refresh=False)
        app.update()
        assert app.live_hud is None, "HUD tidak ditutup saat live berhenti"
        assert app.wm_state() == "normal", "jendela utama tidak dipulihkan"
        assert not app.hud_mode, "mode HUD tidak direset"

        # Mode overlay: IQ Option tetap layar utama, hanya badge tebakan tampil.
        app.screen_region = (100, 250, 1430, 800)
        app.state.screen_region = app.screen_region
        app.overlay_mode = True
        app.show_overlay()
        app.update()
        assert app.overlay is not None, "overlay tidak dibuat"
        badge = app.overlay.window
        assert badge.winfo_exists()
        assert app.wm_state() == "withdrawn", "jendela utama terlihat saat overlay"
        assert int(badge.attributes("-topmost")) == 1, "badge tidak always-on-top"
        # Regresi: teks badge pernah terpotong karena lebar dipaku pada 176 px.
        # Ukuran jendela harus menampung isi frame apa adanya.
        content = app.overlay.frame.winfo_reqwidth()
        assert badge.winfo_width() >= content, (
            f"isi badge tidak muat: jendela {badge.winfo_width()}px < isi {content}px")
        assert badge.winfo_height() >= app.overlay.frame.winfo_reqheight(), "tinggi badge kurang"
        # Badge tidak boleh keluar layar setelah diseret melewati tepi.
        screen_w, screen_h = badge.winfo_screenwidth(), badge.winfo_screenheight()
        app.overlay._move(99999, 99999)
        app.update()
        assert badge.winfo_x() + badge.winfo_width() <= screen_w, "badge keluar layar di kanan"
        assert badge.winfo_y() + badge.winfo_height() <= screen_h, "badge keluar layar di bawah"
        app.overlay.place("top-right")
        app.update()
        before = (badge.winfo_x(), badge.winfo_y())
        app.overlay.cycle_corner()
        app.update()
        assert (badge.winfo_x(), badge.winfo_y()) != before, "badge tidak berpindah"

        # Badge harus hilang saat piksel diambil, lalu kembali.
        app._hide_for_capture()
        assert float(badge.attributes("-alpha")) < 0.01, "badge ikut terpotret"
        app._show_after_capture()
        app.update()
        assert float(badge.attributes("-alpha")) > 0.5, "badge tidak dipulihkan"
        assert app.wm_state() == "withdrawn", "jendela penuh muncul saat overlay aktif"

        # Jendela log harus bisa dibuka tanpa galat. Tutup hanya jendela itu:
        # menghancurkan semua Toplevel akan ikut menghapus badge overlay.
        log_window = app.open_log_window()
        app.update()
        assert log_window.winfo_exists(), "jendela log tidak terbuka"
        app.log.info("Uji salin seluruh log")
        header = log_window.winfo_children()[0]
        copy = next(w for w in header.winfo_children() if w.winfo_class() == "Button"
                    and w.cget("text") == "COPY LOG")
        assert copy.winfo_ismapped(), "tombol copy tidak terlihat"
        copy.invoke()
        assert app.clipboard_get().splitlines() == app.log.text().splitlines()
        log_window.destroy()
        app.update()

        # Regresi: refresh() saat overlay aktif dulu membuat jendela tidak bisa
        # kembali. Sekarang tidak boleh mengubah state apa pun.
        state_before = app.wm_state()
        app.refresh()
        app.update()
        assert app.wm_state() == state_before, "refresh() mengubah state saat overlay"
        # Badge manual hanya menyediakan kontrol jendela.
        labels = [child.cget("text") for child in app.overlay.buttons.winfo_children()]
        assert labels == ["⚙", "✕"], f"kontrol badge tidak sesuai mode manual: {labels}"

        # Tebak otomatis: tebakan dikunci, lalu dinilai saat chart bergeser.
        app.live_candles = list(make_demo_candles(8))
        app._advance_round(app.live_candles)
        assert app.rounds.current is not None, "putaran tidak dimulai"
        locked = app.rounds.current.direction
        next_direction = 1 if locked != 1 else -1
        shifted = app.live_candles[1:] + [Candle(1.0, 2.0, 0.0, 2.0 if next_direction == 1 else 0.5)]
        app.live_candles = shifted
        app._advance_round(shifted)
        assert app.rounds.stats.played == 1, "putaran bergeser tidak dinilai"
        assert app.state.live_rounds, "hasil putaran tidak dicatat"

        # Area chart tersimpan agar live bisa dimulai tanpa memilih ulang.
        app._save()
        assert app.store.load().screen_region == (100, 250, 1430, 800)

        app.expand_from_overlay()
        app.update()
        assert app.wm_state() == "normal", "jendela tidak kembali"
        assert app.overlay is None, "overlay tidak ditutup"

        app.show_overlay()
        app.stop_live_monitor(refresh=False)
        app.update()
        assert app.overlay is None and not app.overlay_mode, "overlay tidak dibersihkan"

        app.stop_live_monitor(refresh=False)
        print("GUI smoke test passed: pages, capture, preview, live HUD, and overlay.")
    finally:
        app.destroy()
