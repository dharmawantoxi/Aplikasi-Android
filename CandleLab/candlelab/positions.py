"""Jurnal prospektif posisi demo; harga berasal dari riwayat broker, bukan piksel."""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation


def price(value):
    try:
        result = Decimal(str(value).strip().replace(',', '.'))
    except InvalidOperation:
        raise ValueError('Harga harus berupa angka desimal.') from None
    if not result.is_finite() or result <= 0:
        raise ValueError('Harga harus positif dan terbatas.')
    return result


@dataclass
class Position:
    session: str
    stamp: str
    direction: int
    entry: str = ''
    expiry: str = ''

    def settle(self, entry, expiry):
        a, b = price(entry), price(expiry)
        self.entry, self.expiry = str(a), str(b)

    def score(self):
        if not self.entry or not self.expiry:
            return None
        a, b = price(self.entry), price(self.expiry)
        return self.direction * ((b > a) - (b < a))


def session_result(rows, session):
    scores = [r.score() for r in rows if r.session == session]
    wins, losses, ties = (scores.count(k) for k in (1, -1, 0))
    def pct(n):
        return f'{100*n/(wins+losses):.1f}%' if wins+losses else '—'
    strategy = 'Pantulan rentang 4' if session.endswith(' • Pantulan rentang 4') else 'Close vs rata-rata 3'
    return (f'{strategy}: {wins} benar, {losses} salah ({pct(wins)})  •  '
            f'Arah terbalik: {losses} benar, {wins} salah ({pct(losses)})\n'
            f'Seri {ties} (di luar akurasi)  •  Belum diisi {scores.count(None)}')
