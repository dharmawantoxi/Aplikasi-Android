"""Aturan putaran tebak otomatis: kapan dinilai, kapan dilewati."""
import unittest

from candlelab.model import Candle, CandleModel, DOJI, GREEN, RED
from candlelab.rounds import RoundTracker


def candle(direction: int) -> Candle:
    if direction == DOJI:
        return Candle(1.0, 2.0, 0.0, 1.0)
    if direction == GREEN:
        return Candle(1.0, 2.0, 0.0, 2.0)
    return Candle(2.0, 2.5, 0.0, 1.0)


def series(*directions):
    return [candle(d) for d in directions]


class RoundTrackerTests(unittest.TestCase):
    def setUp(self):
        self.tracker = RoundTracker()

    def feed(self, candles):
        prediction = CandleModel.predict(candles, 1, 0)
        return self.tracker.observe(candles, prediction)

    def test_first_read_opens_round_without_scoring(self):
        finished = self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        self.assertIsNone(finished)
        self.assertIsNotNone(self.tracker.current)
        self.assertEqual(self.tracker.stats.played, 0)

    def test_prediction_is_not_updated_while_candle_runs(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        locked = self.tracker.current.direction
        # Pembacaan berulang dengan data yang sama tidak boleh mengubah tebakan.
        for _ in range(4):
            self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        self.assertEqual(self.tracker.current.direction, locked)
        self.assertEqual(self.tracker.stats.played, 0)

    def test_finished_candle_scores_previous_prediction(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        locked = self.tracker.current.direction
        actual = GREEN if locked != GREEN else RED
        finished = self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, actual))
        self.assertIsNotNone(finished)
        self.assertEqual(finished.actual, actual)
        self.assertFalse(finished.correct)
        self.assertEqual(self.tracker.stats.losses, 1)
        self.assertEqual(self.tracker.stats.played, 1)

    def test_correct_prediction_is_counted(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        locked = self.tracker.current.direction
        finished = self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, locked))
        self.assertTrue(finished.correct)
        self.assertEqual(self.tracker.stats.wins, 1)

    def test_doji_is_skipped_not_counted_as_loss(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        finished = self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, DOJI))
        self.assertIsNone(finished)
        self.assertEqual(self.tracker.stats.skipped, 1)
        self.assertEqual(self.tracker.stats.played, 0)

    def test_dropped_candle_resets_instead_of_scoring(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, RED, GREEN))
        # Deteksi kehilangan satu candle: jumlah menurun, bukan bertambah.
        finished = self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        self.assertIsNone(finished)
        self.assertIn("menurun", self.tracker.reset_reason)

    def test_scrolling_chart_scores_without_growing(self):
        """Chart broker bergeser, bukan bertambah panjang."""
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        locked = self.tracker.current.direction
        # Jendela tetap 6 candle; satu candle baru masuk, satu keluar di kiri.
        finished = self.feed(series(RED, GREEN, GREEN, RED, GREEN, locked))
        self.assertIsNotNone(finished, "pergeseran satu langkah harus dinilai")
        self.assertTrue(finished.correct)
        self.assertEqual(self.tracker.stats.wins, 1)

    def test_two_step_jump_resets_instead_of_scoring(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        # Dua candle masuk sekaligus: candle yang dinilai tidak jelas.
        self.feed(series(GREEN, GREEN, RED, GREEN, RED, GREEN))
        self.assertIn("bergeser", self.tracker.reset_reason)

    def test_streak_tracks_consecutive_results(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        first = self.tracker.current.direction
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, first))
        self.assertEqual(self.tracker.stats.streak, 1)
        second = self.tracker.current.direction
        wrong = RED if second == GREEN else GREEN
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, first, wrong))
        self.assertEqual(self.tracker.stats.streak, -1)

    def test_unavailable_prediction_keeps_no_round(self):
        finished = self.feed(series(GREEN, RED))
        self.assertIsNone(finished)
        self.assertIsNone(self.tracker.current)

    def test_reset_reason_is_settable(self):
        """UI mengosongkan alasan setelah ditampilkan; harus punya setter."""
        self.tracker.reset_reason = "uji"
        self.assertEqual(self.tracker.reset_reason, "uji")
        self.tracker.reset_reason = ""
        self.assertEqual(self.tracker.reset_reason, "")

    def test_every_attribute_ui_writes_on_tracker_exists(self):
        """Cegah AttributeError di jalur live: cek atribut yang ditulis ui.py.

        ui.py tidak diimpor karena membutuhkan tkinter; berkasnya dipindai
        sebagai teks dan cukup bagian setelah ``RoundTracker`` dibuat.
        """
        import re
        from pathlib import Path

        from candlelab.rounds import RoundTracker

        source = Path(__file__).resolve().parents[1] / "candlelab" / "ui.py"
        text = source.read_text(encoding="utf-8")
        text = text[text.index("RoundTracker()"):]
        written = set(re.findall(r"self\.rounds\.([a-zA-Z_][\w]*)", text))
        self.assertIn("reset_reason", written, "pemindaian gagal menemukan atribut")
        tracker = RoundTracker()
        for name in sorted(written):
            self.assertTrue(hasattr(tracker, name), f"RoundTracker tidak punya atribut {name}")
            current = getattr(tracker, name)
            try:
                setattr(tracker, name, current)
            except AttributeError:
                self.fail(f"atribut {name} tidak dapat ditulis dari UI")

    def test_waiting_seconds_grows_while_candle_runs(self):
        """Badge memakai ini agar tidak tampak diam."""
        self.assertIsNone(self.tracker.waiting_seconds(), "belum ada putaran")
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        self.assertIsNotNone(self.tracker.waiting_seconds())

    def test_waiting_seconds_stops_after_resolution(self):
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN))
        locked = self.tracker.current.direction
        self.feed(series(GREEN, RED, GREEN, GREEN, RED, GREEN, locked))
        # Putaran baru sudah dibuka; tidak boleh melaporkan waktu putaran lama.
        self.assertIsNotNone(self.tracker.waiting_seconds())
        self.assertTrue(self.tracker.current is not None)

    def test_status_text(self):
        self.assertEqual(self.tracker.status_text(), "0/0 benar")
        self.tracker.stats.wins, self.tracker.stats.losses = 3, 1
        self.assertEqual(self.tracker.status_text(), "3/4 benar")


if __name__ == "__main__":
    unittest.main()
