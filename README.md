# Stop Motion Pro 5.0 – Premium Landscape

Pembaruan 5.0: Quality, Aspect Ratio, Foreground, dan Filter dipindahkan ke toolbar khusus di bagian atas layar. Ditambahkan tombol **New Project** untuk membuat project baru dengan nama sendiri. Project baru mengosongkan timeline setelah konfirmasi bila masih ada frame, lalu mengembalikan pengaturan kualitas/filter/rasio/foreground ke nilai awal.

Fitur tetap: kamera landscape, pengambilan dan pengelolaan frame, onion-skin, chroma key, timer, foreground PNG/JPG dengan transparansi, filter Original/B&W/Sepia/Vintage/Cool/Warm, aspect ratio 16:9/4:3/1:1/9:16, quality SD/HD/HQ/4K, dan ekspor MP4.

## Catatan kualitas
Pilihan SD/HD/HQ/4K menentukan resolusi render frame dan ekspor. Preview internal kamera diproses pada 1024×576, sehingga HQ dan 4K dapat berupa pembesaran (upscaling), bukan detail optik native 1080p/4K. Dukungan encoding 4K bergantung pada perangkat Android.

## Build APK
Unggah seluruh isi folder project ke branch `main` di GitHub. Jalankan workflow **Build APK** dari tab Actions, lalu unduh artifact `StopMotionStudio-apk`.


## Kontrol pemutaran (perbaikan)
- **Play / Lanjut** memulai animasi dari awal atau melanjutkan dari frame terakhir setelah jeda.
- **Jeda** menghentikan sementara tanpa mengatur ulang posisi frame.
- **Stop** menghentikan pemutaran dan mengembalikan preview ke frame pertama.
