import json
from pathlib import Path
import tempfile
import unittest

from candlelab.experiments import summarise
from candlelab.model import DOJI, GREEN, RED, Candle, CandleModel, make_demo_candles
from candlelab.storage import AppState, JsonStore


class ExperimentTests(unittest.TestCase):
    def setUp(self):
        self.candles = make_demo_candles()
        self.state = AppState(list(self.candles), is_demo=True)
        self.prediction = CandleModel.predict(self.candles, 1, 60)
        self.experiment = self.state.lock_prediction(self.prediction)

    def test_only_one_pending_prediction(self):
        with self.assertRaisesRegex(ValueError, "menunggu"):
            self.state.lock_prediction(self.prediction)

    def test_correct_prediction_is_scored(self):
        direction = GREEN if self.prediction.green_probability > 0.5 else RED
        candle = Candle(10, 12, 9, 11) if direction == GREEN else Candle(11, 12, 9, 10)
        resolved = self.state.append_candle(candle)
        self.assertEqual(resolved, self.experiment)
        self.assertEqual(resolved.status, "resolved")
        self.assertEqual(resolved.score, 1.0)
        self.assertFalse(self.state.is_demo)
        summary = summarise(self.state.experiments)
        self.assertEqual(summary.resolved, 1)
        self.assertEqual(summary.accuracy, 1.0)

    def test_wrong_prediction_and_brier(self):
        direction = RED if self.prediction.green_probability > 0.5 else GREEN
        candle = Candle(10, 12, 9, 11) if direction == GREEN else Candle(11, 12, 9, 10)
        resolved = self.state.append_candle(candle)
        self.assertEqual(resolved.score, 0.0)
        actual_green = 1.0 if direction == GREEN else 0.0
        self.assertAlmostEqual(resolved.brier_score,
                               (self.prediction.green_probability - actual_green) ** 2)

    def test_doji_is_not_in_accuracy(self):
        resolved = self.state.append_candle(Candle(10, 12, 9, 10))
        self.assertEqual(resolved.actual_direction, DOJI)
        self.assertIsNone(resolved.score)
        self.assertEqual(summarise(self.state.experiments).resolved, 0)

    def test_replacing_data_cancels_pending(self):
        self.state.replace_candles(make_demo_candles(30))
        self.assertEqual(self.experiment.status, "cancelled")
        self.assertIsNone(self.state.pending)
        self.assertEqual(summarise(self.state.experiments).resolved, 0)

    def test_clear_history_preserves_pending(self):
        self.state.clear_history()
        self.assertEqual(self.state.experiments, [self.experiment])


class StorageTests(unittest.TestCase):
    def test_first_launch_creates_demo_and_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "data.json"
            store = JsonStore(path)
            state = store.load()
            self.assertTrue(state.is_demo)
            self.assertTrue(path.exists())
            restored = JsonStore(path).load()
            self.assertEqual(restored.candles, state.candles)
            self.assertEqual(restored.experiments, [])

    def test_pending_prediction_resolves_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.json"
            store = JsonStore(path)
            state = store.load()
            state.lock_prediction(CandleModel.predict(state.candles))
            state.append_candle(Candle(100, 102, 99, 101, 2000000000))
            store.save(state)
            restored = JsonStore(path).load()
            self.assertIsNone(restored.pending)
            self.assertEqual(restored.experiments[-1].status, "resolved")

    def test_pending_survives_if_actual_not_added(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.json"
            store = JsonStore(path)
            state = store.load()
            state.lock_prediction(CandleModel.predict(state.candles))
            store.save(state)
            restored = JsonStore(path).load()
            self.assertIsNotNone(restored.pending)

    def test_corrupt_file_is_backed_up(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.json"
            path.write_text("{not-json", encoding="utf-8")
            store = JsonStore(path)
            state = store.load()
            self.assertTrue(state.is_demo)
            self.assertIn("tidak dapat dibaca", store.warning)
            self.assertEqual(len(list(path.parent.glob("*.corrupt-*.json"))), 1)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["format_version"], 1)


if __name__ == "__main__":
    unittest.main()
