"""Prediksi terkunci dan penilaian hasil eksperimen."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math
from typing import Optional
import uuid

from .model import DOJI, GREEN, Prediction


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class Experiment:
    id: str
    created_at: str
    candle_count: int
    order: int
    window: int
    green_probability: float
    pattern: tuple[int, ...]
    status: str = "pending"
    actual_direction: Optional[int] = None
    score: Optional[float] = None
    brier_score: Optional[float] = None
    resolved_at: Optional[str] = None
    note: str = ""

    @classmethod
    def lock(cls, prediction: Prediction, candle_count: int) -> "Experiment":
        if not prediction.available:
            raise ValueError("Prediksi belum tersedia.")
        return cls(
            id=uuid.uuid4().hex,
            created_at=utc_now(),
            candle_count=candle_count,
            order=prediction.order,
            window=prediction.window,
            green_probability=prediction.green_probability,
            pattern=tuple(prediction.pattern),
        )

    @property
    def pending(self) -> bool:
        return self.status == "pending"

    @property
    def predicted_direction(self) -> int:
        if self.green_probability > 0.5:
            return GREEN
        if self.green_probability < 0.5:
            return -1
        return DOJI

    def resolve(self, actual_direction: int) -> None:
        if not self.pending:
            raise ValueError("Eksperimen ini sudah selesai.")
        if actual_direction not in (-1, 0, 1):
            raise ValueError("Arah candle aktual tidak valid.")
        self.status = "resolved"
        self.actual_direction = actual_direction
        self.resolved_at = utc_now()
        if actual_direction == DOJI:
            self.note = "Doji tidak dinilai"
            return
        actual_green = 1.0 if actual_direction == GREEN else 0.0
        if self.green_probability == 0.5:
            self.score = 0.5
            self.note = "Probabilitas seimbang: skor 0,5"
        else:
            self.score = 1.0 if self.predicted_direction == actual_direction else 0.0
        self.brier_score = (self.green_probability - actual_green) ** 2

    def cancel(self, reason: str) -> None:
        if not self.pending:
            return
        self.status = "cancelled"
        self.resolved_at = utc_now()
        self.note = reason

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "created_at": self.created_at,
            "candle_count": self.candle_count,
            "order": self.order,
            "window": self.window,
            "green_probability": self.green_probability,
            "pattern": list(self.pattern),
            "status": self.status,
            "actual_direction": self.actual_direction,
            "score": self.score,
            "brier_score": self.brier_score,
            "resolved_at": self.resolved_at,
            "note": self.note,
        }

    @classmethod
    def from_dict(cls, raw: dict) -> "Experiment":
        probability = float(raw["green_probability"])
        if not math.isfinite(probability) or not 0.0 <= probability <= 1.0:
            raise ValueError("Probabilitas eksperimen tidak valid.")
        status = str(raw.get("status", "pending"))
        if status not in ("pending", "resolved", "cancelled"):
            raise ValueError("Status eksperimen tidak valid.")
        return cls(
            id=str(raw["id"]),
            created_at=str(raw["created_at"]),
            candle_count=int(raw["candle_count"]),
            order=int(raw["order"]),
            window=int(raw["window"]),
            green_probability=probability,
            pattern=tuple(int(value) for value in raw["pattern"]),
            status=status,
            actual_direction=(None if raw.get("actual_direction") is None else int(raw["actual_direction"])),
            score=(None if raw.get("score") is None else float(raw["score"])),
            brier_score=(None if raw.get("brier_score") is None else float(raw["brier_score"])),
            resolved_at=raw.get("resolved_at"),
            note=str(raw.get("note", "")),
        )


@dataclass(frozen=True)
class ExperimentSummary:
    resolved: int
    accuracy: Optional[float]
    brier_score: Optional[float]
    pending: int


def summarise(experiments: list[Experiment]) -> ExperimentSummary:
    scored = [item for item in experiments
              if item.status == "resolved" and item.score is not None and item.brier_score is not None]
    return ExperimentSummary(
        resolved=len(scored),
        accuracy=(sum(item.score for item in scored) / len(scored)) if scored else None,
        brier_score=(sum(item.brier_score for item in scored) / len(scored)) if scored else None,
        pending=sum(item.pending for item in experiments),
    )
