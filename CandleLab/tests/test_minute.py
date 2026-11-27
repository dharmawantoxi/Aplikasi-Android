import unittest

from candlelab.minute import MinuteGate


class MinuteTests(unittest.TestCase):
    def test_one_signal_and_one_direction_click_per_minute(self):
        gate = MinuteGate()
        frames = [(55, 'HHMMH'), (57, 'HHMMH'), (61, 'HMMHM'), (63, 'HMMHM'),
                  (65, 'MMHMH'), (67, 'MMHMH'), (82, 'MMHMH')]
        self.assertEqual([gate.observe(t, h) for t, h in frames],
                         [False, False, True, False, False, False, False])
        self.assertTrue(gate.can_enter(64, 8, -1))
        for now, last in [(64, 1), (69, -1), (121, -1)]:
            self.assertFalse(gate.can_enter(now, 8, last))

    def test_skip_without_fresh_unambiguous_transition(self):
        for t, history in [(61, 'HHMMH'), (61, 'MMHHH'), (121, 'HHMMHM'), (72, 'HHMMHM')]:
            gate = MinuteGate()
            for sec, seq in [(55, 'HHMMH'), (57, 'HHMMH'), (t, history), (t + 2, history)]:
                self.assertFalse(gate.observe(sec, seq))

    def test_four_closed_plus_one_forming_is_enough(self):
        gate = MinuteGate()
        for t, seq in [(55, 'HHMM'), (57, 'HHMM')]:
            self.assertFalse(gate.observe(t, seq))
        self.assertTrue(gate.observe(61, 'HMMH'))
        self.assertTrue(gate.can_enter(64, 8, -1))

    def test_noisy_left_edge_does_not_block_confirmed_recent_shift(self):
        gate = MinuteGate()
        for t, seq in [(55, 'HHHMMHMHH'), (57, 'MHHMMHMHH')]:
            self.assertFalse(gate.observe(t, seq))
        self.assertTrue(gate.observe(61, 'HHMMHMHHM'))

    def test_unstable_final_reference_cannot_trigger_fast_click(self):
        gate = MinuteGate()
        for t, seq in [(55, 'HHMM'), (57, 'HHMM'), (59, 'HMMM'), (61, 'HMMH')]:
            self.assertFalse(gate.observe(t, seq))

    def test_badge_waits_after_missed_minute_then_accepts_fresh_signal(self):
        gate = MinuteGate()
        for t, history in [(55, 'HHMM'), (57, 'HHMM'), (61, 'HMMH')]:
            gate.observe(t, history)
        self.assertTrue(gate.has_current_signal(119))
        self.assertFalse(gate.has_current_signal(120))
        for t, history in [(175, 'HMMH'), (177, 'HMMH'), (181, 'MMHM')]:
            gate.observe(t, history)
        self.assertTrue(gate.has_current_signal(181))

    def test_geometry_on_repeated_colour(self):
        old = tuple(zip(range(0, 60, 10), [5, 12, 8, 16, 6, 1]))
        for new, expected in [
                (old, False),
                (old + ((60, 3),), True),
                (tuple(zip(range(0, 60, 10), [12, 8, 16, 6, 9, 2])), True),
                (tuple(zip(range(0, 60, 10), [9, -3, 1, -17, 8, 6])), False)]:
            gate = MinuteGate()
            for t in (55, 57):
                gate.observe(t, 'H'*8, old)
            self.assertEqual(gate.observe(61, 'H'*8, new), expected)
            self.assertFalse(gate.observe(63, 'H'*8, new))
