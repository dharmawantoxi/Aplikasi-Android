# Bagaimana CandleLab Bekerja

Dokumen ini menjelaskan cara kerja aplikasi dari dalam: dari piksel layar sampai
angka probabilitas di badge. Ditulis agar Anda bisa menilai sendiri di mana
aplikasi ini kuat dan di mana ia lemah.

---

## 1. Gambaran besar

CandleLab tidak terhubung ke IQ Option sama sekali. Tidak ada API, tidak ada
login, tidak ada pembacaan data broker. Yang dilakukannya hanya satu: **memotret
sebagian layar Anda, mencari bentuk candle di dalamnya, lalu menghitung
probabilitas warna candle berikutnya dari urutan warna yang ditemukan.**

```
Layar (chart IQ Option)
        │  potret area tertentu
        ▼
   vision.py          cari bentuk candle → urutan warna  H H M H M …
        │
        ▼
   model.py           hitung P(Hijau) dari pola urutan itu
        │
        ▼
   overlay.py         tampilkan badge kecil di atas chart
```

Semua berjalan lokal di komputer Anda. Tidak ada data yang dikirim ke internet,
tidak ada gambar yang disimpan ke disk.

---

## 2. Membaca layar (vision.py)

Ini bagian yang paling banyak bekerja, dan paling sering menjadi sumber kesalahan.

### Langkah 1 — potret

`screen_capture.py` memakai `PIL.ImageGrab` untuk menyalin area layar yang Anda
pilih menjadi gambar. Ini hanya membaca piksel; tidak menyentuh aplikasi lain.

### Langkah 2 — pisahkan warna

Setiap piksel diklasifikasikan menjadi **hijau**, **merah**, atau **diabaikan**,
dengan aturan warna (bukan sekadar "lebih banyak hijau dari merah"):

- Hijau: `g - r ≥ 28`, `g - b ≥ 4`, `g ≥ r × 1.16`
- Merah: `r - g ≥ 32`, `r - b ≥ 27`, `r ≥ g × 1.20`

Ada dua penyaring penting di depan:

- **Kecerahan minimum 65** — supaya latar gelap tidak ikut terdeteksi.
- **Saturasi minimum 0.27** — supaya abu-abu, putih, dan bayangan tidak dianggap
  candle. Ini yang mencegah garis grid halus dan teks abu-abu masuk hitungan.

Aturan ini sengaja mencakup **teal** juga, karena banyak platform trading memakai
hijau kebiruan, bukan hijau murni.

### Langkah 3 — kelompokkan piksel jadi objek

Piksel hijau yang saling menempel dikelompokkan menjadi satu *komponen*
(algoritma flood-fill / connected components). Hasilnya berupa daftar bentuk
beserta kotak pembatasnya.

### Langkah 4 — buang yang bukan candle

Ini krusial, karena layar penuh teks, tombol, logo, dan ikon berwarna. Setiap
komponen diuji:

| Uji | Tujuan menolak |
|---|---|
| Lebar ≥ 2 px, tinggi ≥ 4 px | noise satu piksel |
| Baris terisi ≥ 18% tinggi | garis tipis, teks |
| Body ≥ 20% tinggi total | titik kecil, ikon |
| Lebar ≤ 16% lebar gambar | blok warna besar, banner |
| Body harus mendominasi tinggi (wick relatif tipis) | garis tren, panah |

### Langkah 5 — cari "deret" yang teratur

Candle selalu berjajar rapi dengan jarak seragam. Teks dan ikon tidak. Jadi:

1. Ambil **lebar body yang paling sering muncul** (modus) — candle berbagi lebar.
   Ini trik penting: kalau memakai rata-rata, banyaknya teks bisa menggeser hasil.
2. Buang komponen yang lebarnya menyimpang >30% dari lebar khas itu.
3. Kalau ada dua objek di posisi x yang sama (misalnya candle dan bar volume di
   bawahnya), simpan yang lebih besar.
4. Hitung jarak antar-pusat, ambil **median**-nya sebagai jarak acuan.
5. Cari rantai objek terpanjang yang jaraknya konsisten dengan kelipatan jarak
   acuan (toleransi galat 38%). Kelipatan >1 memungkinkan candle yang terlewat
   tetap dihitung dalam deret.
6. Buang objek yang tinggi badannya < 35% median — biasanya ikon kecil yang
   kebetulan sejajar.

### Langkah 6 — hitung keyakinan (confidence)

```
konsistensi = 1 / (1 + rata-rata galat jarak)
cakupan     = jumlah terpilih / jumlah kandidat
confidence  = konsistensi × (0.55 + 0.45 × cakupan)
```

Angka ini muncul di status sebagai "konsistensi 87%". Artinya: seberapa rapi
deret yang ditemukan, bukan seberapa benar prediksinya.

### Langkah 7 — tentukan arah

Sumbu Y piksel bertambah ke bawah, sedangkan harga bertambah ke atas. Jadi
dibalik: `harga = tinggi_gambar - y`.

- Badan hijau → buka di bawah, tutup di atas (naik).
- Badan merah → buka di atas, tutup di bawah (turun).
- Body ≤ 1 px → **doji** (tidak dinilai).

### Peringatan yang mungkin muncul

| Peringatan | Arti sebenarnya |
|---|---|
| "Objek terdeteksi terlalu kecil…" | Yang terbaca ikon/teks, bukan candle. Area salah. |
| "Kurang dari 5 candle…" | Area terlalu sempit atau zoom terlalu jauh. |
| "Candle tampak saling menempel…" | Candle terlalu rapat, batas body sulit dipisah. Perbesar zoom. |
| "Spasi/lebar kurang konsisten…" | Deret tidak rapi; periksa preview dengan teliti. |

---

## 3. Menghitung probabilitas (model.py)

### Idenya

Model ini disebut **n-gram dengan prior Bayesian**. Sesederhana: "kapan pun tiga
candle terakhir berwarna begini, apa yang terjadi sesudahnya?"

### Rumus

```
P(Hijau) = (hijau_setelah_pola + 6 × frekuensi_dasar) / (jumlah_pola + 6)
```

- `jumlah_pola` = berapa kali pola itu muncul sebelumnya
- `hijau_setelah_pola` = berapa kali diikuti candle hijau
- `frekuensi_dasar` = proporsi hijau di seluruh data (base rate)
- `6` = kekuatan prior

### Kenapa ada prior?

Tanpa prior, pola yang muncul 1 kali dan diikuti hijau akan menghasilkan
**P = 100%**. Itu omong kosong. Prior menarik hasil kembali ke base rate sesuai
sedikitnya bukti:

| Kemunculan pola | Pengaruh data | Pengaruh prior |
|---|---|---|
| 0 | 0% | 100% → hasil = base rate |
| 2 | 25% | 75% |
| 6 | 50% | 50% |
| 20 | 77% | 23% |

Inilah sebabnya dengan 8 candle Anda mendapat **51–56%**, bukan angka dramatis.
Model sedang berkata jujur: "saya belum tahu apa-apa."

### Doji

Doji dibuang saat membentuk pola **dan** saat dinilai. Alasannya: doji adalah
candle tanpa arah, dan memasukkannya membuat pola jadi ambigu.

### Jendela

Anda bisa membatasi data yang dipakai ke 30, 60, 100 candle terakhir, atau semua.
Jendela pendek = lebih relevan dengan kondisi sekarang, tapi sampel lebih sedikit.
Ini trade-off yang tidak bisa dihindari.

### Batas minimum

- `MIN_CANDLES = 5` — di bawah ini model menolak menebak.
- `WEAK_EVIDENCE_CANDLES = 20` — di bawah ini hasil ditandai **DATA SEDIKIT**.

---

## 4. Backtest — apakah model lebih baik dari tebakan buta?

Halaman **Eksperimen** menghitung ini dengan *walk-forward*: untuk setiap candle,
model hanya boleh melihat candle **sebelumnya**, lalu ditebak, baru dicocokkan
dengan kenyataan. Tidak ada kebocoran masa depan.

Tiga angka yang dilaporkan:

- **Akurasi model** — persentase tebakan benar.
- **Akurasi baseline** — akurasi kalau selalu menebak warna mayoritas.
- **Brier score** — rata-rata `(probabilitas - kenyataan)²`. Semakin kecil semakin
  baik. Ini mengukur *kalibrasi*: apakah "67%" benar-benar terjadi 67% dari waktu.

**Yang harus Anda cari:** apakah akurasi model **konsisten di atas** baseline.
Kalau hanya 50–52%, itu artinya model tidak menemukan pola apa pun. Itu hasil yang
sah, dan justru informasi paling berharga yang bisa diberikan aplikasi ini.

---

## 5. Mode live — kenapa rumit?

Masalahnya sederhana tapi fatal: **kalau CandleLab memotret layar sementara
badge-nya sendiri menempel di atas chart, ia akan membaca badge-nya sendiri
sebagai candle.** Ini dulu benar-benar terjadi — menghasilkan 17 "candle" palsu
berukuran 13×9 px.

Solusinya adalah protokol empat langkah yang dijaga `live_monitor.py`:

```
1. Worker minta izin     →  MonitorEvent(ready=True)
2. UI sembunyikan semua  →  badge & panel jadi transparan (alpha 0)
3. Worker tunggu 0.12 s  →  beri window manager waktu menyelesaikan gambar
4. Worker potret & hitung → hasil dikirim, UI tampilkan lagi
```

Setiap 2 detik. Karena itu badge berkedip sangat singkat — itu memang disengaja.

### Kenapa transparansi, bukan sembunyikan jendela?

`withdraw()` menghilangkan jendela dari taskbar dan bisa mengubah fokus. Dengan
`-alpha 0`, jendela tetap ada tapi tak terlihat. Lebih halus dan tidak mengganggu
jendela IQ Option.

### Konfirmasi dua kali

Urutan candle yang berubah tidak langsung dipercaya. Ia harus muncul **dua
pembacaan berturut-turut** sebelum dipakai. Ini meredam kedipan deteksi, dengan
harga: reaksi tertunda sekitar satu interval (2 detik).

---

## 6. Eksperimen terkunci — mengapa perlu?

Karena memori manusia selektif. Mudah sekali merasa model "sering benar" kalau
tebakan yang salah dilupakan.

Alur kerjanya:

1. Anda tekan **Kunci prediksi** saat candle terakhir masih terbentuk.
2. Probabilitas, pola, dan jumlah candle dicatat ke `data.json`.
3. Setelah candle selesai, Anda menandai hasil aktualnya (Hijau/Merah/Doji).
4. Aplikasi menghitung **skor** (0 atau 1) dan **Brier score**.

Doji tidak dinilai, tapi tetap dicatat. Prediksi 50% tepat diberi skor 0.5 —
bukan dianggap benar.

Ini satu-satunya cara jujur mengukur apakah aplikasi ini berguna bagi Anda.

---

## 7. Penyimpanan (storage.py)

Satu file JSON lokal:

- **Windows:** `%APPDATA%\CandleLab\data.json`
- **Linux:** `~/.local/share/candlelab/data.json`
- **macOS:** `~/Library/Application Support/CandleLab/data.json`

Isinya: daftar candle, riwayat eksperimen, dan **koordinat** area chart terakhir.
Yang terakhir itu hanya empat angka (mis. `100, 250, 1430, 800`) supaya `F7`
berikutnya tidak perlu memilih area lagi.

**Tidak ada screenshot yang disimpan.** Tidak ada isi layar, tidak ada harga
pasar. Penulisan bersifat atomik (tulis ke file sementara lalu ganti), jadi
matinya listrik di tengah penyimpanan tidak merusak data.

---

## 8. Bagian yang sengaja dibuat "bodoh"

Beberapa keputusan mungkin tampak seperti kekurangan, tapi disengaja:

| Keputusan | Alasan |
|---|---|
| Tidak memakai ML/neural network | Data 8 candle tidak cukup untuk melatih apa pun. Jaringan saraf akan menghafal noise dan memberi angka percaya diri yang salah. |
| Prior menarik hasil ke 50% | Supaya Anda tidak dibohongi oleh 3 sampel. |
| Persen disembunyikan saat data tipis | Angka "56%" dari 3 kemunculan dibaca otak sebagai "peluang menang 56%". Itu menyesatkan. |
| Doji diabaikan | Candle tanpa arah membuat pola ambigu. |
| Konfirmasi 2 pembacaan | Lebih baik terlambat 2 detik daripada salah baca. |
| Preview sebelum impor | Deteksi warna bisa keliru; mata Anda lebih andal. |

---

## 9. Batasan yang harus Anda sadari

1. **Warna candle tidak menyimpan memori panjang.** Ini asumsi dasar yang bisa
   saja salah. Kalau harga bergerak acak (random walk), akurasi akan mentok di
   ~50% dan tidak ada model pola warna yang bisa mengubahnya.

2. **Deteksi bergantung pada tampilan.** Ganti tema IQ Option, ubah skala
   Windows, atau zoom chart, dan hasilnya bisa berubah. Selalu periksa preview
   setelah mengubah tampilan.

3. **Screenshot bukan data pasar.** Skala harganya relatif (piksel), bukan harga
   sebenarnya. Untuk analisis yang serius, impor CSV OHLC.

4. **2 detik terlalu lambat untuk entry presisi.** Interval ini dipilih agar
   ringan, bukan agar cepat.

5. **Tidak ada jaminan apa pun.** Ini alat eksperimen. Angka 60% bukan berarti
   Anda akan menang 6 dari 10 kali.

---

## 10. Cara memakai dengan jujur

Kalau tujuan Anda benar-benar mengukur apakah ini berguna:

1. **Kumpulkan 50–100 eksperimen terkunci** sebelum menyimpulkan apa pun.
   Dengan 10 percobaan, hasilnya hanya keberuntungan.
2. **Lihat Brier score, bukan cuma akurasi.** Brier score menangkap apakah
   angka probabilitasnya masuk akal.
3. **Bandingkan dengan baseline.** Kalau baseline 52% dan model 53%, tidak ada
   yang istimewa.
4. **Jangan menaikkan nominal** berdasarkan badge. Kalau modelnya belum terbukti,
   uang Anda lebih berharga daripada eksperimennya.

Ringkasnya: CandleLab dibangun untuk menjawab satu pertanyaan — *apakah urutan
warna candle punya pola yang bisa dipakai?* — dan dibangun sedemikian rupa agar
jawaban "tidak" bisa muncul dengan jelas, bukan tenggelam dalam angka yang terlihat
meyakinkan.
