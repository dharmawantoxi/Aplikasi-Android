import unittest

from candlelab.model import Candle, close_vs_three


class CloseSignalTests(unittest.TestCase):
    def signal(self, prices, previous=0):
        # Semua candle merah: hasil harus mengikuti close, bukan warna.
        return close_vs_three([Candle(p+1, p+1, p, p) for p in prices], previous)

    def test_both_directions(self):
        self.assertEqual(self.signal([100, 102, 101, 103]).predicted_direction, 1)
        self.assertEqual(self.signal([100, 102, 101, 99]).predicted_direction, -1)

    def test_equal_and_warmup(self):
        for previous in [-1, 0, 1]:
            self.assertEqual(self.signal([100, 102, 101, 101], previous).predicted_direction, previous)
        self.assertFalse(self.signal([102, 101, 100], 1).available)
