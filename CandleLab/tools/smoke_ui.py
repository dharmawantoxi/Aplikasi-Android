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
    from candlelab.model import GREEN, RED
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
        assert app.state() == "withdrawn", "jendela utama masih terlihat"
        assert hud.winfo_viewable(), "panel HUD tidak tampil"
        assert int(hud.attributes("-topmost")) == 1
        before = (hud.winfo_x(), hud.winfo_y())
        app.live_hud.cycle_corner()
        app.update()
        assert (hud.winfo_x(), hud.winfo_y()) != before, "HUD tidak berpindah sudut"
        # HUD harus ikut tak terlihat saat piksel diambil.
        app._hide_for_capture()
        assert float(hud.attributes("-alpha")) < 0.01
        app._show_after_capture()
        assert float(hud.attributes("-alpha")) > 0.99
        app.expand_from_hud()
        app.update()
        assert app.state() == "normal", "jendela utama tidak kembali"
        app.show_live_hud()
        app.stop_live_monitor(refresh=False)
        app.update()
        assert app.live_hud is None, "HUD tidak ditutup saat live berhenti"
        assert app.state() == "normal", "jendela utama tidak dipulihkan"

        app.stop_live_monitor(refresh=False)
        print("GUI smoke test passed: pages, capture, preview, live updates, and live HUD.")
    finally:
        app.destroy()
