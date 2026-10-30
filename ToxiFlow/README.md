# ToxiFlow

Aplikasi Android berbahasa Indonesia atau Inggris untuk mencatat pemasukan dan pengeluaran pribadi. ToxiFlow adalah proyek baru yang terpisah dari DocScan Toxi, dibuat dengan komponen Android native dan penyimpanan SQLite di perangkat.

## Yang bisa dilakukan

- Pilih **Bahasa Indonesia** (bawaan) atau **English** melalui tombol **ID/EN** di kanan atas halaman Beranda, Transaksi, atau Laporan. Pilihan disimpan di perangkat dan dipakai kembali saat aplikasi dibuka, termasuk pada splash, formulir, validasi, tanggal, kategori, laporan PDF/cetak, dan label ekspor CSV.
- Catat **pemasukan** atau **pengeluaran** dengan nominal, nama, kategori, tanggal, dan catatan opsional. Nominal berupa rupiah penuh dari Rp1 sampai Rp999.999.999.999 per transaksi; titik pemisah ribuan ditambahkan otomatis saat mengetik. Tanggal dan jam pembuatan otomatis dicatat saat transaksi baru disimpan dan tampil pada daftar serta detail transaksi.
- Lihat **saldo keseluruhan** (seluruh pemasukan dikurangi seluruh pengeluaran yang dicatat). Semua tanggal yang tercatat, termasuk tanggal mendatang, ikut dihitung. Saldo bisa negatif; tidak ada saldo awal terpisah, jadi catat saldo awal sebagai pemasukan bila diperlukan.
- Pilih periode bulan dengan panah atau ketuk nama bulan. Ringkasan pemasukan/pengeluaran, riwayat, dan laporan mengikuti periode tersebut. Saldo keseluruhan tetap mencakup semua periode.
- Cari nama, kategori, atau catatan dan filter riwayat berdasarkan jenis. Ketuk transaksi untuk melihat detail, **ubah**, atau **hapus** dengan konfirmasi.
- Lampirkan **satu foto opsional** pada pemasukan maupun pengeluaran, misalnya foto struk atau bukti. Pilih foto dari perangkat atau ambil foto dengan aplikasi kamera yang tersedia. Lihat dan perbesar foto dari detail; foto dapat diganti atau dihapus saat transaksi diubah. Transaksi tanpa foto tetap dapat disimpan seperti biasa.
- Lihat perbandingan arus kas dan pengeluaran per kategori untuk bulan yang dipilih. Persentase kategori dibulatkan ke bawah.
- Lihat **Sorotan bulanan**: transaksi pengeluaran terbesar, kategori pengeluaran terbesar, transaksi pemasukan terbesar, dan sumber pemasukan terbesar (total transaksi dengan nama dan kategori yang sama pada bulan tersebut).
- Lihat hingga tiga kelompok **pengeluaran berulang** dengan total terbesar pada bulan terpilih, beserta jumlah kemunculan dan rentang bulan yang diperiksa. Aplikasi juga memberi saran sederhana secara offline berdasarkan catatan yang tersedia.
- **Cetak laporan bulanan** dari halaman Laporan melalui dialog cetak Android, atau pilih **Simpan sebagai PDF** jika tersedia di perangkat. Laporan memuat ringkasan, sorotan dan saran, pengeluaran per kategori, serta seluruh transaksi, waktu pembuatan, dan catatannya untuk bulan yang dipilih. Foto transaksi dapat disertakan sebagai **halaman lampiran PDF/cetak**, satu foto per halaman, dengan penanda yang menghubungkannya ke transaksi.
- Sembunyikan atau tampilkan nominal pada beranda dengan ikon mata. Ini adalah penyamaran tampilan saja, bukan kunci aplikasi.
- Ekspor semua transaksi ke file CSV melalui menu **Tentang** di kanan atas. CSV dapat dibuka di aplikasi spreadsheet, tetapi **belum dapat diimpor kembali** ke ToxiFlow.

## Privasi dan batasan

- Data disimpan secara lokal di database privat aplikasi; aplikasi tidak memerlukan akun, server, atau izin internet. Database **tidak dienkripsi** dan aplikasi tidak menyediakan PIN atau kunci biometrik.
- ID aplikasi Android dan nama database tetap sama dengan versi sebelumnya supaya transaksi tetap tersedia saat memperbarui APK yang ditandatangani dengan kunci yang sama. Pembaruan database menambahkan kolom waktu dan foto tanpa menghapus data lama; transaksi yang dibuat sebelum fitur waktu memiliki waktu **Tidak tercatat**, dan transaksi lama awalnya tidak memiliki foto.
- Pencadangan otomatis Android dinonaktifkan. **Menghapus aplikasi atau membersihkan data aplikasi menghapus semua transaksi dan foto**. CSV hanya menyimpan salinan teks yang dapat dibaca, **tidak menyertakan gambar** dan bukan cadangan yang bisa dipulihkan ke aplikasi. File CSV tidak dienkripsi: simpan di lokasi yang tepercaya.
- Foto disimpan di database privat yang **tidak dienkripsi**, bukan di server. Impor menerima file hingga **20 MiB**, maksimal 30.000 piksel pada satu sisi dan 100 juta piksel keseluruhan. Aplikasi mencoba memperbaiki orientasi EXIF jika formatnya didukung perangkat, mengecilkan foto menjadi JPEG dengan sisi terpanjang maksimal **1.600 piksel** dan ukuran maksimal **512 KiB**, serta membuang metadata EXIF termasuk lokasi. Kualitas dan resolusi foto dapat berkurang; file asli yang dipilih tidak diubah.
- Foto pilihan baru dan hasil kamera memakai berkas sementara di cache privat hingga disimpan atau dibatalkan; cache dapat dibersihkan Android. Jika pemrosesan terhenti atau cache hilang, pilih foto lagi sebelum menyimpan. Menghapus atau mengganti foto pada formulir baru berlaku setelah **Simpan transaksi**; perubahan yang belum disimpan dapat dibatalkan. Menghapus transaksi juga menghapus foto yang terlampir dari database, tetapi bukan penghapusan aman untuk mencegah pemulihan forensik. Cache sementara yang tertinggal dibersihkan saat aplikasi dibuka kembali setelah tujuh hari.
- ToxiFlow tidak meminta izin kamera atau penyimpanan: aplikasi membuka pemilih dokumen sistem atau aplikasi kamera lain. Aplikasi kamera atau penyedia foto yang dipilih mungkin membuat salinan sendiri atau menggunakan jaringan, sesuai kebijakan masing-masing; pilih layanan yang dipercaya. Foto asli dan salinan lain di luar ToxiFlow tidak otomatis dihapus.
- Penyedia file menentukan lokasi dan perilaku penyimpanan CSV. Jika ekspor gagal, file tidak lengkap mungkin tertinggal. Teks yang berpotensi ditafsirkan sebagai formula spreadsheet diawali tanda petik satu saat diekspor.
- Jam pembuatan menunjukkan waktu lokal perangkat **saat transaksi disimpan**, bukan waktu terjadinya transaksi. Jam ini tidak berubah saat transaksi diedit atau zona waktu perangkat kemudian diganti. Tanggal transaksi yang dapat dipilih pengguna bisa berbeda dan tetap menentukan bulan laporan. Ketepatannya bergantung pada pengaturan jam perangkat. Daftar dan detail menampilkan jam serta menit; CSV menambahkan kolom `Dibuat (waktu perangkat)` berformat `yyyy-MM-dd HH:mm:ss` tanpa zona waktu, dan kolom ini kosong untuk transaksi lama.
- CSV menambahkan kolom `Foto terlampir (foto tidak disertakan)` dengan nilai `Ada` atau `Tidak` (dalam bahasa Inggris, `Photo attached (image not included)` dengan `Yes` atau `No`), tetapi **file CSV tidak memuat gambar**. Label jenis dan kategori juga mengikuti bahasa yang dipilih; tanggal tetap berformat ISO. Pada laporan cetak/PDF, pilihan **Sertakan foto sebagai lampiran** aktif secara awal dan dapat dimatikan. Foto yang disertakan muncul sebagai gambar pada halaman PDF/cetak, bukan sebagai berkas JPEG terpisah di dalam PDF.
- Laporan cetak/PDF dapat berisi foto, informasi keuangan, waktu pembuatan, dan catatan yang tidak disamarkan meskipun nominal pada beranda disembunyikan. PDF tidak dienkripsi oleh ToxiFlow; simpan atau cetak hanya melalui lokasi, printer, dan layanan yang tepercaya. Layanan cetak yang dipilih dapat menyimpan salinan atau mengirimnya melalui jaringan, walaupun ToxiFlow sendiri tidak memiliki izin internet.
- Laporan mencakup **seluruh** transaksi di bulan terpilih, tanpa mengikuti pencarian atau filter pada halaman Transaksi. **Selisih periode** adalah pemasukan dikurangi pengeluaran bulan tersebut, bukan saldo keseluruhan dan tanpa saldo awal. Bila bulan kosong, laporan tetap menunjukkan total nol.
- Pengeluaran dianggap berulang jika **nama dan kategori yang sama** tercatat minimal dua kali pada bulan terpilih, atau tercatat pada minimal dua bulan dalam rentang bulan terpilih dan hingga dua bulan sebelumnya. Perbedaan huruf besar/kecil, bentuk Unicode yang setara, dan spasi berlebih diabaikan; nama yang berbeda tidak ditebak sebagai sama. Kelompok harus muncul di bulan terpilih dan diurutkan berdasarkan total **bulan itu saja**. Rentang menjadi lebih pendek pada batas awal tanggal yang didukung. Deteksi ini bukan jaminan adanya jadwal atau tagihan rutin.
- Sorotan, jumlah, dan saran hanya berdasarkan data yang dicatat dan bisa keliru atau tidak lengkap. Saran bersifat panduan umum berbasis aturan lokal, **bukan nasihat keuangan profesional** dan bukan prediksi. Laporan cetak juga menunjukkan jumlah bulan terdeteksi dari riwayat sebelumnya, tetapi tidak mencantumkan nominal atau rincian transaksi bulan sebelumnya di bagian tersebut.
- Untuk menjaga penggunaan memori, laporan dibatasi hingga **500 halaman** termasuk lampiran dan maksimal **64 halaman foto dalam satu proses cetak/PDF**. Resolusi gambar dapat diperkecil lagi saat banyak foto dicetak sekaligus. Jika melebihi batas foto, pilih rentang halaman yang lebih kecil (jika didukung dialog cetak) atau ulangi tanpa foto. Jika jumlah total halaman melebihi batas, coba tanpa foto atau gunakan CSV untuk salinan **teks** transaksi. Pilih A4 atau Letter jika area cetak terlalu kecil. Pilihan kertas, printer, rentang halaman, dan ketersediaan Simpan sebagai PDF bergantung pada sistem/layanan cetak Android.
- Saat laporan disiapkan, data dan foto diambil sebagai satu salinan yang konsisten. Foto sementara disimpan pada cache privat aplikasi, dihapus setelah proses cetak selesai/dibatalkan, dan sisa akibat proses terhenti dibersihkan saat aplikasi dibuka kembali setelah tujuh hari. Salinan sementara ini tidak dienkripsi dan dapat dibersihkan oleh Android. Jika foto rusak/hilang atau ruang tidak cukup, pembuatan laporan gagal dengan pesan, bukan diam-diam menghilangkan foto; coba kembali atau cetak tanpa foto.
- Tidak ada sinkronisasi antarperangkat, impor, perhitungan desimal/sen, multi-mata-uang, atau integrasi rekening bank.
- Bahasa hanya mengubah tampilan dan label, bukan data transaksi, kategori yang tersimpan, foto, nominal rupiah, atau teks yang dimasukkan pengguna. Pergantian bahasa tidak memerlukan koneksi internet dan tidak mengubah bahasa perangkat. Aplikasi sistem atau pihak ketiga (misalnya pemilih lokasi file, kamera, dan layanan cetak) dapat tetap menggunakan bahasa sistem perangkat.
- Android 7.0 (API 24) atau lebih baru.

## Cara menggunakan

Tombol **ID/EN** di kanan atas membuka pilihan bahasa; perubahan langsung diterapkan tanpa menghapus data. Petunjuk di bawah menggunakan label Bahasa Indonesia.

1. Buka aplikasi dan tekan **Catat** atau **Catat transaksi**.
2. Pilih **Pengeluaran** atau **Pemasukan**, lalu ketik angka rupiah; titik ribuan muncul otomatis, misalnya `1000000` menjadi `1.000.000`. Jika menempel angka, gunakan angka saja atau titik ribuan yang tepat (misalnya `12.500` berarti Rp12.500, bukan nilai desimal); koma dan nilai desimal tidak didukung. Nominal yang salah format atau lebih dari 12 digit tidak disimpan. Lengkapi nama, kategori, tanggal, dan catatan bila perlu. Jika ingin, gulir ke **Foto transaksi** dan ketuk **Pilih foto** atau **Ambil foto**. Foto bersifat opsional; tekan **Simpan transaksi** untuk menyimpan semuanya. Jam pembuatan dicatat otomatis dan tidak dapat diubah. Tanggal awal mengikuti bulan yang sedang dipilih, dengan nomor hari ini (atau hari terakhir jika bulan lebih pendek); ubah jika perlu.
3. Gunakan **Transaksi** untuk mencari/filter, lalu ketuk baris untuk melihat foto, mengubah atau menghapusnya. Pada formulir ubah, gunakan **Ganti foto**, **Hapus foto**, atau **Gunakan foto semula**, lalu simpan. Pada tampilan foto, cubit atau gunakan tombol perbesar/perkecil. Gunakan **Laporan** untuk melihat ringkasan, sorotan terbesar, pengeluaran berulang, dan saran sederhana untuk bulan tersebut.
4. Untuk mencetak, buka **Laporan**, pilih bulan, lalu ketuk **Cetak laporan bulanan**. Periksa peringatan privasi. Biarkan **Sertakan foto sebagai lampiran** tercentang untuk menyertakan foto atau hilangkan centang jika tidak diperlukan, lalu tekan **Lanjutkan**. Di dialog Android, pilih printer atau **Simpan sebagai PDF** (jika tersedia), sesuaikan kertas dan halaman, lalu konfirmasi. Untuk membatalkan, tutup dialog tanpa mencetak. Laporan memakai salinan data yang dibuat saat proses cetak disiapkan setelah konfirmasi; jika data berubah sesudahnya, mulai cetak kembali.
5. Untuk menyimpan salinan CSV, ketuk ikon informasi di kanan atas, pilih **Ekspor CSV**, lalu pilih lokasi melalui pemilih file Android.

## Ikon dan splash screen

Ikon launcher ToxiFlow tersedia untuk Android 7, ikon adaptif Android 8+, dan ikon monokrom untuk tema Android 13+. Sumber desain yang dipilih tersimpan di `design/toxiflow-icon.png`. Untuk membuat ulang aset setelah mengubah sumber, pasang Pillow lalu jalankan `python3 tools/generate_launcher_icons.py` dari folder `ToxiFlow`; perintah ini menimpa aset launcher yang dihasilkan. Periksa ukuran dan area aman dengan `python3 tools/test_launcher_icons.py`.

Saat aplikasi dibuka dari launcher, splash memakai logo DharmawanToxi dan gaya hitam-biru dari `splash_screen.py` dan `splash_logo.png` di root repositori, seperti splash Android DocScan Toxi. Salinan logo Android yang sama tersimpan sebagai `app/src/main/res/drawable-nodpi/splash_logo.png`; nama dan versi mengikuti ToxiFlow yang terpasang. Splash tampil sekitar **3 detik** seperti versi yang sudah ada, lalu beralih ke beranda. Batang biru adalah animasi pembuka, bukan indikator kemajuan pekerjaan sebenarnya. Rotasi tidak mengulang waktunya, dan tombol kembali membatalkan pembukaan. Komponen launcher lama dipertahankan agar pintasan yang sudah ada tetap bisa digunakan.

## Membuat dan menguji APK

Buka folder `ToxiFlow/` di Android Studio dengan JDK 17, Android SDK Platform 35 dan build tools yang diminta Android Studio. Sinkronisasi pertama membutuhkan akses ke Google Maven, Maven Central, dan distribusi Gradle. Atau jalankan:

```bash
cd ToxiFlow
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
# Windows: gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Perintah tersebut juga mengompilasi APK tes Android (`assembleDebugAndroidTest`), tanpa menjalankan emulator.

APK debug ada di `app/build/outputs/apk/debug/app-debug.apk`. APK debug dapat di-debug dan hanya cocok untuk pengujian, bukan distribusi produksi atau data keuangan sensitif. Kunci debug berbeda antar lingkungan; APK yang ditandatangani dengan kunci berbeda tidak dapat memperbarui instalasi lama. Menghapus instalasi lama juga menghapus data lokalnya.

Tes perhitungan, validasi, tanggal dan jam, pilihan bahasa, batas foto, filter, ekspor, sorotan dan pola berulang, isi laporan bulanan, pembagian halaman, rentang halaman, dan waktu splash juga dapat dijalankan tanpa Android SDK atau dependensi Gradle, cukup JDK:

```bash
cd ToxiFlow
./tools/run-unit-tests.sh
```

Untuk tes penyimpanan dan migrasi database, pemrosesan dan privasi foto, pergantian bahasa dan alur layar, serta pembuatan PDF (termasuk teks panjang, halaman foto, dan rentang halaman) di perangkat/emulator yang terhubung, jalankan `./gradlew connectedDebugAndroidTest`. Tes menggunakan database khusus yang terpisah dari transaksi pengguna dan mengembalikan pengaturan bahasa setelah selesai; tetap disarankan memakai emulator khusus pengujian. Workflow GitHub Actions **ToxiFlow Android CI** dapat dijalankan secara manual untuk membuat APK debug, menjalankan tes unit, dan menguji alur di emulator; unduh artifact `ToxiFlow-debug-apk` setelah job build berhasil.

Jika tes emulator di workflow gagal dan hasil XML tersedia, nama tes beserta cuplikan kesalahan muncul sebagai anotasi GitHub Actions. Laporan lengkap HTML dan XML tersedia di artifact `ToxiFlow-device-test-report`. Pemeriksaan pembuat anotasi dapat dijalankan tanpa Android SDK melalui `python3 tools/test_device_test_failures.py` dari folder `ToxiFlow`.
