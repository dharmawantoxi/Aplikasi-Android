import unittest

from candlelab.hud_layout import HEIGHT, WIDTH, best_corner, corner_positions

SCREEN_W, SCREEN_H = 1920, 1080


def overlaps(region, x, y, width=WIDTH, height=HEIGHT):
    x1, y1, x2, y2 = region
    return not (x + width <= x1 or x >= x2 or y + height <= y1 or y >= y2)


class BestCornerTests(unittest.TestCase):
    def test_no_region_defaults_to_bottom_right(self):
        self.assertEqual(best_corner(None, SCREEN_W, SCREEN_H), "bottom-right")

    def test_chart_top_left_moves_panel_to_bottom_right(self):
        chart = (100, 100, 900, 500)
        corner = best_corner(chart, SCREEN_W, SCREEN_H)
        self.assertEqual(corner, "bottom-right")

    def test_chart_bottom_left_moves_panel_top_right(self):
        chart = (100, 500, 900, 1000)
        corner = best_corner(chart, SCREEN_W, SCREEN_H)
        self.assertEqual(corner, "top-right")

    def test_chart_top_right_moves_panel_bottom_left(self):
        chart = (1000, 100, 1800, 500)
        corner = best_corner(chart, SCREEN_W, SCREEN_H)
        self.assertEqual(corner, "bottom-left")

    def test_chart_spanning_bottom_forces_top_corner(self):
        # Chart lebar yang menyentuh bagian bawah layar: sudut bawah akan
        # menutupinya, jadi panel harus pindah ke atas.
        chart = (200, 300, 1700, 1000)
        corner = best_corner(chart, SCREEN_W, SCREEN_H)
        self.assertTrue(corner.startswith("top-"), corner)

    def test_common_iq_option_layout_does_not_overlap(self):
        # Tata letak seperti screenshot pengguna: chart di tengah, panel kanan.
        chart = (100, 250, 1430, 800)
        corner = best_corner(chart, SCREEN_W, SCREEN_H)
        x, y = corner_positions(SCREEN_W, SCREEN_H)[corner]
        self.assertFalse(overlaps(chart, x, y), f"{corner} menutupi chart")


if __name__ == "__main__":
    unittest.main()
