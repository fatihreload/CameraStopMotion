# Motion Studio Pro — Tahap 1 (Fondasi)

## Cara menjalankan
1. Buka folder ini di Android Studio (Koala 2024.1+ / JDK 17). Biarkan Gradle sync (jika diminta membuat wrapper, setujui).
2. Jalankan di perangkat/emulator Android 10+ (minSdk 29).

## Sudah berfungsi di Tahap 1
- Home, Project Library (buat / ganti nama / duplikat / hapus), Editor, Export, Settings; Asset Library = placeholder "Belum tersedia"
- Impor video & foto dari Photo Picker (multi-select, tanpa izin storage), metadata, thumbnail
- Preview Media3 ExoPlayer, play/pause, scrub dengan menggeser timeline, zoom (cubit/tombol)
- Edit: split di playhead, trim (range slider), duplikat, hapus, geser urutan, undo/redo
- Auto-save (Room, debounce 500 ms + saat app ke background); proyek rusak ditangani tanpa crash
- Export Media3 Transformer: 480/720/1080p, kualitas, H.264/H.265, estimasi ukuran, progress, batal, simpan ke Movies/MotionStudioPro, bagikan

## Belum ada (tampil "belum tersedia")
Audio, teks, caption, voice over, canvas (T2) · efek, filter, adjust, transisi, keyframe (T3) · chroma key, mask, tracking, speed ramp, AI (T4)

## Batasan yang diketahui
- Kode belum pernah dikompilasi di lingkungan pembuatnya; kemungkinan ada error kecil saat build pertama (versi API Media3 1.4.1 bersifat eksperimental).
- Satu track utama (multi-layer menyusul). GIF diperlakukan sebagai gambar diam.
- Modul masih berupa package (`data`, `editor`, `export`, `ui`); dipecah ke modul Gradle terpisah di tahap berikutnya.
- URI Photo Picker bisa kehilangan izin setelah restart perangkat; proyek lama bisa gagal memuat media tersebut.

## Build tanpa Android Studio (hanya GitHub)
1. Buat repo GitHub, upload seluruh isi folder ini ke branch `main`.
2. Buka tab **Actions** → workflow **Build APK** (jalan otomatis saat push, atau klik *Run workflow*).
3. Setelah hijau, unduh artifact **MotionStudioPro-debug-apk** → ekstrak → install APK di HP.
4. Jika merah, buka log langkah "Build debug APK" dan kirim error-nya.
