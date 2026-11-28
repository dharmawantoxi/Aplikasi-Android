"""Model probabilitas yang transparan; modul ini tidak bergantung pada GUI."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import math
import random
from typing import Optional, Sequence

GREEN = 1
RED = -1
DOJI = 0
# Ambang rendah supaya chart broker dengan sedikit candle tetap bisa dibaca.
# Angka prediction pada rentang ini sebagian besar berasal dari prior, bukan data.
MIN_CANDLES = 5
MIN_DIRECTIONAL = 5
# Di bawah jumlah ini hasil dianggap tidak layak untuk dijadikan dasar keputusan.
WEAK_EVIDENCE_CANDLES = 20
PRIOR_STRENGTH = 6.0
# Seberapa besar informasi harga boleh menggeser probabilitas. Kecil dengan
# sengaja: harga hanya menambah bukti, dan model warna tetap dominan.
PRICE_SIGNAL_WEIGHT = 0.30
MAX_CANDLES = 2000


@dataclass(frozen=True)
class Candle:
    open: float
    high: float
    low: float
    close: float
    timestamp: float = 0.0

    def __post_init__(self) -> None:
        values = (self.open, self.high, self.low, self.close)
        if not all(math.isfinite(value) for value in values):
            raise ValueError("OHLC harus berupa angka yang valid.")
        if self.high < max(self.open, self.close, self.low):
            raise ValueError("High harus merupakan nilai tertinggi dari OHLC.")
        if self.low > min(self.open, self.close, self.high):
            raise ValueError("Low harus merupakan nilai terendah dari OHLC.")
        if not math.isfinite(self.timestamp):
            raise ValueError("Waktu candle tidak valid.")

    @property
    def direction(self) -> int:
        if self.close > self.open:
            return GREEN
        if self.close < self.open:
            return RED
        return DOJI


@dataclass(frozen=True)
class CloseSignal:
    predicted_direction: int = DOJI
    reason: str = "Menunggu candle baru terkonfirmasi."
    green_probability = None  # Aturan arah, bukan estimasi peluang.

    @property
    def available(self):
        return self.predicted_direction != DOJI


def range_reversion(history: Sequence[Candle], previous: int = DOJI) -> CloseSignal:
    if len(history) < 4:
        return CloseSignal(reason="Perlu empat candle tertutup.")
    bars = history[-4:]
    low, high = min(c.low for c in bars), max(c.high for c in bars)
    close = bars[-1].close
    if close - low < high - close:
        return CloseSignal(GREEN, "Close di separuh bawah rentang 4: uji pantulan naik.")
    if close - low > high - close:
        return CloseSignal(RED, "Close di separuh atas rentang 4: uji pantulan turun.")
    return CloseSignal(previous, "Close di tengah rentang; pertahankan arah jika tersedia.")


def close_vs_three(history: Sequence[Candle], previous: int = DOJI) -> CloseSignal:
    if len(history) < 4:
        return CloseSignal(reason="Perlu empat candle tertutup.")
    a, b, c, latest = (item.close for item in history[-4:])
    # Selisih relatif menghindari pembulatan saat menjumlahkan harga besar.
    delta = math.fsum((latest - a, latest - b, latest - c))
    if delta == 0:
        return CloseSignal(previous, "Close sama dengan rata-rata; pertahankan arah terakhir."
                           if previous else "Close sama; belum ada arah sebelumnya.")
    return CloseSignal(GREEN if delta > 0 else RED,
                       "Close terakhir di atas rata-rata tiga close sebelumnya." if delta > 0
                       else "Close terakhir di bawah rata-rata tiga close sebelumnya.")


@dataclass(frozen=True)
class Prediction:
    available: bool
    green_probability: float = 0.5
    matches: int = 0
    green_matches: int = 0
    base_green_probability: float = 0.5
    directional_count: int = 0
    order: int = 1
    window: int = 60
    price_signal: bool = False
    pattern: tuple[int, ...] = ()
    reason: str = ""
    thin_data: bool = False

    @property
    def red_probability(self) -> float:
        return 1.0 - self.green_probability

    @property
    def predicted_direction(self) -> int:
        if self.green_probability > 0.5:
            return GREEN
        if self.green_probability < 0.5:
            return RED
        return DOJI

    @property
    def red_matches(self) -> int:
        return self.matches - self.green_matches

    @property
    def evidence_label(self) -> str:
        """Keterangan singkat; menyebut harga bila sinyalnya ikut dipakai."""
        dasar = self._volume_label()
        if self.price_signal:
            return dasar + " + harga"
        return dasar

    def _volume_label(self) -> str:
        if self.matches < 5:
            return "Bukti sangat terbatas"
        if self.matches < 20:
            return "Bukti terbatas"
        if self.matches < 50:
            return "Bukti cukup"
        return "Bukti lebih banyak"


@dataclass(frozen=True)
class Backtest:
    samples: int
    accuracy: Optional[float]
    baseline_accuracy: Optional[float]
    brier_score: Optional[float]


def price_signal_direction(candles: Sequence[Candle]) -> tuple[int, float]:
    """Arah yang disarankan informasi harga, beserta kekuatannya.

    Model warna memperlakukan candle hijau raksasa dan candle hijau seujung
    jarum sebagai sama. Harga memberi dua petunjuk yang tidak terlihat dari
    warna saja:

    - arah tick terakhir (close ke close), sebagai momentum sangat pendek;
    - posisi close di dalam rentang high-low, sebagai tekanan pembeli/penjual.

    Mengembalikan (arah, bobot 0..1). Arah 0 berarti tidak ada petunjuk.
    """
    if len(candles) < 2:
        return 0, 0.0
    last = candles[-1]
    before = candles[-2]
    votes = 0.0
    strength = 0.0
    if last.close != before.close:
        momentum = 1 if last.close > before.close else -1
        votes += momentum
        strength += 0.5
    span = last.high - last.low
    if span > 0:
        position = (last.close - last.low) / span
        if position >= 0.66:
            votes += 1
            strength += position - 0.5
        elif position <= 0.34:
            votes -= 1
            strength += 0.5 - position
    if votes == 0:
        return 0, 0.0
    direction = 1 if votes > 0 else -1
    return direction, min(1.0, strength)


class CandleModel:
    """N-gram arah candle dengan prior Bayesian menuju frekuensi dasar.

    Doji diabaikan saat membentuk pola dan tidak dinilai pada backtest. Model
    hanya memakai candle sebelum target pada setiap langkah walk-forward.
    """

    @staticmethod
    def _settings(order: int, window: int) -> None:
        if order not in (1, 2, 3):
            raise ValueError("Pola harus terdiri dari 1, 2, atau 3 candle.")
        if window != 0 and window not in (30, 60, 100):
            raise ValueError("Jendela harus 30, 60, 100, atau 0 untuk semua data.")

    @staticmethod
    def predict(candles: Sequence[Candle], order: int = 1, window: int = 60) -> Prediction:
        CandleModel._settings(order, window)
        selected = list(candles[-window:] if window else candles)
        directions = [c.direction for c in selected if c.direction != DOJI]
        directional_count = len(directions)
        unavailable = Prediction(
            available=False, order=order, window=window,
            directional_count=directional_count,
            pattern=tuple(directions[-order:]),
        )
        if len(selected) < MIN_CANDLES:
            return Prediction(
                **{**unavailable.__dict__,
                   "reason": f"Tambahkan minimal {MIN_CANDLES - len(selected)} candle untuk memulai."}
            )
        if directional_count < MIN_DIRECTIONAL:
            return Prediction(
                **{**unavailable.__dict__,
                   "reason": f"Perlu minimal {MIN_DIRECTIONAL} candle non-doji; saat ini {directional_count}."}
            )
        if len(directions) < order + 1:
            return Prediction(
                **{**unavailable.__dict__, "reason": "Pola belum memiliki cukup data."}
            )

        greens = sum(direction == GREEN for direction in directions)
        base = greens / directional_count
        pattern = tuple(directions[-order:])
        matches = 0
        green_matches = 0
        for index in range(order, directional_count):
            if tuple(directions[index - order:index]) == pattern:
                matches += 1
                if directions[index] == GREEN:
                    green_matches += 1

        probability = (
            green_matches + PRIOR_STRENGTH * base
        ) / (matches + PRIOR_STRENGTH)
        # Informasi harga digabungkan sebagai bukti tambahan, bukan pengganti.
        # Bobotnya kecil dan tetap: pada data acak ini hanya menggeser beberapa
        # persen, tetapi pada data yang memang punya pola harga efeknya nyata.
        arah_harga, kekuatan = price_signal_direction(selected)
        used_price = arah_harga != 0 and kekuatan > 0
        if used_price:
            bukti = PRICE_SIGNAL_WEIGHT * kekuatan
            if arah_harga > 0:
                probability += bukti * (1.0 - probability)
            else:
                probability -= bukti * probability
        # Batasi galat floating point agar kedua probabilitas selalu valid.
        probability = min(1.0, max(0.0, probability))
        return Prediction(
            available=True,
            thin_data=directional_count < WEAK_EVIDENCE_CANDLES,
            green_probability=probability,
            matches=matches,
            green_matches=green_matches,
            base_green_probability=base,
            directional_count=directional_count,
            order=order,
            window=window,
            pattern=pattern,
            price_signal=used_price,
        )

    @staticmethod
    def backtest(candles: Sequence[Candle], order: int = 1, window: int = 60) -> Backtest:
        CandleModel._settings(order, window)
        samples = 0
        model_score = 0.0
        baseline_score = 0.0
        brier = 0.0
        # Target doji tetap masuk ke data pelatihan berikutnya, tetapi tidak dinilai.
        for target in range(MIN_CANDLES, len(candles)):
            actual = candles[target].direction
            if actual == DOJI:
                continue
            training = candles[:target]
            prediction = CandleModel.predict(training, order, window)
            if not prediction.available:
                continue
            probability = prediction.green_probability
            baseline = prediction.base_green_probability
            model_score += CandleModel._directional_score(probability, actual)
            baseline_score += CandleModel._directional_score(baseline, actual)
            brier += (probability - (1.0 if actual == GREEN else 0.0)) ** 2
            samples += 1
        if not samples:
            return Backtest(0, None, None, None)
        return Backtest(
            samples=samples,
            accuracy=model_score / samples,
            baseline_accuracy=baseline_score / samples,
            brier_score=brier / samples,
        )

    @staticmethod
    def _directional_score(probability: float, actual: int) -> float:
        if probability == 0.5:
            return 0.5
        predicted = GREEN if probability > 0.5 else RED
        return 1.0 if predicted == actual else 0.0


def make_demo_candles(count: int = 96) -> list[Candle]:
    """Membuat data sintetis deterministik, bukan harga pasar sesungguhnya."""
    if not MIN_CANDLES <= count <= MAX_CANDLES:
        raise ValueError("Jumlah data simulasi tidak valid.")
    rng = random.Random(20261109)
    start = datetime(2026, 1, 5, 9, 0, tzinfo=timezone.utc)
    price = 100.0
    candles: list[Candle] = []
    for index in range(count):
        opening = price
        wave = math.sin(index / 5.5) * 0.42 + math.sin(index / 17.0) * 0.28
        delta = wave + rng.gauss(0.0, 0.72)
        closing = max(1.0, opening + delta)
        upper = rng.uniform(0.12, 0.85)
        lower = rng.uniform(0.12, 0.85)
        candles.append(Candle(
            open=round(opening, 4),
            high=round(max(opening, closing) + upper, 4),
            low=round(max(0.01, min(opening, closing) - lower), 4),
            close=round(closing, 4),
            timestamp=(start + timedelta(hours=index)).timestamp(),
        ))
        price = closing
    return candles
