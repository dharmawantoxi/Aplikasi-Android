"""Widget kecil yang dipakai ulang oleh antarmuka desktop."""
from __future__ import annotations

import tkinter as tk
from tkinter import ttk

from .theme import BG, FONT, INK, MUTED, SURFACE


class ScrollFrame(ttk.Frame):
    def __init__(self, parent):
        super().__init__(parent)
        self.canvas = tk.Canvas(self, bg=BG, highlightthickness=0, bd=0)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.interior = tk.Frame(self.canvas, bg=BG)
        self.window = self.canvas.create_window((0, 0), window=self.interior, anchor="nw")
        self.interior.bind("<Configure>", self._content_changed)
        self.canvas.bind("<Configure>", self._canvas_changed)
        self.canvas.bind("<Enter>", self._bind_wheel)
        self.canvas.bind("<Leave>", self._unbind_wheel)

    def _content_changed(self, _event=None):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _canvas_changed(self, event):
        self.canvas.itemconfigure(self.window, width=event.width)

    def _bind_wheel(self, _event=None):
        self.canvas.bind_all("<MouseWheel>", self._wheel)
        self.canvas.bind_all("<Button-4>", self._wheel)
        self.canvas.bind_all("<Button-5>", self._wheel)

    def _unbind_wheel(self, _event=None):
        self.canvas.unbind_all("<MouseWheel>")
        self.canvas.unbind_all("<Button-4>")
        self.canvas.unbind_all("<Button-5>")

    def _wheel(self, event):
        if getattr(event, "num", None) == 4:
            delta = -1
        elif getattr(event, "num", None) == 5:
            delta = 1
        else:
            delta = -1 if event.delta > 0 else 1
        self.canvas.yview_scroll(delta * 2, "units")


def page_heading(parent, title, subtitle):
    frame = tk.Frame(parent, bg=BG)
    tk.Label(frame, text=title, font=(FONT, 24, "bold"), fg=INK, bg=BG,
             anchor="w").pack(fill="x")
    tk.Label(frame, text=subtitle, font=(FONT, 10), fg=MUTED, bg=BG,
             anchor="w", justify="left", wraplength=820).pack(fill="x", pady=(6, 0))
    return frame


def metric(parent, title, value, note="", bg=SURFACE, value_color=INK):
    frame = tk.Frame(parent, bg=bg, padx=14, pady=13)
    tk.Label(frame, text=title.upper(), font=(FONT, 8, "bold"), fg=MUTED, bg=bg,
             anchor="w").pack(fill="x")
    tk.Label(frame, text=value, font=(FONT, 19, "bold"), fg=value_color, bg=bg,
             anchor="w").pack(fill="x", pady=(5, 1))
    if note:
        tk.Label(frame, text=note, font=(FONT, 8), fg=MUTED, bg=bg,
                 anchor="w", justify="left", wraplength=125).pack(fill="x")
    return frame
