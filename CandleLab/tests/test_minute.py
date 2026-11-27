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
