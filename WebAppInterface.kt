package com.nie.pustaka

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Setiap method di sini adalah pengganti 1:1 dari sebuah @app.route(...) di _Library.py.
 * Dipanggil dari JS lewat: Android.namaMethod(...)  -> selalu mengembalikan String JSON.
 *
 * Tidak ada server, tidak ada port 5000, tidak ada request/response HTTP:
 * WebView memanggil fungsi Kotlin secara langsung (in-process).
 */
class WebAppInterface(
    private val db: DatabaseHelper,
    private val storage: FileStorageManager,
    private val hostCallbacks: HostCallbacks
) {

    /** Diimplementasikan oleh MainActivity untuk hal yang butuh UI/Activity (file picker, buka viewer eksternal). */
    interface HostCallbacks {
        fun pickImage(requestId: String)
        fun pickChapterSourceFolder(requestId: String)
        fun openExternalFile(pathOrUri: String, isContentUri: Boolean, mime: String)
        fun requestManageStoragePermission()
        fun toggleFullscreen(): Boolean
        fun exitFullscreen()
    }

    // ---------------------------------------------------------------
    // KOLEKSI  (setara: /, /add, /edit/<id>, /delete/<id>, /detail/<id>)
    // ---------------------------------------------------------------

    @JavascriptInterface
    fun listCollections(
        sJudul: String, sTipe: String, sGenre: String, sStatus: String,
        catId: String, favOnly: String
    ): String {
        val readable = db.readableDatabase
        val where = StringBuilder("title LIKE ?")
        val args = mutableListOf("%$sJudul%")
        var query = "SELECT c.* FROM collection c"

        if (catId.isNotEmpty()) {
            query = "SELECT c.* FROM collection c JOIN collection_categories cc ON c.id = cc.collection_id"
            where.clear(); where.append("cc.category_id = ? AND c.title LIKE ?")
            args.clear(); args.add(catId); args.add("%$sJudul%")
        } else if (favOnly == "1") {
            where.append(" AND is_favorite = 1")
        }

        fun addFilter(col: String, value: String) {
            if (value.isEmpty()) return
            val terms = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (terms.isEmpty()) return
            val prefix = if (catId.isNotEmpty()) "c." else ""
            val clauses = terms.joinToString(" OR ") { "$prefix$col LIKE ?" }
            where.append(" AND ($clauses)")
            terms.forEach { args.add("%$it%") }
        }
        addFilter("type", sTipe)
        addFilter("genre", sGenre)
        addFilter("status", sStatus)

        val orderCol = if (catId.isNotEmpty()) "c.title" else "title"
        val sql = "$query WHERE $where ORDER BY $orderCol ASC"

        val cursor = readable.rawQuery(sql, args.toTypedArray())
        val out = JSONArray()
        cursor.use {
            while (it.moveToNext()) out.put(rowToJson(it))
        }

        var activePlaylistName = ""
        if (catId.isNotEmpty()) {
            readable.rawQuery("SELECT name FROM categories WHERE id = ?", arrayOf(catId)).use { c ->
                if (c.moveToFirst()) activePlaylistName = c.getString(0)
            }
        }

        val result = JSONObject()
        result.put("items", out)
        result.put("active_playlist_name", activePlaylistName)
        return result.toString()
    }

    @JavascriptInterface
    fun getDetail(id: Int): String {
        val readable = db.readableDatabase
        val result = JSONObject()

        readable.rawQuery("SELECT * FROM collection WHERE id = ?", arrayOf(id.toString())).use {
            if (!it.moveToFirst()) return JSONObject().put("error", "not_found").toString()
            result.put("item", rowToJson(it))
        }

        val chapters = JSONArray()
        readable.rawQuery(
            "SELECT * FROM chapter_list WHERE collection_id = ? ORDER BY order_index ASC, id ASC",
            arrayOf(id.toString())
        ).use { while (it.moveToNext()) chapters.put(rowToJson(it)) }
        result.put("chapters", chapters)

        val selectedCats = JSONArray()
        readable.rawQuery(
            "SELECT category_id FROM collection_categories WHERE collection_id = ?", arrayOf(id.toString())
        ).use { while (it.moveToNext()) selectedCats.put(it.getInt(0)) }
        result.put("selected_category_ids", selectedCats)

        val allCats = JSONArray()
        readable.rawQuery("SELECT * FROM categories ORDER BY name ASC", null).use {
            while (it.moveToNext()) allCats.put(rowToJson(it))
        }
        result.put("all_categories", allCats)

        return result.toString()
    }

    /**
     * payload JSON: {title,type,genre,status,chapters,description,storage_location,
     *                image_base64 (opsional), image_file_name (opsional)}
     */
    @JavascriptInterface
    fun addCollection(payloadJson: String): String {
        val p = JSONObject(payloadJson)
        var filename = ""
        val imgB64 = p.optString("image_base64", "")
        if (imgB64.isNotEmpty()) {
            val bytes = Base64.decode(imgB64, Base64.DEFAULT)
            val rawName = p.optString("title") + "_" + p.optString("image_file_name", "cover.jpg")
            filename = storage.saveFile(bytes, p.optString("storage_location", "internal"), "uploads", rawName)
        }
        val cv = ContentValues().apply {
            put("title", p.optString("title"))
            put("type", p.optString("type"))
            put("genre", p.optString("genre"))
            put("status", p.optString("status"))
            put("chapters", p.optString("chapters", ""))
            put("episodes", "")
            put("description", p.optString("description"))
            put("image_path", filename)
        }
        val newId = db.writableDatabase.insert("collection", null, cv)
        return JSONObject().put("id", newId).toString()
    }

    @JavascriptInterface
    fun editCollection(id: Int, payloadJson: String): String {
        val p = JSONObject(payloadJson)
        val cv = ContentValues().apply {
            put("title", p.optString("title"))
            put("type", p.optString("type"))
            put("genre", p.optString("genre"))
            put("status", p.optString("status"))
            put("chapters", p.optString("chapters", ""))
            put("episodes", "")
            put("description", p.optString("description"))
        }
        val imgB64 = p.optString("image_base64", "")
        if (imgB64.isNotEmpty()) {
            val bytes = Base64.decode(imgB64, Base64.DEFAULT)
            val rawName = p.optString("title") + "_" + p.optString("image_file_name", "cover.jpg")
            val filename = storage.saveFile(bytes, p.optString("storage_location", "internal"), "uploads", rawName)
            cv.put("image_path", filename)
        }
        db.writableDatabase.update("collection", cv, "id = ?", arrayOf(id.toString()))
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun deleteCollection(id: Int): String {
        db.writableDatabase.delete("collection", "id = ?", arrayOf(id.toString()))
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun updateItemCategories(id: Int, isFavorite: Boolean, catIdsCsv: String): String {
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put("is_favorite", if (isFavorite) 1 else 0)
            wdb.update("collection", cv, "id = ?", arrayOf(id.toString()))
            wdb.delete("collection_categories", "collection_id = ?", arrayOf(id.toString()))
            if (catIdsCsv.isNotEmpty()) {
                catIdsCsv.split(",").forEach { catId ->
                    val v = ContentValues()
                    v.put("collection_id", id)
                    v.put("category_id", catId.trim())
                    wdb.insert("collection_categories", null, v)
                }
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // PLAYLIST / KATEGORI  (setara: /playlists, /delete_category/<id>)
    // ---------------------------------------------------------------

    @JavascriptInterface
    fun listCategories(): String {
        val out = JSONArray()
        db.readableDatabase.rawQuery("SELECT * FROM categories ORDER BY name ASC", null).use {
            while (it.moveToNext()) out.put(rowToJson(it))
        }
        return out.toString()
    }

    @JavascriptInterface
    fun addCategory(name: String): String {
        val cv = ContentValues(); cv.put("name", name)
        db.writableDatabase.insertWithOnConflict("categories", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun deleteCategory(id: Int): String {
        db.writableDatabase.delete("categories", "id = ?", arrayOf(id.toString()))
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // CHAPTER  (setara: /add_chapter, /edit_chapters, /delete_chapter)
    // ---------------------------------------------------------------

    /**
     * payload: {ch_name, file_relpath}
     * `file_relpath` adalah path relatif terhadap Folder Chapter yang dipilih user sendiri
     * (lihat pickChapterSourceFolder/listChapterFiles) -- BUKAN isi file. File TIDAK disalin
     * ke storage app; hanya referensinya yang disimpan di DB. Ini yang menghilangkan bug
     * penyimpanan membengkak & layar hitam saat upload chapter berukuran besar.
     */
    @JavascriptInterface
    fun addChapter(collId: Int, payloadJson: String): String {
        val p = JSONObject(payloadJson)
        val chName = p.optString("ch_name")
        val relPath = p.optString("file_relpath")
        if (relPath.isEmpty() || !storage.isSupportedChapterExt(relPath)) {
            return JSONObject().put("error", "unsupported_or_missing_file").toString()
        }
        if (storage.resolveChapterSource(relPath) == null) {
            return JSONObject().put("error", "file_not_found").toString()
        }

        val readable = db.readableDatabase
        var maxOrder = 0
        readable.rawQuery(
            "SELECT MAX(order_index) FROM chapter_list WHERE collection_id = ?", arrayOf(collId.toString())
        ).use { if (it.moveToFirst()) maxOrder = it.getInt(0) }

        val cv = ContentValues().apply {
            put("collection_id", collId)
            put("chapter_name", chName)
            put("file_name", relPath)
            put("order_index", maxOrder + 1)
        }
        db.writableDatabase.insert("chapter_list", null, cv)
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // FOLDER CHAPTER (baru): user memilih SATU folder tempat semua file
    // chapter (pdf/txt/mp4/cbz) disimpan; app hanya MEMBACA dari sana.
    // ---------------------------------------------------------------

    @JavascriptInterface
    fun getChapterFolderInfo(): String {
        return JSONObject().apply {
            put("configured", storage.hasChapterFolder())
            put("path", storage.getChapterFolderLabel())
        }.toString()
    }

    @JavascriptInterface
    fun pickChapterSourceFolder(requestId: String) = hostCallbacks.pickChapterSourceFolder(requestId)

    /** Daftar folder & file (pdf/txt/mp4/cbz) di dalam Folder Chapter, pada sub-path tertentu. */
    @JavascriptInterface
    fun listChapterFiles(subPath: String): String {
        val out = JSONArray()
        storage.listChapterEntries(subPath).forEach { entry ->
            out.put(JSONObject().apply {
                put("name", entry.name)
                put("is_dir", entry.isDirectory)
                put("rel_path", entry.relativePath)
            })
        }
        return out.toString()
    }

    /** payload: [{id, name, order}, ...] */
    @JavascriptInterface
    fun editChapters(payloadJsonArray: String): String {
        val arr = JSONArray(payloadJsonArray)
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val cv = ContentValues()
                cv.put("chapter_name", row.getString("name"))
                cv.put("order_index", row.getInt("order"))
                wdb.update("chapter_list", cv, "id = ?", arrayOf(row.getInt("id").toString()))
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
        return JSONObject().put("success", true).toString()
    }

    /**
     * Hapus HANYA entri di database. File aslinya TIDAK disentuh, karena sejak update ini
     * file chapter selalu berupa referensi ke file milik user di Folder Chapter -- bukan
     * salinan milik app. (Chapter lama dari sebelum update yang masih tersalin di storage
     * app tetap dihapus fisik juga, agar tidak menumpuk sisa data.)
     */
    @JavascriptInterface
    fun deleteChapter(chId: Int): String {
        val readable = db.readableDatabase
        var fileName: String? = null
        readable.rawQuery("SELECT file_name FROM chapter_list WHERE id = ?", arrayOf(chId.toString())).use {
            if (it.moveToFirst()) fileName = it.getString(0)
        }
        // Hanya hapus fisik kalau file itu masih berupa salinan lama di storage internal app.
        storage.findFile(fileName, "chapters")?.let { storage.deleteFile(fileName, "chapters") }
        db.writableDatabase.delete("chapter_list", "id = ?", arrayOf(chId.toString()))
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // BOOKMARK  (setara: /api/add_bookmark, /bookmarks/<id>, /delete_bookmark)
    // ---------------------------------------------------------------

    @JavascriptInterface
    fun addBookmark(chapterId: Int, collectionId: Int, pageIndex: Int): String {
        val readable = db.readableDatabase
        var exists = false
        readable.rawQuery(
            "SELECT id FROM bookmarks WHERE chapter_id = ? AND page_index = ?",
            arrayOf(chapterId.toString(), pageIndex.toString())
        ).use { if (it.moveToFirst()) exists = true }

        if (!exists) {
            val cv = ContentValues().apply {
                put("collection_id", collectionId)
                put("chapter_id", chapterId)
                put("page_index", pageIndex)
            }
            db.writableDatabase.insert("bookmarks", null, cv)
        }
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun listBookmarks(collId: Int): String {
        val out = JSONArray()
        db.readableDatabase.rawQuery(
            """SELECT b.id, b.page_index, c.chapter_name, c.file_name
               FROM bookmarks b JOIN chapter_list c ON b.chapter_id = c.id
               WHERE b.collection_id = ?
               ORDER BY c.order_index ASC, b.page_index ASC""",
            arrayOf(collId.toString())
        ).use { while (it.moveToNext()) out.put(rowToJson(it)) }
        return out.toString()
    }

    @JavascriptInterface
    fun deleteBookmark(bmId: Int): String {
        db.writableDatabase.delete("bookmarks", "id = ?", arrayOf(bmId.toString()))
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // FILE SERVING  (setara: /media/uploads/.., /view_file/.., /cbz_image/.., /read/..)
    // Tidak ada HTTP response lagi -- semua langsung dikembalikan sebagai data: URL / teks.
    // ---------------------------------------------------------------

    /** URL virtual (dicegat LocalContentInterceptor) -- pengganti /media/uploads/<file>. */
    @JavascriptInterface
    fun getCoverUrl(imagePath: String): String = LocalContentInterceptor.coverUrl(imagePath)

    /** URL virtual mentah dgn dukungan Range -- pengganti /view_file/<file> (dipakai <video>). */
    @JavascriptInterface
    fun getChapterRawUrl(fileName: String): String = LocalContentInterceptor.chapterRawUrl(fileName)

    /** URL virtual per-halaman CBZ -- pengganti /cbz_image/<file>/<page>. */
    @JavascriptInterface
    fun getCbzPageUrl(fileName: String, pageIndex: Int): String = LocalContentInterceptor.cbzPageUrl(fileName, pageIndex)

    /** URL virtual per-halaman PDF (render native, pengganti pdf.js). */
    @JavascriptInterface
    fun getPdfPageUrl(fileName: String, pageIndex: Int): String = LocalContentInterceptor.pdfPageUrl(fileName, pageIndex)

    /** Info file chapter untuk reader: ekstensi + jumlah halaman (cbz/pdf) atau isi teks (txt). */
    @JavascriptInterface
    fun getChapterInfo(fileName: String): String {
        val source = storage.resolveChapterSource(fileName)
            ?: return JSONObject().put("error", "not_found").toString()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val result = JSONObject()
        result.put("ext", ext)
        when (ext) {
            "cbz", "zip" -> result.put("page_count", storage.cbzPageCount(source))
            "pdf" -> result.put("page_count", PdfPageRenderer.pageCount { storage.openParcelFileDescriptor(source) })
            "txt" -> result.put("text", storage.readTextFile(source))
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getChapterIdByFileName(fileName: String): String {
        val result = JSONObject()
        db.readableDatabase.rawQuery(
            "SELECT id, collection_id FROM chapter_list WHERE file_name = ?", arrayOf(fileName)
        ).use {
            if (it.moveToFirst()) {
                result.put("chapter_id", it.getInt(0))
                result.put("collection_id", it.getInt(1))
            }
        }
        return result.toString()
    }

    /**
     * Setara fallback Python: `else: return redirect(url_for('view_file', filename=filename))` --
     * dipakai reader untuk ekstensi yang tak dikenal (di luar pdf/txt/mp4/cbz), serahkan ke
     * aplikasi lain di perangkat lewat "Buka dengan..." (tidak ada browser bawaan di app native).
     */
    @JavascriptInterface
    fun openWithExternalApp(fileName: String): String {
        val source = storage.resolveChapterSource(fileName)
            ?: return JSONObject().put("error", "not_found").toString()
        val mime = storage.mimeTypeFor(fileName)
        when (source) {
            is FileStorageManager.ChapterSource.Path -> hostCallbacks.openExternalFile(source.file.absolutePath, false, mime)
            is FileStorageManager.ChapterSource.Doc -> hostCallbacks.openExternalFile(source.uri.toString(), true, mime)
        }
        return JSONObject().put("success", true).toString()
    }

    // ---------------------------------------------------------------
    // FILE PICKER & PERMISSION (menggantikan <input type="file"> milik browser)
    // ---------------------------------------------------------------

    @JavascriptInterface
    fun pickImage(requestId: String) = hostCallbacks.pickImage(requestId)

    @JavascriptInterface
    fun hasManageStoragePermission(): Boolean = storage.hasManageStoragePermission()

    @JavascriptInterface
    fun requestManageStoragePermission() = hostCallbacks.requestManageStoragePermission()

    /** Layar penuh SUNGGUHAN (menyembunyikan status bar & navigation bar Android),
     *  bukan cuma Fullscreen API browser yang tidak berlaku di dalam WebView. */
    @JavascriptInterface
    fun toggleFullscreen(): Boolean = hostCallbacks.toggleFullscreen()

    /** Dipanggil router JS setiap kali pindah dari reader ke halaman lain, supaya
     *  status bar/navigation bar tidak "terbawa" ke halaman non-reader lain. */
    @JavascriptInterface
    fun exitFullscreen() = hostCallbacks.exitFullscreen()

    // ---------------------------------------------------------------
    private fun rowToJson(c: Cursor): JSONObject {
        val obj = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> obj.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> obj.put(name, c.getDouble(i))
                Cursor.FIELD_TYPE_NULL -> obj.put(name, JSONObject.NULL)
                else -> obj.put(name, c.getString(i))
            }
        }
        return obj
    }
}
