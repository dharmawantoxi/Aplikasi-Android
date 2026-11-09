"""Penyimpanan JSON lokal dengan penulisan atomik."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
import json
import os
from pathlib import Path
import sys
from typing import Optional

from .experiments import Experiment
from .model import Candle, MAX_CANDLES, make_demo_candles


def default_data_path() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        return base / "CandleLab" / "data.json"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "CandleLab" / "data.json"
    base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    return base / "candlelab" / "data.json"


@dataclass
class AppState:
    candles: list[Candle] = field(default_factory=list)
    experiments: list[Experiment] = field(default_factory=list)
    is_demo: bool = False

    @property
    def pending(self) -> Optional[Experiment]:
        return next((item for item in self.experiments if item.pending), None)

    def lock_prediction(self, prediction) -> Experiment:
        if self.pending:
            raise ValueError("Selesaikan prediksi yang masih menunggu terlebih dahulu.")
        experiment = Experiment.lock(prediction, len(self.candles))
        self.experiments.append(experiment)
        return experiment

    def append_candle(self, candle: Candle) -> Optional[Experiment]:
        if len(self.candles) >= MAX_CANDLES:
            raise ValueError(f"Data set maksimal berisi {MAX_CANDLES} candle.")
        self.candles.append(candle)
        self.is_demo = False
        pending = self.pending
        if pending and pending.candle_count + 1 == len(self.candles):
            pending.resolve(candle.direction)
            return pending
        return None

    def replace_candles(self, candles: list[Candle], *, demo: bool = False) -> None:
        if not candles:
            raise ValueError("Data set tidak boleh kosong.")
        if len(candles) > MAX_CANDLES:
            raise ValueError(f"Data set maksimal berisi {MAX_CANDLES} candle.")
        if self.pending:
            self.pending.cancel("Dibatalkan karena data set diganti")
        self.candles = list(candles)
        self.is_demo = demo

    def delete_last(self) -> Candle:
        if not self.candles:
            raise ValueError("Tidak ada candle untuk dihapus.")
        if self.pending:
            self.pending.cancel("Dibatalkan karena riwayat candle diubah")
        self.is_demo = False
        return self.candles.pop()

    def clear_history(self) -> None:
        self.experiments = [item for item in self.experiments if item.pending]


class JsonStore:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path else default_data_path()
        self.warning = ""

    def load(self) -> AppState:
        if not self.path.exists():
            state = AppState(candles=make_demo_candles(), is_demo=True)
            self.save(state)
            return state
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            candles = [Candle(
                open=float(item["open"]), high=float(item["high"]),
                low=float(item["low"]), close=float(item["close"]),
                timestamp=float(item.get("timestamp", 0.0)),
            ) for item in raw["candles"]]
            if len(candles) > MAX_CANDLES:
                raise ValueError("Jumlah candle melebihi batas.")
            experiments = [Experiment.from_dict(item) for item in raw.get("experiments", [])]
            if sum(item.pending for item in experiments) > 1:
                raise ValueError("Ditemukan lebih dari satu prediksi tertunda.")
            state = AppState(candles, experiments[-200:], bool(raw.get("is_demo", False)))
            # Prediksi tertunda dari sesi lama hanya sah jika candle berikutnya belum masuk.
            pending = state.pending
            if pending and len(candles) == pending.candle_count + 1:
                pending.resolve(candles[-1].direction)
                self.save(state)
            elif pending and len(candles) != pending.candle_count:
                pending.cancel("Dibatalkan karena jumlah data berubah")
                self.save(state)
            return state
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
            self.warning = f"Data lokal tidak dapat dibaca ({exc}). Data simulasi dimuat; file lama disimpan sebagai cadangan."
            try:
                backup = self.path.with_suffix(f".corrupt-{datetime.now():%Y%m%d-%H%M%S}.json")
                self.path.replace(backup)
            except OSError:
                pass
            state = AppState(candles=make_demo_candles(), is_demo=True)
            self.save(state)
            return state

    def save(self, state: AppState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "format_version": 1,
            "is_demo": state.is_demo,
            "candles": [
                {"open": c.open, "high": c.high, "low": c.low, "close": c.close, "timestamp": c.timestamp}
                for c in state.candles
            ],
            "experiments": [item.to_dict() for item in state.experiments[-200:]],
        }
        temporary = self.path.with_suffix(".tmp")
        with temporary.open("w", encoding="utf-8") as output:
            json.dump(payload, output, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.path)
