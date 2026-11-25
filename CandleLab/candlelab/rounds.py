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
from typing import Optional, Sequence


@dataclass
class Round:
    """Satu tebakan yang dikunci untuk satu candle tertentu."""

    green_probability: float
    direction: int
    candle_count: int
    pattern: tuple[int, ...] = ()
    matches: int = 0
    actual: Optional[int] = None
    correct: Optional[bool] = None

    @property
    def resolved(self) -> bool:
        return self.actual is not None


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

    def __init__(self) -> None:
        self.stats = RoundStats()
        self.current: Optional[Round] = None
        self._history: list[int] = []
        self._reset_reason = ""

    @property
    def reset_reason(self) -> str:
        return self._reset_reason

    @reset_reason.setter
    def reset_reason(self, value: str) -> None:
        # UI membaca lalu mengosongkan alasan agar tidak tampil berulang.
        self._reset_reason = value or ""

    def reset(self) -> None:
        """Lupakan posisi; dipakai saat area/jendela pembacaan berubah."""
        self.current = None
        self._history = []
        self._reset_reason = ""

    def observe(self, history_candles: Sequence, prediction) -> Optional[Round]:
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
        return resolved

    # -- internal ---------------------------------------------------------
    def _open(self, prediction, candle_count: int) -> Optional[Round]:
        if prediction is None or not getattr(prediction, "available", False):
            return None
        return Round(
            green_probability=float(prediction.green_probability),
            direction=int(prediction.predicted_direction),
            candle_count=candle_count,
            pattern=tuple(getattr(prediction, "pattern", ())),
            matches=int(getattr(prediction, "matches", 0)),
        )

    def _resolve(self, round_: Optional[Round], actual: int) -> Optional[Round]:
        from .model import DOJI
        if round_ is None:
            return None
        if actual == DOJI:
            self.stats.skipped += 1
            return None
        round_.actual = actual
        if round_.green_probability == 0.5:
            # Tebakan seimbang tidak dinilai menang atau kalah.
            self.stats.skipped += 1
            return round_
        round_.correct = round_.direction == actual
        if round_.correct:
            self.stats.wins += 1
            self.stats.streak = max(1, self.stats.streak + 1)
        else:
            self.stats.losses += 1
            self.stats.streak = min(-1, self.stats.streak - 1)
        self.stats.last_correct = round_.correct
        return round_

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
