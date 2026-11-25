"""Uji hipotesis yang jujur secara statistik.

Bagian ini ada karena masalah terbesar dalam eksperimen seperti ini bukan
menemukan pola, melainkan **membedakan pola nyata dari kebetulan**.

Tiga hal yang dijaga modul ini:

1. **Uji binomial dua sisi** — peluang melihat hasil seekstrem ini bila model
   sebenarnya tidak lebih baik dari koin.
2. **Koreksi Benjamini-Hochberg** — bila banyak hipotesis diuji sekaligus,
   nilai-p dikoreksi agar "penemuan" tidak muncul hanya karena jumlah percobaan.
3. **Ukuran sampel minimum** — melaporkan berapa trade lagi yang dibutuhkan
   sebelum hasil dapat dipercaya.

Modul ini murni (tanpa tkinter, tanpa layar) sehingga seluruh perhitungan dapat
diuji.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import erf, exp, lgamma, log, sqrt
from typing import Optional

# Peluang minimum yang dianggap "layak dicurigai" (dua sisi).
ALPHA = 0.05


def _normal_cdf(value: float) -> float:
    return 0.5 * (1.0 + erf(value / sqrt(2.0)))


def binomial_two_sided_p(wins: int, total: int, p0: float = 0.5) -> float:
    """Nilai-p dua sisi untuk uji proporsi terhadap ``p0``.

    Dihitung eksak dengan sebaran binomial, bukan pendekatan normal, karena
    jumlah sampel di eksperimen ini sering kecil (20–200).
    """
    if total <= 0:
        return 1.0
    wins = max(0, min(int(wins), int(total)))
    total = int(total)
    if total > 500:
        # Sampel besar: gunakan pendekatan normal dengan koreksi kontinuitas.
        # Nilai eksak tidak praktis dan tidak menambah ketelitian berarti.
        mean = total * p0
        sigma = sqrt(total * p0 * (1 - p0))
        if sigma <= 0:
            return 1.0
        z = (abs(wins - mean) - 0.5) / sigma
        return min(1.0, max(0.0, 2 * (1 - _normal_cdf(max(0.0, z)))))
    # Nilai eksak: jumlahkan seluruh hasil yang kemungkinannya tidak lebih besar
    # dari hasil teramati. Dihitung dalam ruang log agar tidak overflow.
    log_observed = _log_binomial(wins, total, p0)
    tail = 0.0
    for k in range(total + 1):
        value = _log_binomial(k, total, p0)
        if value <= log_observed + 1e-9:
            tail += exp(value)
    return min(1.0, max(0.0, tail))


def _log_binomial(k: int, n: int, p: float) -> float:
    """log P(X = k) untuk X ~ Binomial(n, p), stabil untuk n besar."""
    if p <= 0.0:
        return 0.0 if k == 0 else -1e300
    if p >= 1.0:
        return 0.0 if k == n else -1e300
    return (lgamma(n + 1) - lgamma(k + 1) - lgamma(n - k + 1)
            + k * log(p) + (n - k) * log(1 - p))


def sample_size_needed(delta: float, alpha: float = ALPHA, power: float = 0.80) -> Optional[int]:
    """Trade yang dibutuhkan untuk membuktikan edge sebesar ``delta`` di atas 50%."""
    if delta <= 0:
        return None
    # Pendekatan normal untuk uji proporsi satu sisi dengan power tertentu.
    z_alpha = 1.6449 if alpha >= 0.05 else 1.96
    z_beta = {0.80: 0.8416, 0.90: 1.2816}.get(power, 0.8416)
    p0, p1 = 0.5, 0.5 + delta
    numerator = (z_alpha * sqrt(p0 * (1 - p0)) + z_beta * sqrt(p1 * (1 - p1))) ** 2
    return int(numerator / (delta ** 2)) + 1


def benjamini_hochberg(p_values: list[float]) -> list[float]:
    """Koreksi nilai-p untuk banyak hipotesis (false discovery rate)."""
    count = len(p_values)
    if not count:
        return []
    ordered = sorted(range(count), key=lambda index: p_values[index])
    adjusted = [1.0] * count
    running = 1.0
    for rank in range(count - 1, -1, -1):
        index = ordered[rank]
        candidate = p_values[index] * count / (rank + 1)
        running = min(running, candidate)
        adjusted[index] = min(1.0, running)
    return adjusted


@dataclass
class Verdict:
    """Kesimpulan jujur untuk satu hipotesis."""

    label: str
    wins: int
    total: int
    p_value: float
    adjusted_p: float = 1.0
    payout: float = 0.82

    @property
    def accuracy(self) -> Optional[float]:
        return None if not self.total else self.wins / self.total

    @property
    def break_even(self) -> float:
        """Akurasi minimum agar tidak rugi pada payout tertentu."""
        return 1.0 / (1.0 + self.payout)

    @property
    def significant(self) -> bool:
        return self.adjusted_p < ALPHA

    @property
    def profitable(self) -> bool:
        """Layak secara statistik DAN melewati ambang impas."""
        if not self.significant or self.accuracy is None:
            return False
        return self.accuracy > self.break_even

    @property
    def trades_needed(self) -> Optional[int]:
        """Sampel yang dibutuhkan bila edge-nya sebesar kelebihan di atas 50%."""
        if self.accuracy is None:
            return None
        return sample_size_needed(abs(self.accuracy - 0.5))

    def summary(self) -> str:
        if self.accuracy is None:
            return "Belum ada sampel."
        if not self.significant:
            kurang = self.trades_needed
            ekor = (f" Butuh ~{kurang} sampel bila edge-nya sebesar ini."
                    if kurang else "")
            return (f"Belum bisa dibedakan dari kebetulan (p={self.adjusted_p:.3f})."
                    + ekor)
        if self.profitable:
            return (f"Melewati kebetulan DAN ambang impas {self.break_even * 100:.1f}%. "
                    f"p={self.adjusted_p:.3f}. Uji ulang pada data baru sebelum dipakai.")
        return (f"Secara statistik berbeda dari kebetulan (p={self.adjusted_p:.3f}), "
                f"tetapi TIDAK melewati ambang impas {self.break_even * 100:.1f}%. "
                "Tidak layak dipakai.")


def evaluate(label: str, wins: int, total: int, payout: float = 0.82) -> Verdict:
    p_value = binomial_two_sided_p(wins, total)
    # Tanpa koreksi, nilai-p terkoreksi sama dengan nilai-p mentah.
    return Verdict(label=label, wins=wins, total=total,
                   p_value=p_value, adjusted_p=p_value, payout=payout)


def evaluate_many(results: list[tuple[str, int, int]], payout: float = 0.82) -> list[Verdict]:
    """Nilai beberapa hipotesis sekaligus, dengan koreksi false discovery."""
    verdicts = [evaluate(label, wins, total, payout) for label, wins, total in results]
    adjusted = benjamini_hochberg([item.p_value for item in verdicts])
    for item, value in zip(verdicts, adjusted):
        item.adjusted_p = value
    return verdicts
