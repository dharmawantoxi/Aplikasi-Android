"""Deteksi candlestick berwarna dari gambar, tanpa OCR atau layanan cloud.

Harga hasil deteksi hanya memakai skala piksel relatif. Model warna tidak
memerlukan harga pasar absolut, tetapi pengguna tetap harus memeriksa preview.
"""
from __future__ import annotations

from dataclasses import dataclass
from statistics import median
from typing import TYPE_CHECKING, Optional

from .model import Candle, DOJI, GREEN, RED

if TYPE_CHECKING:
    from PIL import Image


@dataclass
class Component:
    direction: int
    box: tuple[int, int, int, int]
    area: int
    row_counts: dict[int, int]

    @property
    def center_x(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def width(self) -> int:
        return self.box[2] - self.box[0] + 1

    @property
    def height(self) -> int:
        return self.box[3] - self.box[1] + 1


@dataclass
class DetectedCandle:
    candle: Candle
    direction: int
    box: tuple[int, int, int, int]
    body_box: tuple[int, int, int, int]


@dataclass
class VisionResult:
    candles: list[Candle]
    detections: list[DetectedCandle]
    annotated_image: "Image.Image"
    confidence: float
    candidate_count: int
    warning: str = ""

    @property
    def sequence(self) -> str:
        return "".join("H" if item.direction == GREEN else "M" if item.direction == RED else "D"
                       for item in self.detections)


def _masks(image: "Image.Image") -> tuple[bytearray, bytearray]:
    rgb = image.convert("RGB")
    green = bytearray(rgb.width * rgb.height)
    red = bytearray(rgb.width * rgb.height)
    pixels = list(rgb.getdata())
    for index, (r, g, b) in enumerate(pixels):
        brightest = max(r, g, b)
        if brightest < 65 or (brightest - min(r, g, b)) / brightest < 0.27:
            continue
        # Mencakup hijau murni dan teal yang umum dipakai grafik trading.
        if g - r >= 28 and g - b >= 4 and g >= r * 1.16 and g >= b * 0.97:
            green[index] = 1
        elif r - g >= 32 and r - b >= 27 and r >= g * 1.20 and r >= b * 1.16:
            red[index] = 1
    return green, red


def _components(mask: bytearray, width: int, height: int, direction: int) -> list[Component]:
    visited = bytearray(mask)
    components: list[Component] = []
    for start in range(width * height):
        if not visited[start]:
            continue
        visited[start] = 0
        stack = [start]
        min_x = max_x = start % width
        min_y = max_y = start // width
        area = 0
        row_counts: dict[int, int] = {}
        while stack:
            index = stack.pop()
            x, y = index % width, index // width
            area += 1
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
            row_counts[y] = row_counts.get(y, 0) + 1
            for dy in (-1, 0, 1):
                ny = y + dy
                if ny < 0 or ny >= height:
                    continue
                for dx in (-1, 0, 1):
                    nx = x + dx
                    if nx < 0 or nx >= width or (dx == 0 and dy == 0):
                        continue
                    neighbour = ny * width + nx
                    if visited[neighbour]:
                        visited[neighbour] = 0
                        stack.append(neighbour)
        if area >= 4:
            components.append(Component(direction, (min_x, min_y, max_x, max_y), area, row_counts))
    return components


def _body_box(component: Component) -> Optional[tuple[int, int, int, int]]:
    x1, y1, x2, y2 = component.box
    threshold = max(2, round(component.width * 0.50))
    rows = [y for y, count in component.row_counts.items() if count >= threshold]
    if not rows or max(rows) - min(rows) + 1 < 2:
        return None
    return x1, min(rows), x2, max(rows)


def _regular_series(components: list[Component]) -> tuple[list[Component], float]:
    """Pilih deret dengan lebar dan spasi horizontal yang paling konsisten."""
    if len(components) < 3:
        return components, 0.35 if components else 0.0
    widths = [item.width for item in components]
    common_width = median(widths)
    # Satu deret candle umumnya memiliki lebar body yang hampir sama.
    width_filtered = [item for item in components if abs(item.width - common_width) <= max(1, common_width * 0.35)]
    if len(width_filtered) >= max(3, len(components) * 0.35):
        components = width_filtered

    # Satu komponen per posisi x. Jika volume ikut terpilih, komponen yang lebih
    # tinggi dipertahankan; memotong area volume tetap sangat disarankan.
    components = sorted(components, key=lambda item: item.center_x)
    deduplicated: list[Component] = []
    tolerance = max(1.5, common_width * 0.45)
    for item in components:
        if deduplicated and abs(item.center_x - deduplicated[-1].center_x) <= tolerance:
            if item.height * item.area > deduplicated[-1].height * deduplicated[-1].area:
                deduplicated[-1] = item
        else:
            deduplicated.append(item)
    components = deduplicated
    if len(components) < 3:
        return components, 0.3

    differences = [b.center_x - a.center_x for a, b in zip(components, components[1:])]
    positive = [value for value in differences if value >= 2]
    if not positive:
        return components, 0.2
    spacing = median(positive)
    if spacing <= 0:
        return components, 0.2

    # Longest chain dengan langkah 1–4 kali spasi median. Langkah lebih dari
    # satu memungkinkan candle yang tidak terdeteksi tetap berada dalam deret.
    chains: list[list[int]] = [[index] for index in range(len(components))]
    errors: list[float] = [0.0 for _ in components]
    for index in range(len(components)):
        best_score = 1.0
        for previous in range(index):
            distance = components[index].center_x - components[previous].center_x
            steps = max(1, round(distance / spacing))
            if steps > 4:
                continue
            error = abs(distance - steps * spacing) / (steps * spacing)
            if error > 0.38:
                continue
            score = len(chains[previous]) + 1 - error * 0.25 - (steps - 1) * 0.04
            if score > best_score:
                best_score = score
                chains[index] = chains[previous] + [index]
                errors[index] = errors[previous] + error
    best = max(range(len(chains)), key=lambda index: (len(chains[index]), -errors[index]))
    selected = [components[index] for index in chains[best]]
    consistency = 1.0 / (1.0 + errors[best] / max(1, len(selected) - 1))
    coverage = len(selected) / len(components)
    confidence = max(0.0, min(1.0, consistency * (0.55 + 0.45 * coverage)))
    return selected, confidence


def detect_candles(image: "Image.Image") -> VisionResult:
    from PIL import ImageDraw

    # Batasi biaya connected-components pada tangkapan monitor besar.
    work = image.convert("RGB")
    if work.width > 1600 or work.height > 1600:
        ratio = min(1600 / work.width, 1600 / work.height)
        work = work.resize((max(1, round(work.width * ratio)), max(1, round(work.height * ratio))))
    if work.width < 20 or work.height < 20:
        empty = work.copy()
        return VisionResult([], [], empty, 0.0, 0, "Area terlalu kecil. Pilih area grafik yang lebih besar.")

    green_mask, red_mask = _masks(work)
    raw = _components(green_mask, work.width, work.height, GREEN)
    raw += _components(red_mask, work.width, work.height, RED)
    candidates: list[Component] = []
    for component in raw:
        if component.width < 2 or component.height < 4:
            continue
        if component.width > min(80, work.width * 0.16):
            continue
        if _body_box(component) is None:
            continue
        candidates.append(component)
    selected, confidence = _regular_series(candidates)
    selected.sort(key=lambda item: item.center_x)

    detections: list[DetectedCandle] = []
    for component in selected:
        body = _body_box(component)
        if body is None:
            continue
        x1, y1, x2, y2 = component.box
        _, body_top, _, body_bottom = body
        # Sumbu harga dibalik karena koordinat piksel bertambah ke bawah.
        high = float(work.height - y1)
        low = float(work.height - y2)
        if body_bottom - body_top <= 1:
            opening = closing = float(work.height - (body_top + body_bottom) / 2)
            direction = DOJI
        elif component.direction == GREEN:
            opening = float(work.height - body_bottom)
            closing = float(work.height - body_top)
            direction = GREEN
        else:
            opening = float(work.height - body_top)
            closing = float(work.height - body_bottom)
            direction = RED
        candle = Candle(opening, max(high, opening, closing), min(low, opening, closing), closing)
        detections.append(DetectedCandle(candle, direction, component.box, body))

    annotated = work.copy()
    draw = ImageDraw.Draw(annotated)
    for index, detection in enumerate(detections):
        color = "#20C997" if detection.direction == GREEN else "#FF6B6B" if detection.direction == RED else "#FFD166"
        x1, y1, x2, y2 = detection.box
        draw.rectangle((max(0, x1 - 2), max(0, y1 - 2), min(work.width - 1, x2 + 2), min(work.height - 1, y2 + 2)),
                       outline=color, width=2)
        if index == len(detections) - 1:
            draw.line((x2 + 4, 0, x2 + 4, work.height), fill="#FFD166", width=1)

    warning = ""
    if len(detections) < 20:
        warning = "Kurang dari 20 candle terdeteksi; model probabilitas mungkin belum tersedia."
    elif confidence < 0.65:
        warning = "Spasi/lebar deteksi kurang konsisten. Periksa preview dengan teliti."
    return VisionResult([item.candle for item in detections], detections, annotated,
                        confidence, len(candidates), warning)
