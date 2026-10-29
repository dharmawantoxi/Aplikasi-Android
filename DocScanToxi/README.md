# DocScan Toxi

Aplikasi Android berbahasa Indonesia untuk memindai, merapikan, menyimpan, dan berbagi dokumen. Dibangun secara native dengan Kotlin.

## Fitur

- **Pindai otomatis** dengan pemindai dokumen ML Kit: mendeteksi tepi, mengoreksi perspektif, dan memindai beberapa halaman. Pengguna juga dapat mengambil foto dengan kamera sistem atau mengimpor foto.
- Simpan maksimal **25 halaman per dokumen**, cari dan ubah nama dokumen, tambah, urutkan, atau hapus halaman.
- **Edit teks PDF asli**: buka PDF yang didukung, cari segmen teks yang sudah ada pada halaman, lalu ganti operand teks tersebut memakai font dan pengaturan PDF asli. Hasilnya tetap PDF dengan konten asli, bukan halaman yang diraster menjadi JPEG. Simpan sebagai salinan atau, setelah mencadangkan dan mengonfirmasi, ganti file asli.
- **ZIP**: buat arsip ZIP dari beberapa file atau ekstrak arsip ZIP ke subfolder baru pada folder pilihan.
- **Gabung PDF**: pilih beberapa PDF, atur urutan file, lalu simpan hasilnya sebagai PDF baru tanpa meraster halaman.
- **Edit file Word, Excel, dan PowerPoint**: buka DOCX, XLSX, atau PPTX yang sudah ada lalu ganti teks segmen, isi sel, atau teks slide secara langsung pada file Office-nya. Bagian lain seperti gambar, gaya, lembar, slide, catatan pembicara, dan formula dipertahankan; tidak ada halaman yang diraster. Simpan sebagai salinan atau, setelah mencadangkan dan mengonfirmasi, ganti file asli.
- **Ekspor hasil OCR ke Word, Excel, atau PowerPoint**: setelah **Pindai teks**, simpan teks yang telah dikoreksi sebagai DOCX, XLSX, atau PPTX yang dapat diedit. Ini hanya memindahkan teks yang dikenali; tata letak, foto, dan tabel asli tidak direkonstruksi.
- **Edit PDF sebagai gambar**: impor halaman PDF sebagai dokumen baru untuk mengatur halaman, memotong, memutar, memberi filter, atau menambahkan teks pada gambar. Ini merupakan alur yang berbeda dari edit teks PDF asli.
- Edit halaman gambar dengan menggeser empat sudut, memutar, dan memilih filter asli, abu-abu, atau hitam putih. Perubahan disimpan setelah menekan **Simpan**.
- Ekspor dokumen berbasis gambar sebagai satu **PDF** atau ekspor tiap halaman sebagai **JPG**. PDF atau JPG juga dapat dibagikan ke aplikasi lain. Halaman PDF yang diimpor sebagai gambar mempertahankan ukuran dan orientasi asal sejauh mungkin; foto baru menggunakan A4.
- Kenali teks Latin (termasuk Bahasa Indonesia) dengan **OCR di perangkat**, lalu perbaiki, salin, atau simpan sebagai **TXT**, **Word (DOCX)**, **Excel (XLSX)**, atau **PowerPoint (PPTX)**. OCR tidak selalu akurat; periksa hasilnya. PDF dari alur pemindaian dan edit gambar tetap berupa gambar, bukan PDF dengan teks yang dapat dicari.
- Tema terang/gelap mengikuti pengaturan perangkat.

## Persyaratan dan batasan

- Android 7.0 (API 24) atau lebih baru.
- Pemindai otomatis memerlukan **Google Play Services**, perangkat yang didukung (termasuk RAM yang cukup), dan mungkin koneksi internet pada penggunaan pertama untuk mengunduh komponen pemindai. Bila tidak tersedia, pilih **Kamera biasa** atau **Impor foto**. Kamera biasa tidak melakukan deteksi tepi otomatis; sudut dapat diatur di editor setelahnya.
- OCR menggunakan model Latin yang disertakan dalam aplikasi dan dapat berjalan tanpa koneksi setelah aplikasi terpasang. Edit teks PDF juga diproses di perangkat.
- Alur edit PDF dibatasi **100 MB dan 25 halaman**. PDF rusak atau terenkripsi tidak didukung. Pada editor teks asli, halaman dengan aliran konten terlalu besar atau kompleks mungkin tidak dapat dibaca atau diedit.
- **ZIP**: pembuatan mendukung 1–100 file (bukan folder) dengan total maksimal 100 MB; setiap nama dalam arsip diberi nomor sesuai urutan pilihan. Ekstraksi mendukung ZIP standar hingga 110 MB, 200 entri, 500 file/folder termasuk folder perantara, kedalaman 12, dan 200 MB hasil. ZIP terenkripsi, rusak, nama duplikat/ambigu, atau jalur tidak aman ditolak; tautan simbolis di arsip tidak dipulihkan sebagai tautan. Isi diperiksa sebelum ditulis dan disimpan dalam subfolder baru; penyedia harus mendukung pembuatan folder, baca, dan tulis. Kegagalan dapat meninggalkan subfolder atau file hasil yang tidak lengkap. Penyedia dan perubahan bersamaan tidak selalu menjamin keamanan atau penulisan atomik. Pilih nama ZIP baru yang bukan sumber; hasil yang dipilih dapat ditimpa oleh penyedia. Untuk sumber kosong, identitas file sumber dan tujuan harus dapat dibedakan melalui ID dokumen dari penyedia yang sama.
- **Gabung PDF** menerima 2–20 PDF dengan total maksimal 100 MB dan 100 halaman. PDF rusak, terenkripsi, dibatasi untuk penggabungan, atau bertanda tangan ditolak. Halaman tidak diraster, tetapi formulir, tautan, penanda, metadata dan fitur PDF kompleks mungkin berubah; periksa hasil. Gunakan nama/lokasi baru; pemilih atau penyedia tertentu dapat menimpa file yang sudah ada, termasuk sumber jika dipilih. Aplikasi membandingkan URI dan isi yang dapat dibaca untuk mengurangi risiko alias, tetapi tidak dapat menjamin keamanan jika penyedia berperilaku berbeda atau file berubah bersamaan. Hasil harus dapat dibaca kembali untuk verifikasi; penyedia yang tidak memberikan akses baca tidak didukung. Penulisan yang gagal dapat meninggalkan file hasil yang tidak lengkap.
- **Batasan edit teks PDF asli:** hanya segmen teks yang dikenali di aliran konten halaman yang dapat diganti. Teks bisa terpecah per kata atau karakter, dan tidak semua font dapat mengkodekan karakter baru; font subset sering hanya mendukung karakter yang telah ada. Teks pada gambar/hasil pindai tidak menjadi teks asli yang dapat diedit. Isi formulir, XObject, teks vertikal, beberapa layer, teks tersembunyi tertentu, dan segmen dengan `ActualText` di properti kontennya dilewati. PDF bertanda tangan digital atau dengan izin edit terbatas ditolak, karena mengubahnya dapat membatalkan tanda tangan.
- Editor tidak mengalirkan ulang tata letak. Penggantian yang lebih panjang atau lebih pendek dapat mengubah spasi, menggeser teks berikutnya, atau bertumpuk; penyesuaian kerning pada segmen `TJ` dapat hilang. **Periksa setiap halaman dan hasilnya** di pembaca PDF lain. Elemen lain seperti halaman, gambar, dan tautan tidak sengaja diraster, tetapi kompatibilitas atau keutuhan seluruh fitur PDF kompleks tidak dapat dijamin; struktur aksesibilitas dan metadata mungkin tidak ikut diperbarui. Ini bukan alat redaksi aman: teks yang sama dapat tetap ada di metadata, lampiran, layer lain, atau salinan cadangan.
- **Simpan salinan** disarankan. Pilih file dan lokasi yang berbeda dari sumber; jangan memilih file asli atau file yang berisi cadangan penting pada dialog penyimpanan. Aplikasi menolak URI yang dikenali sebagai sumber, tetapi tidak semua penyedia mengungkapkan jika dua URI adalah alias file yang sama, dan perilaku dialog penyedia dapat berbeda.
- **Ganti file asli** hanya tersedia jika penyedia memberi izin menulis. Simpan cadangan terpisah (sebaiknya di lokasi atau penyedia lain), lalu konfirmasi kembali. Aplikasi memeriksa cadangan dan perubahan eksternal sebelum menulis, memverifikasi hasil, dan mencoba memulihkan jika penulisan gagal. Penyedia dokumen/cloud mungkin tidak mendukung penulisan atomik: listrik terputus, perubahan bersamaan, atau pemulihan yang gagal tetap dapat merusak file. Simpan cadangan independen dan periksa hasil sebelum menghapusnya.
- **Batasan edit file Office:** hanya format OOXML modern **DOCX, XLSX, dan PPTX** biasa yang didukung. Format lama (`.doc`, `.xls`, `.ppt`), template (`.dotx`, `.xltx`, `.potx`), file dilindungi kata sandi atau proteksi edit, file bertanda tangan digital, file bermakro, dan referensi bagian eksternal ditolak. Maksimal 100 MB. Word: teks di isi dokumen, header/footer terdaftar, catatan kaki, dan catatan akhir; bagian lain (mis. kotak teks tertentu) tidak ditampilkan. PowerPoint: teks pada slide, bukan teks di master/layout atau catatan pembicara. Excel: sel berisi teks/angka/boolean, bukan sel formula, tanggal berformat ISO, atau error. Hanya satu segmen/sel yang diganti per tindakan, satu baris tanpa karakter kontrol, maksimal 32.767 karakter; angka harus valid dan boolean harus TRUE/FALSE. Mengganti teks tidak memindahkan tata letak, sehingga teks yang lebih panjang dapat meluber atau terpotong; format run pada segmen yang diganti dapat hilang. Untuk Excel, aplikasi menandai workbook agar dihitung ulang saat dibuka, tetapi nilai tersimpan yang bergantung pada sel yang diubah belum tentu diperbarui di aplikasi pembaca lain. Editor ini berupa daftar isi, bukan pratinjau tata letak: **periksa hasil di aplikasi Word/Excel/PowerPoint** sebelum mengandalkannya.
- **Ekspor OCR ke Office** hanya menulis teks yang dikenali. Pada Excel, kolom dipisahkan tab atau dua spasi atau lebih; penanda `--- Halaman N ---` menjadi lembar atau slide terpisah bila urutannya utuh, dan seluruh teks digabung menjadi satu halaman bila tidak. Pemenggalan baris mengikuti hasil OCR. Simpan TXT lebih dulu bila Anda ingin menyunting teksnya sebelum diekspor. Hasil ekspor tidak memuat foto, tabel bergaya, atau tata letak asli.
- **Edit sebagai gambar** merender setiap halaman menjadi JPEG dengan sisi terpanjang maksimal 2.400 piksel. Teks asli tidak dapat dipilih, dicari, atau diedit di tempat; tautan, formulir, lampiran, dan tanda tangan digital tidak dipertahankan. Kualitas gambar dan teks dapat berkurang. Teks baru menyatu dengan gambar setelah disimpan dan tidak dapat dipindahkan atau diedit secara terpisah. Ukuran halaman dipertahankan sejauh mungkin, dan disesuaikan jika dipotong atau diputar; foto tambahan menggunakan A4 dengan margin.
- **Simpan salinan** dan **Ganti file asli** untuk file Office memakai aturan keamanan yang sama dengan editor PDF: cadangan terpisah wajib, konfirmasi ulang, verifikasi isi hasil, deteksi perubahan eksternal, dan percobaan pemulihan. Jika pemulihan gagal, penggantian berikutnya diblokir dan file asli harus diperiksa atau dibuka ulang. Penyedia penyimpanan dapat menimpa file yang dipilih di dialog; jangan memilih file asli atau cadangan penting sebagai tujuan.
- Dokumen gambar dan sesi edit PDF/Office disimpan secara privat di perangkat, bukan di server aplikasi. Sesi PDF dan Office menggunakan cache sementara: jangan mengandalkan perubahan yang belum disimpan. Cadangan Android dinonaktifkan untuk privasi: **menghapus aplikasi akan menghapus dokumen lokal**. Gunakan **Simpan salinan**, **Simpan PDF**, **Simpan sebagai JPG**, **Simpan TXT**, atau ekspor Office untuk membuat salinan yang tetap tersedia. Aplikasi penerima yang dipilih saat berbagi akan memperoleh salinan yang Anda bagikan.

## Cara menggunakan

### Mengganti teks PDF asli

1. Tekan **Edit PDF**, baca batasannya, lalu pilih **Pilih PDF**. PDF tidak diubah saat dibuka.
2. Buka halaman yang diinginkan dengan tombol halaman. Cari teks di daftar dan ketuk segmen yang tersedia; segmen tidak selalu sama dengan satu baris atau paragraf. Ketik pengganti satu baris lalu tekan **Terapkan**. Jika font tidak mendukung karakter baru, gunakan karakter yang tersedia pada font itu atau editor PDF lain.
3. Periksa pratinjau. Tekan **Simpan salinan** dan pilih nama/lokasi berbeda untuk menyimpan hasil tanpa sengaja mengganti sumber.
4. Jika memang ingin menimpa sumber, tekan **Ganti file asli**, simpan cadangan pada file yang benar-benar terpisah, lalu konfirmasi **Ganti file asli** sekali lagi. Periksa hasil dan simpan cadangan. Jika penyedia tidak memberikan izin menulis, gunakan **Simpan salinan**.

### Menggabungkan PDF

1. Tekan **Gabung PDF**, lalu **Tambah PDF** untuk memilih file satu per satu.
2. Gunakan **Naik**, **Turun**, dan **Hapus** untuk mengatur urutan file. Semua halaman setiap file disertakan dalam urutan aslinya.
3. Tekan **Gabung dan simpan**, baca peringatan, dan pilih nama serta lokasi baru yang berbeda dari seluruh sumber. Buka hasil di pembaca PDF dan periksa isinya.

### Membuat atau mengekstrak ZIP

1. Tekan **ZIP** di halaman utama. Untuk membuat arsip, tekan **Tambah file** satu per satu, lalu **Buat dan simpan ZIP**. Pilih nama dan lokasi baru. Nama file dalam arsip diawali nomor sesuai urutan pilihan.
2. Untuk ekstraksi, tekan **Pilih ZIP**, pilih arsip, lalu tekan **Ekstrak ke folder**. Pilih folder tujuan; aplikasi membuat subfolder baru untuk isinya. Periksa hasil di pengelola file. Folder atau file yang tidak lengkap mungkin tertinggal jika penyimpanan gagal.

### Mengganti isi file Word, Excel, atau PowerPoint

1. Tekan **Edit Office**, baca batasannya, lalu pilih file DOCX, XLSX, atau PPTX. File tidak diubah saat dibuka.
2. Gunakan tombol **Sebelumnya**/**Berikutnya** untuk berpindah bagian: isi dokumen/header Word, lembar Excel, atau slide PowerPoint. Cari teks atau alamat sel, lalu ketuk baris untuk menggantinya. Masukkan teks satu baris, atau angka/TRUE/FALSE untuk sel Excel, lalu tekan **Terapkan**. Perubahan disimpan pada salinan kerja privat.
3. Tekan **Simpan salinan** dan pilih nama/lokasi berbeda, lalu buka hasilnya di aplikasi Office untuk memeriksa tata letak.
4. Untuk menimpa sumber, tekan **Ganti file asli**, simpan cadangan pada file yang benar-benar terpisah, lalu konfirmasi. Jika penyedia tidak memberi izin menulis, gunakan **Simpan salinan**.

### Mengekspor hasil pemindaian ke Office

1. Buat dokumen dari pindaian/foto, lalu tekan **Pindai teks** dan tunggu sampai hasil OCR selesai.
2. Koreksi teks di kotak editor. Baris `--- Halaman N ---` dipertahankan sebagai pemisah halaman.
3. Tekan **Word**, **Excel**, atau **PowerPoint**, lalu pilih nama dan lokasi. File dibuat di cache, diperiksa ulang sebagai paket OOXML yang valid, baru disalin ke lokasi pilihan.

### Memindai atau mengedit halaman sebagai gambar

1. Tekan **Mulai pindai** atau **Impor foto**. Jika pemindai otomatis tidak tersedia, pilih kamera biasa atau impor foto. Untuk merender PDF sebagai gambar, pilih **Edit PDF > Edit sebagai gambar**, baca peringatannya, lalu pilih file.
2. Di halaman dokumen, tekan **Tambah** untuk menambah halaman foto. Ketuk halaman untuk memotong, memutar, atau memilih filter. Gunakan menu titik tiga untuk menyimpan JPG, mengubah urutan, dan menghapus halaman.
3. Tekan **Tambah teks** pada halaman, ketik teks, lalu ketuk atau geser pratinjau untuk memosisikannya. Tombol **Posisi** menyediakan pilihan posisi tanpa geser. Pilih ukuran dan warna, lalu tekan **Simpan**. Tambahkan baris lain dengan mengulangi langkah ini.
4. Tekan **Simpan PDF** dan pilih nama/lokasi baru, atau **Bagikan** untuk mengirim salinan PDF.
5. Tekan **Pindai teks** untuk membaca teks semua halaman. Koreksi hasilnya jika perlu, lalu salin, simpan sebagai TXT, atau ekspor ke **Word**, **Excel**, atau **PowerPoint** sesuai bagian sebelumnya.

## Membuat APK

1. Buka folder `DocScanToxi` sebagai project di Android Studio terbaru yang mendukung Android Gradle Plugin 8.13.
2. Pasang **JDK 17**, Android SDK Platform **36**, serta SDK Build Tools yang diminta oleh Android Studio. Pertama kali sinkronisasi, Gradle perlu mengunduh dependensi dari Google Maven dan Maven Central.
3. Pilih **Build > Build APK(s)**. APK debug yang dapat dipasang ada di `app/build/outputs/apk/debug/app-debug.apk`.

Atau melalui terminal:

```bash
cd DocScanToxi
./gradlew testDebugUnitTest assembleDebug
# Windows: gradlew.bat testDebugUnitTest assembleDebug
```

APK debug ditandatangani dengan kunci debug dan cocok untuk pengujian, bukan untuk distribusi produksi. Untuk rilis gunakan **Build > Generate Signed Bundle / APK** dan keystore milik Anda sendiri; simpan keystore dengan aman.

Workflow GitHub Actions `Android CI` dijalankan **secara manual** melalui `workflow_dispatch`; workflow ini membangun APK debug, menjalankan unit test, dan menguji alur dasar pada emulator Android. Pilih branch yang ingin diuji saat menjalankannya. Setelah build berhasil, APK dapat diunduh dari artifact **DocScanToxi-debug-apk** di halaman run Actions. Untuk menjalankan tes perangkat sendiri, sambungkan perangkat atau nyalakan emulator lalu jalankan `./gradlew connectedDebugAndroidTest`.

## Struktur

- `app/src/main/java/.../data/` — penyimpanan privat, normalisasi foto, dan ekspor gambar.
- `pdf/` — edit teks PDF asli, penggabungan PDF, sesi privat, verifikasi hasil, dan cadangan.
- `zip/` — pembuatan ZIP, validasi dan ekstraksi ke folder penyedia dokumen.
- `office/` — edit DOCX/XLSX/PPTX asli, sesi privat dengan cadangan terverifikasi, dan ekspor OCR ke Office.
- `scan/` — pemindai ML Kit, kamera sistem, dan pemilih foto.
- `edit/` — koreksi perspektif, filter, dan penambahan teks pada gambar.
- `ocr/` — pengenalan dan penyuntingan teks.
- `ui/` — komponen tampilan bersama.
- `app/src/test/` dan `app/src/androidTest/` — unit test dan tes perangkat.

Pemindaian otomatis dan OCR menggunakan Google ML Kit. Editor PDF menggunakan [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) berlisensi Apache-2.0, beserta dependensi Bouncy Castle dan font Liberation Sans. Lisensi dan pemberitahuan komponen PDF tersedia di `app/src/main/assets/licenses/` serta menu **Tentang aplikasi > Lisensi PDF**. Tidak diperlukan akun atau server backend untuk aplikasi ini.
