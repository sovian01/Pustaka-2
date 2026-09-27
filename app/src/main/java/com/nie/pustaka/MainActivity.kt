package com.nie.pustaka

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Pengganti "app.run(host='0.0.0.0', port=5000)" dari Flask.
 * Di sini tidak ada server yang berjalan sama sekali -- WebView memuat halaman
 * dari assets lokal (file:///android_asset/www/index.html) dan JavaScript di
 * halaman itu memanggil langsung method Kotlin lewat window.Android.*
 */
class MainActivity : AppCompatActivity(), WebAppInterface.HostCallbacks {

    private lateinit var webView: WebView
    private lateinit var bridge: WebAppInterface
    private lateinit var storage: FileStorageManager

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        deliverPickedFile(uri, pendingImageRequestId)
        pendingImageRequestId = null
    }

    // Folder tempat SEMUA file chapter (pdf/txt/mp4/cbz) sudah disimpan user sendiri.
    // Dipilih SEKALI lewat System Folder Picker (SAF) -- app hanya membaca dari sini,
    // tidak pernah menyalin isinya ke storage app. Ini yang memperbaiki bug
    // "penyimpanan membengkak" & "layar hitam" saat upload chapter besar.
    private val pickChapterFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val reqId = pendingChapterFolderRequestId
        pendingChapterFolderRequestId = null
        if (uri == null) {
            webView.post { webView.evaluateJavascript("window.onNativeFolderPicked && window.onNativeFolderPicked('$reqId', false, '')", null) }
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* beberapa penyedia hanya izinkan salah satu flag, tidak fatal */ }
        storage.setChapterFolder(uri)
        val label = storage.getChapterFolderLabel().replace("'", "")
        webView.post { webView.evaluateJavascript("window.onNativeFolderPicked && window.onNativeFolderPicked('$reqId', true, '$label')", null) }
    }

    // Untuk Android 6.0-9.0 (API 23-28): WRITE_EXTERNAL_STORAGE adalah dangerous permission
    // yang wajib diminta lewat dialog runtime seperti ini (Settings ACTION_MANAGE_..._PERMISSION
    // di bawah hanya berlaku mulai Android 11/API 30 ke atas).
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            webView.post { webView.evaluateJavascript("window.onAppResumed && window.onAppResumed()", null) }
        }

    private var pendingImageRequestId: String? = null
    private var pendingChapterFolderRequestId: String? = null
    private var isImmersive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val dbHelper = DatabaseHelper(this)
        storage = FileStorageManager(this)
        storage.ensureDefaultFolders()
        bridge = WebAppInterface(dbHelper, storage, this)

        val interceptor = LocalContentInterceptor(storage)

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false // supaya <video autoplay> jalan sama seperti versi Flask
        }
        webView.addJavascriptInterface(bridge, "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            // Ini pengganti "server": setiap <img>/<video> yang menunjuk ke
            // https://pustaka.internal/... dicegat & dijawab langsung dari
            // penyimpanan lokal lewat LocalContentInterceptor -- tidak ada
            // request jaringan sungguhan yang pernah terjadi.
            override fun shouldInterceptRequest(
                view: WebView, request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return interceptor.intercept(request) ?: super.shouldInterceptRequest(view, request)
            }
        }
        // TANPA WebChromeClient, WebView Android SECARA DIAM-DIAM mengabaikan
        // window.alert() (tidak menampilkan apa pun) dan otomatis membatalkan
        // window.confirm() (selalu balikan `false` tanpa pernah bertanya ke user)
        // -- berbeda dari browser biasa (Chrome/desktop) tempat app.js ini awalnya
        // diuji, di mana keduanya tampil sebagai dialog normal. Akibatnya semua
        // tombol "Hapus" (kategori, koleksi, chapter, bookmark) dan semua pesan
        // validasi/notifikasi lewat alert() di app.js akan terlihat seperti tidak
        // berbuat apa-apa saat ditekan di dalam app native. Di sinilah keduanya
        // diberi implementasi native (AlertDialog) agar benar-benar berfungsi
        // sama seperti di browser/versi Flask.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .setCancelable(true)
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .setCancelable(true)
                    .show()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onResume() {
        super.onResume()
        // Setelah user kembali dari halaman Settings Android (mis. setelah memberi izin
        // "Kelola semua file" lewat requestManageStoragePermission()), beri tahu JS agar
        // status/peringatan terkait bisa diperbarui otomatis tanpa perlu navigasi manual.
        webView.post { webView.evaluateJavascript("window.onAppResumed && window.onAppResumed()", null) }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ---------------- HostCallbacks (dipanggil dari WebAppInterface) ----------------

    override fun pickImage(requestId: String) {
        pendingImageRequestId = requestId
        pickImageLauncher.launch("image/*")
    }

    override fun pickChapterSourceFolder(requestId: String) {
        pendingChapterFolderRequestId = requestId
        pickChapterFolderLauncher.launch(null)
    }

    override fun openExternalFile(pathOrUri: String, isContentUri: Boolean, mime: String) {
        val uri: Uri = if (isContentUri) {
            Uri.parse(pathOrUri)
        } else {
            FileProvider.getUriForFile(this, "com.nie.pustaka.fileprovider", java.io.File(pathOrUri))
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Buka dengan"))
        } catch (_: Exception) {
            webView.post { webView.evaluateJavascript("alert('Tidak ada aplikasi yang bisa membuka file ini.')", null) }
        }
    }

    override fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : izin "Kelola semua file" hanya bisa diberikan lewat halaman Settings.
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            // Android 7-9 (API 24-28, minSdk proyek ini 24): WRITE_EXTERNAL_STORAGE
            // diminta lewat dialog izin runtime biasa, BUKAN lewat halaman Settings.
            requestStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * "Toggle Layar Penuh" versi Flask memanggil Fullscreen API browser biasa
     * (`requestFullscreen()`), yang di BROWSER SUNGGUHAN memang menyembunyikan
     * seluruh chrome (address bar, dst). Tapi di dalam WebView milik app native,
     * API JS itu TIDAK punya akses untuk menyembunyikan status bar/navigation
     * bar Android -- makanya tombolnya "jalan" tapi layar tidak benar-benar
     * penuh. Di sini diganti perintah native Android (immersive mode) supaya
     * hasilnya benar-benar layar penuh, sama seperti yang dimaksud aslinya.
     */
    override fun toggleFullscreen(): Boolean {
        isImmersive = !isImmersive
        applyImmersiveMode()
        return isImmersive
    }

    override fun exitFullscreen() {
        if (!isImmersive) return
        isImmersive = false
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, !isImmersive)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isImmersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Baca file yang dipilih user lalu kirim balik ke JS sebagai base64 (menggantikan multipart upload ke Flask). */
    private fun deliverPickedFile(uri: Uri?, requestId: String?) {
        if (uri == null || requestId == null) {
            webView.post { webView.evaluateJavascript("window.onNativeFilePicked && window.onNativeFilePicked('$requestId', null, null)", null) }
            return
        }
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val name = queryFileName(uri) ?: "file"
            webView.post {
                webView.evaluateJavascript(
                    "window.onNativeFilePicked && window.onNativeFilePicked('$requestId', '${b64}', '${name.replace("'", "")}')",
                    null
                )
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) name = cursor.getString(nameIndex)
        }
        return name
    }
}
