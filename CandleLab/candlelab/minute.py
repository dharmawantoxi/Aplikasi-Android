"""Konfirmasi candle TF 1 menit menggunakan riwayat tertutup dan menit lokal."""


class MinuteGate:
    def __init__(self):
        self.base = None
        self.previous = None
        self.signal = -1
        self.reason = "Menunggu pembacaan chart."

    def observe(self, captured, closed):
        minute, sec = divmod(captured, 60)
        # Gangguan deteksi di tepi kiri tidak mengubah empat close strategi.
        closed = closed[-8:]
        key = (int(minute), closed)
        confirmed = key == self.previous
        self.previous = key
        if len(closed) < 4:
            self.reason = f"Terbaca {len(closed)}/4 candle tertutup; perlu 5 candle terlihat."
            return False
        if sec >= 45:
            if not confirmed:
                self.base = None
                self.reason = "Menunggu bacaan kedua untuk acuan akhir menit."
                return False
            self.reason = "Acuan siap; tunggu awal menit baru."
            self.base = key
            return False
        self.reason = "Menunggu acuan akhir menit dan awal menit baru."
        if sec > 10 or minute <= self.signal or not self.base:
            return False
        old_minute, old = self.base
        if old_minute != minute - 1:
            return False
        self.reason = "Pergeseran satu candle belum terkonfirmasi."
        if closed[:-1] != old and not (closed != old and closed[:-1] == old[1:]):
            return False
        self.signal = int(minute)
        return True

    def can_enter(self, now, seconds, last_entry):
        minute, sec = divmod(now, 60)
        return self.signal == minute and minute > last_entry and sec <= seconds
