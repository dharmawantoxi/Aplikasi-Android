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
