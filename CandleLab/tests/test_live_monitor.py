import queue
import unittest

from PIL import Image

from candlelab.live_monitor import LiveScreenMonitor, MonitorEvent
from candlelab.model import make_demo_candles
from candlelab.vision import VisionResult


def result(count=20):
    candles = make_demo_candles(count)
    return VisionResult(candles, [], Image.new("RGB", (20, 20)), 0.9, count)


class LiveMonitorTests(unittest.TestCase):
    def _start(self, events, capture, generation=7, interval=0.5):
        monitor = LiveScreenMonitor(events, (1, 2, 30, 40), generation, interval=interval,
                                    capture_fn=capture, detect_fn=lambda _image: result(),
                                    capture_delay=0)
        monitor.start()
        return monitor

    def test_worker_waits_for_ui_before_capturing(self):
        """Worker tidak boleh memotret sebelum UI menyembunyikan jendela."""
        events = queue.Queue(maxsize=4)
        calls = []
        monitor = self._start(events, lambda region: calls.append(region) or object())

        ready = events.get(timeout=1.0)
        self.assertTrue(ready.ready)
        # Belum ada tangkapan sebelum UI membalas release().
        self.assertEqual(calls, [])

        monitor.release()
        done = events.get(timeout=1.0)
        monitor.stop()
        monitor.thread.join(timeout=1.0)

        self.assertEqual(done.generation, 7)
        self.assertEqual(len(done.result.candles), 20)
        self.assertEqual(calls, [(1, 2, 30, 40)])
        self.assertFalse(monitor.thread.is_alive())

    def test_ready_event_precedes_every_capture(self):
        events = queue.Queue(maxsize=8)
        monitor = self._start(events, lambda region: object())

        order = []
        for _ in range(2):
            ready = events.get(timeout=1.0)
            self.assertTrue(ready.ready)
            monitor.release()
            done = events.get(timeout=1.0)
            self.assertFalse(done.ready)
            order.append(bool(done.result))
        monitor.stop()
        monitor.thread.join(timeout=1.0)
        self.assertEqual(order, [True, True])

    def test_stop_releases_waiting_worker(self):
        events = queue.Queue(maxsize=4)
        monitor = self._start(events, lambda region: object(), interval=5)
        self.assertTrue(events.get(timeout=1.0).ready)
        monitor.stop()  # tanpa release(): harus tetap berhenti
        monitor.thread.join(timeout=1.0)
        self.assertFalse(monitor.thread.is_alive())

    def test_capture_error_is_emitted_and_worker_stops(self):
        events = queue.Queue(maxsize=2)

        def fail(_region):
            raise RuntimeError("screen unavailable")

        monitor = self._start(events, fail, generation=3)
        self.assertTrue(events.get(timeout=1.0).ready)
        monitor.release()
        event = events.get(timeout=1.0)
        monitor.thread.join(timeout=1.0)
        self.assertEqual(event.error, "screen unavailable")
        self.assertFalse(monitor.thread.is_alive())

    def test_queue_keeps_latest_result_when_gui_is_busy(self):
        events = queue.Queue(maxsize=1)
        monitor = LiveScreenMonitor(events, (0, 0, 30, 30), 1)
        events.put(MonitorEvent(1, error="old"))
        monitor._emit(MonitorEvent(1, result=result()))
        event = events.get_nowait()
        self.assertEqual(event.error, "")
        self.assertIsNotNone(event.result)

    def test_invalid_interval_and_duplicate_start_are_rejected(self):
        with self.assertRaises(ValueError):
            LiveScreenMonitor(queue.Queue(), (0, 0, 30, 30), 1, interval=0.1)
        monitor = LiveScreenMonitor(queue.Queue(), (0, 0, 30, 30), 1, interval=5,
                                    capture_fn=lambda region: object(),
                                    detect_fn=lambda image: result())
        monitor.start()
        try:
            with self.assertRaises(RuntimeError):
                monitor.start()
        finally:
            monitor.stop()
            monitor.thread.join(timeout=1.0)


if __name__ == "__main__":
    unittest.main()
