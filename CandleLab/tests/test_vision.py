import unittest

from PIL import Image, ImageDraw

from candlelab.model import GREEN, RED
from candlelab.vision import detect_candles


def chart(directions, hollow=False, noise=False):
    image = Image.new("RGB", (40 + len(directions) * 16, 300), "#101513")
    draw = ImageDraw.Draw(image)
    for index, direction in enumerate(directions):
        x = 20 + index * 16
        color = "#26A69A" if direction == GREEN else "#EF5350"
        top = 50 + (index % 5) * 8
        bottom = 190 - (index % 4) * 7
        draw.line((x + 4, top - 15, x + 4, bottom + 15), fill=color, width=1)
        if hollow:
            draw.rectangle((x, top, x + 8, bottom), outline=color, width=2)
        else:
            draw.rectangle((x, top, x + 8, bottom), fill=color)
    if noise:
        # Logo kecil dan garis sumbu tidak boleh menjadi deret utama.
        draw.rectangle((2, 2, 5, 6), fill="#26A69A")
        draw.line((0, 295, image.width, 295), fill="#EF5350", width=1)
    return image


class VisionTests(unittest.TestCase):
    def test_detects_complete_colour_sequence(self):
        expected = [RED if index % 3 == 0 else GREEN for index in range(40)]
        result = detect_candles(chart(expected, noise=True))
        self.assertEqual(len(result.candles), 40)
        self.assertEqual([item.direction for item in result.detections], expected)
        self.assertEqual(result.sequence, "".join("M" if item == RED else "H" for item in expected))
        self.assertGreaterEqual(result.confidence, 0.9)
        self.assertEqual(result.warning, "")

    def test_hollow_candles_are_supported(self):
        expected = [GREEN, RED] * 12
        result = detect_candles(chart(expected, hollow=True))
        self.assertEqual([item.direction for item in result.detections], expected)

    def test_relative_ohlc_has_correct_direction(self):
        result = detect_candles(chart([GREEN, RED] * 10))
        for detection in result.detections:
            candle = detection.candle
            self.assertEqual(candle.direction, detection.direction)
            self.assertGreaterEqual(candle.high, max(candle.open, candle.close))
            self.assertLessEqual(candle.low, min(candle.open, candle.close))

    def test_blank_and_low_saturation_images_return_warning(self):
        for colour in ("#101513", "#888888", "#3366CC"):
            with self.subTest(colour=colour):
                image = Image.new("RGB", (500, 250), colour)
                result = detect_candles(image)
                self.assertEqual(result.candles, [])
                self.assertIn("Kurang dari 20", result.warning)

    def test_tiny_capture_is_rejected_gracefully(self):
        result = detect_candles(Image.new("RGB", (10, 10), "#26A69A"))
        self.assertEqual(result.candles, [])
        self.assertIn("terlalu kecil", result.warning)

    def test_large_capture_is_downscaled_and_still_read(self):
        image = chart([GREEN, RED] * 15).resize((1200, 720))
        result = detect_candles(image)
        self.assertEqual(len(result.candles), 30)
        self.assertLessEqual(result.annotated_image.width, 1600)


if __name__ == "__main__":
    unittest.main()
