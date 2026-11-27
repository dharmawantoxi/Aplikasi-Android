"""Putaran tebak otomatis pada mode live.

Satu putaran = satu candle. Saat candle terakhir di chart masih terbentuk,
tebakan dikunci untuk candle itu. Ketika candle tersebut selesai dan candle
baru muncul, arah candle yang sudah selesai dipakai sebagai bukti: tebakan
benar atau salah.

Modul ini tidak bergantung pada Tkinter maupun layar sehingga seluruh aturan
penilaian dapat diuji tanpa GUI.

Catatan penting: tebakan **tidak** diperbarui selama candle berjalan. Kalau
diperbarui, setiap putaran akan selalu "benar" karena tebakan mengikuti warna
candle yang sedang bergerak.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import time
from typing import Optional, Sequence


# Di bawah jumlah putaran ini, skor belum layak disimpulkan: variasi acak
# jauh lebih besar daripada perbedaan yang mungkin disebabkan model.
MIN_ROUNDS_FOR_SIGNAL = 30


def accuracy_is_meaningful(played: int) -> bool:
    return played >= MIN_ROUNDS_FOR_SIGNAL


@dataclass
class Round:
    """Satu tebakan yang dikunci untuk satu candle tertentu."""

    green_probability: Optional[float]
    direction: int
    candle_count: int
    pattern: tuple[int, ...] = ()
    matches: int = 0
    actual: Optional[int] = None
    correct: Optional[bool] = None
    # Riwayat arah yang dipakai saat menebak. Disimpan agar pengguna dapat
    # membandingkan apa yang "dilihat" aplikasi dengan chart aslinya.
    history: tuple[int, ...] = ()
    resolved_at: str = ""
    # Nomor putaran sejak live dimulai. Dipakai agar klik otomatis mencatat
    # "sudah klik untuk putaran ini" tanpa bergantung pada jumlah candle layar,
    # yang bisa turun ketika candle lama keluar dari area pembacaan.
    seq: int = 0

    @property
    def resolved(self) -> bool:
        return self.actual is not None

    def to_dict(self) -> dict:
        return {
            "p": None if self.green_probability is None else round(self.green_probability, 4),
            "dir": self.direction,
            "actual": self.actual,
            "ok": self.correct,
            "matches": self.matches,
            "n": self.candle_count,
            "hist": list(self.history[-12:]),
            "at": self.resolved_at,
        }


@dataclass
class RoundStats:
    wins: int = 0
    losses: int = 0
    skipped: int = 0
    last_correct: Optional[bool] = None
    streak: int = 0  # positif = benar beruntun, negatif = salah beruntun

    @property
    def played(self) -> int:
        return self.wins + self.losses

    @property
    def accuracy(self) -> Optional[float]:
        return None if not self.played else self.wins / self.played


def _directions(candles: Sequence) -> list[int]:
    """Arah candle **termasuk doji**.

    Doji tetap dicatat agar posisi candle dapat dilacak. Tanpa itu, candle
    doji yang selesai tidak terlihat sebagai perubahan dan putaran berikutnya
    akan dinilai dengan candle yang salah. Doji baru diabaikan saat menilai.
    """
    return [candle.direction for candle in candles]


@dataclass
class _State:
    history: list[int] = field(default_factory=list)
    current: Optional[Round] = None


class RoundTracker:
    """Mengunci tebakan per candle dan menilainya saat candle selesai.

    ``observe`` menerima daftar candle **historis** (tanpa candle yang sedang
    berjalan) dan prediksi untuk candle berikutnya. Riwayat historis yang
    bertambah panjang menandakan candle sebelumnya sudah selesai.
    """

    def __init__(self, start_monotonic: Optional[float] = None, keep: int = 200) -> None:
        self.stats = RoundStats()
        self.rounds_opened = 0
        # Putaran yang sudah selesai, terbaru di akhir.
        self.log: list[Round] = []
        self.keep = keep
        self.current: Optional[Round] = None
        self._history: list[int] = []
        self._reset_reason = ""
        self._opened_at: Optional[float] = None
        if start_monotonic is not None:
            self._opened_at = start_monotonic

    @property
    def reset_reason(self) -> str:
        return self._reset_reason

    @reset_reason.setter
    def reset_reason(self, value: str) -> None:
        # UI membaca lalu mengosongkan alasan agar tidak tampil berulang.
        self._reset_reason = value or ""

    def reset(self) -> None:
        """Lupakan posisi; dipakai saat area/jendela pembacaan berubah."""
        self.rounds_opened = 0
        self.current = None
        self._history = []
        self._reset_reason = ""
        self._opened_at = None


    def observe(self, history_candles: Sequence, prediction, *, geometry=False) -> Optional[Round]:
        """Perbarui putaran. Kembalikan putaran yang baru selesai (bila ada).

        Chart broker umumnya **bergeser**, bukan bertambah panjang: jumlah
        candle di layar tetap, candle lama keluar di kiri. Karena itu ada dua
        cara candle selesai terdeteksi:

        - riwayat bertambah panjang (chart melebar), atau
        - riwayat bergeser satu langkah: ``new[:-1] == old[1:]``.
        """
        history = _directions(history_candles)
        old = self._history
        resolved: Optional[Round] = None
        self._reset_reason = ""

        if not old:
            pass
        elif geometry and history:
            resolved = self._resolve(self.current, history[-1])
        elif len(history) > len(old):
            if history[:len(old)] == old:
                resolved = self._resolve(self.current, history[len(old)])
            else:
                self._reset_reason = "Riwayat bergeser; putaran sebelumnya tidak dinilai."
                self.current = None
        elif len(history) == len(old):
            if history == old:
                pass  # pembacaan berulang: tebakan tidak berubah
            elif len(history) >= 2 and history[:-1] == old[1:]:
                # Bergeser tepat satu candle: candle pertama yang lama selesai.
                resolved = self._resolve(self.current, history[-1])
            else:
                self._reset_reason = "Riwayat bergeser; putaran sebelumnya tidak dinilai."
                self.current = None
        else:
            # Candle hilang dari deteksi. Menilai sekarang berarti memakai
            # candle yang salah sebagai bukti.
            self._reset_reason = "Jumlah candle menurun; putaran diulang."
            self.current = None

        self._history = history

        if self.current is None or resolved is not None:
            self.current = self._open(prediction, len(history))
            if self.current is not None:
                self._opened_at = time.monotonic()
        return resolved

    # -- internal ---------------------------------------------------------
    def _open(self, prediction, candle_count: int) -> Optional[Round]:
        if prediction is None or not getattr(prediction, "available", False):
            return None
        self.rounds_opened += 1
        return Round(
            seq=self.rounds_opened,
            green_probability=prediction.green_probability,
            direction=int(prediction.predicted_direction),
            candle_count=candle_count,
            pattern=tuple(getattr(prediction, "pattern", ())),
            matches=int(getattr(prediction, "matches", 0)),
            history=tuple(self._history),
        )

    def _resolve(self, round_: Optional[Round], actual: int) -> Optional[Round]:
        from .model import DOJI
        if round_ is None:
            return None
        if actual == DOJI:
            self.current = None
            self.stats.skipped += 1
            return None
        round_.actual = actual
        self.log.append(round_)
        del self.log[:-self.keep]
        if round_.direction == DOJI:
            # Tebakan seimbang tidak dinilai menang atau kalah.
            self.stats.skipped += 1
            return round_
        from datetime import datetime
        round_.resolved_at = datetime.now().strftime("%H:%M:%S")
        round_.correct = round_.direction == actual
        if round_.correct:
            self.stats.wins += 1
            self.stats.streak = max(1, self.stats.streak + 1)
        else:
            self.stats.losses += 1
            self.stats.streak = min(-1, self.stats.streak - 1)
        self.stats.last_correct = round_.correct
        return round_

    def waiting_seconds(self) -> Optional[float]:
        """Berapa lama tebakan menunggu candle-nya selesai.

        Dipakai badge untuk menunjukkan aplikasi masih bekerja; angka yang
        terus naik juga menjadi petunjuk bahwa deteksi berhenti (mis. jendela
        chart tertutup).
        """
        if self.current is None or self.current.resolved or self._opened_at is None:
            return None
        return max(0.0, time.monotonic() - self._opened_at)

    def history_text(self, limit: int = 12) -> str:
        """Riwayat ringkas: arah yang dilihat aplikasi vs hasil sebenarnya."""
        rows = []
        for item in self.log[-limit:]:
            tebak = "H" if item.direction == 1 else "M" if item.direction == -1 else "="
            aktual = "H" if item.actual == 1 else "M" if item.actual == -1 else "D"
            if item.correct is None:
                tanda = "?"
            else:
                tanda = "v" if item.correct else "x"
            rows.append(f"{item.resolved_at} {tebak}->{aktual} {tanda}")
        return " | ".join(rows)

    def observed_text(self, limit: int = 12) -> str:
        """Urutan candle yang dibaca aplikasi (H=hijau, M=merah, D=doji)."""
        if self.current is None or not self.current.history:
            return "-"
        return "".join("H" if d == 1 else "M" if d == -1 else "D"
                       for d in self.current.history[-limit:])

    def status_text(self) -> str:
        """Ringkasan pendek untuk badge/panel."""
        played = self.stats.played
        if not played:
            return "0/0 benar"
        return f"{self.stats.wins}/{played} benar"


def needed_candles(visible: int) -> int:
    """Jumlah candle terlihat agar satu putaran bisa dimulai.

    Candle yang sedang berjalan selalu dikeluarkan dari dasar tebakan, jadi
    dibutuhkan satu candle lebih banyak daripada batas minimum model.
    """
    from .model import MIN_CANDLES
    return max(0, MIN_CANDLES + 1 - int(visible))


class CandleClock:
    """Mengukur durasi candle dari waktu pergantian candle.

    TF tidak perlu diisi manual: selisih waktu antar-pergantian candle yang
    terdeteksi sudah cukup. Dipakai untuk memberi tahu pengguna kapan candle
    berikutnya dimulai, karena entry 1 menit hanya masuk akal di awal candle.
    """

    MIN_STEP = 5.0      # abaikan selisih yang terlalu pendek (deteksi berkedip)
    MAX_STEP = 3600.0   # abaikan celah panjang (live dihentikan)
    KEEP = 8

    def __init__(self) -> None:
        self._last: Optional[float] = None
        self._deltas: list[float] = []

    def mark_new_candle(self, now: float) -> None:
        if self._last is not None:
            delta = now - self._last
            if self.MIN_STEP <= delta <= self.MAX_STEP:
                self._deltas.append(delta)
                del self._deltas[:-self.KEEP]
        self._last = now

    def reset(self) -> None:
        self._last = None
        self._deltas = []

    @property
    def period(self) -> Optional[float]:
        if len(self._deltas) < 2:
            return None
        ordered = sorted(self._deltas)
        return ordered[len(ordered) // 2]

    def seconds_to_next(self, now: float) -> Optional[float]:
        """Sisa waktu sampai candle berikutnya, bila durasi sudah terukur."""
        period = self.period
        if period is None or self._last is None:
            return None
        elapsed = max(0.0, now - self._last)
        remaining = period - (elapsed % period)
        return remaining if remaining > 0.05 else 0.0

    def elapsed_fraction(self, now: float) -> Optional[float]:
        """Berapa bagian candle yang sedang berjalan sudah terlewati (0..1).

        Tunggu konfirmasi candle baru sebelum membuka jendela entry lagi.
        """
        period = self.period
        if period is None or self._last is None or period <= 0:
            return None
        elapsed = max(0.0, now - self._last)
        return min(1.0, elapsed / period)

    def label(self) -> str:
        """'1 mnt', '15 mnt', '30 dtk' — untuk ditampilkan ke pengguna."""
        period = self.period
        if period is None:
            return "?"
        if period >= 55:
            # TF 1 menit harus terbaca "1 mnt", bukan "60 dtk".
            return f"{round(period / 60)} mnt"
        return f"{round(period)} dtk"
