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
        print("GUI smoke test passed: pages, prediction lock, screen capture, and vision preview.")
    finally:
        app.destroy()
