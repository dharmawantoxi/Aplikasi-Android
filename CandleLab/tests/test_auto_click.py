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


class _Prediction:
    """Prediksi minimal untuk menguji RoundTracker tanpa model."""

    def __init__(self, direction: int):
        self.available = True
        self.green_probability = 0.6 if direction == 1 else 0.4
        self.predicted_direction = direction
        self.pattern = (1,)
        self.matches = 1


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
        limiter = ClickLimiter(min_interval=20)
        self.assertTrue(limiter.allow(0)[0])
        limiter.record(0)
        allowed, reason = limiter.allow(5)
        self.assertFalse(allowed)
        self.assertIn("Terlalu cepat", reason)

    def test_allows_after_interval(self):
        limiter = ClickLimiter(min_interval=20)
        limiter.record(0)
        self.assertTrue(limiter.allow(25)[0])

    def test_continues_past_fifty(self):
        limiter = ClickLimiter()
        for moment in range(0, 60 * 55, 60):
            self.assertTrue(limiter.allow(moment)[0])
            limiter.record(moment)
        self.assertEqual(limiter.count, 55)

    def test_reset_clears_count(self):
        limiter = ClickLimiter()
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


class FirstClickTests(unittest.TestCase):
    """Regresi: klik pertama dulu ditahan 'terlalu cepat sejak klik sebelumnya'."""

    def test_first_click_is_allowed_immediately(self):
        limiter = ClickLimiter(min_interval=20.0)
        allowed, reason = limiter.allow(1000.0)
        self.assertTrue(allowed, reason)

    def test_second_click_inside_interval_is_refused(self):
        limiter = ClickLimiter(min_interval=20.0)
        limiter.allow(1000.0)
        limiter.record(1000.0)
        allowed, _ = limiter.allow(1005.0)
        self.assertFalse(allowed)

    def test_click_allowed_again_after_interval(self):
        limiter = ClickLimiter(min_interval=20.0)
        limiter.allow(1000.0)
        limiter.record(1000.0)
        allowed, _ = limiter.allow(1025.0)
        self.assertTrue(allowed)


class NewOptionTests(unittest.TestCase):
    """Tombol NEW OPTION oranye harus dikenali tanpa tertukar dengan LOWER."""

    ORANGE = (235, 110, 0)     # diukur dari screenshot IQ Option
    LOWER_RED = (215, 76, 57)
    GREEN = (0, 155, 46)

    def _image(self, blocks):
        from PIL import Image

        image = Image.new("RGB", (420, 780), (255, 255, 255))
        for box, colour in blocks:
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    image.putpixel((x, y), colour)
        return image

    def _session_buttons(self):
        """Susunan seperti layar IQ Option: HIGHER hijau, LOWER merah."""
        return [
            ((160, 460, 400, 580), self.GREEN),
            ((160, 620, 400, 740), self.LOWER_RED),
        ]

    def test_orange_button_is_detected(self):
        from candlelab.auto_click import find_new_option

        image = self._image(self._session_buttons() + [((20, 60, 300, 300), self.ORANGE)])
        box, reason = find_new_option(image)
        self.assertIsNotNone(box, reason)
        self.assertLess(box[1], 400, "tombol oranye harus ditemukan di bagian atas")

    def test_orange_is_not_mistaken_for_lower(self):
        from candlelab.auto_click import find_button_pair, find_new_option

        image = self._image(self._session_buttons() + [((20, 60, 300, 300), self.ORANGE)])
        self.assertIsNotNone(find_new_option(image)[0])
        pair, reason = find_button_pair(image)
        # Pasangan tetap harus ketemu, dan Lower tidak boleh jatuh di area oranye.
        self.assertIsNotNone(pair, reason)
        self.assertGreater(pair.lower[1], 500, "Lower tertukar dengan tombol NEW OPTION")

    def test_no_orange_reports_nothing(self):
        from candlelab.auto_click import find_new_option

        image = self._image(self._session_buttons())
        box, reason = find_new_option(image)
        self.assertIsNone(box)
        self.assertIn("tidak terlihat", reason)

    def test_green_and_red_are_not_orange(self):
        from candlelab.auto_click import orange_mask

        image = self._image([((10, 10, 200, 200), self.GREEN),
                             ((10, 210, 200, 400), self.LOWER_RED)])
        self.assertEqual(sum(orange_mask(image)), 0,
                         "hijau/merah tidak boleh masuk masker oranye")

    def test_new_option_point_is_absolute(self):
        from candlelab.auto_click import new_option_point

        self.assertEqual(new_option_point((100, 100), (1450, 110), (20, 40, 80, 100)),
                         (1450 + 50, 110 + 70))

    def test_wait_helper_gives_up_after_timeout(self):
        from candlelab.auto_click_win import wait_for_new_option

        calls = []

        def always_visible():
            calls.append(1)
            return True

        self.assertFalse(wait_for_new_option(always_visible, timeout=0.2, interval=0.05))
        self.assertGreater(len(calls), 1)

    def test_wait_helper_returns_once_hidden(self):
        from candlelab.auto_click_win import wait_for_new_option

        state = {"n": 0}

        def hides_quickly():
            state["n"] += 1
            return state["n"] < 2

        self.assertTrue(wait_for_new_option(hides_quickly, timeout=1.0, interval=0.02))


