"""Impor/ekspor CSV OHLC tanpa dependensi pihak ketiga."""
from __future__ import annotations

import csv
from datetime import datetime, timezone
import io
import math
from pathlib import Path
import re
from typing import Iterable, Optional

from .model import Candle, DOJI, GREEN, MAX_CANDLES

_HEADERS = {
    "open": {"open", "o", "pembukaan", "harga_open"},
    "high": {"high", "h", "tertinggi", "harga_high"},
    "low": {"low", "l", "terendah", "harga_low"},
    "close": {"close", "c", "penutupan", "harga_close"},
    "timestamp": {"time", "timestamp", "date", "datetime", "tanggal", "waktu"},
}
MAX_CSV_BYTES = 4 * 1024 * 1024


def _normalise(value: str) -> str:
    return re.sub(r"[\s\-]+", "_", value.strip().lower().lstrip("\ufeff"))


def _number(value: str, delimiter: str) -> float:
    text = value.strip().replace(" ", "")
    # CSV bertitik-koma sering memakai koma sebagai desimal.
    if delimiter == ";" and "," in text and "." not in text:
        text = text.replace(",", ".")
    try:
        number = float(text)
    except ValueError as exc:
        raise ValueError(f"'{value.strip()}' bukan angka yang valid.") from exc
    if not math.isfinite(number):
        raise ValueError("Angka OHLC harus berupa nilai yang terbatas.")
    return number


def _timestamp(value: str) -> float:
    text = value.strip()
    if not text:
        return 0.0
    try:
        return float(text)
    except ValueError:
        pass
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.timestamp()
    except ValueError:
        # Waktu tidak wajib; urutan baris tetap dipertahankan.
        return 0.0


def parse_csv(text: str) -> list[Candle]:
    text = text.lstrip("\ufeff")
    if not text.strip():
        raise ValueError("File CSV kosong.")
    sample = text[:8192]
    try:
        delimiter = csv.Sniffer().sniff(sample, delimiters=",;\t").delimiter
    except csv.Error:
        first = next((line for line in text.splitlines() if line.strip()), "")
        delimiter = max((",", ";", "\t"), key=first.count)
        if not first.count(delimiter):
            delimiter = ","

    rows = [row for row in csv.reader(io.StringIO(text), delimiter=delimiter)
            if any(cell.strip() for cell in row)]
    if not rows:
        raise ValueError("File CSV tidak memiliki baris data.")
    header_map = {_normalise(cell): index for index, cell in enumerate(rows[0])}
    positions: dict[str, Optional[int]] = {}
    has_header = False
    for field, aliases in _HEADERS.items():
        positions[field] = next((header_map[name] for name in aliases if name in header_map), None)
        if positions[field] is not None and field != "timestamp":
            has_header = True

    start_row = 0
    if has_header:
        missing = [field for field in ("open", "high", "low", "close") if positions[field] is None]
        if missing:
            raise ValueError("Kolom wajib tidak lengkap: " + ", ".join(missing) + ".")
        start_row = 1
    else:
        if len(rows[0]) < 4:
            raise ValueError("Gunakan empat kolom open,high,low,close atau sertakan header.")
        # Tanpa header, lima kolom atau lebih dianggap diawali waktu.
        offset = 1 if len(rows[0]) >= 5 else 0
        for offset_index, field in enumerate(("open", "high", "low", "close")):
            positions[field] = offset + offset_index
        positions["timestamp"] = 0 if offset else None

    data_rows = rows[start_row:]
    if not data_rows:
        raise ValueError("File CSV hanya berisi header.")
    if len(data_rows) > MAX_CANDLES:
        raise ValueError(f"Maksimal {MAX_CANDLES:,} candle per data set.".replace(",", "."))

    candles: list[Candle] = []
    for relative_index, row in enumerate(data_rows):
        line_number = relative_index + start_row + 1
        try:
            values = {
                field: _number(row[int(positions[field])], delimiter)
                for field in ("open", "high", "low", "close")
            }
            time_index = positions.get("timestamp")
            stamp = _timestamp(row[time_index]) if time_index is not None and time_index < len(row) else 0.0
            candles.append(Candle(**values, timestamp=stamp))
        except (ValueError, IndexError) as exc:
            raise ValueError(f"Kesalahan pada baris {line_number}: {exc}") from exc
    return candles


def load_csv(path: str | Path) -> list[Candle]:
    file_path = Path(path)
    if file_path.stat().st_size > MAX_CSV_BYTES:
        raise ValueError("Ukuran CSV maksimal 4 MiB.")
    try:
        text = file_path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValueError("CSV harus memakai encoding UTF-8.") from exc
    return parse_csv(text)


def export_csv(candles: Iterable[Candle], path: str | Path) -> None:
    with Path(path).open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("timestamp", "open", "high", "low", "close", "direction"))
        for candle in candles:
            direction = {GREEN: "green", DOJI: "doji"}.get(candle.direction, "red")
            writer.writerow((
                f"{candle.timestamp:.3f}", f"{candle.open:.10g}", f"{candle.high:.10g}",
                f"{candle.low:.10g}", f"{candle.close:.10g}", direction,
            ))
