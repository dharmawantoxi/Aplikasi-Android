# ToxiFlow

Aplikasi Android berbahasa Indonesia untuk mencatat pemasukan dan pengeluaran pribadi. ToxiFlow adalah proyek baru yang terpisah dari DocScan Toxi, dibuat dengan komponen Android native dan penyimpanan SQLite di perangkat.

## Yang bisa dilakukan

- Catat **pemasukan** atau **pengeluaran** dengan nominal, nama, kategori, tanggal, dan catatan opsional. Nominal berupa rupiah penuh dari Rp1 sampai Rp999.999.999.999 per transaksi.
- Lihat **saldo keseluruhan** (seluruh pemasukan dikurangi seluruh pengeluaran yang dicatat). Semua tanggal yang tercatat, termasuk tanggal mendatang, ikut dihitung. Saldo bisa negatif; tidak ada saldo awal terpisah, jadi catat saldo awal sebagai pemasukan bila diperlukan.
- Pilih periode bulan dengan panah atau ketuk nama bulan. Ringkasan pemasukan/pengeluaran, riwayat, dan laporan mengikuti periode tersebut. Saldo keseluruhan tetap mencakup semua periode.
- Cari nama, kategori, atau catatan dan filter riwayat berdasarkan jenis. Ketuk transaksi untuk melihat detail, **ubah**, atau **hapus** dengan konfirmasi.
- Lihat perbandingan arus kas dan pengeluaran per kategori untuk bulan yang dipilih. Persentase kategori dibulatkan ke bawah.
- Sembunyikan atau tampilkan nominal pada beranda dengan ikon mata. Ini adalah penyamaran tampilan saja, bukan kunci aplikasi.
- Ekspor semua transaksi ke file CSV melalui menu **Tentang** di kanan atas. CSV dapat dibuka di aplikasi spreadsheet, tetapi **belum dapat diimpor kembali** ke ToxiFlow.

## Privasi dan batasan

- Data disimpan secara lokal di database privat aplikasi; aplikasi tidak memerlukan akun, server, atau izin internet. Database **tidak dienkripsi** dan aplikasi tidak menyediakan PIN atau kunci biometrik.
- ID aplikasi Android dan nama database tetap sama dengan versi sebelumnya supaya transaksi tetap tersedia saat memperbarui APK yang ditandatangani dengan kunci yang sama.
- Pencadangan otomatis Android dinonaktifkan. **Menghapus aplikasi atau membersihkan data aplikasi menghapus semua transaksi**; ekspor CSV sebelum melakukannya jika ingin menyimpan salinan yang dapat dibaca. CSV bukan cadangan yang bisa dipulihkan ke aplikasi, dan file CSV tidak dienkripsi: simpan di lokasi yang tepercaya.
- Penyedia file menentukan lokasi dan perilaku penyimpanan CSV. Jika ekspor gagal, file tidak lengkap mungkin tertinggal. Teks yang berpotensi ditafsirkan sebagai formula spreadsheet diawali tanda petik satu saat diekspor.
- Tidak ada sinkronisasi antarperangkat, impor, perhitungan desimal/sen, multi-mata-uang, atau integrasi rekening bank.
- Android 7.0 (API 24) atau lebih baru. Beberapa dialog, seperti pemilih tanggal dan lokasi file, mengikuti tampilan/bahasa sistem perangkat.

## Cara menggunakan

1. Buka aplikasi dan tekan **Catat** atau **Catat transaksi**.
2. Pilih **Pengeluaran** atau **Pemasukan**, masukkan angka rupiah tanpa titik atau koma, nama, kategori, tanggal, dan catatan bila perlu, lalu tekan **Simpan transaksi**. Tanggal awal mengikuti bulan yang sedang dipilih, dengan nomor hari ini (atau hari terakhir jika bulan lebih pendek); ubah jika perlu.
3. Gunakan **Transaksi** untuk mencari/filter, lalu ketuk baris untuk mengubah atau menghapusnya. Gunakan **Laporan** untuk melihat ringkasan bulan tersebut.
4. Untuk menyimpan salinan CSV, ketuk ikon informasi di kanan atas, pilih **Ekspor CSV**, lalu pilih lokasi melalui pemilih file Android.

## Ikon aplikasi

Ikon launcher ToxiFlow tersedia untuk Android 7, ikon adaptif Android 8+, dan ikon monokrom untuk tema Android 13+. Sumber desain yang dipilih tersimpan di `design/toxiflow-icon.png`. Untuk membuat ulang aset setelah mengubah sumber, pasang Pillow lalu jalankan `python3 tools/generate_launcher_icons.py` dari folder `ToxiFlow`; perintah ini menimpa aset launcher yang dihasilkan. Periksa ukuran dan area aman dengan `python3 tools/test_launcher_icons.py`.

## Membuat dan menguji APK

Buka folder `ToxiFlow/` di Android Studio dengan JDK 17, Android SDK Platform 35 dan build tools yang diminta Android Studio. Sinkronisasi pertama membutuhkan akses ke Google Maven, Maven Central, dan distribusi Gradle. Atau jalankan:

```bash
cd ToxiFlow
./gradlew testDebugUnitTest assembleDebug
# Windows: gradlew.bat testDebugUnitTest assembleDebug
```

APK debug ada di `app/build/outputs/apk/debug/app-debug.apk`. APK debug dapat di-debug dan hanya cocok untuk pengujian, bukan distribusi produksi atau data keuangan sensitif. Kunci debug berbeda antar lingkungan; APK yang ditandatangani dengan kunci berbeda tidak dapat memperbarui instalasi lama. Menghapus instalasi lama juga menghapus data lokalnya.

Tes perhitungan, validasi, tanggal, filter, dan ekspor juga dapat dijalankan tanpa Android SDK atau dependensi Gradle, cukup JDK:

```bash
cd ToxiFlow
./tools/run-unit-tests.sh
```

Untuk tes penyimpanan dan alur layar di perangkat/emulator yang terhubung, jalankan `./gradlew connectedDebugAndroidTest`. Tes menggunakan database khusus yang terpisah dari transaksi pengguna; tetap disarankan memakai emulator khusus pengujian. Workflow GitHub Actions **ToxiFlow Android CI** dapat dijalankan secara manual untuk membuat APK debug, menjalankan tes unit, dan menguji alur di emulator; unduh artifact `ToxiFlow-debug-apk` setelah job build berhasil.
