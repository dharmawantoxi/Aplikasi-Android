"""Pemilih area layar transparan berbasis jendela Tkinter."""
from __future__ import annotations

import sys
import tkinter as tk

from .screen_capture import capture_after_overlay_closed
from .theme import ACCENT, FONT


def _virtual_screen(root) -> tuple[int, int, int, int]:
    if sys.platform == "win32":
        try:
            import ctypes
            metrics = ctypes.windll.user32.GetSystemMetrics
            # SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN
            return metrics(76), metrics(77), metrics(78), metrics(79)
        except Exception:
            pass
    return 0, 0, root.winfo_screenwidth(), root.winfo_screenheight()


def select_and_capture(root):
    """Sembunyikan aplikasi, minta drag area, lalu kembalikan (image, box)."""
    root.withdraw()
    root.update()
    virtual_x, virtual_y, width, height = _virtual_screen(root)
    overlay = tk.Toplevel(root)
    overlay.overrideredirect(True)
    overlay.attributes("-topmost", True)
    try:
        overlay.attributes("-alpha", 0.28)
    except tk.TclError:
        pass
    overlay.configure(bg="#000000")
    overlay.geometry(f"{width}x{height}{virtual_x:+d}{virtual_y:+d}")
    canvas = tk.Canvas(overlay, bg="#000000", highlightthickness=0, cursor="crosshair")
    canvas.pack(fill="both", expand=True)
    canvas.create_text(
        width / 2, 35,
        text="Drag untuk memilih area grafik candle  •  Lepaskan untuk membaca  •  Esc untuk batal",
        fill="#FFFFFF", font=(FONT, 13, "bold"),
    )
    start = None
    rectangle = None
    dimensions = None
    result = {"box": None}

    def press(event):
        nonlocal start, rectangle, dimensions
        start = (event.x, event.y)
        if rectangle:
            canvas.delete(rectangle)
        if dimensions:
            canvas.delete(dimensions)
        rectangle = canvas.create_rectangle(event.x, event.y, event.x, event.y,
                                            outline=ACCENT, width=3)
        dimensions = canvas.create_text(event.x + 8, event.y + 18, text="", anchor="nw",
                                        fill=ACCENT, font=(FONT, 11, "bold"))

    def drag(event):
        if start and rectangle:
            canvas.coords(rectangle, start[0], start[1], event.x, event.y)
            canvas.itemconfigure(dimensions, text=f"{abs(event.x-start[0])} × {abs(event.y-start[1])}")

    def release(event):
        if not start:
            return
        x1, x2 = sorted((start[0], event.x))
        y1, y2 = sorted((start[1], event.y))
        if x2 - x1 >= 20 and y2 - y1 >= 20:
            result["box"] = (virtual_x + x1, virtual_y + y1, virtual_x + x2, virtual_y + y2)
            overlay.destroy()

    def cancel(_event=None):
        result["box"] = None
        overlay.destroy()

    canvas.bind("<ButtonPress-1>", press)
    canvas.bind("<B1-Motion>", drag)
    canvas.bind("<ButtonRelease-1>", release)
    overlay.bind("<Escape>", cancel)
    overlay.protocol("WM_DELETE_WINDOW", cancel)
    overlay.focus_set()
    try:
        overlay.grab_set()
        overlay.wait_window()
        if result["box"] is None:
            return None
        root.update()
        image = capture_after_overlay_closed(result["box"])
        return image, result["box"]
    finally:
        if overlay.winfo_exists():
            overlay.destroy()
        root.deiconify()
        root.update()
