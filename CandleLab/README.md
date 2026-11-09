# CandleLab — Desktop Probability Lab

**CandleLab** adalah aplikasi desktop offline untuk bereksperimen dengan probabilitas warna candlestick berikutnya: **hijau** (`close > open`) atau **merah** (`close < open`). Aplikasi ini bukan mesin jawaban pasti dan bukan penyedia sinyal trading. Model, data historis, dan hasil prediksi dapat diperiksa langsung oleh pengguna.

Dibangun dengan **Python dan Tkinter**. Grafik digambar dengan Canvas, sedangkan **Pillow** dipakai untuk menangkap dan membaca piksel layar. Data disimpan lokal dan aplikasi tidak meminta akun atau koneksi internet.

## Fitur

- **Mode live tanpa screenshot berulang**: pilih area sekali dengan `F9`, lalu grafik dan probabilitas diperbarui otomatis setiap 2 detik.
- Perubahan urutan candle dikonfirmasi dalam dua pembacaan konsisten; candle paling kanan yang masih terbentuk dapat diabaikan dari model.
- **Baca langsung dari layar desktop**: pilih area chart dengan drag, deteksi candle hijau/teal dan merah, lalu verifikasi kotak deteksi sebelum mengimpor.
- Tangkap ulang area yang sama dengan `Ctrl+F8`; area dan screenshot hanya berada di memori sesi berjalan.
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
2. Buka folder `CandleLab` dan pasang dependensi pembacaan layar:

```bat
py -3 -m pip install -r requirements.txt
```

3. Klik dua kali **`run_candlelab.bat`**, atau jalankan:

```bat
py -3 candlelab.py
```

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
python3 -m pip install -r requirements.txt
./run_candlelab.sh
```

### macOS

Gunakan Python yang menyertakan Tkinter, lalu jalankan:

```bash
cd CandleLab
python3 -m pip install -r requirements.txt
python3 candlelab.py
```

## Alur eksperimen yang disarankan

1. Coba dahulu dengan data simulasi atau impor CSV pada **Data candle**.
2. Pilih pengaturan model pada **Prediksi**. Periksa jumlah pola yang cocok dan backtest, bukan hanya warna dominan.
3. Klik **Kunci prediksi untuk eksperimen**. Hanya satu prediksi tertunda yang diperbolehkan.
4. Setelah candle berikutnya benar-benar selesai, klik **Tambah candle aktual** dan masukkan OHLC.
5. Lihat skor pada **Eksperimen**. Ulangi hingga jumlah sampel memadai; beberapa tebakan benar tidak membuktikan model memiliki keunggulan.
6. Gunakan **Ekspor CSV** untuk membuat salinan data candle. Ekspor tidak mencakup riwayat eksperimen.

Pintasan: `Ctrl+N` tambah candle, `Ctrl+I` impor CSV, `Ctrl+S` ekspor CSV, `F5` muat ulang tampilan, **`F8` baca area layar baru**, **`Ctrl+F8` tangkap ulang area**, **`F9` mulai/stop live**, dan **`Ctrl+F9` pilih area live baru**.

## Mode live — prediksi terus diperbarui

1. Buka chart dan atur zoom/warna agar candle terlihat jelas.
2. Tekan **`F9`** atau klik **Mulai live**. Jika belum ada area tersimpan, drag area price chart sekali.
3. Kembalikan fokus ke CandleLab tanpa menutupi area chart. Aplikasi membaca area yang sama setiap **2 detik** dan memperbarui grafik serta probabilitas secara otomatis.
4. **Ctrl+F9** memilih area baru; **F9** atau **Stop live** menghentikan monitor.
5. Opsi **Abaikan candle paling kanan** aktif secara default. Candle yang masih terbentuk tetap tampil pada grafik tetapi tidak ikut prediksi. Ketika candle baru muncul dan urutan stabil, candle yang sudah selesai otomatis dihitung.
6. Untuk eksperimen, klik **Kunci prediksi live & berhenti**. Snapshot yang sedang dihitung disimpan, monitor berhenti, dan prediksi dikunci. Jika live dihentikan tanpa dikunci, data set lama tidak berubah.

Perubahan urutan warna harus terlihat dalam **dua pembacaan berturut-turut** sebelum diterima. Ini meredam satu pembacaan yang keliru dengan konsekuensi jeda sekitar satu interval. Selama live, jangan menutupi chart dengan jendela lain atau mengubah zoom/warna. Indikator konsistensi dan waktu pembacaan terakhir tampil pada status bar.

Worker berjalan di background thread dan berhenti saat aplikasi ditutup. Antrean hanya menyimpan hasil terbaru agar UI tidak tertinggal. Screenshot/data live tidak ditulis ke disk setiap interval dan tidak dikirim ke internet.

## Membaca candle langsung dari layar

1. Buka chart pada aplikasi atau situs yang ingin dibaca. Perbesar chart agar body candle dan jaraknya terlihat jelas.
2. Di CandleLab, klik **Baca layar** atau tekan `F8`. CandleLab akan diminimalkan.
3. Drag hanya pada **area price chart**. Usahakan tidak menyertakan volume, indikator, toolbar, teks berwarna, atau panel lain. Lepaskan mouse untuk menangkap; tekan `Esc` untuk batal.
4. Periksa preview. Setiap candle yang digunakan diberi kotak hijau/merah, dan candle terakhir diberi garis kuning. Urutan `H/M/D` serta tingkat konsistensi deteksi ditampilkan.
5. Pilih:
   - **Ganti data dengan hasil** untuk memakai seluruh urutan yang terbaca; prediksi tertunda dibatalkan karena data berubah.
   - **Tambah candle terakhir** untuk menambahkan hanya candle paling kanan dan menilai prediksi yang sedang dikunci.
   - **Tangkap ulang area** jika ada objek yang salah terdeteksi. `Ctrl+F8` juga mengulang tangkapan pada koordinat yang sama.

Deteksi bekerja berdasarkan warna piksel hijau/teal dan merah, body/sumbu, serta konsistensi lebar dan spasi—**bukan OCR harga**. OHLC yang dihasilkan hanya memakai skala piksel relatif agar grafik dan arah dapat diproses; nilainya bukan harga aset sebenarnya. Warna chart custom, candle terlalu kecil/saling menempel, atau objek lain berwarna sama dapat menyebabkan kesalahan. **Jangan gunakan hasil tanpa memeriksa preview.**

Screenshot dan koordinat area hanya disimpan dalam memori selama aplikasi berjalan, tidak ditulis ke disk, dan tidak dikirim ke mana pun. Di macOS, berikan izin **Screen Recording** kepada CandleLab/Python. Pada Linux Wayland, kebijakan sistem dapat membatasi tangkapan layar; X11 atau impor CSV dapat digunakan sebagai alternatif.

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

Aplikasi tidak mengambil harga live dari bursa, tidak mengenali berita/volume/spread/likuiditas, dan tidak terhubung ke broker. Fitur baca layar hanya menganalisis piksel area yang Anda pilih dan dapat salah membaca chart. **Probabilitas tinggi tetap bisa salah. Hasil historis bukan jaminan hasil berikutnya. Jangan jadikan aplikasi ini satu-satunya dasar keputusan finansial.**

## Pengujian

Pengujian tidak membuka GUI dan tidak memerlukan Tkinter. Pillow diperlukan untuk pengujian visi:

```bash
cd CandleLab
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
```

Cakupan meliputi validasi OHLC, arah/doji, rumus Bayesian, pola dan jendela, rentang metrik, pencegahan kebocoran data backtest, format CSV, round-trip ekspor, eksperimen benar/salah/doji, pembatalan, pemulihan penyimpanan, deteksi candle terisi/hollow, teal/merah, noise, gambar kosong, perubahan resolusi, worker live berulang, antrean hasil terbaru, dan penanganan error tangkapan.

Pada mesin desktop yang memiliki display, smoke test berikut membuka seluruh halaman dan menguji penguncian prediksi dengan folder data sementara:

```bash
python tools/smoke_ui.py
```

Workflow Windows menjalankan smoke test ini sebelum membuat `.exe`; test tidak membaca atau mengubah data pengguna.
