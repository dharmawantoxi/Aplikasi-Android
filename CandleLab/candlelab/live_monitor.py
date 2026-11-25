"""Worker tangkapan layar berkala tanpa menyentuh Tkinter dari background thread.

Urutan satu siklus penting agar CandleLab tidak memotret dirinya sendiri:

1. Worker mengirim ``MonitorEvent(ready=True)`` lalu menunggu.
2. UI (thread utama) menyembunyikan jendela, memberi tahu ``release()``.
3. Worker menunggu ``CAPTURE_DELAY`` supaya window manager selesai
   menggambar, baru menangkap dan mendeteksi.
4. UI menampilkan kembali jendela saat event hasil diterima.
"""
from __future__ import annotations

from dataclasses import dataclass
import queue
import threading
import time
from typing import Callable, Optional

from .screen_capture import capture_region
from .vision import VisionResult, detect_candles

# Jeda setelah UI menyembunyikan jendela sebelum piksel diambil.
CAPTURE_DELAY = 0.12


@dataclass
class MonitorEvent:
    generation: int
    result: Optional[VisionResult] = None
    error: str = ""
    elapsed: float = 0.0
    ready: bool = False


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
        capture_delay: float = CAPTURE_DELAY,
    ):
        if interval < 0.5:
            raise ValueError("Interval live minimal 0,5 detik.")
        self.events = events
        self.region = region
        self.generation = generation
        self.interval = interval
        self.capture_fn = capture_fn
        self.detect_fn = detect_fn
        self.capture_delay = max(0.0, capture_delay)
        self.stop_event = threading.Event()
        self.ready_event = threading.Event()
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
        # Lepaskan worker yang sedang menunggu agar thread bisa berhenti.
        self.ready_event.set()

    def release(self) -> None:
        """Dipanggil UI setelah jendela disembunyikan; worker boleh menangkap."""
        self.ready_event.set()

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

    def _wait_for_ui(self) -> bool:
        """Minta UI menyembunyikan jendela; False bila monitor dihentikan."""
        self.ready_event.clear()
        self._emit(MonitorEvent(self.generation, ready=True))
        while not self.stop_event.is_set():
            if self.ready_event.wait(timeout=0.5):
                return not self.stop_event.is_set()
        return False

    def _run(self) -> None:
        while not self.stop_event.is_set():
            started = time.monotonic()
            if not self._wait_for_ui():
                break
            # Beri window manager waktu menghapus jendela dari layar.
            if self.capture_delay and self.stop_event.wait(self.capture_delay):
                break
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
