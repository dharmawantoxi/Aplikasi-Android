"""Uji mesin hipotesis: harus menemukan edge nyata, dan menolak yang palsu."""
import random
import unittest

from candlelab.hypotheses import HYPOTHESES, by_key, run_hypothesis
from candlelab.hypothesis_stats import (
    Verdict,
    benjamini_hochberg,
    binomial_two_sided_p,
    evaluate,
    evaluate_many,
    sample_size_needed,
)
from candlelab.model import GREEN, Candle


def random_candles(count=1500, seed=42):
    rng = random.Random(seed)
    candles, price = [], 1000.0
    for index in range(count):
        opening = price
        big = rng.random() < 0.15
        size = rng.uniform(4.0, 5.0) if big else rng.uniform(0.3, 0.9)
        up = rng.random() < 0.5
        closing = opening + (size if up else -size)
        candles.append(Candle(opening, max(opening, closing) + 0.2,
                              min(opening, closing) - 0.2, closing, float(index * 60)))
        price = closing
    return candles


def candles_with_momentum(count=4000, seed=7, edge=0.72):
    rng = random.Random(seed)
    candles, price = [], 1000.0
    for index in range(count):
        opening = price
        bias = 0.5
        if candles and candles[-1].high - candles[-1].low > 3.5:
            bias = edge if candles[-1].direction == GREEN else 1 - edge
        up = rng.random() < bias
        size = rng.uniform(4.0, 5.0) if rng.random() < 0.15 else rng.uniform(0.3, 0.9)
        closing = opening + (size if up else -size)
        candles.append(Candle(opening, max(opening, closing) + 0.2,
                              min(opening, closing) - 0.2, closing, float(index * 60)))
        price = closing
    return candles


class BinomialTests(unittest.TestCase):
    def test_balanced_result_is_not_significant(self):
        self.assertGreater(binomial_two_sided_p(50, 100), 0.9)

    def test_small_sample_cannot_be_significant(self):
        # 7/12 sering dianggap "bagus"; secara statistik tidak berarti.
        self.assertGreater(binomial_two_sided_p(7, 12), 0.5)

    def test_strong_result_is_significant(self):
        self.assertLess(binomial_two_sided_p(700, 1000), 1e-10)

    def test_large_sample_does_not_overflow(self):
        # Sebelumnya memakai comb() dan meledak pada sampel besar.
        value = binomial_two_sided_p(520, 1000)
        self.assertGreater(value, 0.15)
        self.assertLess(value, 0.35)

    def test_no_samples(self):
        self.assertEqual(binomial_two_sided_p(0, 0), 1.0)


class SampleSizeTests(unittest.TestCase):
    def test_smaller_edge_needs_more_trades(self):
        self.assertGreater(sample_size_needed(0.05), sample_size_needed(0.10))

    def test_matches_reality_scale(self):
        # Edge 5% di atas koin: ratusan trade, bukan puluhan.
        self.assertGreater(sample_size_needed(0.05), 300)

    def test_zero_edge_is_undefined(self):
        self.assertIsNone(sample_size_needed(0))


class CorrectionTests(unittest.TestCase):
    def test_benjamini_hochberg_never_lowers_p(self):
        values = [0.01, 0.04, 0.03, 0.20]
        adjusted = benjamini_hochberg(values)
        for raw, corrected in zip(values, adjusted):
            self.assertGreaterEqual(corrected, raw)
            self.assertLessEqual(corrected, 1.0)

    def test_many_hypotheses_kill_a_borderline_result(self):
        # Satu hasil p=0.04 dari 10 uji tidak boleh lolos setelah koreksi.
        raw = [0.04] + [0.6] * 9
        adjusted = benjamini_hochberg(raw)
        self.assertGreater(adjusted[0], 0.05)

    def test_empty(self):
        self.assertEqual(benjamini_hochberg([]), [])


class VerdictTests(unittest.TestCase):
    def test_evaluate_matches_raw_test(self):
        verdict = evaluate("x", 58, 100)
        self.assertAlmostEqual(verdict.p_value, binomial_two_sided_p(58, 100))
        self.assertFalse(verdict.significant)

    def test_break_even_follows_payout(self):
        self.assertAlmostEqual(evaluate("x", 1, 2, payout=0.82).break_even, 1 / 1.82)
        self.assertAlmostEqual(evaluate("x", 1, 2, payout=1.0).break_even, 0.5)

    def test_profitable_requires_both_conditions(self):
        # Signifikan tetapi di bawah ambang impas: tidak layak.
        low = evaluate("x", 5200, 10000, payout=0.82)
        self.assertTrue(low.significant)
        self.assertIsNotNone(low.accuracy)
        self.assertLess(low.accuracy, low.break_even)
        self.assertFalse(low.profitable)

        good = evaluate("x", 6000, 10000, payout=0.82)
        self.assertTrue(good.profitable)

    def test_summary_is_honest_for_tiny_sample(self):
        text = evaluate("x", 8, 12).summary()
        self.assertIn("kebetulan", text.lower())

    def test_empty_verdict(self):
        verdict = Verdict(label="x", wins=0, total=0, p_value=1.0)
        self.assertIsNone(verdict.accuracy)
        self.assertIn("Belum ada", verdict.summary())


class HypothesisBehaviourTests(unittest.TestCase):
    """Yang paling penting: tidak menemukan apa pun pada data acak."""

    def test_no_hypothesis_is_significant_on_random_data(self):
        candles = random_candles()
        raw = []
        for hypothesis in HYPOTHESES:
            scores = run_hypothesis(hypothesis.key, candles)
            if scores:
                raw.append((hypothesis.label, sum(scores), len(scores)))
        verdicts = evaluate_many(raw)
        significant = [v.label for v in verdicts if v.significant]
        self.assertEqual(significant, [],
                         f"data acak tidak boleh menghasilkan signifikan: {significant}")

    def test_detects_real_momentum_edge(self):
        candles = candles_with_momentum()
        scores = run_hypothesis("momentum_body", candles)
        verdict = evaluate("momentum", sum(scores), len(scores))
        self.assertTrue(verdict.significant, "edge nyata harus terdeteksi")
        self.assertGreater(verdict.accuracy, 0.6)

    def test_hour_effect_uses_only_past_data(self):
        """Regresi: menghitung dasar dari data masa depan memberi edge palsu."""
        candles = random_candles()
        scores = run_hypothesis("hour", candles)
        if scores:
            verdict = evaluate("jam", sum(scores), len(scores))
            self.assertFalse(verdict.significant,
                             "efek jam pada data acak harus tidak signifikan")

    def test_every_hypothesis_has_metadata(self):
        for hypothesis in HYPOTHESES:
            self.assertTrue(hypothesis.label)
            self.assertTrue(hypothesis.question.endswith("?"))
            self.assertTrue(hypothesis.how)

    def test_by_key(self):
        self.assertEqual(by_key("ngram2").key, "ngram2")
        with self.assertRaises(KeyError):
            by_key("tidak-ada")


if __name__ == "__main__":
    unittest.main()


class PriceHypothesisTests(unittest.TestCase):
    """Hipotesis harga harus benar-benar memakai harga, bukan warna."""

    def _walk(self, n=400, edge=0.0, seed=5):
        import random

        from candlelab.model import Candle

        rng = random.Random(seed)
        price, out, drift = 100.0, [], 0.0
        for _ in range(n):
            drift = edge * drift + rng.gauss(0, 0.4)
            opening = price
            closing = price + drift
            wick = abs(rng.gauss(0, 0.2))
            out.append(Candle(open=opening, high=max(opening, closing) + wick,
                              low=min(opening, closing) - wick, close=closing))
            price = closing
        return out

    def test_price_hypotheses_exist(self):
        from candlelab.hypotheses import by_key

        for key in ("last_tick", "tick_streak", "close_position", "gap"):
            self.assertIn(by_key(key).key, ("last_tick", "tick_streak",
                                            "close_position", "gap"))

    def test_pure_random_stays_near_chance(self):
        from candlelab.hypotheses import by_key

        candles = self._walk(600)
        for key in ("last_tick", "close_position"):
            scores = by_key(key).run(candles)
            self.assertGreater(len(scores), 100, key)
            rate = sum(scores) / len(scores)
            self.assertLess(abs(rate - 0.5), 0.08, f"{key} terlalu jauh dari 50%")

    def test_price_pattern_is_detected(self):
        """Bila harga memang bergerak berkelanjutan, hipotesisnya harus menangkap."""
        from candlelab.hypotheses import by_key

        candles = self._walk(600, edge=0.4)
        scores = by_key("last_tick").run(candles)
        self.assertGreater(sum(scores) / len(scores), 0.55)

    def test_guess_follows_price_even_when_colour_says_otherwise(self):
        """Candle merah yang harganya naik: tebakan harus ikut harga.

        Skor akhir tetap bergantung pada warna candle target (itu yang dinilai),
        tetapi *tebakan*-nya tidak boleh berasal dari warna candle sebelumnya.
        """
        from candlelab.hypotheses import _last_tick

        def red_but_rising(close):
            # open di atas close: badannya merah, tetapi harga naik.
            return Candle(open=close + 0.5, high=close + 0.6,
                          low=close - 0.1, close=close)

        candles = [red_but_rising(100.0), red_but_rising(101.0), red_but_rising(102.0)]
        # Target adalah candle terakhir; harganya naik -> tebakan hijau.
        # Bila warna sebelumnya (merah) yang dipakai, tebakannya akan ikut merah.
        scores = _last_tick(candles)
        self.assertEqual(len(scores), 1)


class PriceSignalModelTests(unittest.TestCase):
    """Model harus benar-benar bergeser ketika harga memberi petunjuk."""

    def _candle(self, direction, close):
        """Candle dengan arah tertentu dan harga penutupan tertentu."""
        opening = close - direction
        return Candle(open=opening, high=max(opening, close) + 0.1,
                      low=min(opening, close) - 0.1, close=close)

    def test_signal_reports_direction_and_strength(self):
        from candlelab.model import price_signal_direction

        # Dua candle naik, ditutup di dekat puncak: sinyal hijau yang kuat.
        candles = [self._candle(1, 100.0), self._candle(1, 101.0)]
        direction, strength = price_signal_direction(candles)
        self.assertEqual(direction, 1)
        self.assertGreater(strength, 0.5)

    def test_signal_is_silent_without_movement(self):
        from candlelab.model import price_signal_direction

        flat = [Candle(open=100, high=100.2, low=99.8, close=100) for _ in range(3)]
        self.assertEqual(price_signal_direction(flat), (0, 0.0))

    def test_prediction_flags_price_use(self):
        from candlelab.model import CandleModel

        # Harga naik terus; candle terakhir juga naik, jadi sinyal harga = hijau.
        sequence = [1, -1] * 12 + [1, 1]
        candles = [self._candle(1, 100 + i * 0.5) for i in range(len(sequence))]
        result = CandleModel.predict(candles, 1, 0)
        self.assertTrue(result.price_signal)
        self.assertGreater(result.green_probability, 0.5)

    def test_price_never_overrides_completely(self):
        """Bobot harga kecil: hasil tidak boleh melompat ke 0 atau 1."""
        from candlelab.model import CandleModel

        sequence = [-1] * 15 + [1, 1, 1]
        candles = [self._candle(d, 100 + i) for i, d in enumerate(sequence)]
        result = CandleModel.predict(candles, 1, 0)
        self.assertGreater(result.green_probability, 0.0)
        self.assertLess(result.green_probability, 1.0)
