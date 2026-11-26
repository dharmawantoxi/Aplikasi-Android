"""Konfirmasi candle TF 1 menit menggunakan riwayat tertutup dan menit lokal."""


class MinuteGate:
    def __init__(self):
        self.base = None
        self.previous = None
        self.signal = -1

    def observe(self, captured, closed):
        minute, sec = divmod(captured, 60)
        key = (int(minute), closed)
        confirmed = key == self.previous
        self.previous = key
        if not confirmed or len(closed) < 5:
            return False
        if sec >= 45:
            self.base = key
            return False
        if sec > 10 or minute <= self.signal or not self.base:
            return False
        old_minute, old = self.base
        if old_minute != minute - 1:
            return False
        if closed[:-1] != old and not (closed != old and closed[:-1] == old[1:]):
            return False
        self.signal = int(minute)
        return True

    def can_enter(self, now, seconds, last_entry):
        minute, sec = divmod(now, 60)
        return self.signal == minute and minute > last_entry and sec <= seconds
