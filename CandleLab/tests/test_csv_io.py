from pathlib import Path
import tempfile
import unittest

from candlelab.csv_io import export_csv, load_csv, parse_csv
from candlelab.model import GREEN, RED, make_demo_candles


class CsvTests(unittest.TestCase):
    def test_english_header_and_ignored_direction(self):
        rows = parse_csv(
            "time,open,high,low,close,direction\n"
            "2026-01-01T00:00:00Z,10,12,9,11,green\n"
            "2026-01-01T01:00:00Z,11,12,10,10.5,red\n"
        )
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0].direction, GREEN)
        self.assertEqual(rows[1].direction, RED)
        self.assertGreater(rows[0].timestamp, 0)

    def test_indonesian_semicolon_and_decimal_comma(self):
        rows = parse_csv("pembukaan;tertinggi;terendah;penutupan\n10,5;12,0;9,5;11,25\n")
        self.assertEqual(rows[0].open, 10.5)
        self.assertEqual(rows[0].close, 11.25)

    def test_headerless_ohlc(self):
        rows = parse_csv("10,12,9,11\n11,13,10,12\n")
        self.assertEqual([(c.open, c.close) for c in rows], [(10, 11), (11, 12)])

    def test_headerless_timestamp_and_ohlc(self):
        rows = parse_csv("1767225600,10,12,9,11\n")
        self.assertEqual(rows[0].timestamp, 1767225600)
        self.assertEqual(rows[0].open, 10)

    def test_quoted_csv_and_bom(self):
        rows = parse_csv('\ufeff"open","high","low","close"\n"10","12","9","11"\n')
        self.assertEqual(rows[0].close, 11)

    def test_invalid_row_reports_line(self):
        with self.assertRaisesRegex(ValueError, "baris 3"):
            parse_csv("open,high,low,close\n10,12,9,11\n11,10,9,12\n")
        with self.assertRaisesRegex(ValueError, "Kolom"):
            parse_csv("open,high,close\n10,12,11\n")
        with self.assertRaisesRegex(ValueError, "kosong"):
            parse_csv("  \n")

    def test_too_many_rows_are_rejected(self):
        text = "open,high,low,close\n" + "10,12,9,11\n" * 2001
        with self.assertRaisesRegex(ValueError, "Maksimal"):
            parse_csv(text)

    def test_round_trip(self):
        candles = make_demo_candles(25)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "roundtrip.csv"
            export_csv(candles, path)
            restored = load_csv(path)
        self.assertEqual(len(restored), len(candles))
        for expected, actual in zip(candles, restored):
            self.assertAlmostEqual(expected.open, actual.open)
            self.assertAlmostEqual(expected.high, actual.high)
            self.assertAlmostEqual(expected.low, actual.low)
            self.assertAlmostEqual(expected.close, actual.close)
            self.assertAlmostEqual(expected.timestamp, actual.timestamp, places=2)


if __name__ == "__main__":
    unittest.main()
