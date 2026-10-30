#!/usr/bin/env python3
"""Checks generated launcher resources. Requires Pillow."""
from pathlib import Path
import math
import unittest
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


class LauncherIconTest(unittest.TestCase):
    def test_legacy_sizes_and_transparent_corners(self):
        for density, size in DENSITIES.items():
            for icon in ("ic_launcher", "ic_launcher_round"):
                path = RES / ("mipmap-" + density) / (icon + ".png")
                with self.subTest(path=path), Image.open(path) as im:
                    self.assertEqual((size, size), im.size)
                    self.assertEqual("RGBA", im.mode)
                    self.assertEqual(0, im.getpixel((0, 0))[3])
                    self.assertEqual(255, im.getpixel((size // 2, size // 2))[3])

    def test_adaptive_and_monochrome_layers(self):
        with Image.open(RES / "drawable-nodpi" / "ic_launcher_foreground.png") as fg:
            self.assertEqual((432, 432), fg.size)
            self.assertEqual((255, 255), fg.getchannel("A").getextrema())
        with Image.open(RES / "drawable-nodpi" / "ic_launcher_monochrome.png") as mono:
            self.assertEqual((432, 432), mono.size)
            alpha = mono.getchannel("A")
            self.assertEqual(0, alpha.getpixel((0, 0)))
            self.assertEqual(255, alpha.getextrema()[1])
            # Android's recommended 66dp circle inside a 108dp foreground.
            radius = 432 * 66 / 108 / 2
            for y in range(432):
                for x in range(432):
                    if alpha.getpixel((x, y)):
                        self.assertLessEqual(math.hypot(x - 216, y - 216), radius)

    def test_adaptive_resource_versions(self):
        for version in (26, 33):
            for name in ("ic_launcher", "ic_launcher_round"):
                root = ET.parse(RES / ("mipmap-anydpi-v" + str(version)) / (name + ".xml")).getroot()
                self.assertEqual("adaptive-icon", root.tag)
                self.assertEqual("@drawable/ic_launcher_foreground", root.find("foreground").get(ANDROID + "drawable"))
                if version == 33:
                    self.assertEqual("@drawable/ic_launcher_monochrome", root.find("monochrome").get(ANDROID + "drawable"))
                else:
                    self.assertIsNone(root.find("monochrome"))


if __name__ == "__main__":
    unittest.main()
