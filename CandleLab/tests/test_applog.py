"""Tes log aplikasi: isinya harus cukup untuk mendiagnosis tanpa screenshot."""
import unittest

from candlelab.applog import CLICK, ERROR, INFO, REJECT, AppLog


class AppLogTests(unittest.TestCase):
    def setUp(self):
        self.log = AppLog(keep=5)
        jam = iter([f"10:00:0{i}" for i in range(10)])
        self.log.clock = lambda: next(jam)

    def test_entries_carry_time_and_level(self):
        entry = self.log.reject("jendela tidak aktif")
        self.assertEqual(entry.level, REJECT)
        self.assertIn("10:00:00", entry.line())
        self.assertIn("jendela tidak aktif", entry.line())

    def test_keep_limit_drops_oldest(self):
        for i in range(8):
            self.log.info(f"catatan {i}")
        self.assertEqual(len(self.log.entries), 5)
        self.assertIn("catatan 7", self.log.text())
        self.assertNotIn("catatan 2", self.log.text())

    def test_whitespace_is_normalised(self):
        # Pesan dari Windows sering mengandung baris baru.
        entry = self.log.info("baris satu\n\n  baris   dua")
        self.assertEqual(entry.message, "baris satu baris dua")

    def test_text_is_copy_ready(self):
        self.log.info("live dimulai")
        self.log.click("LOWER di 1780,600")
        self.log.reject("titik di luar area")
        isi = self.log.text()
        self.assertEqual(len(isi.splitlines()), 3)
        self.assertIn("KLIK", isi)
        self.assertIn("TOLAK", isi)

    def test_summary_counts_by_level(self):
        self.log.info("a")
        self.log.reject("b")
        self.log.reject("c")
        self.log.error("d")
        ringkas = self.log.summary()
        self.assertIn("4 catatan", ringkas)
        self.assertIn("2 tolak", ringkas)
        self.assertIn("1 galat", ringkas)

    def test_empty_summary(self):
        self.assertEqual(self.log.summary(), "log kosong")

    def test_tail_returns_latest_lines(self):
        for i in range(6):
            self.log.info(f"n{i}")
        tail = self.log.tail(2).splitlines()
        self.assertEqual(len(tail), 2)
        self.assertIn("n5", tail[-1])

    def test_clear_empties_everything(self):
        self.log.info("a")
        self.log.clear()
        self.assertEqual(self.log.count(), 0)
        self.assertEqual(self.log.text(), "")

    def test_unknown_level_falls_back_to_info(self):
        entry = self.log.add("ANEh", "pesan")
        self.assertEqual(entry.level, INFO)

    def test_click_and_error_helpers(self):
        self.assertEqual(self.log.click("x").level, CLICK)
        self.assertEqual(self.log.error("y").level, ERROR)


class UiWiringTests(unittest.TestCase):
    """Log harus benar-benar dipakai dan bisa dibuka dari aplikasi."""

    @classmethod
    def setUpClass(cls):
        from pathlib import Path

        cls.ui = (Path(__file__).resolve().parents[1] / "candlelab" / "ui.py").read_text(
            encoding="utf-8")

    def test_ui_creates_a_log(self):
        self.assertIn("AppLog()", self.ui)

    def test_rejections_are_logged(self):
        start = self.ui.index("def _click_problem")
        body = self.ui[start:start + 400]
        self.assertIn("_log(REJECT", body)

    def test_successful_clicks_are_logged(self):
        self.assertIn("_log(CLICK", self.ui)

    def test_log_window_can_copy(self):
        self.assertIn("def open_log_window", self.ui)
        self.assertIn("clipboard_append", self.ui)
        self.assertIn("Buka log (bisa disalin)", self.ui)
