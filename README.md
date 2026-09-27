# Pustaka — WebView + Jembatan Native Kotlin (tanpa Flask/server)

Proyek Android Studio ini menggantikan **seluruh** `_Library.py` (Flask) dengan
`WebView` + `addJavascriptInterface`. Tidak ada `app.run()`, tidak ada port 5000,
tidak ada request/response HTTP — WebView memuat `assets/www/index.html` secara
lokal (`file:///android_asset/...`) dan JavaScript di halaman itu memanggil
method Kotlin **langsung, in-process**, lewat `window.Android.xxx(...)`.

## Cara build APK KALAU CUMA PUNYA HP (tanpa laptop/Android Studio)
Proyek ini sudah dilengkapi `.github/workflows/build.yml` supaya APK bisa
di-compile otomatis oleh server GitHub (gratis), lalu kamu tinggal download
hasil APK-nya lewat browser HP. Caranya:

1. Buat akun GitHub (gratis) lewat browser HP kalau belum punya.
2. Install **Termux** (dari F-Droid, bukan Play Store — versi Play Store sudah
   tidak dilanjutkan) dan buka.
3. Di Termux, jalankan:
   ```
   pkg install git unzip -y
   termux-setup-storage
   ```
4. Pindahkan `PustakaApp.zip` yang sudah kamu download ke folder yang
   Termux bisa akses (biasanya sudah ada di `~/storage/downloads/`), lalu:
   ```
   cd ~
   unzip ~/storage/downloads/PustakaApp.zip
   cd PustakaApp
   git init
   git add .
   git commit -m "init"
   ```
5. Di GitHub (browser), buat repository baru KOSONG (jangan centang "Add README"),
   misal namanya `PustakaApp`. Buat **Personal Access Token** di
   Settings → Developer settings → Personal access tokens (pilih scope `repo`).
6. Kembali ke Termux, hubungkan dan push:
   ```
   git branch -M main
   git remote add origin https://github.com/USERNAME/PustakaApp.git
   git push -u origin main
   ```
   Saat diminta password, tempel **token** yang dibuat di langkah 5 (bukan password akun).
7. Buka `github.com/USERNAME/PustakaApp/actions` di browser HP. Akan ada
   proses "Build APK" berjalan otomatis (sekitar 2–5 menit).
8. Setelah selesai (centang hijau), tap workflow run itu → scroll ke bagian
   **Artifacts** → download `pustaka-debug-apk` (berupa .zip berisi .apk).
9. Ekstrak zip itu di HP, lalu install APK-nya (aktifkan dulu
   "Izinkan dari sumber ini" untuk browser/file manager di pengaturan HP).

Alternatif tanpa Termux sama sekali: buat repo GitHub baru lewat browser, lalu
di halaman repo pakai tombol **Add file → Create new file**, ketik path lengkap
tiap file (mis. `app/src/main/java/com/nie/pustaka/MainActivity.kt`) — GitHub
otomatis bikin foldernya — lalu paste isi filenya satu per satu. Lebih lama,
tapi tidak perlu install app tambahan sama sekali.

## Cara pakai (kalau nanti punya akses ke Android Studio)
1. Buka folder ini di Android Studio (File → Open).
2. Sync Gradle (koneksi internet dibutuhkan sekali untuk download dependency AndroidX).
3. Run ke device/emulator. Tidak perlu Termux/Python/Flask sama sekali.
4. Untuk pilihan "Eksternal Storage" (seperti path `/storage/...` di versi Python),
   aplikasi akan minta izin **"Kelola semua file"** lewat halaman Settings sistem
   (`Android.requestManageStoragePermission()` dari JS) — ini normal untuk app
   pribadi yang di-sideload, tidak bisa diotomatisasi penuh karena kebijakan
   Scoped Storage Android 11+.

## Struktur file
```
app/src/main/java/com/nie/pustaka/
  DatabaseHelper.kt      -> pengganti init_db()/get_db()  (skema SQLite identik)
  FileStorageManager.kt  -> pengganti get_storage_base()/find_file()
  WebAppInterface.kt     -> pengganti SEMUA @app.route(...)  (dipanggil dari JS)
  MainActivity.kt        -> pengganti app.run(...)  (setup WebView, file picker, FileProvider)
app/src/main/assets/www/
  index.html, style.css, app.js  -> pengganti seluruh render_template_string()
```

## Pemetaan route Flask → method Kotlin
| Flask route                          | Method di `WebAppInterface.kt`        |
|---------------------------------------|----------------------------------------|
| `GET /`                               | `listCollections(...)`                |
| `GET/POST /add`                       | `addCollection(json)`                 |
| `GET/POST /edit/<id>`                 | `editCollection(id, json)`            |
| `GET /delete/<id>`                    | `deleteCollection(id)`                |
| `POST /update_item_categories/<id>`   | `updateItemCategories(id, fav, catIds)` |
| `GET/POST /playlists`                 | `listCategories()` + `addCategory()`  |
| `GET /delete_category/<id>`           | `deleteCategory(id)`                  |
| `GET /detail/<id>`                    | `getDetail(id)`                       |
| `POST /add_chapter/<id>`              | `pickChapterSourceFolder()` + `listChapterFiles()` + `addChapter(id, json)` (referensi saja) |
| `GET/POST /edit_chapters/<id>`        | `editChapters(json)`                  |
| `GET /delete_chapter/<id>/<coll>`     | `deleteChapter(id)`                   |
| `POST /api/add_bookmark`              | `addBookmark(chId, collId, page)`     |
| `GET /bookmarks/<coll>`               | `listBookmarks(coll)`                 |
| `GET /delete_bookmark/<id>/<coll>`    | `deleteBookmark(id)`                  |
| `GET /media/uploads/<file>`           | `getCoverUrl(path)` → `LocalContentInterceptor` |
| `GET /view_file/<file>`               | `getChapterRawUrl(file)` (dgn HTTP Range, dipakai `<video>`) |
| `GET /cbz_image/<file>/<page>`        | `getCbzPageUrl(file, page)` → `LocalContentInterceptor` |
| *(baru, ganti pdf.js)*                | `getPdfPageUrl(file, page)` → render native `PdfPageRenderer` |
| `GET /read/<file>`                    | `renderReader()` di `app.js` + `getChapterInfo()` |

Upload gambar cover (`<input type="file">` browser) diganti `Android.pickImage()`,
yang membuka file picker native Android lalu mengirim isi file sebagai base64 balik
ke JS lewat `window.onNativeFilePicked` (cover berukuran kecil, jadi ini masih aman).

**Upload chapter TIDAK lagi lewat cara di atas.** File chapter (pdf/txt/mp4/cbz)
tidak pernah dibaca-penuh-ke-memori atau disalin ke storage app -- itulah yang
sebelumnya membuat penyimpanan app membengkak dan menyebabkan layar hitam saat
mengunggah file besar. Sekarang: user memilih SATU "Folder Chapter" sekali lewat
System Folder Picker (`Android.pickChapterSourceFolder()`, pakai SAF/
`ACTION_OPEN_DOCUMENT_TREE`), lalu setiap kali menambah chapter, user cukup
menjelajah folder itu di dalam app (`Android.listChapterFiles(subPath)`) dan
memilih file yang SUDAH ada di sana. Yang disimpan ke database hanyalah path
relatif terhadap folder itu (`Android.addChapter(collId, {ch_name, file_relpath})`)
-- bukan isi filenya. Saat dibaca (reader/video), app membaca langsung dari
folder itu (streaming, termasuk dukungan Range untuk seek video), lewat
`FileStorageManager.ChapterSource` yang otomatis memilih akses `java.io.File`
langsung (kalau foldernya ada di storage utama) atau lewat `DocumentFile`/SAF
(kalau di kartu SD lepasan yang tidak bisa dipetakan ke path asli).

## Reader sekarang sudah 1:1 dengan versi Flask
- **PDF**: dirender native pakai `android.graphics.pdf.PdfRenderer` (lihat
  `PdfPageRenderer.kt`) — per halaman jadi PNG, ditampilkan lewat mode baca
  yang SAMA dengan CBZ (fit height/width/full, scroll vertical/horizontal
  RTL-LTR/paginated, thumbnail navigator, page slider, fullscreen). Tidak
  perlu bundling `pdf.js` sama sekali.
- **CBZ**: mode baca lengkap (fit & scroll settings, thumbnail nav, slider,
  lazy-load per halaman) di-port persis dari `renderReader()` versi Flask.
- **MP4**: video diputar lewat URL lokal (`getChapterRawUrl`) yang mendukung
  **HTTP Range** (lihat `LocalContentInterceptor.kt`), jadi seek/scrub di
  video tetap berfungsi normal seperti browser, bukan cuma play dari awal.
- **TXT/Novel**: styling (font Georgia, ukuran, warna) & bookmark posisi
  scroll dibuat identik dengan versi asli.
- Semua file (cover, halaman cbz/pdf, video) sekarang dilayani lewat
  `WebViewClient.shouldInterceptRequest` ke domain virtual
  `https://pustaka.internal/...` — **bukan base64** lagi (lebih hemat memori
  untuk file besar) dan **bukan server jaringan sungguhan** (tidak ada
  socket/port yang dibuka, semuanya in-process).

## Riwayat penyisiran kesesuaian dgn _Library.py
Sudah disisir baris demi baris terhadap versi Flask sebanyak 3 kali, khusus
mencari tombol yang terlihat ada tapi tidak berfungsi. Yang ditemukan &
diperbaiki:

- **BUG PENTING #1**: tombol buka bacaan dari halaman **🔖 Penanda Halaman selalu
  gagal** dengan pesan "File Tidak Ditemukan", meskipun file & penandanya valid.
  Sebabnya ada di router SPA (`route()` di app.js): saat memecah hash URL jadi
  segmen path, parameter `?page=N` yang ditambahkan link penanda ikut menempel
  di nama file (karena query string belum dipisah sebelum `hash.split('/')`),
  sehingga `Android.getChapterInfo()` mencari file dengan nama yang salah.
  Sudah diperbaiki: query string dipisah dulu sebelum path di-parse. Tombol
  buka bacaan biasa (bukan dari penanda) tidak kena bug ini karena linknya
  tidak membawa `?page=`.
- **BUG PENTING #2**: tombol 🔖 (Tandai Halaman) dan ⚙️ (Pengaturan) di pojok
  kanan atas reader PDF/CBZ berpotensi salah sasaran saat dipencet (area tap
  dua tombol itu bisa saling tumpang tindih/menyempit tak terduga). Sebabnya:
  CSS Android (`style.css`) menerapkan `width:100%` ke SEMUA elemen `<button>`
  secara default (`input, select, textarea, button {...}`), sedangkan versi
  Flask aslinya HANYA menerapkan itu ke `input, select, textarea` (TIDAK
  termasuk `button`) -- jadi di Flask tombol ikon tetap kecil alami, tapi di
  Android dua tombol ikon itu berebut lebar 100% di dalam wrapper yang sama.
  Sudah diperbaiki dengan `width:auto` eksplisit pada kedua tombol tersebut
  (dan tombol 🔖 di reader TXT).
- **BUG PENTING #3**: "Toggle Layar Penuh" tidak benar-benar layar penuh --
  status bar & navigation bar Android tetap tampil. Sebabnya: tombol itu hanya
  memanggil Fullscreen API JavaScript standar (`requestFullscreen()`), yang di
  BROWSER SUNGGUHAN memang menyembunyikan semua chrome, tapi di dalam WebView
  milik app native API itu tidak punya akses ke status bar/navigation bar
  sama sekali. Sudah diganti perintah native Android (immersive mode lewat
  `WindowInsetsControllerCompat`) yang dipicu tombol yang sama lewat
  `Android.toggleFullscreen()`. Router SPA juga otomatis memanggil
  `Android.exitFullscreen()` saat pindah dari reader ke halaman lain, supaya
  mode layar penuh tidak "terbawa" ke daftar/detail/dst.
- Ekstensi `.zip` sempat ikut diperlakukan sebagai `.cbz` di reader -- versi Flask
  hanya mengenali `.cbz` secara eksplisit. Sudah disamakan: hanya
  `.pdf/.txt/.mp4/.cbz` yang didukung, sama seperti form upload Flask.
- `HostCallbacks.openExternalFile` sudah diimplementasikan di `MainActivity.kt`
  tapi TIDAK PERNAH dipanggil dari mana pun (dead code). Sudah disambungkan
  sebagai fallback saat reader menemui ekstensi tak dikenal.
- Teks error "File Tidak Ditemukan" kurang satu baris dibanding versi Flask --
  sudah disamakan persis.
- Kartu daftar koleksi kehilangan fallback `episodes`/`'-'` untuk kolom progress
  -- sudah disamakan.
- `<img src="">` pada cover kosong bisa memicu WebView memuat ulang halaman --
  diganti data-URI transparan.
- Beberapa nilai CSS reader meleset beberapa piksel dari nilai asli Flask --
  disamakan.

Sudah dicek juga (tidak ditemukan masalah): setiap `Android.xxx()` yang
dipanggil dari app.js dicocokkan satu per satu dengan method yang benar-benar
ada di `WebAppInterface.kt` (tidak ada yang salah nama/hilang), setiap `id`
yang dipanggil `getElementById()` dicocokkan dengan elemen yang benar-benar
dirender, callback native (`onNativeFilePicked`/`onNativeFolderPicked`)
dicocokkan namanya persis di kedua sisi Kotlin & JS, dan semua tombol
berpasangan di dalam modal (pakai `flex:1`) sudah aman dari bug `width:100%`
karena flex-basis menang atas width dalam perhitungan layout flex.

**Penyisiran ke-4** -- fokus khusus mencari fitur yang tampak berfungsi di browser
biasa (tempat app.js ini awalnya ditulis/diuji) tapi diam-diam TIDAK berfungsi
penuh di dalam WebView aplikasi native:
- **BUG PENTING #4**: `MainActivity` tidak pernah memasang `WebChromeClient`.
  Tanpa itu, WebView Android SECARA DIAM-DIAM mengabaikan `window.alert()` (tidak
  menampilkan apa-apa) dan otomatis membatalkan `window.confirm()` (selalu
  balikan `false` tanpa pernah bertanya ke user) -- beda dari browser biasa
  tempat keduanya tampil sebagai dialog normal. Akibatnya semua tombol **Hapus**
  (kategori, koleksi, chapter, bookmark) dan semua pesan validasi/notifikasi
  lewat `alert()` terlihat seperti tidak berbuat apa-apa saat ditekan. Sudah
  diperbaiki dengan `WebChromeClient` + `AlertDialog` native di `onJsAlert`/
  `onJsConfirm`.
- **BUG PENTING #5**: Urutan halaman CBZ bisa salah. Versi Flask (`cbz_image`)
  eksplisit mengurutkan nama file gambar di dalam arsip (`sorted(...)`), tapi
  `FileStorageManager.cbzPageBytes/cbzPageCount` membaca sesuai urutan mentah
  fisik di dalam zip -- kalau arsip CBZ tidak disimpan berurutan, halaman jadi
  berantakan dibanding versi Python. Sudah diperbaiki: mengurutkan nama entri
  secara abjad dulu (persis `sorted()` Python) sebelum membaca isinya.
- **BUG PENTING #6**: Opsi "Eksternal Storage" untuk cover selalu diam-diam
  gagal/fallback ke Internal tanpa penjelasan apa pun ke user. Fungsi bridge
  `hasManageStoragePermission()`/`requestManageStoragePermission()` sudah ada
  sejak awal tapi TIDAK PERNAH dipanggil dari `app.js` (dead code, mirip pola
  `openExternalFile` yang ditemukan di penyisiran sebelumnya) -- di Python opsi
  ini selalu berhasil karena akses filesystem penuh; di Android butuh izin
  runtime yang tidak pernah diminta. Sudah disambungkan: form Tambah/Edit
  sekarang menampilkan peringatan + tombol "Berikan Izin" saat opsi Eksternal
  dipilih tanpa izin, dan statusnya otomatis diperbarui saat app di-resume
  (`window.onAppResumed`, dipanggil dari `MainActivity.onResume()`).
- **BUG PENTING #7**: `hasManageStoragePermission()` selalu balikan `true`
  untuk SEMUA versi Android di bawah 11, padahal `WRITE_EXTERNAL_STORAGE`
  adalah dangerous permission yang di Android 7-9 (API 24-28) wajib diminta
  lewat dialog runtime (bukan cukup dideklarasikan di manifest) dan tidak
  otomatis diberikan saat instal -- bisa membuat penyimpanan ke Eksternal
  Storage gagal (`SecurityException`) di rentang versi itu meski kode mengira
  sudah punya akses. Sudah diperbaiki: cek `ContextCompat.checkSelfPermission`
  yang sesungguhnya untuk SDK<30, dan `requestManageStoragePermission()`
  sekarang memicu dialog izin runtime biasa (bukan halaman Settings, yang
  hanya berlaku mulai Android 11) di rentang versi itu.
- Ditambahkan juga `android:requestLegacyExternalStorage="true"` di manifest --
  tanpa ini, khusus di Android 10 (API 29), Scoped Storage tetap aktif secara
  default walau `WRITE_EXTERNAL_STORAGE` sudah diberikan, sehingga menulis ke
  folder Eksternal Storage pilihan user (path bebas di luar folder app) tetap
  bisa gagal diam-diam. Diabaikan otomatis di Android 11+ (di sana
  `MANAGE_EXTERNAL_STORAGE` yang berlaku).

Satu perbedaan yang SENGAJA dipertahankan (bukan bug): `PRAGMA foreign_keys = ON`
diaktifkan di `DatabaseHelper.onOpen()`, sedangkan koneksi sqlite3 Python TIDAK
pernah mengaktifkan pragma ini, sehingga `ON DELETE CASCADE` di skema Flask
sebenarnya tidak pernah benar-benar berjalan di sana (chapter/bookmark/kategori
yang koleksinya dihapus akan menumpuk sebagai baris yatim). Di versi Android ini
cascade-nya benar-benar berjalan -- lebih rapi, bukan regresi.

## Yang masih perlu kamu perhatikan
- **Folder Chapter**: pilih folder ini SEKALI dari dalam app (tombol "+ TAMBAH" di
  halaman detail akan memintanya kalau belum diatur). Kalau kamu memindahkan atau
  menghapus file itu lewat File Manager di luar app, chapter yang bersangkutan
  akan tampil sebagai "File Tidak Ditemukan" -- ini normal karena app memang
  hanya membaca referensi, bukan menyimpan salinannya sendiri.
- **SD Card eksternal beneran** (kartu SD lepasan, bukan storage utama HP): untuk
  folder chapter, ini sudah didukung lewat SAF/`DocumentFile` (lebih lambat sedikit
  dibanding akses `java.io.File` langsung, tapi tetap streaming, bukan base64).
  Untuk COVER dengan pilihan "Eksternal Storage" (path raw `/storage/...`), raw
  `java.io.File` masih hanya dijamin jalan untuk storage utama meski sudah
  `MANAGE_EXTERNAL_STORAGE` -- belum pakai SAF, cukup catatan di
  `FileStorageManager.kt`.
- Import database lama: copy `manga_library.db` kamu ke
  `context.getDatabasePath("manga_library.db")` sebelum run pertama kali agar
  data lama ikut terbawa (skema tabel sudah dibuat identik). Chapter lama yang
  sempat tersalin ke storage app (sebelum fitur Folder Chapter ini ada) tetap
  bisa dibaca seperti biasa (ada fallback otomatis), dan akan dihapus fisik
  kalau dihapus dari daftar isi -- tidak seperti chapter baru yang hanya berupa
  referensi.

## Kenapa ini "tanpa server"
Di Flask, WebView (browser) berkomunikasi ke proses Python lewat HTTP request/response.
Di sini, WebView memanggil objek Kotlin (`WebAppInterface`) yang di-inject lewat
`webView.addJavascriptInterface(bridge, "Android")` — panggilan JS→Kotlin terjadi
lewat JNI dalam satu proses aplikasi yang sama, tanpa membuka socket/port apa pun.
