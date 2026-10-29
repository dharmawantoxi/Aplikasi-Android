#!/usr/bin/env python3
"""Regenerate launcher bitmaps from design/docscan-icon.png. Requires Pillow."""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
source = Image.open(ROOT / "design" / "docscan-icon.png").convert("RGB")
if source.width != source.height:
    raise ValueError("Source icon must be square")

# Android adaptive icons have an outer 108dp canvas but only the central 66dp
# is guaranteed to survive launcher masks. Scale the artwork into that safe
# region, extending the artwork’s blue background at the edges.
size = 1024
original = source.resize((size, size), Image.Resampling.LANCZOS)
inner_size = round(size * 0.74)
inner = original.resize((inner_size, inner_size), Image.Resampling.LANCZOS).convert("RGBA")
offset = (size - inner_size) // 2
# Match the artwork's blue background, then feather the outer edge of the
# scaled square. This avoids hard seams when the source contains decoration.
samples = [original.getpixel((round(size * x), round(size * y)))
           for x, y in [(0.2, 0.2), (0.8, 0.2), (0.2, 0.8), (0.8, 0.8)]]
gradient = Image.new("RGB", (256, 256))
px = gradient.load()
for y in range(256):
    fy = y / 255
    for x in range(256):
        fx = x / 255
        px[x, y] = tuple(round((1 - fy) * ((1 - fx) * samples[0][c] + fx * samples[1][c]) +
                               fy * ((1 - fx) * samples[2][c] + fx * samples[3][c])) for c in range(3))
adaptive = gradient.resize((size, size), Image.Resampling.BILINEAR).convert("RGBA")
mask = Image.new("L", (inner_size, inner_size))
mp = mask.load()
feather = inner_size * 0.19
for y in range(inner_size):
    for x in range(inner_size):
        distance = min(x, y, inner_size - 1 - x, inner_size - 1 - y)
        t = max(0.0, min(1.0, distance / feather))
        mp[x, y] = round(255 * t * t * (3 - 2 * t))
inner.putalpha(mask)
adaptive.alpha_composite(inner, (offset, offset))
adaptive.convert("RGB").resize((432, 432), Image.Resampling.LANCZOS).save(
    RES / "drawable-nodpi" / "ic_launcher_art.png", optimize=True)

# Legacy launchers (Android 7.x) use a rounded square or circular bitmap.
for density, pixels in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = RES / f"mipmap-{density}"
    for name, circular in [("ic_launcher", False), ("ic_launcher_round", True)]:
        # Render masks at 4x size to keep the edges smooth even at mdpi.
        side = pixels * 4
        image = source.resize((side, side), Image.Resampling.LANCZOS).convert("RGBA")
        shape = Image.new("L", (side, side))
        draw = ImageDraw.Draw(shape)
        if circular:
            draw.ellipse((0, 0, side - 1, side - 1), fill=255)
        else:
            draw.rounded_rectangle((0, 0, side - 1, side - 1), radius=round(side * 0.22), fill=255)
        image.putalpha(shape)
        image.resize((pixels, pixels), Image.Resampling.LANCZOS).save(folder / f"{name}.png", optimize=True)
