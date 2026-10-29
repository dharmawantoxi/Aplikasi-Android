# DocScan Toxi

Aplikasi Android berbahasa Indonesia untuk memindai, merapikan, menyimpan, dan berbagi dokumen. Dibangun secara native dengan Kotlin.

## Fitur

- **Pindai otomatis** dengan pemindai dokumen ML Kit: mendeteksi tepi, mengoreksi perspektif, dan memindai beberapa halaman. Pengguna juga dapat mengambil foto dengan kamera sistem atau mengimpor foto.
- Simpan maksimal **25 halaman per dokumen**, cari dan ubah nama dokumen, tambah, urutkan, atau hapus halaman.
- **Edit PDF**: impor PDF yang sudah ada sebagai dokumen baru, lalu atur halaman seperti hasil pindai. Tambahkan satu baris teks per tindakan, pilih ukuran dan warna, dan atur posisinya di pratinjau.
- Edit halaman dengan menggeser empat sudut, memutar, dan memilih filter asli, abu-abu, atau hitam putih. Perubahan disimpan setelah menekan **Simpan**.
- Ekspor seluruh halaman sebagai satu **PDF** atau ekspor tiap halaman sebagai **JPG**. PDF atau JPG juga dapat dibagikan ke aplikasi lain. Halaman impor mempertahankan ukuran dan orientasi PDF asal; foto baru menggunakan A4.
- Kenali teks Latin (termasuk Bahasa Indonesia) dengan **OCR di perangkat**, lalu perbaiki, salin, atau simpan sebagai **TXT**. OCR tidak selalu akurat; periksa hasilnya. PDF yang dihasilkan berupa gambar dan bukan PDF dengan teks yang dapat dicari.
- Tema terang/gelap mengikuti pengaturan perangkat.

## Persyaratan dan batasan

- Android 7.0 (API 24) atau lebih baru.
- Pemindai otomatis memerlukan **Google Play Services**, perangkat yang didukung (termasuk RAM yang cukup), dan mungkin koneksi internet pada penggunaan pertama untuk mengunduh komponen pemindai. Bila tidak tersedia, pilih **Kamera biasa** atau **Impor foto**. Kamera biasa tidak melakukan deteksi tepi otomatis; sudut dapat diatur di editor setelahnya.
- OCR menggunakan model Latin yang disertakan dalam aplikasi dan dapat berjalan tanpa koneksi setelah aplikasi terpasang.
- Foto dan halaman PDF yang diimpor dinormalisasi menjadi gambar JPEG dengan sisi terpanjang maksimal 2.400 piksel. PDF impor dibatasi **100 MB dan 25 halaman**; PDF rusak atau yang memerlukan kata sandi tidak didukung. Impor dapat memerlukan ruang dan waktu untuk merender setiap halaman.
- **Batasan edit PDF:** impor merender setiap halaman menjadi gambar. Teks asli tidak dapat dipilih, dicari, atau diedit di tempat; tautan, formulir yang dapat diisi, lampiran, dan tanda tangan digital tidak dipertahankan. Kualitas gambar dan teks dapat berkurang. Teks baru juga menyatu dengan gambar setelah disimpan dan tidak dapat dipindahkan atau diedit secara terpisah. Gunakan aplikasi pengedit PDF lain jika perlu mempertahankan atau mengedit elemen-elemen tersebut.
- Impor tidak mengubah PDF sumber. Saat **Simpan PDF**, aplikasi menyarankan nama dengan akhiran **(edit)**; pilih nama atau lokasi baru dan jangan sengaja menimpa sumber jika ingin mempertahankannya. Ukuran halaman asal dipertahankan sejauh mungkin, dan disesuaikan jika dipotong atau diputar; foto yang ditambahkan menggunakan halaman A4 dengan margin.
- Dokumen disimpan di penyimpanan privat aplikasi, bukan di server aplikasi. Cadangan Android dinonaktifkan untuk privasi: **menghapus aplikasi akan menghapus dokumen lokal**. Gunakan **Simpan PDF**, **Simpan sebagai JPG**, atau **Simpan TXT** untuk membuat salinan yang tetap tersedia. Aplikasi penerima yang dipilih saat berbagi akan memperoleh salinan yang Anda bagikan.

## Cara menggunakan

1. Tekan **Mulai pindai** atau **Impor foto**. Jika pemindai otomatis tidak tersedia, pilih kamera biasa atau impor foto. Untuk PDF yang sudah ada, tekan **Edit PDF**, baca penjelasan batasannya, lalu pilih file.
2. Di halaman dokumen, tekan **Tambah** untuk menambah halaman foto. Ketuk halaman untuk memotong, memutar, atau memilih filter. Gunakan menu titik tiga untuk menyimpan JPG, mengubah urutan, dan menghapus halaman.
3. Tekan **Tambah teks** pada halaman, ketik teks, lalu ketuk atau geser pratinjau untuk memosisikannya. Tombol **Posisi** menyediakan pilihan posisi tanpa geser. Pilih ukuran dan warna, lalu tekan **Simpan**. Tambahkan baris lain dengan mengulangi langkah ini.
4. Tekan **Simpan PDF** dan pilih nama/lokasi baru, atau **Bagikan** untuk mengirim salinan PDF.
5. Tekan **Pindai teks** untuk membaca teks semua halaman. Koreksi hasilnya jika perlu, lalu salin atau simpan sebagai TXT.

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

Workflow GitHub Actions `Android CI` membangun APK debug, menjalankan unit test, dan menguji alur dasar pada emulator Android. Setelah build berhasil, APK dapat diunduh dari artifact **DocScanToxi-debug-apk** di halaman run Actions. Untuk menjalankan tes perangkat sendiri, sambungkan perangkat atau nyalakan emulator lalu jalankan `./gradlew connectedDebugAndroidTest`.

## Struktur

- `app/src/main/java/.../data/` — penyimpanan privat, normalisasi foto, dan ekspor.
- `scan/` — pemindai ML Kit, kamera sistem, dan pemilih foto.
- `edit/` — koreksi perspektif, filter, dan penambahan teks.
- `ocr/` — pengenalan dan penyuntingan teks.
- `ui/` — komponen tampilan bersama.
- `app/src/test/` — unit test untuk nama file, tata letak PDF, dan geometri potong.

Pemindaian otomatis dan OCR menggunakan Google ML Kit. Tidak diperlukan akun atau server backend untuk aplikasi ini.
