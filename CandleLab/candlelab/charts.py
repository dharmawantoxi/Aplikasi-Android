"""Grafik berbasis Tkinter Canvas; tidak memerlukan Matplotlib."""
from __future__ import annotations

import tkinter as tk

from .model import Candle, DOJI, GREEN as GREEN_DIRECTION
from .theme import (
    DOJI as DOJI_COLOR, FONT, GREEN, GREEN_BRIGHT, INK, LINE, MUTED,
    RED, SURFACE, format_price,
)


class CandleChart(tk.Canvas):
    def __init__(self, parent, **kwargs):
        super().__init__(parent, bg=SURFACE, highlightthickness=0, height=270, **kwargs)
        self.candles: list[Candle] = []
        self._points: list[tuple[float, Candle]] = []
        self._tooltip = None
        self.bind("<Configure>", lambda _event: self.redraw())
        self.bind("<Motion>", self._hover)
        self.bind("<Leave>", lambda _event: self._hide_tooltip())

    def set_data(self, candles):
        self.candles = list(candles[-64:])
        self.redraw()

    def redraw(self):
        self.delete("all")
        self._points = []
        width = max(self.winfo_width(), 300)
        height = max(self.winfo_height(), 200)
        left, right, top, bottom = 20, width - 72, 18, height - 30
        if not self.candles:
            self.create_text(width / 2, height / 2, text="Belum ada candle untuk ditampilkan",
                             fill=MUTED, font=(FONT, 11))
            return
        low = min(c.low for c in self.candles)
        high = max(c.high for c in self.candles)
        padding = max((high - low) * 0.08, 0.0001)
        low -= padding
        high += padding
        price_range = max(high - low, 0.000001)

        def y_position(price):
            return top + (high - price) / price_range * (bottom - top)

        for index in range(5):
            ratio = index / 4
            y = top + ratio * (bottom - top)
            price = high - ratio * price_range
            self.create_line(left, y, right, y, fill=LINE, dash=(2, 4))
            self.create_text(right + 9, y, text=format_price(price), anchor="w",
                             fill=MUTED, font=(FONT, 8))
        count = len(self.candles)
        slot = (right - left) / max(count, 1)
        body_width = max(2, min(12, slot * 0.58))
        for index, candle in enumerate(self.candles):
            x = left + slot * (index + 0.5)
            self._points.append((x, candle))
            color = GREEN if candle.direction == GREEN_DIRECTION else RED if candle.direction != DOJI else DOJI_COLOR
            high_y, low_y = y_position(candle.high), y_position(candle.low)
            open_y, close_y = y_position(candle.open), y_position(candle.close)
            self.create_line(x, high_y, x, low_y, fill=color, width=1)
            body_top = min(open_y, close_y)
            body_bottom = max(open_y, close_y)
            if body_bottom - body_top < 2:
                middle = (body_top + body_bottom) / 2
                body_top, body_bottom = middle - 1, middle + 1
            self.create_rectangle(
                x - body_width / 2, body_top, x + body_width / 2, body_bottom,
                fill=color if candle.direction != DOJI else SURFACE,
                outline=color, width=1,
            )
            if index == count - 1:
                self.create_line(x, top, x, bottom, fill=color, dash=(1, 5), stipple="gray50")
        self.create_text(left, height - 9, text=f"{count - 1} candle lalu", anchor="w",
                         fill=MUTED, font=(FONT, 8))
        self.create_text(right, height - 9, text="Terbaru", anchor="e",
                         fill=MUTED, font=(FONT, 8))

    def _hover(self, event):
        if not self._points:
            return
        x, candle = min(self._points, key=lambda item: abs(item[0] - event.x))
        if abs(x - event.x) > 18:
            self._hide_tooltip()
            return
        self._hide_tooltip()
        label = (f"O {format_price(candle.open)}   H {format_price(candle.high)}\n"
                 f"L {format_price(candle.low)}   C {format_price(candle.close)}")
        self._tooltip = self.create_text(
            min(max(event.x + 14, 8), max(8, self.winfo_width() - 190)),
            max(event.y - 38, 8), anchor="nw", text=label,
            fill=INK, font=(FONT, 9), tags="tooltip",
        )
        bounds = self.bbox(self._tooltip)
        if bounds:
            rectangle = self.create_rectangle(
                bounds[0] - 9, bounds[1] - 7, bounds[2] + 9, bounds[3] + 7,
                fill="#FFFFFF", outline=LINE, tags="tooltip",
            )
            self.tag_raise(self._tooltip, rectangle)

    def _hide_tooltip(self):
        self.delete("tooltip")
        self._tooltip = None


class ProbabilityBar(tk.Canvas):
    def __init__(self, parent, bg):
        super().__init__(parent, height=48, bg=bg, highlightthickness=0)
        self.probability = 0.5
        self.background = bg
        self.bind("<Configure>", lambda _event: self.redraw())

    def set_probability(self, probability):
        self.probability = probability
        self.redraw()

    def redraw(self):
        self.delete("all")
        width = max(200, self.winfo_width())
        left, right, top, bottom = 2, width - 2, 9, 25
        split = left + (right - left) * self.probability
        self.create_rectangle(left, top, right, bottom, fill=RED, outline="")
        self.create_rectangle(left, top, split, bottom, fill=GREEN, outline="")
        middle = left + (right - left) / 2
        self.create_line(middle, top - 4, middle, bottom + 4, fill="#FFFFFF", width=2)
        self.create_text(left, 38, text="HIJAU", anchor="w", fill=GREEN,
                         font=(FONT, 8, "bold"))
        self.create_text(middle, 38, text="50%", anchor="center", fill=MUTED,
                         font=(FONT, 8))
        self.create_text(right, 38, text="MERAH", anchor="e", fill=RED,
                         font=(FONT, 8, "bold"))


class LogoMark(tk.Canvas):
    def __init__(self, parent, size=38, bg=None):
        super().__init__(parent, width=size, height=size, bg=bg or parent.cget("bg"),
                         highlightthickness=0)
        scale = size / 38
        self.create_rectangle(5 * scale, 16 * scale, 12 * scale, 29 * scale,
                              fill=RED, outline="")
        self.create_line(8.5 * scale, 10 * scale, 8.5 * scale, 33 * scale, fill=RED)
        self.create_rectangle(16 * scale, 8 * scale, 23 * scale, 25 * scale,
                              fill=GREEN_BRIGHT, outline="")
        self.create_line(19.5 * scale, 4 * scale, 19.5 * scale, 30 * scale, fill=GREEN_BRIGHT)
        self.create_rectangle(27 * scale, 13 * scale, 34 * scale, 31 * scale,
                              fill=GREEN_BRIGHT, outline="")
        self.create_line(30.5 * scale, 8 * scale, 30.5 * scale, 35 * scale, fill=GREEN_BRIGHT)
