"""Smoke test GUI untuk lingkungan desktop; tidak dijalankan pada runner headless."""
import os
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Cegah smoke test menyentuh data pengguna atau data runner yang sesungguhnya.
with tempfile.TemporaryDirectory() as directory:
    os.environ["APPDATA"] = str(Path(directory) / "appdata")
    os.environ["XDG_DATA_HOME"] = str(Path(directory) / "xdg")

    from candlelab.ui import CandleLabApp

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
        print("GUI smoke test passed: all pages and prediction lock rendered.")
    finally:
        app.destroy()
