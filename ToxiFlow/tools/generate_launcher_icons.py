#!/usr/bin/env python3
"""Regenerate Android launcher resources from the selected ToxiFlow artwork.

Requires Pillow. The source artwork stays unchanged; generated resources are
written to app/src/main/res. Android's adaptive artwork gets additional safe
padding, and the Android 13 monochrome layer uses the artwork's alpha silhouette.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "toxiflow-icon.png"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE_SIZE = 432  # 108dp at xxxhdpi
ADAPTIVE_SCALE = 0.83  # keep the wallet and arrows within the 66dp safe zone
BACKGROUND = (36, 70, 54, 255)


def main():
    with Image.open(SOURCE) as source:
        artwork = source.convert("RGB")
    if artwork.width != artwork.height or artwork.width < 512:
        raise ValueError("The source icon must be a square image of at least 512 pixels")

    # Render masks at the source resolution and downsample for smooth legacy edges.
    side = artwork.width
    rounded = Image.new("L", (side, side), 0)
    ImageDraw.Draw(rounded).rounded_rectangle((0, 0, side - 1, side - 1),
                                             radius=round(side * 0.22), fill=255)
    circular = Image.new("L", (side, side), 0)
    ImageDraw.Draw(circular).ellipse((0, 0, side - 1, side - 1), fill=255)
    for density, pixels in DENSITIES.items():
        target = RES / ("mipmap-" + density)
        target.mkdir(parents=True, exist_ok=True)
        for name, mask in (("ic_launcher", rounded), ("ic_launcher_round", circular)):
            legacy = artwork.convert("RGBA")
            legacy.putalpha(mask)
            legacy.resize((pixels, pixels), Image.Resampling.LANCZOS).save(target / (name + ".png"), optimize=True)

    target = RES / "drawable-nodpi"
    target.mkdir(parents=True, exist_ok=True)
    scaled_size = round(ADAPTIVE_SIZE * ADAPTIVE_SCALE)
    offset = (ADAPTIVE_SIZE - scaled_size) // 2
    scaled = artwork.resize((scaled_size, scaled_size), Image.Resampling.LANCZOS).convert("RGBA")
    foreground = Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), BACKGROUND)
    foreground.alpha_composite(scaled, (offset, offset))
    foreground.save(target / "ic_launcher_foreground.png", optimize=True)

    # The chosen mark is light on a dark background. A smooth brightness ramp
    # removes the background while preserving anti-aliased edges and gold dots.
    luminance = artwork.convert("L")
    alpha = luminance.point(lambda y: 0 if y <= 85 else 255 if y >= 145 else round((y - 85) * 255 / 60))
    silhouette = Image.new("RGBA", artwork.size, (255, 255, 255, 0))
    silhouette.putalpha(alpha)
    silhouette = silhouette.resize((scaled_size, scaled_size), Image.Resampling.LANCZOS)
    monochrome = Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), (255, 255, 255, 0))
    monochrome.alpha_composite(silhouette, (offset, offset))
    monochrome.save(target / "ic_launcher_monochrome.png", optimize=True)

    print("Generated legacy, adaptive, and monochrome launcher icons from", SOURCE.relative_to(ROOT))


if __name__ == "__main__":
    main()
