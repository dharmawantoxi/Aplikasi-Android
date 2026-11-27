"""Konfirmasi candle TF 1 menit menggunakan riwayat tertutup dan menit lokal."""


from statistics import median


def bar_trace(detections):
    """Pusat X dan tinggi body bertanda; bar terakhir masih berjalan."""
    return tuple(((d.body_box[0] + d.body_box[2]) / 2,
                  (d.body_box[3] - d.body_box[1] + 1) * d.direction)
                 for d in detections[-8:])


def geometry_shift(old, new):
    n = min(len(old) - 1, len(new) - 2, 5)
    if n < 3:
        return False
    before, after = old[-n-1:-1], new[-n-2:-2]
    gaps = [b[0]-a[0] for trace in (old[-n-1:], new[-n-2:])
            for a, b in zip(trace, trace[1:])]
    pitch = median(gaps)
    tol = max(1, pitch * 0.12)
    if pitch < 5 or any(abs(g-pitch) > tol for g in gaps):
        return False
    # Bar lama tetap di X yang sama (append), atau bergeser satu slot ke kiri.
    dx = after[-1][0] - before[-1][0]
    if min(abs(dx), abs(dx + pitch)) > tol:
        return False
    if any(abs(b[0]-a[0]-dx) > tol or abs(b[1]-a[1]) > 2
           for a, b in zip(before, after)):
        return False
    # Saat chart menggulung, grid X saja identik dengan chart diam.
    # Harus ada ciri body yang membedakan kedua penjajaran tersebut.
    grew = abs(dx) <= tol and len(new) > len(old)
    return grew or any(abs(a[1] - b[1]) > 4
                       for a, b in zip(before, new[-n-1:-1]))


class MinuteGate:
    def __init__(self):
        self.base = None
        self.base_trace = ()
        self.prev_trace = ()
        self.used_geometry = False
        self.previous = None
        self.signal = -1
        self.reason = "Menunggu pembacaan chart."

    def observe(self, captured, closed, trace=()):
        minute, sec = divmod(captured, 60)
        # Gangguan deteksi di tepi kiri tidak mengubah empat close strategi.
        closed = closed[-8:]
        key = (int(minute), closed)
        confirmed = key == self.previous
        prev_trace, self.prev_trace = self.prev_trace, trace
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
            stable = len(trace) == len(prev_trace) and all(
                abs(a[0]-b[0]) <= 2 and abs(a[1]-b[1]) <= 2
                for a, b in zip(trace[:-1], prev_trace[:-1]))
            self.base_trace = trace if stable else ()
            self.base = key
            return False
        self.reason = "Menunggu acuan akhir menit dan awal menit baru."
        if sec > 10 or minute <= self.signal or not self.base:
            return False
        old_minute, old = self.base
        if old_minute != minute - 1:
            return False
        self.reason = f"Pergeseran belum terkonfirmasi • acuan {old}, terbaca {closed}."
        self.used_geometry = geometry_shift(self.base_trace, trace)
        if not self.used_geometry and closed[:-1] != old and not (closed != old and closed[:-1] == old[1:]):
            return False
        self.signal = int(minute)
        return True

    def has_current_signal(self, now):
        return self.signal >= 0 and self.signal == int(now // 60)

    def can_enter(self, now, seconds, last_entry):
        minute, sec = divmod(now, 60)
        return self.signal == minute and minute > last_entry and sec <= seconds
