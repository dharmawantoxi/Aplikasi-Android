import unittest

from candlelab.hud_layout import (
    BADGE_CORNERS,
    badge_positions,
    best_badge_corner,
    clamp_position,
    estimate_badge_width,
    fit_size,
    next_corner,
)

W, H = 1918, 987
BW, BH = 176, 46


def overlaps(region, x, y, w=BW, h=BH):
    x1, y1, x2, y2 = region
    return not (x + w <= x1 or x >= x2 or y + h <= y1 or y >= y2)


class BadgeLayoutTests(unittest.TestCase):
    def test_positions_respect_margins(self):
        positions = badge_positions(W, H, BW, BH)
        self.assertEqual(len(positions), 4)
        for name, (x, y) in positions.items():
            self.assertGreaterEqual(x, 0, name)
            self.assertGreaterEqual(y, 0, name)
            self.assertLessEqual(x + BW, W, name)
            self.assertLessEqual(y + BH, H, name)

    def test_badge_avoids_the_chart_region(self):
        chart = (100, 250, 1430, 800)
        corner = best_badge_corner(chart, W, H, BW, BH)
        x, y = badge_positions(W, H, BW, BH)[corner]
        self.assertFalse(overlaps(chart, x, y), f"{corner} menutupi chart")

    def test_no_region_prefers_top_right(self):
        self.assertEqual(best_badge_corner(None, W, H, BW, BH), "top-right")

    def test_every_corner_is_cyclable(self):
        seen = set()
        corner = "top-right"
        for _ in range(len(BADGE_CORNERS)):
            seen.add(corner)
            corner = next_corner(corner)
        self.assertEqual(seen, set(BADGE_CORNERS))


if __name__ == "__main__":
    unittest.main()


class BadgeSizingTests(unittest.TestCase):
    """Regresi: teks badge pernah terpotong karena lebar dipaku 176 px."""

    def test_long_detail_widens_badge(self):
        narrow = estimate_badge_width("HIJAU", "next\ndata 8", 14)
        wide = estimate_badge_width("HIJAU", "next · data 8\nperkiraan lemah", 14)
        self.assertGreater(wide, narrow)
        self.assertGreater(wide, 176)

    def test_warning_text_fits(self):
        # "perkiraan lemah" adalah baris terpanjang yang dulu terpotong.
        width = estimate_badge_width("HIJAU", "next · data 8\nperkiraan lemah", 14)
        text_width = len("perkiraan lemah") * 14 * 0.60
        self.assertGreaterEqual(width, text_width)

    def test_clamp_keeps_badge_on_screen(self):
        x, y = clamp_position(1900, 1050, 1920, 1080, 200, 60)
        self.assertLessEqual(x + 200, 1920)
        self.assertLessEqual(y + 60, 1080)
        self.assertGreaterEqual(x, 4)
        self.assertGreaterEqual(y, 4)

    def test_clamp_handles_negative_drag(self):
        x, y = clamp_position(-300, -80, 1920, 1080, 200, 60)
        self.assertEqual((x, y), (4, 4))

    def test_content_wins_on_absurdly_small_screen(self):
        # Runner CI tanpa display melaporkan layar sangat kecil. Teks tetap
        # diutamakan: lebih baik badge keluar layar daripada terpotong.
        width, height = fit_size(400, 80, 320, 200)
        self.assertEqual((width, height), (400, 80))

    def test_fit_size_bounded_on_real_screen(self):
        width, height = fit_size(4000, 80, 1920, 1080)
        self.assertEqual(width, 1920 - 8)
        self.assertEqual(height, 80)

    def test_fit_size_never_below_minimum(self):
        width, height = fit_size(10, 10, 1920, 1080)
        self.assertGreaterEqual(width, 120)
        self.assertGreaterEqual(height, 30)


class BadgeCornerPreferenceTests(unittest.TestCase):
    """Regresi: badge selalu jatuh di kanan bawah, menutupi Higher/Lower."""

    def test_prefers_top_corner_over_bottom_right(self):
        # Area chart di kiri layar: keempat sudut bebas, jadi preferensi berlaku.
        corner = best_badge_corner((100, 250, 900, 700), 1920, 1080, 226, 46)
        self.assertEqual(corner, "top-right")

    def test_falls_back_when_top_right_overlaps_chart(self):
        # Chart menutupi pojok kanan atas layar.
        corner = best_badge_corner((0, 0, 1920, 300), 1920, 1080, 226, 46)
        self.assertNotEqual(corner, "top-right")

    def test_still_avoids_chart_when_possible(self):
        region = (100, 250, 900, 700)
        corner = best_badge_corner(region, 1920, 1080, 226, 46)
        position = badge_positions(1920, 1080, 226, 46)[corner]
        self.assertFalse(overlaps(region, *position, 226, 46))
