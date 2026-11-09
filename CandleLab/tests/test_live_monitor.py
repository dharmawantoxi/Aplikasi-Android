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
    def test_worker_captures_repeatedly_without_gui_calls(self):
        events = queue.Queue(maxsize=2)
        calls = []

        def capture(region):
            calls.append(region)
            return object()

        monitor = LiveScreenMonitor(events, (1, 2, 30, 40), 7, interval=0.5,
                                    capture_fn=capture, detect_fn=lambda _image: result())
        monitor.start()
        first = events.get(timeout=1.0)
        monitor.stop()
        monitor.thread.join(timeout=1.0)
        self.assertEqual(first.generation, 7)
        self.assertEqual(len(first.result.candles), 20)
        self.assertGreaterEqual(len(calls), 1)
        self.assertFalse(monitor.thread.is_alive())
        self.assertTrue(all(region == (1, 2, 30, 40) for region in calls))

    def test_capture_error_is_emitted_and_worker_stops(self):
        events = queue.Queue(maxsize=1)

        def fail(_region):
            raise RuntimeError("screen unavailable")

        monitor = LiveScreenMonitor(events, (0, 0, 30, 30), 3, capture_fn=fail,
                                    detect_fn=lambda image: result())
        monitor.start()
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
