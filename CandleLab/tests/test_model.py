import unittest
from unittest import mock

from candlelab.model import (
    DOJI, GREEN, PRIOR_STRENGTH, RED,
    Candle, CandleModel, make_demo_candles,
)


def candle(direction, timestamp=0):
    if direction == GREEN:
        return Candle(10, 12, 9, 11, timestamp)
    if direction == RED:
        return Candle(11, 12, 9, 10, timestamp)
    return Candle(10, 12, 9, 10, timestamp)


class CandleTests(unittest.TestCase):
    def test_direction_and_ohlc_validation(self):
        self.assertEqual(candle(GREEN).direction, GREEN)
        self.assertEqual(candle(RED).direction, RED)
        self.assertEqual(candle(DOJI).direction, DOJI)
        with self.assertRaisesRegex(ValueError, "High"):
            Candle(10, 9, 8, 11)
        with self.assertRaisesRegex(ValueError, "Low"):
            Candle(10, 12, 11, 9)
        with self.assertRaisesRegex(ValueError, "angka"):
            Candle(float("nan"), 12, 9, 11)

    def test_demo_is_deterministic_and_valid(self):
        first = make_demo_candles()
        second = make_demo_candles()
        self.assertEqual(first, second)
        self.assertEqual(len(first), 96)
        self.assertGreater(sum(c.direction == GREEN for c in first), 0)
        self.assertGreater(sum(c.direction == RED for c in first), 0)


class PredictionTests(unittest.TestCase):
    def test_insufficient_data_is_explicit(self):
        result = CandleModel.predict([candle(GREEN)] * 19)
        self.assertFalse(result.available)
        self.assertIn("1 candle", result.reason)

    def test_doji_is_ignored_in_pattern(self):
        # Pola non-doji: hijau, merah, hijau. Doji di tengah tidak mengubah pola.
        sequence = [GREEN, RED, GREEN] * 7
        candles = [candle(value) for value in sequence]
        candles.insert(10, candle(DOJI))
        result = CandleModel.predict(candles, order=2, window=0)
        self.assertTrue(result.available)
        self.assertEqual(result.pattern, (RED, GREEN))
        directions = [value for value in sequence if value != DOJI]
        expected_matches = sum(
            tuple(directions[i - 2:i]) == (RED, GREEN)
            for i in range(2, len(directions))
        )
        self.assertEqual(result.matches, expected_matches)

    def test_bayesian_formula_and_complement(self):
        # Enam hijau lalu enam merah, diulang. Pola terakhir merah -> hijau.
        sequence = ([GREEN] * 6 + [RED] * 6) * 2
        result = CandleModel.predict([candle(value) for value in sequence], 1, 0)
        base = 12 / 24
        # Setelah merah: 5 merah + 1 hijau pada setiap blok, kecuali target terakhir.
        matches = 11
        greens = 1
        expected = (greens + PRIOR_STRENGTH * base) / (matches + PRIOR_STRENGTH)
        self.assertEqual(result.matches, matches)
        self.assertEqual(result.green_matches, greens)
        self.assertAlmostEqual(result.green_probability, expected)
        self.assertAlmostEqual(result.green_probability + result.red_probability, 1.0)

    def test_rare_pattern_is_shrunk_towards_base(self):
        # Pola RR hanya memiliki satu outcome hijau; hasil tidak boleh 100%.
        directions = [GREEN, RED] * 10 + [RED, RED, GREEN, RED, RED]
        result = CandleModel.predict([candle(value) for value in directions], 2, 0)
        self.assertTrue(result.available)
        self.assertLess(result.green_probability, 1.0)
        self.assertGreater(result.green_probability, 0.0)

    def test_window_and_order_are_validated(self):
        candles = make_demo_candles()
        with self.assertRaises(ValueError):
            CandleModel.predict(candles, order=4)
        with self.assertRaises(ValueError):
            CandleModel.predict(candles, window=45)
        short = CandleModel.predict(candles, window=30)
        self.assertTrue(short.available)
        self.assertEqual(short.directional_count, 30)


class BacktestTests(unittest.TestCase):
    def test_backtest_has_no_future_leakage(self):
        candles = make_demo_candles(45)
        with mock.patch.object(CandleModel, "predict", wraps=CandleModel.predict) as wrapped:
            CandleModel.backtest(candles, 2, 30)
        # Pada target ke-i, candle terakhir yang boleh dilihat model adalah i-1.
        for call in wrapped.call_args_list:
            training = call.args[0]
            self.assertEqual(len(training), candles.index(training[-1]) + 1)
        # Mengubah target terakhir tidak mengubah data pelatihan pada langkah apa pun.
        changed = list(candles[:-1])
        last = candles[-1]
        close = last.open + 5 if last.direction != GREEN else last.open - 5
        changed.append(Candle(last.open, max(last.high, last.open, close),
                              min(last.low, last.open, close), close, last.timestamp))
        with mock.patch.object(CandleModel, "predict", wraps=CandleModel.predict) as changed_wrapped:
            CandleModel.backtest(changed, 2, 30)
        original_training = [call.args[0] for call in wrapped.call_args_list]
        changed_training = [call.args[0] for call in changed_wrapped.call_args_list]
        self.assertEqual(original_training, changed_training)

    def test_metrics_have_valid_ranges(self):
        result = CandleModel.backtest(make_demo_candles(), 1, 60)
        self.assertGreater(result.samples, 0)
        for value in (result.accuracy, result.baseline_accuracy, result.brier_score):
            self.assertGreaterEqual(value, 0.0)
            self.assertLessEqual(value, 1.0)

    def test_doji_target_is_not_scored(self):
        candles = [candle(GREEN if i % 2 else RED) for i in range(25)]
        with_doji = candles + [candle(DOJI)]
        self.assertEqual(CandleModel.backtest(candles).samples,
                         CandleModel.backtest(with_doji).samples)


if __name__ == "__main__":
    unittest.main()
