"""Pengenalan tombol broker dan pengaman sebelum klik.

Klik otomatis adalah fitur paling berisiko: klik yang salah sasaran menekan
tombol lain di broker. Tes di sini menekankan penolakan, bukan keberhasilan.
"""
import unittest

from PIL import Image, ImageDraw

from candlelab.auto_click import (
    MIN_BUTTON_HEIGHT,
    MIN_BUTTON_WIDTH,
    click_point,
    find_button_pair,
    find_buttons,
)
from candlelab.auto_click_win import ClickLimiter, check_before_click, is_point_inside


class _Prediction:
    """Prediksi minimal untuk menguji RoundTracker tanpa model."""

    def __init__(self, direction: int):
        self.available = True
        self.green_probability = 0.6 if direction == 1 else 0.4
        self.predicted_direction = direction
        self.pattern = (1,)
        self.matches = 1


def broker_panel(width=300, height=400, higher_box=(60, 40, 200, 160),
                  lower_box=(60, 210, 200, 330)):
    image = Image.new("RGB", (width, height), "#FFFFFF")
    draw = ImageDraw.Draw(image)
    if higher_box:
        draw.rectangle(higher_box, fill="#21B45B")
    if lower_box:
        draw.rectangle(lower_box, fill="#E03A3A")
    return image


class ButtonDetectionTests(unittest.TestCase):
    def test_finds_stacked_higher_and_lower(self):
        pair, reason = find_button_pair(broker_panel())
        self.assertIsNotNone(pair, reason)
        self.assertEqual(pair.higher, (130, 100))
        self.assertEqual(pair.lower, (130, 270))

    def test_ignores_small_icon_of_same_colour(self):
        image = broker_panel()
        ImageDraw.Draw(image).rectangle((250, 100, 268, 118), fill="#21B45B")
        candidates = find_buttons(image)
        self.assertEqual(len(candidates), 2, "ikon kecil harus ditolak")
        self.assertTrue(all(min(c.box[2] - c.box[0], c.box[3] - c.box[1]) >= MIN_BUTTON_WIDTH
                            for c in candidates))

    def test_rejects_when_only_one_button_visible(self):
        pair, reason = find_button_pair(broker_panel(lower_box=None))
        self.assertIsNone(pair)
        self.assertIn("tidak dikenali", reason)

    def test_rejects_when_buttons_not_stacked(self):
        # Kedua tombol ada, tetapi bersebelahan: bukan panel broker biasa.
        pair, reason = find_button_pair(
            broker_panel(higher_box=(20, 40, 140, 160), lower_box=(160, 40, 280, 160)))
        self.assertIsNone(pair)
        self.assertIn("sejajar", reason)

    def test_rejects_plain_candles(self):
        # Chart candle: body sempit dan tinggi, harus ditolak sebagai tombol.
        image = Image.new("RGB", (400, 300), "#101513")
        draw = ImageDraw.Draw(image)
        for index in range(10):
            x = 20 + index * 30
            draw.rectangle((x, 60, x + 12, 240), fill="#26A69A" if index % 2 else "#EF5350")
        pair, reason = find_button_pair(image)
        self.assertIsNone(pair, "candle tidak boleh dianggap tombol broker")

    def test_click_point_offsets_to_screen(self):
        pair, _ = find_button_pair(broker_panel())
        self.assertEqual(click_point((300, 400), (100, 250), pair, 1), (230, 350))
        self.assertEqual(click_point((300, 400), (100, 250), pair, -1), (230, 520))

    def test_button_height_floor_is_sane(self):
        self.assertGreaterEqual(MIN_BUTTON_HEIGHT, 40)


class ClickSafetyTests(unittest.TestCase):
    """Semua pengaman harus gagal-tertutup (menolak), bukan gagal-terbuka."""

    def test_outside_region_is_rejected(self):
        region = (100, 200, 400, 600)
        with _patched_platform("win32"):
            guard = check_before_click((900, 300), region, require_active=False,
                                       window_title="IQ Option")
        self.assertFalse(guard.allowed)
        self.assertIn("luar area", guard.reason)

    def test_inside_region_passes_point_check(self):
        self.assertTrue(is_point_inside((200, 300), (100, 200, 400, 600)))
        self.assertFalse(is_point_inside((99, 300), (100, 200, 400, 600)))

    def test_non_windows_always_rejected(self):
        with _patched_platform("linux"):
            guard = check_before_click((200, 300), (100, 200, 400, 600),
                                       require_active=False, window_title="IQ Option")
            self.assertFalse(guard.allowed)
            self.assertIn("Windows", guard.reason)


class ClickLimiterTests(unittest.TestCase):
    def test_blocks_two_clicks_within_interval(self):
        limiter = ClickLimiter(min_interval=20, max_per_session=10)
        self.assertTrue(limiter.allow(0)[0])
        limiter.record(0)
        allowed, reason = limiter.allow(5)
        self.assertFalse(allowed)
        self.assertIn("Terlalu cepat", reason)

    def test_allows_after_interval(self):
        limiter = ClickLimiter(min_interval=20, max_per_session=10)
        limiter.record(0)
        self.assertTrue(limiter.allow(25)[0])

    def test_stops_at_session_limit(self):
        limiter = ClickLimiter(min_interval=0, max_per_session=2)
        for moment in (0, 10):
            self.assertTrue(limiter.allow(moment)[0])
            limiter.record(moment)
        allowed, reason = limiter.allow(99)
        self.assertFalse(allowed)
        self.assertIn("Batas", reason)

    def test_reset_clears_count(self):
        limiter = ClickLimiter(min_interval=0, max_per_session=1)
        limiter.record(0)
        self.assertFalse(limiter.allow(10)[0])
        limiter.reset()
        self.assertTrue(limiter.allow(10)[0])


class _patched_platform:
    """Ganti sys.platform sementara pada modul auto_click_win."""

    def __init__(self, value):
        self.value = value

    def __enter__(self):
        from candlelab import auto_click_win
        self.module = auto_click_win
        self.original = auto_click_win.sys.platform
        auto_click_win.sys.platform = self.value
        return self

    def __exit__(self, *exc):
        self.module.sys.platform = self.original
        return False


if __name__ == "__main__":
    unittest.main()


class FirstClickTests(unittest.TestCase):
    """Regresi: klik pertama dulu ditahan 'terlalu cepat sejak klik sebelumnya'."""

    def test_first_click_is_allowed_immediately(self):
        limiter = ClickLimiter(min_interval=20.0)
        allowed, reason = limiter.allow(1000.0)
        self.assertTrue(allowed, reason)

    def test_second_click_inside_interval_is_refused(self):
        limiter = ClickLimiter(min_interval=20.0)
        limiter.allow(1000.0)
        limiter.record(1000.0)
        allowed, _ = limiter.allow(1005.0)
        self.assertFalse(allowed)

    def test_click_allowed_again_after_interval(self):
        limiter = ClickLimiter(min_interval=20.0)
        limiter.allow(1000.0)
        limiter.record(1000.0)
        allowed, _ = limiter.allow(1025.0)
        self.assertTrue(allowed)


class ClickDiagnosticsTests(unittest.TestCase):
    """Alasan penolakan harus terlihat, bukan tertimpa status live."""

    @classmethod
    def setUpClass(cls):
        from pathlib import Path

        cls.ui = (Path(__file__).resolve().parents[1] / "candlelab" / "ui.py").read_text(
            encoding="utf-8")
        cls.overlay = (Path(__file__).resolve().parents[1] / "candlelab"
                       / "overlay.py").read_text(encoding="utf-8")

    def test_rejections_go_to_a_separate_status(self):
        self.assertIn("self.click_status", self.ui)
        self.assertIn("def _click_problem", self.ui)
        # setiap penolakan memakai helper, bukan set_status langsung
        self.assertNotIn('self.set_status("KLIK DITOLAK', self.ui)

    def test_badge_shows_the_click_note(self):
        self.assertIn("click_note", self.overlay)
        self.assertIn("klik ditolak", self.overlay)

    def test_there_is_a_dry_run_check(self):
        self.assertIn("def test_click_now", self.ui)
        self.assertIn("Uji klik sekarang", self.ui)

    def test_click_retries_while_round_is_open(self):
        self.assertIn("def _try_auto_click_for_current", self.ui)
        # penilai klik tidak lagi bergantung pada jumlah candle layar
        self.assertIn("current.seq", self.ui)


class RoundSequenceTests(unittest.TestCase):
    """``seq`` harus naik walau jumlah candle di layar menurun."""

    def test_each_round_gets_a_new_sequence(self):
        from candlelab.rounds import RoundTracker
        from candlelab.model import Candle

        tracker = RoundTracker()
        pred = _Prediction(1)
        for i in range(3):
            tracker.observe([Candle(open=1, high=2, low=0.5, close=1.5) for _ in range(10 + i)],
                            pred)
        self.assertIsNotNone(tracker.current)
        self.assertEqual(tracker.current.seq, tracker.rounds_opened)
        self.assertGreaterEqual(tracker.current.seq, 1)

    def test_reset_clears_sequence(self):
        from candlelab.rounds import RoundTracker

        tracker = RoundTracker()
        tracker.rounds_opened = 5
        tracker.reset()
        self.assertEqual(tracker.rounds_opened, 0)


class ClickRecursionTests(unittest.TestCase):
    """Menolak klik memperbarui badge, dan badge memicu percobaan klik lagi."""

    def test_click_attempt_is_guarded(self):
        from pathlib import Path

        ui = (Path(__file__).resolve().parents[1] / "candlelab" / "ui.py").read_text(
            encoding="utf-8")
        self.assertIn("self._clicking", ui)
        start = ui.index("def _try_auto_click_for_current")
        body = ui[start:start + 1200]
        self.assertIn("if self._clicking:", body)


class ClickSafetyPointTests(unittest.TestCase):
    """Pengaman klik harus berbasis titik, bukan judul jendela."""

    def test_safety_helper_exists(self):
        from candlelab import auto_click_win

        self.assertTrue(hasattr(auto_click_win, "click_safety"))
        self.assertTrue(hasattr(auto_click_win, "foreground_title"))
        self.assertTrue(hasattr(auto_click_win, "window_root_at"))

    def test_non_windows_is_refused_before_anything_else(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        # Harus dipaksa: di runner Windows platform_supported() bernilai True,
        # sehingga pemeriksaan area yang lebih dulu menolak.
        with patch("candlelab.auto_click_win.platform_supported", return_value=False):
            guard = click_safety((900, 300), (100, 200, 400, 600), True)
        self.assertFalse(guard.allowed)
        self.assertIn("Windows", guard.reason)

    def test_point_outside_region_is_refused(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True):
            guard = click_safety((50, 50), (100, 200, 400, 600), True)
        self.assertFalse(guard.allowed)
        self.assertIn("luar area", guard.reason)

    def test_own_window_active_produces_a_clear_hint(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True), \
                patch("candlelab.auto_click_win.foreground_window", return_value=111), \
                patch("candlelab.auto_click_win.is_our_window", return_value=True):
            guard = click_safety((200, 300), (100, 200, 400, 600), True)
        self.assertFalse(guard.allowed)
        self.assertIn("CandleLab", guard.reason)

    def test_click_into_active_foreign_window_is_allowed(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True), \
                patch("candlelab.auto_click_win.foreground_window", return_value=111), \
                patch("candlelab.auto_click_win.is_our_window", return_value=False), \
                patch("candlelab.auto_click_win.window_root_at", return_value=111):
            guard = click_safety((200, 300), (100, 200, 400, 600), True)
        self.assertTrue(guard.allowed, guard.reason)

    def test_point_belonging_to_another_window_is_refused(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True), \
                patch("candlelab.auto_click_win.foreground_window", return_value=111), \
                patch("candlelab.auto_click_win.is_our_window", return_value=False), \
                patch("candlelab.auto_click_win.window_root_at", return_value=999):
            guard = click_safety((200, 300), (100, 200, 400, 600), True)
        self.assertFalse(guard.allowed)
        self.assertIn("bukan milik jendela", guard.reason)


class BadgeFocusTests(unittest.TestCase):
    """Badge tidak boleh merebut fokus, karena itu mematikan kliknya sendiri."""

    def test_badge_is_no_activate(self):
        from pathlib import Path

        source = (Path(__file__).resolve().parents[1] / "candlelab"
                  / "overlay.py").read_text(encoding="utf-8")
        self.assertIn("WIN32_EX_NOACTIVATE", source)
        self.assertIn("def _make_no_activate", source)

    def test_ui_can_restore_focus(self):
        from pathlib import Path

        source = (Path(__file__).resolve().parents[1] / "candlelab"
                  / "ui.py").read_text(encoding="utf-8")
        self.assertIn("def _restore_previous_window", source)
        self.assertIn("def _remember_foreign_window", source)


class WindowsBindingTests(unittest.TestCase):
    """Regresi: GetWindowThreadProcessId ada di user32, bukan kernel32."""

    def _patch_windll(self, user32, kernel32=None):
        from unittest.mock import MagicMock, patch

        from candlelab import auto_click_win as mod

        kernel32 = kernel32 or MagicMock()
        windll = MagicMock()
        windll.user32 = user32
        windll.kernel32 = kernel32
        fake_ctypes = MagicMock(windll=windll, byref=mod.ctypes.byref,
                                c_ulong=mod.ctypes.c_ulong,
                                wintypes=mod.ctypes.wintypes)
        return patch.multiple(
            mod, platform_supported=MagicMock(return_value=True),
            ctypes=fake_ctypes, _user32=MagicMock(return_value=user32))

    def test_pid_lookup_uses_user32(self):
        from unittest.mock import MagicMock

        from candlelab import auto_click_win as mod

        user32 = MagicMock()
        user32.GetWindowThreadProcessId.side_effect = (
            lambda handle, ref: setattr(ref._obj, "value", 4321))
        kernel32 = MagicMock()
        kernel32.GetCurrentProcessId.return_value = 4321

        with self._patch_windll(user32, kernel32):
            self.assertTrue(mod.is_our_window(12345))
        user32.GetWindowThreadProcessId.assert_called_once()

    def test_missing_function_does_not_raise(self):
        from unittest.mock import MagicMock

        from candlelab import auto_click_win as mod

        user32 = MagicMock()
        user32.GetWindowThreadProcessId.side_effect = AttributeError(
            "function 'GetWindowThreadProcessId' not found")

        with self._patch_windll(user32):
            # Tidak boleh melempar; jawaban False membuat pengaman lain bekerja.
            self.assertFalse(mod.is_our_window(12345))
            self.assertIsNone(mod.window_process_id(12345))

    def test_window_root_at_survives_missing_function(self):
        from unittest.mock import MagicMock

        from candlelab import auto_click_win as mod

        user32 = MagicMock()
        user32.WindowFromPoint.side_effect = AttributeError("nope")

        with self._patch_windll(user32):
            self.assertIsNone(mod.window_root_at((10, 10)))


class LiveErrorResilienceTests(unittest.TestCase):
    """Kegagalan klak ctypes tidak boleh menutup live dengan dialog."""

    def test_live_keeps_running_on_click_error(self):
        from pathlib import Path

        ui = (Path(__file__).resolve().parents[1] / "candlelab" / "ui.py").read_text(
            encoding="utf-8")
        start = ui.index("def report_callback_exception")
        body = ui[start:start + 700]
        self.assertIn("live_active", body)
        self.assertIn("KLIK DITOLAK", body)


class BadgeClickColourTests(unittest.TestCase):
    """⚡ harus berwarna hanya saat klik otomatis aktif.

    tkinter tidak tersedia di semua lingkungan tes (mis. runner Linux tanpa
    X), jadi warna dibaca dari sumber, bukan dengan mengimpor overlay.
    """

    @classmethod
    def setUpClass(cls):
        import re
        from pathlib import Path

        cls.source = (Path(__file__).resolve().parents[1] / "candlelab"
                      / "overlay.py").read_text(encoding="utf-8")
        cls.re = re

    def _colour(self, name: str) -> str:
        found = self.re.search(rf'^{name} = "(#[0-9A-Fa-f]{{6}})"', self.source, self.re.M)
        self.assertIsNotNone(found, f"{name} tidak ditemukan")
        return found.group(1)

    @staticmethod
    def _lum(hex_colour: str) -> float:
        r, g, b = (int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))
        return 0.299 * r + 0.587 * g + 0.114 * b

    def test_off_colour_is_muted_not_coloured(self):
        off = self._colour("CLICK_OFF_FG")
        on = self._colour("CLICK_ON_FG")
        self.assertLess(self._lum(off), self._lum(on))
        r, g, b = (int(off[i:i + 2], 16) for i in (1, 3, 5))
        self.assertLess(max(r, g, b) - min(r, g, b), 40,
                        "warna saat mati terlalu mencolok; harus mendekati abu-abu")

    def test_state_uses_the_on_colour(self):
        self.assertIn("fg=CLICK_ON_FG if enabled else CLICK_OFF_FG", self.source)

    def test_button_starts_uncoloured(self):
        self.assertIn('("⚡", self._toggle_click, CLICK_OFF_FG)', self.source)


class NewOptionTests(unittest.TestCase):
    """Tombol NEW OPTION oranye harus dikenali tanpa tertukar dengan LOWER."""

    ORANGE = (235, 110, 0)     # diukur dari screenshot IQ Option
    LOWER_RED = (215, 76, 57)
    GREEN = (0, 155, 46)

    def _image(self, blocks):
        from PIL import Image

        image = Image.new("RGB", (420, 780), (255, 255, 255))
        for box, colour in blocks:
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    image.putpixel((x, y), colour)
        return image

    def _session_buttons(self):
        """Susunan seperti layar IQ Option: HIGHER hijau, LOWER merah."""
        return [
            ((160, 460, 400, 580), self.GREEN),
            ((160, 620, 400, 740), self.LOWER_RED),
        ]

    def test_orange_button_is_detected(self):
        from candlelab.auto_click import find_new_option

        image = self._image(self._session_buttons() + [((20, 60, 300, 300), self.ORANGE)])
        box, reason = find_new_option(image)
        self.assertIsNotNone(box, reason)
        self.assertLess(box[1], 400, "tombol oranye harus ditemukan di bagian atas")

    def test_orange_is_not_mistaken_for_lower(self):
        from candlelab.auto_click import find_button_pair, find_new_option

        image = self._image(self._session_buttons() + [((20, 60, 300, 300), self.ORANGE)])
        self.assertIsNotNone(find_new_option(image)[0])
        pair, reason = find_button_pair(image)
        # Pasangan tetap harus ketemu, dan Lower tidak boleh jatuh di area oranye.
        self.assertIsNotNone(pair, reason)
        self.assertGreater(pair.lower[1], 500, "Lower tertukar dengan tombol NEW OPTION")

    def test_no_orange_reports_nothing(self):
        from candlelab.auto_click import find_new_option

        image = self._image(self._session_buttons())
        box, reason = find_new_option(image)
        self.assertIsNone(box)
        self.assertIn("tidak terlihat", reason)

    def test_green_and_red_are_not_orange(self):
        from candlelab.auto_click import orange_mask

        image = self._image([((10, 10, 200, 200), self.GREEN),
                             ((10, 210, 200, 400), self.LOWER_RED)])
        self.assertEqual(sum(orange_mask(image)), 0,
                         "hijau/merah tidak boleh masuk masker oranye")

    def test_new_option_point_is_absolute(self):
        from candlelab.auto_click import new_option_point

        self.assertEqual(new_option_point((100, 100), (1450, 110), (20, 40, 80, 100)),
                         (1450 + 50, 110 + 70))

    def test_wait_helper_gives_up_after_timeout(self):
        from candlelab.auto_click_win import wait_for_new_option

        calls = []

        def always_visible():
            calls.append(1)
            return True

        self.assertFalse(wait_for_new_option(always_visible, timeout=0.2, interval=0.05))
        self.assertGreater(len(calls), 1)

    def test_wait_helper_returns_once_hidden(self):
        from candlelab.auto_click_win import wait_for_new_option

        state = {"n": 0}

        def hides_quickly():
            state["n"] += 1
            return state["n"] < 2

        self.assertTrue(wait_for_new_option(hides_quickly, timeout=1.0, interval=0.02))


class SameWindowTests(unittest.TestCase):
    """Jendela anak / proses lain dalam aplikasi yang sama harus dianggap sama."""

    def test_identical_handles_match(self):
        from candlelab.auto_click_win import same_window

        self.assertTrue(same_window(0x1234, 0x1234))

    def test_child_element_in_same_process_matches(self):
        """WindowFromPoint sering mengembalikan elemen chart, bukan jendela induk."""
        from unittest.mock import patch

        from candlelab.auto_click_win import same_window

        with patch("candlelab.auto_click_win.window_process_id", return_value=4242), \
                patch("candlelab.auto_click_win.window_caption", return_value=""):
            self.assertTrue(same_window(0xABC, 0xDEF))

    def test_chromium_helper_process_matches_by_title(self):
        """IQ Option di peramban: jendela anak bisa beda PID, judul tetap sama."""
        from unittest.mock import patch

        from candlelab.auto_click_win import same_window

        with patch("candlelab.auto_click_win.window_process_id",
                   side_effect=[111, 222]), \
                patch("candlelab.auto_click_win.window_caption",
                      return_value="IQ Option - Google Chrome"):
            self.assertTrue(same_window(0x1, 0x2))

    def test_different_windows_do_not_match(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import same_window

        with patch("candlelab.auto_click_win.window_process_id",
                   side_effect=[111, 222]), \
                patch("candlelab.auto_click_win.window_caption",
                      side_effect=["IQ Option", "Notepad"]):
            self.assertFalse(same_window(0x1, 0x2))

    def test_unknown_identity_does_not_reject(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import same_window

        with patch("candlelab.auto_click_win.window_process_id", return_value=None), \
                patch("candlelab.auto_click_win.window_caption", return_value=""):
            self.assertFalse(same_window(0x1, 0x2))

    def test_click_safety_allows_when_target_window_is_unknown(self):
        """Kalau jendela di titik tak terbaca, pengaman lain yang menentukan."""
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True), \
                patch("candlelab.auto_click_win.foreground_window", return_value=111), \
                patch("candlelab.auto_click_win.is_our_window", return_value=False), \
                patch("candlelab.auto_click_win.window_root_at", return_value=None):
            guard = click_safety((200, 300), (100, 200, 400, 600), True)
        self.assertTrue(guard.allowed, guard.reason)

    def test_rejection_names_both_windows(self):
        from unittest.mock import patch

        from candlelab.auto_click_win import click_safety

        with patch("candlelab.auto_click_win.platform_supported", return_value=True), \
                patch("candlelab.auto_click_win.foreground_window", return_value=111), \
                patch("candlelab.auto_click_win.is_our_window", return_value=False), \
                patch("candlelab.auto_click_win.window_root_at", return_value=999), \
                patch("candlelab.auto_click_win.same_window", return_value=False), \
                patch("candlelab.auto_click_win.window_caption",
                      side_effect=["IQ Option", "Notepad"]):
            guard = click_safety((200, 300), (100, 200, 400, 600), True)
        self.assertFalse(guard.allowed)
        self.assertIn("IQ Option", guard.reason)
        self.assertIn("Notepad", guard.reason)
