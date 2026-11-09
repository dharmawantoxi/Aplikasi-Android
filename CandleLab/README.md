# CandleLab — Desktop Probability Lab

**CandleLab** adalah aplikasi desktop offline untuk bereksperimen dengan probabilitas warna candlestick berikutnya: **hijau** (`close > open`) atau **merah** (`close < open`). Aplikasi ini bukan mesin jawaban pasti dan bukan penyedia sinyal trading. Model, data historis, dan hasil prediksi dapat diperiksa langsung oleh pengguna.

Dibangun dengan **Python dan Tkinter**, tanpa library runtime pihak ketiga. Grafik digambar dengan Canvas, data disimpan lokal, dan aplikasi tidak meminta akun atau koneksi internet.

## Fitur

- Grafik hingga 64 candle terakhir dengan tooltip OHLC saat kursor diarahkan.
- Estimasi `P(Hijau)` dan `P(Merah)` yang selalu berjumlah 100%.
- Pola 1, 2, atau 3 arah candle; jendela 30, 60, 100, atau semua data.
- Model n-gram sederhana dengan penghalusan Bayesian menuju frekuensi dasar.
- Doji tampil pada grafik/tabel, tetapi diabaikan dalam pola dan tidak dinilai.
- Backtest **walk-forward**: setiap target hanya diprediksi dari candle sebelum target.
- Metrik jumlah sampel, akurasi model, akurasi baseline, dan **Brier score**.
- Kunci prediksi sebelum hasil diketahui. Saat OHLC aktual berikutnya ditambahkan, aplikasi menilai prediksi secara otomatis.
- Riwayat eksperimen selesai, tertunda, doji, dan dibatalkan. Mengganti/mengurangi data membatalkan prediksi tertunda agar hasil tidak dinilai pada data yang salah.
- Impor/ekspor CSV dan input OHLC manual dengan validasi.
- 96 candle simulasi saat pertama dibuka; selalu diberi label **DATA SIMULASI**, bukan harga pasar.
- Penyimpanan JSON atomik di folder data pengguna; tidak ada sinkronisasi atau pengiriman data.

## Menjalankan aplikasi

### Windows — dari source

1. Pasang **Python 3.10 atau lebih baru** dari python.org. Installer Python Windows umumnya sudah menyertakan Tkinter.
2. Buka folder `CandleLab`.
3. Klik dua kali **`run_candlelab.bat`**, atau jalankan:

```bat
py -3 candlelab.py
```

Tidak perlu menjalankan `pip install` untuk memakai aplikasi dari source.

### Windows — file `.exe`

Workflow **CandleLab Desktop CI** menjalankan pengujian lalu membuat `CandleLab.exe` portable satu file. Unduh artifact **`CandleLab-Windows-x64`** dari run workflow yang berhasil. File tidak ditandatangani dengan sertifikat kode, sehingga Windows SmartScreen dapat menampilkan peringatan; periksa sumber dan hash artifact sebelum menjalankan.

Untuk membuat sendiri di Windows:

```bat
tools\build_windows.bat
```

Hasilnya berada di `dist\CandleLab.exe`. Build memerlukan koneksi internet hanya untuk memasang PyInstaller; aplikasi yang dihasilkan tetap bekerja offline.

### Linux

Pasang Python 3 dan Tkinter melalui package manager, lalu jalankan launcher:

```bash
# Debian/Ubuntu, jika Tkinter belum tersedia:
sudo apt install python3-tk

cd CandleLab
./run_candlelab.sh
```

### macOS

Gunakan Python yang menyertakan Tkinter, lalu jalankan:

```bash
cd CandleLab
python3 candlelab.py
```

## Alur eksperimen yang disarankan

1. Coba dahulu dengan data simulasi atau impor CSV pada **Data candle**.
2. Pilih pengaturan model pada **Prediksi**. Periksa jumlah pola yang cocok dan backtest, bukan hanya warna dominan.
3. Klik **Kunci prediksi untuk eksperimen**. Hanya satu prediksi tertunda yang diperbolehkan.
4. Setelah candle berikutnya benar-benar selesai, klik **Tambah candle aktual** dan masukkan OHLC.
5. Lihat skor pada **Eksperimen**. Ulangi hingga jumlah sampel memadai; beberapa tebakan benar tidak membuktikan model memiliki keunggulan.
6. Gunakan **Ekspor CSV** untuk membuat salinan data candle. Ekspor tidak mencakup riwayat eksperimen.

Pintasan: `Ctrl+N` tambah candle, `Ctrl+I` impor CSV, `Ctrl+S` ekspor CSV, dan `F5` muat ulang tampilan.

## Format CSV

Header yang disarankan:

```csv
timestamp,open,high,low,close
1767225600,100,102.5,99.5,101.25
1767229200,101.25,103,100.5,102
```

- Urutan baris harus dari candle **paling lama ke terbaru**.
- `open`, `high`, `low`, dan `close` wajib. `timestamp` opsional dan dapat berupa Unix timestamp atau ISO 8601.
- Alias umum seperti `time`, `date`, `pembukaan`, `tertinggi`, `terendah`, dan `penutupan` dikenali.
- Pemisah koma, titik-koma, atau tab didukung. CSV titik-koma dapat memakai koma desimal.
- Tanpa header, gunakan `open,high,low,close`. Jika terdapat lima kolom atau lebih, kolom pertama dianggap waktu.
- Encoding UTF-8/UTF-8 BOM, ukuran maksimal 4 MiB, maksimal 2.000 candle.
- High harus menjadi nilai tertinggi dan low nilai terendah dari OHLC. Semua baris divalidasi sebelum data set diganti.

File [`contoh_data.csv`](contoh_data.csv) berisi 30 candle sintetis yang dapat digunakan untuk mencoba impor.

## Metode dan cara membaca hasil

Untuk pola arah terakhir, model menghitung berapa kali pola yang sama muncul dan warna candle setelahnya. Probabilitas akhirnya:

```text
P(Hijau) = (hijau setelah pola + 6 × frekuensi dasar hijau)
           ─────────────────────────────────────────────────
                         jumlah pola + 6
```

Angka `6` adalah kekuatan prior. Tujuannya menghindari 0% atau 100% hanya karena pola jarang muncul. Karena itu, probabilitas akhir dapat berbeda dari persentase mentah pola.

**Akurasi** mengukur apakah sisi probabilitas di atas 50% sesuai arah aktual. Prediksi tepat 50%–50% diberi skor 0,5. **Baseline** memakai frekuensi dasar pada data pelatihan yang sama. **Brier score** mengukur galat probabilitas; lebih rendah lebih baik, dengan rentang 0 sampai 1.

Backtest dan eksperimen terkunci berbeda: backtest menilai ulang data historis, sedangkan eksperimen terkunci menyimpan prediksi sebelum candle aktual dimasukkan. Memilih pengaturan berulang kali pada data historis yang sama tetap dapat menyebabkan **overfitting**. Gunakan data baru untuk pengujian yang lebih jujur.

## Lokasi dan privasi data

Data tersimpan tanpa enkripsi di:

- Windows: `%APPDATA%\CandleLab\data.json`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/candlelab/data.json`
- macOS: `~/Library/Application Support/CandleLab/data.json`

Penulisan memakai file sementara lalu `os.replace` agar pembaruan atomik. Jika file rusak, aplikasi mencoba menyimpannya sebagai `*.corrupt-<waktu>.json`, lalu memuat simulasi dan menampilkan peringatan. Membersihkan folder data menghapus candle serta eksperimen lokal.

Aplikasi tidak mengambil harga live, tidak mengenali berita/volume/spread/likuiditas, dan tidak terhubung ke broker. **Probabilitas tinggi tetap bisa salah. Hasil historis bukan jaminan hasil berikutnya. Jangan jadikan aplikasi ini satu-satunya dasar keputusan finansial.**

## Pengujian

Pengujian tidak membuka GUI dan tidak memerlukan Tkinter:

```bash
cd CandleLab
python -m unittest discover -s tests -v
```

Cakupan meliputi validasi OHLC, arah/doji, rumus Bayesian, pola dan jendela, rentang metrik, pencegahan kebocoran data backtest, format CSV, round-trip ekspor, eksperimen benar/salah/doji, pembatalan, serta pemulihan penyimpanan.

Pada mesin desktop yang memiliki display, smoke test berikut membuka seluruh halaman dan menguji penguncian prediksi dengan folder data sementara:

```bash
python tools/smoke_ui.py
```

Workflow Windows menjalankan smoke test ini sebelum membuat `.exe`; test tidak membaca atau mengubah data pengguna.
