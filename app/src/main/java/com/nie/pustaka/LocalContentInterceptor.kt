package com.nie.pustaka

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Pengganti langsung dari:
 *   /media/uploads/<file>   -> host "pustaka.internal", path "/cover/..."
 *   /view_file/<file>       -> host "pustaka.internal", path "/chapter/..."   (mendukung Range, dipakai video)
 *   /cbz_image/<file>/<i>   -> host "pustaka.internal", path "/cbzpage/.../i"
 *   (baru, ganti pdf.js)    -> host "pustaka.internal", path "/pdfpage/.../i"
 *
 * PENTING: ini BUKAN server jaringan. shouldInterceptRequest() adalah callback
 * WebViewClient yang dipanggil in-process setiap kali WebView butuh sebuah
 * resource (mis. <img src>, <video src>) -- tidak ada socket/port yang dibuka.
 *
 * File chapter (pdf/txt/mp4/cbz) dibaca LANGSUNG dari folder yang dipilih user
 * (lihat FileStorageManager.ChapterSource) -- tidak pernah disalin ke storage
 * app, dan tidak pernah dibaca penuh ke memori untuk file besar seperti video;
 * semuanya di-stream, termasuk dukungan Range untuk seek video.
 */
class LocalContentInterceptor(private val storage: FileStorageManager) {

    companion object {
        const val HOST = "pustaka.internal"
        fun coverUrl(relativePath: String) = "https://$HOST/cover/${Uri.encode(relativePath)}"
        fun chapterRawUrl(relativePath: String) = "https://$HOST/chapter/${Uri.encode(relativePath)}"
        fun cbzPageUrl(relativePath: String, index: Int) = "https://$HOST/cbzpage/${Uri.encode(relativePath)}/$index"
        fun pdfPageUrl(relativePath: String, index: Int) = "https://$HOST/pdfpage/${Uri.encode(relativePath)}/$index"
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != HOST) return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return notFound()

        return try {
            when (segments[0]) {
                "cover" -> serveCover(segments.drop(1).joinToString("/"))
                "chapter" -> serveChapterRaw(segments.drop(1).joinToString("/"), request)
                "cbzpage" -> serveCbzPage(segments.subList(1, segments.size - 1).joinToString("/"), segments.last().toInt())
                "pdfpage" -> servePdfPage(segments.subList(1, segments.size - 1).joinToString("/"), segments.last().toInt())
                else -> null
            }
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun serveCover(relativePath: String): WebResourceResponse {
        val file = storage.findFile(relativePath, "uploads") ?: return notFound()
        val mime = storage.mimeTypeFor(file.name)
        return WebResourceResponse(mime, null, FileInputStream(file))
    }

    private fun serveCbzPage(relativePath: String, index: Int): WebResourceResponse {
        val source = storage.resolveChapterSource(relativePath) ?: return notFound()
        val (bytes, mime) = storage.cbzPageBytes(source, index) ?: return notFound()
        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
    }

    private fun servePdfPage(relativePath: String, index: Int): WebResourceResponse {
        val source = storage.resolveChapterSource(relativePath) ?: return notFound()
        val png = PdfPageRenderer.renderPagePng({ storage.openParcelFileDescriptor(source) }, index) ?: return notFound()
        return WebResourceResponse("image/png", null, ByteArrayInputStream(png))
    }

    /** Setara /view_file/<file>: baca mentah + dukung HTTP Range (dibutuhkan <video> agar bisa seek). */
    private fun serveChapterRaw(relativePath: String, request: WebResourceRequest): WebResourceResponse {
        val source = storage.resolveChapterSource(relativePath) ?: return notFound()
        val pfd = storage.openParcelFileDescriptor(source) ?: return notFound()
        val length = pfd.statSize
        val rangeHeader = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

        val mime = storage.mimeTypeFor(relativePath)
        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)

        if (rangeHeader == null) {
            val headers = mapOf("Accept-Ranges" to "bytes", "Content-Length" to length.toString())
            return WebResourceResponse(mime, null, 200, "OK", headers, stream)
        }

        // Format: "bytes=START-END" (END boleh kosong -> sampai akhir file)
        val range = rangeHeader.removePrefix("bytes=").split("-")
        val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = range.getOrNull(1)?.toLongOrNull() ?: (length - 1)

        stream.skip(start)

        val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $start-$end/$length",
            "Content-Length" to (end - start + 1).toString()
        )
        return WebResourceResponse(mime, null, 206, "Partial Content", headers, stream)
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
