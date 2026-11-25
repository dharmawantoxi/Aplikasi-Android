import unittest

from candlelab.hud_layout import BADGE_CORNERS, badge_positions, best_badge_corner, next_corner

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
