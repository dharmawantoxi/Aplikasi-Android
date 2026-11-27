import unittest
from candlelab.positions import Position, session_result


class PositionTests(unittest.TestCase):
    def test_both_directions_and_ties(self):
        rows = [Position('a', str(i), d) for i, d in enumerate([1, -1, 1, 1])]
        for row, expiry in zip(rows, ['1,01', '1.01', '1.000']):
            row.settle('1', expiry)
        self.assertEqual([r.score() for r in rows], [1, -1, 0, None])
        self.assertIn('50.0%', session_result(rows, 'a'))
        self.assertNotIn('%', session_result(rows, 'b'))

    def test_bad_price_does_not_change_pair(self):
        row = Position('a', '12:00', -1)
        for value in ('NaN', 'Infinity', '-1', 'abc'):
            with self.assertRaises(ValueError):
                row.settle('1', value)
        self.assertIsNone(row.score())
