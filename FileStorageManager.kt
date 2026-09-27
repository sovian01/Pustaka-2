package com.nie.pustaka

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pengganti langsung dari get_storage_base() / find_file() di Flask, DITAMBAH
 * dukungan "Folder Chapter" yang dipilih user sendiri lewat System Folder Picker
 * (Storage Access Framework / SAF).
 *
 * PERUBAHAN PENTING (perbaikan bug "APK/penyimpanan membengkak" & "layar hitam"):
 * File CHAPTER (pdf/txt/mp4/cbz) TIDAK PERNAH lagi disalin ke dalam penyimpanan
 * milik app. App hanya menyimpan REFERENSI (path relatif terhadap folder yang
 * dipilih user) di database, lalu membaca langsung dari folder itu setiap kali
 * dibutuhkan -- persis seperti "hanya membaca file yang saya simpan di folder
 * tertentu" yang diminta user. Ini juga yang menghilangkan bug layar hitam,
 * karena isi file besar tidak pernah lagi dibaca penuh ke memori lalu dikirim
 * lewat JS bridge sebagai base64 raksasa.
 *
 * Cover gambar (uploads) TIDAK diubah -- ukurannya kecil, tetap disimpan seperti
 * semula (internal/eksternal, sesuai pilihan user di form).
 */
class FileStorageManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("pustaka_settings", Context.MODE_PRIVATE)

    private val internalBase: File
        get() {
            val base = File(context.getExternalFilesDir(null), "Pustaka")
            base.mkdirs()
            return base
        }

    /** Folder shared publik, dipakai kalau user memilih "eksternal" untuk COVER. */
    private val externalBase: File
        get() = File(Environment.getExternalStorageDirectory(), "Pustaka")

    /**
     * SEBELUMNYA fungsi ini selalu balikan `true` untuk semua versi Android di bawah 11 --
     * padahal WRITE_EXTERNAL_STORAGE adalah izin "berbahaya" (dangerous permission) yang
     * di Android 6.0-9.0 (API 23-28) WAJIB diminta lewat dialog runtime (bukan cukup
     * dideklarasikan di manifest), dan TIDAK OTOMATIS diberikan saat instal. Akibatnya
     * kode di sini mengira sudah punya akses padahal belum, dan menyimpan cover ke
     * "Eksternal Storage" bisa gagal (SecurityException) di rentang versi itu.
     */
    fun hasManageStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** Setara get_storage_base(choice) -- HANYA untuk cover gambar. */
    fun getStorageBase(choice: String): File {
        if (choice == "eksternal" && hasManageStoragePermission()) {
            try {
                val dir = externalBase
                dir.mkdirs()
                if (dir.canWrite()) return dir
            } catch (_: Exception) {
                // fallback ke internal, sama seperti try/except Python
            }
        }
        val dir = internalBase
        dir.mkdirs()
        return dir
    }

    /** Cover: cek internal dulu, lalu eksternal. */
    fun findFile(relativePath: String?, subfolder: String): File? {
        if (relativePath.isNullOrEmpty()) return null
        val intPath = File(File(internalBase, subfolder), relativePath)
        if (intPath.exists()) return intPath
        val extPath = File(File(externalBase, subfolder), relativePath)
        if (extPath.exists()) return extPath
        return null
    }

    fun ensureDefaultFolders() {
        File(internalBase, "uploads").mkdirs()
    }

    /** Simpan bytes cover, kembalikan nama file relatif yang disimpan ke DB. */
    fun saveFile(bytes: ByteArray, storageChoice: String, subfolder: String, fileName: String): String {
        val base = getStorageBase(storageChoice)
        val dir = File(base, subfolder)
        dir.mkdirs()
        val safeName = sanitizeFileName(fileName)
        val out = File(dir, safeName)
        out.writeBytes(bytes)
        return safeName
    }

    fun deleteFile(relativePath: String?, subfolder: String) {
        val f = findFile(relativePath, subfolder) ?: return
        try { f.delete() } catch (_: Exception) {}
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim()
    }

    fun mimeTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** Sama seperti daftar accept=".pdf,.txt,.mp4,.cbz" pada form upload versi Flask. */
    fun isSupportedChapterExt(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("pdf", "txt", "mp4", "cbz")
    }

    // -----------------------------------------------------------------
    // FOLDER CHAPTER (baru): dipilih user sendiri, file tidak pernah disalin
    // -----------------------------------------------------------------

    fun getChapterFolderUri(): Uri? {
        val s = prefs.getString("chapter_tree_uri", null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    fun setChapterFolder(treeUri: Uri) {
        prefs.edit().putString("chapter_tree_uri", treeUri.toString()).apply()
    }

    fun hasChapterFolder(): Boolean = getChapterFolderUri() != null

    /** Path asli kalau folder yang dipilih ada di storage utama (paling umum) -- baca cepat via java.io.File. */
    private fun resolvePrimaryPath(treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":")
            if (split.isEmpty() || !split[0].equals("primary", ignoreCase = true)) return null
            val relative = if (split.size > 1) split[1] else ""
            val base = Environment.getExternalStorageDirectory()
            val dir = if (relative.isEmpty()) base else File(base, relative)
            if (dir.exists()) dir else null
        } catch (_: Exception) {
            null
        }
    }

    /** Nama folder yang ditampilkan ke user di UI (path asli kalau bisa, atau nama folder SAF). */
    fun getChapterFolderLabel(): String {
        val uri = getChapterFolderUri() ?: return ""
        resolvePrimaryPath(uri)?.let { return it.absolutePath }
        return try {
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
        } catch (_: Exception) {
            uri.toString()
        }
    }

    data class ChapterEntry(val name: String, val isDirectory: Boolean, val relativePath: String)

    /** Daftar folder & file (yang didukung) di dalam folder chapter, pada sub-path tertentu. */
    fun listChapterEntries(subPath: String): List<ChapterEntry> {
        val uri = getChapterFolderUri() ?: return emptyList()

        resolvePrimaryPath(uri)?.let { root ->
            val dir = if (subPath.isEmpty()) root else File(root, subPath)
            val files = dir.listFiles() ?: return emptyList()
            return files
                .filter { it.isDirectory || isSupportedChapterExt(it.name) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { ChapterEntry(it.name, it.isDirectory, if (subPath.isEmpty()) it.name else "$subPath/${it.name}") }
        }

        // Fallback SAF (mis. folder di kartu SD lepasan yang tidak bisa dipetakan ke path asli)
        return try {
            var dirDoc = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
            if (subPath.isNotEmpty()) {
                for (seg in subPath.split("/")) {
                    dirDoc = dirDoc.listFiles().firstOrNull { it.name == seg && it.isDirectory } ?: return emptyList()
                }
            }
            dirDoc.listFiles()
                .filter { it.isDirectory || isSupportedChapterExt(it.name ?: "") }
                .sortedWith(compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() }))
                .map { ChapterEntry(it.name ?: "?", it.isDirectory, if (subPath.isEmpty()) (it.name ?: "?") else "$subPath/${it.name}") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Representasi sebuah file chapter yang berhasil ditemukan, baik lewat path asli maupun SAF. */
    sealed class ChapterSource {
        data class Path(val file: File) : ChapterSource()
        data class Doc(val uri: Uri) : ChapterSource()
    }

    /**
     * Cari file chapter berdasarkan path relatif yang tersimpan di DB.
     * Urutan pencarian: folder chapter yang dikonfigurasi user -> (kompatibilitas lama)
     * folder "chapters" bawaan app untuk chapter yang diunggah sebelum update ini.
     */
    fun resolveChapterSource(relativePath: String?): ChapterSource? {
        if (relativePath.isNullOrEmpty()) return null

        getChapterFolderUri()?.let { uri ->
            resolvePrimaryPath(uri)?.let { root ->
                val f = File(root, relativePath)
                if (f.exists()) return ChapterSource.Path(f)
            }
            try {
                var doc = DocumentFile.fromTreeUri(context, uri)
                for (seg in relativePath.split("/")) {
                    doc = doc?.listFiles()?.firstOrNull { it.name == seg }
                }
                if (doc != null && doc.exists()) return ChapterSource.Doc(doc.uri)
            } catch (_: Exception) { /* lanjut ke fallback lama */ }
        }

        // Kompatibilitas lama: chapter yang sempat disalin ke storage app sebelum update ini.
        findFile(relativePath, "chapters")?.let { return ChapterSource.Path(it) }
        File(File(externalBase, "chapters"), relativePath).let { if (it.exists()) return ChapterSource.Path(it) }
        return null
    }

    fun openStream(source: ChapterSource): InputStream? = when (source) {
        is ChapterSource.Path -> source.file.inputStream()
        is ChapterSource.Doc -> context.contentResolver.openInputStream(source.uri)
    }

    fun openParcelFileDescriptor(source: ChapterSource): ParcelFileDescriptor? = when (source) {
        is ChapterSource.Path -> ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        is ChapterSource.Doc -> context.contentResolver.openFileDescriptor(source.uri, "r")
    }

    fun readTextFile(source: ChapterSource): String {
        return openStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    /**
     * Nama entri gambar di dalam .cbz/.zip, URUT ABJAD -- persis seperti
     * `sorted([f for f in z.namelist() if ...])` di versi Flask. Arsip CBZ
     * tidak selalu MENYIMPAN entrinya sudah urut secara fisik di dalam zip,
     * jadi membaca apa adanya sesuai urutan mentah zip (seperti sebelumnya)
     * bisa membuat urutan halaman berantakan dibanding versi Python.
     */
    private fun sortedImageEntryNames(source: ChapterSource): List<String> {
        val stream = openStream(source) ?: return emptyList()
        val names = mutableListOf<String>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isImageEntry(entry.name)) names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names.sorted()
    }

    /** Hitung jumlah halaman gambar di dalam .cbz/.zip. */
    fun cbzPageCount(source: ChapterSource): Int = sortedImageEntryNames(source).size

    /** Ambil satu halaman gambar dari .cbz/.zip sebagai bytes, berdasarkan urutan abjad (bukan urutan fisik zip). */
    fun cbzPageBytes(source: ChapterSource, index: Int): Pair<ByteArray, String>? {
        val targetName = sortedImageEntryNames(source).getOrNull(index) ?: return null
        val stream = openStream(source) ?: return null
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == targetName) {
                    val bytes = zip.readBytes()
                    return bytes to mimeTypeFor(entry.name)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun isImageEntry(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
    }
}
