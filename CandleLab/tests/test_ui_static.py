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
