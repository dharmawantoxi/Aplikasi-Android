"""Worker tangkapan layar berkala tanpa menyentuh Tkinter dari background thread."""
from __future__ import annotations

from dataclasses import dataclass
import queue
import threading
import time
from typing import Callable, Optional

from .screen_capture import capture_region
from .vision import VisionResult, detect_candles


@dataclass
class MonitorEvent:
    generation: int
    result: Optional[VisionResult] = None
    error: str = ""
    elapsed: float = 0.0


class LiveScreenMonitor:
    """Satu worker daemon; antrean dibatasi agar UI selalu membaca hasil terbaru."""

    def __init__(
        self,
        events: queue.Queue,
        region: tuple[int, int, int, int],
        generation: int,
        interval: float = 2.0,
        capture_fn: Callable = capture_region,
        detect_fn: Callable = detect_candles,
    ):
        if interval < 0.5:
            raise ValueError("Interval live minimal 0,5 detik.")
        self.events = events
        self.region = region
        self.generation = generation
        self.interval = interval
        self.capture_fn = capture_fn
        self.detect_fn = detect_fn
        self.stop_event = threading.Event()
        self.thread: Optional[threading.Thread] = None

    @property
    def running(self) -> bool:
        return bool(self.thread and self.thread.is_alive() and not self.stop_event.is_set())

    def start(self) -> None:
        if self.thread and self.thread.is_alive():
            raise RuntimeError("Monitor live sudah berjalan.")
        self.thread = threading.Thread(
            target=self._run, name=f"CandleLabLive-{self.generation}", daemon=True,
        )
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()

    def _emit(self, event: MonitorEvent) -> None:
        try:
            self.events.put_nowait(event)
        except queue.Full:
            try:
                self.events.get_nowait()
            except queue.Empty:
                pass
            try:
                self.events.put_nowait(event)
            except queue.Full:
                pass

    def _run(self) -> None:
        while not self.stop_event.is_set():
            started = time.monotonic()
            try:
                image = self.capture_fn(self.region)
                result = self.detect_fn(image)
                elapsed = time.monotonic() - started
                self._emit(MonitorEvent(self.generation, result=result, elapsed=elapsed))
            except Exception as exc:
                # GUI menampilkan error dan menghentikan sesi; worker tidak memanggil Tk.
                self._emit(MonitorEvent(self.generation, error=str(exc)))
                self.stop_event.set()
                break
            remaining = max(0.05, self.interval - (time.monotonic() - started))
            self.stop_event.wait(remaining)
