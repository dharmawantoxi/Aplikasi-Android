"""Palet dan pembantu tampilan CandleLab."""
from __future__ import annotations

import tkinter as tk

BG = "#F3F5F2"
SURFACE = "#FFFFFF"
INK = "#17231F"
MUTED = "#68766F"
LINE = "#DDE4DE"
SIDEBAR = "#12251F"
SIDEBAR_SOFT = "#1C342C"
GREEN = "#14866D"
GREEN_BRIGHT = "#42D3A5"
GREEN_PALE = "#E4F4ED"
RED = "#D45E62"
RED_PALE = "#FBEAEB"
DOJI = "#8B9690"
ACCENT = "#C9F27C"
WARNING = "#9A671E"
WARNING_PALE = "#FFF4DA"
FONT = "Segoe UI"


def text(parent, value, size=11, color=INK, weight="normal", bg=SURFACE, **kwargs):
    return tk.Label(
        parent, text=value, font=(FONT, size, weight), fg=color, bg=bg,
        justify=kwargs.pop("justify", "left"), **kwargs,
    )


def card(parent, bg=SURFACE, padding=20):
    frame = tk.Frame(
        parent, bg=bg, highlightbackground=LINE, highlightthickness=1,
        bd=0, padx=padding, pady=padding,
    )
    return frame


def button(parent, value, command, kind="secondary", **kwargs):
    palettes = {
        "primary": (GREEN, "#FFFFFF", "#0E6E58", "#FFFFFF"),
        "accent": (ACCENT, SIDEBAR, "#B5E360", SIDEBAR),
        "secondary": (SURFACE, INK, BG, INK),
        "ghost": (BG, INK, LINE, INK),
        "danger": (RED_PALE, RED, "#F5D7D9", RED),
        "dark": (SIDEBAR_SOFT, "#FFFFFF", "#29463C", "#FFFFFF"),
    }
    bg, fg, active_bg, active_fg = palettes[kind]
    return tk.Button(
        parent, text=value, command=command, font=(FONT, 10, "bold"),
        bg=bg, fg=fg, activebackground=active_bg, activeforeground=active_fg,
        disabledforeground=MUTED, relief="flat", bd=0, cursor="hand2",
        padx=15, pady=10, highlightthickness=0, **kwargs,
    )


def format_percent(value, digits=1):
    if value is None:
        return "—"
    return f"{value * 100:.{digits}f}%".replace(".", ",")


def format_price(value):
    if abs(value) >= 1000:
        return f"{value:,.2f}".replace(",", "_").replace(".", ",").replace("_", ".")
    return f"{value:.4f}".rstrip("0").rstrip(".").replace(".", ",")


def direction_name(direction):
    return {1: "Hijau", -1: "Merah", 0: "Doji"}.get(direction, "—")
