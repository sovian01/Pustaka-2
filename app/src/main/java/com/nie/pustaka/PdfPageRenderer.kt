package com.nie.pustaka

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream

/**
 * Pengganti pdf.js (/static/pdf.min.js + pdf.worker.min.js) di versi Flask.
 * Merender tiap halaman PDF jadi bitmap PNG secara native, lalu hasilnya
 * dipakai sebagai <img> biasa di reader -- persis seperti canvas per-halaman
 * pada versi asli, tapi tanpa perlu bundling library JS eksternal apa pun.
 *
 * Menerima ParcelFileDescriptor (bukan File) supaya bisa membaca PDF baik dari
 * path asli maupun dari folder chapter yang diakses lewat SAF/DocumentFile --
 * PdfRenderer sendiri tidak peduli asal fd-nya.
 */
object PdfPageRenderer {

    /** Skala render relatif terhadap ukuran asli PDF (dalam point, 1/72 inch). ~1.5x seperti versi Flask. */
    private const val RENDER_SCALE = 1.6f

    fun pageCount(pfdProvider: () -> ParcelFileDescriptor?): Int {
        val pfd = pfdProvider() ?: return 0
        pfd.use {
            PdfRenderer(it).use { renderer -> return renderer.pageCount }
        }
    }

    /** Kembalikan bytes PNG untuk satu halaman (index mulai dari 0), atau null kalau gagal. */
    fun renderPagePng(pfdProvider: () -> ParcelFileDescriptor?, pageIndex: Int): ByteArray? {
        val pfd = pfdProvider() ?: return null
        pfd.use {
            PdfRenderer(it).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val width = (page.width * RENDER_SCALE).toInt().coerceAtLeast(1)
                    val height = (page.height * RENDER_SCALE).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // PDF umumnya berlatar putih, sama seperti versi Flask
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    bitmap.recycle()
                    return out.toByteArray()
                }
            }
        }
    }
}
