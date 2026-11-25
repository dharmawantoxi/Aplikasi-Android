"""Pengenalan tombol broker dan pengaman sebelum klik.

Klik otomatis adalah fitur paling berisiko: klik yang salah sasaran menekan
tombol lain di broker. Tes di sini menekankan penolakan, bukan keberhasilan.
"""
import unittest

from PIL import Image, ImageDraw

from candlelab.auto_click import (
    MIN_BUTTON_HEIGHT,
    MIN_BUTTON_WIDTH,
    click_point,
    find_button_pair,
    find_buttons,
)
from candlelab.auto_click_win import ClickLimiter, check_before_click, is_point_inside


def broker_panel(width=300, height=400, higher_box=(60, 40, 200, 160),
                  lower_box=(60, 210, 200, 330)):
    image = Image.new("RGB", (width, height), "#FFFFFF")
    draw = ImageDraw.Draw(image)
    if higher_box:
        draw.rectangle(higher_box, fill="#21B45B")
    if lower_box:
        draw.rectangle(lower_box, fill="#E03A3A")
    return image


class ButtonDetectionTests(unittest.TestCase):
    def test_finds_stacked_higher_and_lower(self):
        pair, reason = find_button_pair(broker_panel())
        self.assertIsNotNone(pair, reason)
        self.assertEqual(pair.higher, (130, 100))
        self.assertEqual(pair.lower, (130, 270))

    def test_ignores_small_icon_of_same_colour(self):
        image = broker_panel()
        ImageDraw.Draw(image).rectangle((250, 100, 268, 118), fill="#21B45B")
        candidates = find_buttons(image)
        self.assertEqual(len(candidates), 2, "ikon kecil harus ditolak")
        self.assertTrue(all(min(c.box[2] - c.box[0], c.box[3] - c.box[1]) >= MIN_BUTTON_WIDTH
                            for c in candidates))

    def test_rejects_when_only_one_button_visible(self):
        pair, reason = find_button_pair(broker_panel(lower_box=None))
        self.assertIsNone(pair)
        self.assertIn("tidak dikenali", reason)

    def test_rejects_when_buttons_not_stacked(self):
        # Kedua tombol ada, tetapi bersebelahan: bukan panel broker biasa.
        pair, reason = find_button_pair(
            broker_panel(higher_box=(20, 40, 140, 160), lower_box=(160, 40, 280, 160)))
        self.assertIsNone(pair)
        self.assertIn("sejajar", reason)

    def test_rejects_plain_candles(self):
        # Chart candle: body sempit dan tinggi, harus ditolak sebagai tombol.
        image = Image.new("RGB", (400, 300), "#101513")
        draw = ImageDraw.Draw(image)
        for index in range(10):
            x = 20 + index * 30
            draw.rectangle((x, 60, x + 12, 240), fill="#26A69A" if index % 2 else "#EF5350")
        pair, reason = find_button_pair(image)
        self.assertIsNone(pair, "candle tidak boleh dianggap tombol broker")

    def test_click_point_offsets_to_screen(self):
        pair, _ = find_button_pair(broker_panel())
        self.assertEqual(click_point((300, 400), (100, 250), pair, 1), (230, 350))
        self.assertEqual(click_point((300, 400), (100, 250), pair, -1), (230, 520))

    def test_button_height_floor_is_sane(self):
        self.assertGreaterEqual(MIN_BUTTON_HEIGHT, 40)


class ClickSafetyTests(unittest.TestCase):
    """Semua pengaman harus gagal-tertutup (menolak), bukan gagal-terbuka."""

    def test_outside_region_is_rejected(self):
        region = (100, 200, 400, 600)
        with _patched_platform("win32"):
            guard = check_before_click((900, 300), region, require_active=False,
                                       window_title="IQ Option")
        self.assertFalse(guard.allowed)
        self.assertIn("luar area", guard.reason)

    def test_inside_region_passes_point_check(self):
        self.assertTrue(is_point_inside((200, 300), (100, 200, 400, 600)))
        self.assertFalse(is_point_inside((99, 300), (100, 200, 400, 600)))

    def test_non_windows_always_rejected(self):
        with _patched_platform("linux"):
            guard = check_before_click((200, 300), (100, 200, 400, 600),
                                       require_active=False, window_title="IQ Option")
            self.assertFalse(guard.allowed)
            self.assertIn("Windows", guard.reason)


class ClickLimiterTests(unittest.TestCase):
    def test_blocks_two_clicks_within_interval(self):
        limiter = ClickLimiter(min_interval=20, max_per_session=10)
        self.assertTrue(limiter.allow(0)[0])
        limiter.record(0)
        allowed, reason = limiter.allow(5)
        self.assertFalse(allowed)
        self.assertIn("Terlalu cepat", reason)

    def test_allows_after_interval(self):
        limiter = ClickLimiter(min_interval=20, max_per_session=10)
        limiter.record(0)
        self.assertTrue(limiter.allow(25)[0])

    def test_stops_at_session_limit(self):
        limiter = ClickLimiter(min_interval=0, max_per_session=2)
        for moment in (0, 10):
            self.assertTrue(limiter.allow(moment)[0])
            limiter.record(moment)
        allowed, reason = limiter.allow(99)
        self.assertFalse(allowed)
        self.assertIn("Batas", reason)

    def test_reset_clears_count(self):
        limiter = ClickLimiter(min_interval=0, max_per_session=1)
        limiter.record(0)
        self.assertFalse(limiter.allow(10)[0])
        limiter.reset()
        self.assertTrue(limiter.allow(10)[0])


class _patched_platform:
    """Ganti sys.platform sementara pada modul auto_click_win."""

    def __init__(self, value):
        self.value = value

    def __enter__(self):
        from candlelab import auto_click_win
        self.module = auto_click_win
        self.original = auto_click_win.sys.platform
        auto_click_win.sys.platform = self.value
        return self

    def __exit__(self, *exc):
        self.module.sys.platform = self.original
        return False


if __name__ == "__main__":
    unittest.main()
