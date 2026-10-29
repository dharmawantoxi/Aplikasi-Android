# Persiapan rilis DocScan Toxi

**Status: belum siap dipublikasikan.** Ini adalah panduan persiapan, bukan bukti build produksi, hasil pengujian, atau persetujuan Google Play. Jangan unggah/publikasikan artefak sebelum semua poin yang berlaku selesai. Peraturan dan SDK pihak ketiga dapat berubah; periksa kembali menjelang rilis.

## Penghalang yang masih harus diselesaikan penerbit

- [ ] Tentukan nama penerbit dan email dukungan. Tinjau `PRIVACY_POLICY_DRAFT.md`, isi data yang belum ada, dan publikasikan kebijakan pada URL HTTPS publik yang aktif, dapat diakses global tanpa login, tidak dapat diedit oleh publik, dan bukan PDF.
- [ ] Masukkan URL tersebut ke `privacy_policy_url` dalam `app/src/main/res/values/strings.xml` **sebelum build**, pastikan tombol **Tentang aplikasi > Privasi dan data > Kebijakan privasi** membukanya, dan gunakan URL yang sama di Play Console. Ringkasan privasi di aplikasi bukan pengganti kebijakan lengkap.
- [ ] Audit data yang dikumpulkan/dibagikan seluruh SDK dan versi dependensi aktual, izin dalam **merged release manifest**, layanan Google Play, penyedia penyimpanan/kamera dan alur berbagi; lengkapi Data safety dan semua deklarasi Play Console sesuai keadaan sebenarnya. Jangan menganggap semua pemrosesan offline berarti tidak ada data diagnostik yang keluar: [Google menyatakan ML Kit dapat mengirim metrik dan menghubungi server untuk pembaruan](https://developers.google.com/ml-kit/terms).
- [ ] Buat dan amankan kunci penandatanganan yang sesuai, dan putuskan kompatibilitas update Play/direct **sebelum** rilis pertama (lihat bagian kunci di bawah). Jangan gunakan kunci debug. Siapkan cadangan kunci di lokasi aman dan simpan fingerprint sertifikat SHA-256.
- [ ] Jalankan build kandidat **release minified** dan seluruh unit test, verifikasi tanda tangan, dan uji hasil AAB/APK di perangkat sungguhan. Skrip build di bawah memverifikasi tanda tangan tetapi tidak menggantikan pengujian perangkat atau review kebijakan.
- [ ] Uji Android 7 (API 24) dan Android terbaru yang didukung; pengaturan bahasa, tampilan kecil/gelap, splash, kembali/rotasi, Google Play Services tersedia/tidak tersedia, pemindai dan kamera cadangan, foto, OCR, penyimpanan rendah, file provider lokal dan cloud, ekspor/berbagi, PDF/Office/ZIP, penolakan file tidak aman, cadangan dan pemulihan overwrite. Gunakan file uji, bukan dokumen satu-satunya. Uji instalasi baru **dan upgrade** dengan sertifikat yang benar dan `versionCode` lebih tinggi. Periksa file hasil di aplikasi pembaca lain.
- [ ] Audit lisensi semua dependensi dan pemberitahuan yang diwajibkan. Periksa bahwa fitur dan batasan yang diiklankan sesuai aplikasi. Siapkan ikon, screenshot perangkat yang nyata, deskripsi, kategori, rating konten, target audiens, kontak, serta deklarasi Play Console yang sesuai.
- [ ] Verifikasi semua library native dalam APK **dan APK split yang dibuat dari AAB** mendukung alignment ELF 16 KB; verifikasi zip alignment dan uji pada emulator/perangkat 16 KB. Perintah `zipalign` pada skrip hanya memeriksa alignment isi ZIP, **bukan** alignment ELF atau kompatibilitas runtime. Lihat [panduan Android 16 KB](https://developer.android.com/guide/practices/page-sizes).
- [ ] Selesaikan verifikasi identitas developer dan pendaftaran package serta sertifikat yang relevan sesuai [Android developer verification](https://developer.android.com/developer-verification). Sejak 30 September 2026 perlindungan mulai berlaku untuk pemasangan dari toko yang berpartisipasi di Indonesia, Brasil, Singapura, dan Thailand pada perangkat Android tersertifikasi; periksa persyaratan distribusi langsung dan perluasan berikutnya.
- [ ] Untuk akun personal Play yang dibuat setelah 13 November 2023, ikuti [syarat closed testing](https://support.google.com/googleplay/android-developer/answer/14151465): minimal 12 tester tetap opt-in selama 14 hari berturut-turut sebelum mengajukan akses produksi, jika berlaku. Ikuti juga verifikasi akun dan arahan Play Console lainnya.

Aplikasi saat ini memakai `applicationId` `com.dharmawantoxi.docscan`, `minSdk` 24, `targetSdk` 36. Jangan ubah application ID setelah publikasi karena akan dianggap aplikasi lain. [Syarat Google Play saat ini](https://support.google.com/googleplay/android-developer/answer/11926878) mensyaratkan API 36 untuk aplikasi baru dan update sejak 31 Agustus 2026; validasi lagi saat mengirim.

## Kunci Play dan APK langsung

Android hanya memperbarui instalasi yang sertifikat penandatanganannya kompatibel. **Kunci upload AAB bukan selalu kunci yang dipakai Google menandatangani APK untuk pengguna.** Jika Play membuat app signing key sendiri, APK yang Anda tandatangani dengan upload key umumnya **tidak bisa** memperbarui instalasi Play, atau sebaliknya. Instal ulang dengan tanda tangan berbeda dapat mengharuskan uninstall yang **menghapus dokumen lokal**.

Jika update lintas kanal merupakan kebutuhan, rencanakan hal itu sebelum rilis pertama: [konfigurasikan Play App Signing dengan app signing key milik sendiri](https://developer.android.com/studio/publish/app-signing) dan tandatangani APK langsung dengan app signing key yang sama; gunakan upload key terpisah untuk AAB bila diinginkan. Pastikan kebijakan/kondisi Play Console dan migrasi kunci berlaku untuk akun Anda, verifikasi fingerprint sertifikat rilis Play dan APK, dan uji upgrade kedua arah. Jika memilih kunci yang dikelola Google tanpa salinan sendiri, jangan menjanjikan upgrade lintas kanal. Jangan unggah app signing key ke CI jika dapat melakukan penandatanganan APK secara lokal di mesin tepercaya.

## Build lokal bertanda tangan

Perlu Python 3, JDK 17, SDK Android Platform 36, build-tools (dengan `apksigner` dan `zipalign`) dan akses ke repositori Gradle. Gunakan keystore milik penerbit, bukan yang dibuat untuk pengujian. Jika belum punya, buat pada mesin aman dengan Android Studio atau `keytool -genkeypair` secara interaktif, dan simpan di luar repositori. Jangan kirim kata sandi/keystore melalui chat, Git, artefak build, atau riwayat perintah. Setel SDK lewat `ANDROID_HOME`. Contoh Bash:

```bash
cd DocScanToxi
export DOCSCAN_KEYSTORE_PATH=/lokasi/aman/release.keystore  # path absolut
export DOCSCAN_KEY_ALIAS=nama_alias
export DOCSCAN_VERSION_CODE=1       # harus lebih tinggi daripada SEMUA versionCode yang pernah diunggah untuk update
export DOCSCAN_VERSION_NAME=1.0.0
read -rs -p 'Kata sandi keystore: ' DOCSCAN_STORE_PASSWORD; echo
read -rs -p 'Kata sandi kunci: ' DOCSCAN_KEY_PASSWORD; echo
export DOCSCAN_STORE_PASSWORD DOCSCAN_KEY_PASSWORD
./tools/build_release.sh play-aab   # AAB untuk Play, gunakan upload key
# Ganti variabel keystore/password/alias ke app signing key jika berbeda, lalu:
./tools/build_release.sh direct-apk # APK langsung
unset DOCSCAN_STORE_PASSWORD DOCSCAN_KEY_PASSWORD DOCSCAN_KEYSTORE_PATH DOCSCAN_KEY_ALIAS
```

Skrip menolak build biasa jika `privacy_policy_url` kosong atau bukan URL HTTPS. Pemeriksaan ini hanya memeriksa format, **bukan** akses publik atau kelengkapan kebijakan. Untuk pengujian internal sebelum URL tersedia, set `export DOCSCAN_ALLOW_INCOMPLETE_POLICY=1`; output diberi berkas `TEST_ONLY` dan **jangan dipublikasikan**. Hapus variabel itu sebelum build produksi (`unset DOCSCAN_ALLOW_INCOMPLETE_POLICY`).

Versi 1 hanya contoh untuk aplikasi yang belum pernah mengunggah build dengan kode 1; sesuaikan dengan riwayat di Play Console dan kanal lain. `versionName` menerima huruf/angka serta `.`, `_`, `+`, `-`. Skrip menjalankan `testReleaseUnitTest` dan `bundleRelease` atau `assembleRelease`, memverifikasi AAB dengan `jarsigner` atau APK dengan `apksigner` dan `zipalign -P 16`, lalu menaruh artefak dan SHA-256 di `release-output/`. Pemetaan R8 `<nama-artefak>.mapping.txt` disalin bila ada; arsipkan secara aman untuk diagnosa crash. `release-output/` dan file kunci umum diabaikan Git. Tidak ada jaminan verifikasi tersebut menemukan semua masalah kualitas, keamanan, atau kompatibilitas. **`./gradlew assembleRelease` langsung tanpa konfigurasi signing bisa menghasilkan APK unsigned**; jangan distribusikan itu sebagai rilis.

## Workflow GitHub Actions manual

`.github/workflows/android-release.yml` hanya berjalan melalui **Actions > Android release (manual) > Run workflow** pada branch yang memuat workflow. Ia **tidak** memublikasikan ke Play atau membuat GitHub Release. Pemilik memilih satu kanal per run, dan mengisi `version_code` serta `version_name`; `test_only` hanya untuk kandidat internal (memberi marker larangan publikasi), bukan produksi; jalankan terpisah untuk AAB dan APK bila diperlukan. Artefak bertanda tangan dan mapping tersedia selama 7 hari; perlakukan mapping sebagai sensitif. Batasan ini bukan persetujuan untuk menjalankan workflow: mulai hanya bila pemilik menginstruksikan.

Pada **Settings > Secrets and variables > Actions**, pemilik dapat mengatur empat repository/environment secrets untuk kanal yang dipilih. Base64 harus berisi file keystore secara utuh (tanpa dipotong); buat hanya pada mesin tepercaya. Keystore diterjemahkan ke folder sementara runner dengan izin terbatas dan diupayakan dihapus saat langkah selesai; penghentian paksa atau kegagalan runner dapat mengganggu pembersihan. Hanya jalankan pada branch dan runner tepercaya: perubahan skrip pada branch yang dipilih dapat membaca secrets. Periksa kebijakan keamanan runner/GitHub Anda; bila ragu, buat rilis secara lokal.

| AAB (upload key) | APK langsung (app signing key) | Isi |
|---|---|---|
| `PLAY_UPLOAD_KEYSTORE_BASE64` | `DIRECT_RELEASE_KEYSTORE_BASE64` | Keystore dalam Base64 |
| `PLAY_UPLOAD_STORE_PASSWORD` | `DIRECT_RELEASE_STORE_PASSWORD` | Kata sandi keystore |
| `PLAY_UPLOAD_KEY_ALIAS` | `DIRECT_RELEASE_KEY_ALIAS` | Alias kunci |
| `PLAY_UPLOAD_KEY_PASSWORD` | `DIRECT_RELEASE_KEY_PASSWORD` | Kata sandi kunci |

Jangan masukkan kunci produksi langsung ke secrets hanya demi kemudahan jika kunci bisa tetap offline. Build debug/emulator terpisah di workflow `Android CI` juga manual. Rilis AAB setelah audit harus diunggah melalui Play Console dan melewati review; APK langsung harus didistribusikan melalui kanal tepercaya. Tidak ada tindakan distribusi otomatis di repositori ini.
