# AnimeGo Native

rewrite native AnimeGo berbasis Kotlin + Jetpack Compose. project ini tidak memakai HTML, CSS, JavaScript, WebView, atau source Java.

## requirement build

- Android Studio versi yang mendukung Android Gradle Plugin 9.1.0
- JDK 17
- Android SDK Platform 37 dan Build Tools 37.x
- koneksi internet saat build pertama untuk mengunduh Gradle, plugin, dan dependency

## stack

- Kotlin 2.4.21, Android Gradle Plugin 9.1.0, Gradle 9.3.1, JVM 17
- Jetpack Compose, Material 3, Navigation Compose
- Lucide Compose (`icons-lucide-cmp`) untuk icon vector
- OkHttp 5 untuk WordPress REST API AnimeGo
- Kotlin SQLiteOpenHelper; nama file, versi database, tabel, kolom, dan index mengikuti schema `animego.db` versi 3 dari aplikasi lama
- Preferences DataStore 1.2.1 untuk pengaturan
- Coil 3 untuk poster dan thumbnail
- AndroidX Media3 ExoPlayer untuk stream dan video offline
- WorkManager + CoroutineWorker untuk unduhan background dan retry

## build lokal

```bash
./gradlew :app:assembleDebug
```

script `gradlew` dan `gradlew.bat` di project ini merupakan bootstrap ringan: keduanya mengunduh Gradle 9.3.1 ke cache user ketika belum tersedia, lalu menjalankan build. Versinya disamakan dengan Gradle yang digunakan GitHub Actions dan persyaratan Android Gradle Plugin 9.1.0.

APK debug akan berada di:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## build dengan GitHub Actions

workflow ada di `.github/workflows/android.yml`. workflow otomatis berjalan pada `push`, `pull_request`, atau `workflow_dispatch`.

1. ekstrak ZIP project ini.
2. push seluruh isi folder `AnimeGoNative` ke root repository GitHub (folder `.github` juga harus ikut).
3. buka tab **Actions** di repository.
4. pilih workflow **android build** dan tunggu job selesai.
5. buka hasil run lalu unduh artifact `animego-debug-apk`.

APK tersedia sebagai artifact selama 14 hari. build menggunakan JDK 17, Gradle 9.3.1, Android SDK Platform 37, dan task `:app:assembleDebug`.

contoh push dari Termux:

```bash
git init
git add .
git commit -m "rewrite AnimeGo native Kotlin"
git branch -M main
git remote add origin https://github.com/USERNAME/REPOSITORY.git
git push -u origin main
```

ganti URL remote dengan repository milik lu.

## fitur yang dipindahkan ke native

- beranda dengan rail anime ongoing, top, movie, dan completed
- katalog/filter latest, ongoing, completed, movie, top, random, dan oldest
- pencarian, suggestion, pencocokan slug persis, ranking hasil, dan pagination
- detail anime, metadata, synopsis, genre, episode list, play, dan download
- direktori genre, detail genre, jadwal mingguan per hari, serta pagination
- favorit, riwayat tontonan, resume posisi, hapus item, dan bersihkan riwayat
- Media3 player: play/pause, seek ±10 detik, volume/mute, playback speed, episode sebelumnya/berikutnya, dan auto-next
- mini-player lintas halaman dengan seekbar, timestamp, skip ±10 detik, play/pause, mute, speed, dan episode sebelumnya/berikutnya; PlaybackService juga menyimpan progress tiap 5 detik ketika UI player tidak sedang terbuka
- fullscreen portrait dan landscape
- floating overlay player native dengan drag, pinch-to-resize, kembali ke layar player, seekbar + timestamp, skip ±10 detik, play/pause, mute, playback speed, episode sebelumnya/berikutnya, dan close
- streaming online, membuka URL stream lewat aplikasi eksternal, dan playback file lokal
- antrean download, notifikasi foreground, HTTP Range/resume via file `.part`, retry, hapus file, dan pemulihan antrean ketika boot/update aplikasi
- pengaturan tema gelap, warna dinamis, optimasi baterai, pengelolaan aktivitas latar, dan izin floating overlay

## kompatibilitas data

- `applicationId` tetap `app.animego.inc`.
- nama database tetap `animego.db`, versi 3; tabel `favorites`, `history`, dan `downloads` mengikuti struktur sumber lama.
- path unduhan tetap menggunakan direktori app-specific dari `getExternalMediaDirs()` dan pola folder judul/anime-id/nama-file.
- agar APK dapat meng-upgrade aplikasi yang sudah terpasang, sertifikat signing harus sama. artifact debug GitHub tidak otomatis bisa menggantikan APK release yang ditandatangani key berbeda. untuk memasang debug pertama kali, uninstall versi lama bila signature tidak cocok; uninstall akan menghapus data app-private, jadi backup dulu database/file penting.

## batasan integrasi API

aplikasi sumber memiliki fallback fetch melalui WebView apabila server mengembalikan halaman HTML non-JSON. fallback itu sengaja tidak dipertahankan karena requirement rewrite adalah tanpa WebView sama sekali. implementasi Kotlin memakai OkHttp, browser-like headers, origin warm-up, cookie handling, dan retry. bila proteksi server menolak request native dan mengirim HTML challenge, API tetap perlu penyesuaian dari sisi server; aplikasi tidak menyisipkan WebView tersembunyi.

## catatan perbaikan player (6 november 2026)

- floating player tidak lagi membuat `ComposeView` dari dalam `Service`. overlay dibangun dengan Android Views native, sehingga tidak bergantung pada `ViewTreeLifecycleOwner` milik Activity.
- kegagalan `WindowManager.addView()` ditangani dan dibersihkan, bukan dibiarkan menjatuhkan proses aplikasi.
- tombol floating player yang membuka Settings izin overlay akan otomatis melanjutkan saat pengguna kembali dan izin sudah aktif.
- surface video dipindahkan sementara dari `PlayerView` layar utama ke `PlayerView` overlay, lalu dikembalikan saat overlay ditutup. target view baru dipasang sebelum view lama dilepas agar perpindahan surface tidak melewati keadaan tanpa output.
- pada Android 11+, konteks jendela overlay dibuat dari `createDisplayContext(defaultDisplay)` terlebih dahulu, baru `createWindowContext(TYPE_APPLICATION_OVERLAY)`. service context biasa tidak diasumsikan sudah terikat ke display.
- layout popup sekarang berupa satu jendela video 16:9 yang ringkas; tidak ada baris kontrol di luar/bawah video. `PlayerView` memakai `TextureView`, dan semua kontrol ditempatkan sebagai layer semi-transparan di atas video: judul/episode, mute, kecepatan, buka player, tutup, episode sebelumnya/berikutnya, skip ±10 detik, play/pause, seekbar, serta timestamp.
- ketuk area video untuk menyembunyikan/menampilkan seluruh layer kontrol. Popup mulai di area kanan-bawah, dapat digeser dengan menahan hampir semua area jendela; sentuhan horizontal pada seekbar tetap diprioritaskan untuk scrubbing, sedangkan gesture vertikal dari area seekbar dapat menggeser jendela.
- pinch dengan dua jari di area popup memperbesar atau memperkecil ukuran jendela sambil mempertahankan rasio 16:9. Ukuran minimum dijaga agar kontrol masih dapat digunakan.
- fullscreen mengunci orientasi `LANDSCAPE`/`PORTRAIT` secara eksplisit saat tombol ditekan, mempertahankan state lewat configuration change, dan tidak memulihkan orientasi lama ketika Activity sedang direkreasi karena perubahan konfigurasi.
- seekbar memakai posisi preview terpisah agar polling posisi playback tidak menggeser slider ketika pengguna sedang menggeser.
- layar player portrait dan fullscreen menyediakan seekbar, timestamp, skip ±10 detik, play/pause, mute, kecepatan, navigasi episode, dan akses floating player.
- mini-player dan popup menyediakan seekbar, timestamp, skip ±10 detik, play/pause, mute, kecepatan, serta navigasi episode.
- notifikasi playback menampilkan progress playback dan menyediakan aksi episode sebelumnya/berikutnya, mundur/maju 10 detik, play/pause, dan tutup.

## catatan pengujian

source dan struktur proyek diperiksa secara statis. environment penyusunan ZIP ini tidak menyediakan Android SDK/Gradle distribution dan tidak dapat mengakses dependency repository, jadi APK tidak dapat dikompilasi atau diuji pada perangkat di sini. validasi kompilasi akhir tetap perlu dijalankan melalui GitHub Actions dan pengujian runtime di perangkat Android.
