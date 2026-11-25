"""Himpunan hipotesis yang bisa diuji pada data candle.

Semua hipotesis di sini sengaja dibuat **sederhana dan dapat dijelaskan**. Model
pembelajaran mesin yang rumit hanya akan menghafal derau pada data sekecil ini.

Setiap hipotesis mengembalikan daftar hasil biner (1 = tebakan benar), sehingga
dapat dinilai dengan uji binomial yang sama.
"""
from __future__ import annotations

from dataclasses import dataclass
from statistics import median
from typing import Callable, Sequence

from .model import DOJI, GREEN, RED, Candle


@dataclass(frozen=True)
class Hypothesis:
    key: str
    label: str
    question: str
    how: str
    run: Callable[[Sequence[Candle]], list[int]]


def _directional(candles: Sequence[Candle]) -> list[int]:
    """Arah candle tanpa doji, beserta indeks aslinya."""
    return [index for index, candle in enumerate(candles) if candle.direction != DOJI]


def _body(candle: Candle) -> float:
    return abs(candle.close - candle.open)


def _range(candle: Candle) -> float:
    return max(candle.high - candle.low, 1e-9)


def _scores(candles: Sequence[Candle], decide: Callable[[int], int | None]) -> list[int]:
    """Kumpulkan hasil benar(1)/salah(0) untuk setiap candle yang dinilai.

    ``decide`` menerima indeks candle target dan mengembalikan arah tebakan
    (1 hijau, -1 merah) atau None untuk melewati candle tersebut.
    """
    out: list[int] = []
    for target in range(1, len(candles)):
        actual = candles[target].direction
        if actual == DOJI:
            continue
        guess = decide(target)
        if guess is None:
            continue
        out.append(1 if guess == actual else 0)
    return out


# -- 1. pola warna n-gram (dasar pembanding) ------------------------------
def _ngram(order: int) -> Callable[[Sequence[Candle]], list[int]]:
    def run(candles: Sequence[Candle]) -> list[int]:
        from .model import CandleModel
        out: list[int] = []
        for target in range(5, len(candles)):
            if candles[target].direction == DOJI:
                continue
            prediction = CandleModel.predict(candles[:target], order, 0)
            if not prediction.available or prediction.green_probability == 0.5:
                continue
            out.append(1 if prediction.predicted_direction == candles[target].direction else 0)
        return out
    return run


# -- 2. mean reversion setelah candle besar -------------------------------
def _reversion_body(candles: Sequence[Candle]) -> list[int]:
    """Setelah body besar, anggap arah berikutnya berlawanan."""
    def decide(target: int) -> int | None:
        previous = candles[target - 1]
        if previous.direction == DOJI:
            return None
        sizes = [_body(item) for item in candles[max(0, target - 50):target] if item.direction != DOJI]
        if len(sizes) < 10:
            return None
        if _body(previous) < median(sizes) * 1.5:
            return None
        return RED if previous.direction == GREEN else GREEN
    return _scores(candles, decide)


def _reversion_range(candles: Sequence[Candle]) -> list[int]:
    """Setelah range sangat lebar, anggap arah berikutnya berlawanan."""
    def decide(target: int) -> int | None:
        previous = candles[target - 1]
        if previous.direction == DOJI:
            return None
        ranges = [_range(item) for item in candles[max(0, target - 50):target]]
        if len(ranges) < 10:
            return None
        if _range(previous) < median(ranges) * 1.5:
            return None
        return RED if previous.direction == GREEN else GREEN
    return _scores(candles, decide)


# -- 3. momentum (kebalikan dari mean reversion) --------------------------
def _momentum_body(candles: Sequence[Candle]) -> list[int]:
    """Setelah body besar, anggap arah berlanjut."""
    def decide(target: int) -> int | None:
        previous = candles[target - 1]
        if previous.direction == DOJI:
            return None
        sizes = [_body(item) for item in candles[max(0, target - 50):target] if item.direction != DOJI]
        if len(sizes) < 10 or _body(previous) < median(sizes) * 1.5:
            return None
        return previous.direction
    return _scores(candles, decide)


# -- 4. wick: penolakan harga --------------------------------------------
def _lower_wick(candles: Sequence[Candle]) -> list[int]:
    """Ekor bawah panjang = pembeli menolak harga rendah -> tebak hijau."""
    def decide(target: int) -> int | None:
        previous = candles[target - 1]
        body = _body(previous)
        if body <= 0:
            return None
        lower = min(previous.open, previous.close) - previous.low
        if lower < body * 2:
            return None
        return GREEN
    return _scores(candles, decide)


def _upper_wick(candles: Sequence[Candle]) -> list[int]:
    """Ekor atas panjang = penjual menolak harga tinggi -> tebak merah."""
    def decide(target: int) -> int | None:
        previous = candles[target - 1]
        body = _body(previous)
        if body <= 0:
            return None
        upper = previous.high - max(previous.open, previous.close)
        if upper < body * 2:
            return None
        return RED
    return _scores(candles, decide)


# -- 5. pola berulang dengan jendela lebih panjang -----------------------
def _streak(candles: Sequence[Candle]) -> list[int]:
    """Tiga sejenis berturut-turut: anggap berbalik (pola klasik)."""
    def decide(target: int) -> int | None:
        window = [candles[index].direction for index in range(max(0, target - 3), target)]
        if len(window) < 3 or len(set(window)) != 1 or window[0] == DOJI:
            return None
        return RED if window[0] == GREEN else GREEN
    return _scores(candles, decide)


# -- 6. jam sesi (market berbeda pada jam berbeda) -----------------------
def _hour_effect(candles: Sequence[Candle]) -> list[int]:
    """Pada jam tertentu, apakah frekuensi hijau lebih tinggi dari biasanya?

    Dihitung **walk-forward**: frekuensi dasar untuk satu candle hanya memakai
    candle sebelumnya pada jam yang sama. Menghitung dasar dari seluruh data
    (termasuk masa depan) akan menghasilkan edge palsu — persis kesalahan yang
    membuat hipotesis ini terlihat 60% pada data acak.
    """
    import datetime as _dt
    history: dict[int, list[int]] = {}
    out: list[int] = []
    for candle in candles:
        if candle.direction == DOJI or not candle.timestamp:
            continue
        hour = _dt.datetime.fromtimestamp(candle.timestamp).hour
        seen = history.setdefault(hour, [])
        if len(seen) >= 20:
            base = sum(1 for item in seen if item == GREEN) / len(seen)
            if abs(base - 0.5) >= 0.06:
                guess = GREEN if base > 0.5 else RED
                out.append(1 if guess == candle.direction else 0)
        seen.append(candle.direction)
    return out


HYPOTHESES: tuple[Hypothesis, ...] = (
    Hypothesis(
        "ngram1", "Pola 1 candle", "Apakah warna candle terakhir memprediksi yang berikutnya?",
        "Bandingkan 'setelah hijau' vs 'setelah merah'. Ini dasar pembanding.", _ngram(1)),
    Hypothesis(
        "ngram2", "Pola 2 candle", "Apakah dua warna terakhir memprediksi yang berikutnya?",
        "Empat kemungkinan pola (HH, HM, MH, MM).", _ngram(2)),
    Hypothesis(
        "ngram3", "Pola 3 candle", "Apakah tiga warna terakhir memprediksi yang berikutnya?",
        "Delapan kemungkinan pola; sampel per pola jadi kecil.", _ngram(3)),
    Hypothesis(
        "reversal_body", "Pembalikan setelah body besar",
        "Setelah candle dengan body jauh di atas rata-rata, apakah arah berbalik?",
        "Ukur body terhadap median 50 candle sebelumnya; ambil hanya yang >1,5x.", _reversion_body),
    Hypothesis(
        "reversal_range", "Pembalikan setelah range lebar",
        "Setelah candle dengan rentang harga sangat lebar, apakah arah berbalik?",
        "Sama seperti di atas, tetapi memakai high-low, bukan body.", _reversion_range),
    Hypothesis(
        "momentum_body", "Kelanjutan setelah body besar",
        "Kebalikan dari hipotesis pembalikan: apakah arah berlanjut?",
        "Bila ini menang dan pembalikan kalah, arahnya momentum, bukan reversion.", _momentum_body),
    Hypothesis(
        "lower_wick", "Ekor bawah panjang",
        "Apakah ekor bawah panjang (>2x body) diikuti candle hijau?",
        "Diartikan sebagai pembeli menolak harga rendah.", _lower_wick),
    Hypothesis(
        "upper_wick", "Ekor atas panjang",
        "Apakah ekor atas panjang (>2x body) diikuti candle merah?",
        "Diartikan sebagai penjual menolak harga tinggi.", _upper_wick),
    Hypothesis(
        "streak3", "Tiga sejenis berbalik",
        "Apakah tiga candle searah berturut-turut diikuti pembalikan?",
        "Pola klasik yang sering diklaim, jarang diuji dengan benar.", _streak),
    Hypothesis(
        "hour", "Efek jam",
        "Apakah ada jam tertentu yang condong hijau atau merah?",
        "Memerlukan CSV dengan kolom timestamp.", _hour_effect),
)


def by_key(key: str) -> Hypothesis:
    for item in HYPOTHESES:
        if item.key == key:
            return item
    raise KeyError(key)


def run_hypothesis(key: str, candles: Sequence[Candle]) -> list[int]:
    return list(by_key(key).run(candles))
