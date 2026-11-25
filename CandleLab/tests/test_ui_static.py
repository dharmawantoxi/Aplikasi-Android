"""Pemeriksaan statis UI: cegah tabrakan nama atribut dengan metode Tk.

``CandleLabApp`` menyimpan data aplikasi di ``self.state``. Karena itu
``self.state("normal")`` TIDAK boleh dipakai — pemanggilan itu akan gagal saat
runtime dengan "'AppState' object is not callable". Gunakan ``wm_state()``.
"""
import ast
import unittest
from pathlib import Path

UI = Path(__file__).resolve().parents[1] / "candlelab" / "ui.py"
# Atribut milik CandleLabApp yang menutupi metode Tk bernama sama.
SHADOWED = {"state"}


class TkNameClashTests(unittest.TestCase):
    def test_no_call_of_shadowed_tk_method(self):
        tree = ast.parse(UI.read_text(encoding="utf-8"))
        offenders = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            if (isinstance(func, ast.Attribute) and func.attr in SHADOWED
                    and isinstance(func.value, ast.Name) and func.value.id == "self"):
                offenders.append(node.lineno)
        self.assertEqual(offenders, [], f"Gunakan wm_state(), bukan self.state(): baris {offenders}")

    def test_state_is_still_appdata(self):
        # Pastikan penamaan di atas tetap sesuai kenyataan.
        source = UI.read_text(encoding="utf-8")
        self.assertIn("self.state = self.store.load()", source)


if __name__ == "__main__":
    unittest.main()


class OverlayModeSafetyTests(unittest.TestCase):
    """Regresi: refresh() saat overlay aktif membuat jendela tidak bisa kembali."""

    def setUp(self):
        import inspect
        import re
        from pathlib import Path

        source = Path(__file__).resolve().parents[1] / "candlelab" / "ui.py"
        self.text = source.read_text(encoding="utf-8")
        self.inspect = inspect
        self.re = re

    def _method_source(self, name: str) -> str:
        body = self.text
        start = body.index(f"    def {name}(")
        # potong sampai method berikutnya pada indentasi yang sama
        rest = body[start + 10:]
        match = self.re.search(r"\n    def ", rest)
        return body[start:start + 10 + (match.start() if match else len(rest))]

    def test_refresh_is_a_noop_during_overlay(self):
        source = self._method_source("refresh")
        self.assertIn("overlay_mode", source,
                      "refresh() harus menahan diri saat overlay aktif")
        self.assertIn("return", source)

    def test_toggle_auto_click_works_from_badge(self):
        source = self._method_source("set_auto_click")
        self.assertNotIn("self.refresh()", source,
                         "mengaktifkan klik otomatis tidak boleh rebuild halaman")
        self.assertIn("_refresh_overlay", source)

    def test_badge_is_wired_to_auto_click(self):
        self.assertIn("on_auto_click=self.toggle_auto_click_from_badge", self.text,
                      "badge harus bisa menyalakan klik otomatis tanpa buka jendela")

    def test_dialogs_use_a_safe_parent(self):
        source = self._method_source("toggle_auto_click")
        # pembungkus tidak boleh memakai self sebagai induk dialog saat overlay
        self.assertIn("set_auto_click", source)
        self.assertIn("_dialog_parent", self.text)
