package com.worm.app.convert

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.worm.app.data.Book
import com.worm.app.data.Library
import com.worm.app.data.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Metamorphosis: anything → EPUB, on the phone.
 * - MOBI, AZW/AZW3 (KF8), FB2/FBZ, CBZ and HTML: unpacked by foliate-js in a hidden WebView.
 * - TXT, Markdown, DOCX: converted in Kotlin.
 * The new EPUB lands next to the original; the original is only removed if you ask, after the
 * EPUB has been checked.
 */
object Metamorphosis {
    val ENGINE_FORMATS = setOf("mobi", "azw", "azw3", "kf8", "prc", "fb2", "fbz", "cbz", "html", "htm", "xhtml")
    val TEXT_FORMATS = setOf("txt", "text", "md", "markdown", "docx")
    val ALL = ENGINE_FORMATS + TEXT_FORMATS

    fun canConvert(file: File) = file.extension.lowercase() in ALL

    fun destinationFor(src: File): File {
        val base = src.nameWithoutExtension.removeSuffix(".fb2")
        var out = File(src.parentFile, "$base.epub")
        var n = 2
        while (out.exists()) out = File(src.parentFile, "$base ($n).epub").also { n++ }
        return out
    }

    /**
     * Converts [src] into [out]. [book] (when it's in the library) supplies better title/author/series
     * than the file itself. [progress] gets 0–1.
     */
    suspend fun convert(context: Context, src: File, out: File, book: Book?, progress: (Float) -> Unit) {
        val tmp = File(out.parentFile, ".metamorphosis-${System.currentTimeMillis()}.epub")
        try {
            when (src.extension.lowercase()) {
                in TEXT_FORMATS -> withContext(Dispatchers.IO) {
                    progress(0.2f)
                    val title = book?.title ?: src.nameWithoutExtension.replace('_', ' ')
                    val author = book?.authors?.firstOrNull()
                    val draft = when (src.extension.lowercase()) {
                        "docx" -> DocxConverter.convert(src, title)
                        "md", "markdown" -> MarkdownConverter.convert(decodeText(src.readBytes()), title, author)
                        else -> TxtConverter.convert(decodeText(src.readBytes()), title, author)
                    }
                    progress(0.7f)
                    draft.copy(meta = merge(draft.meta, book)).writeTo(tmp)
                }
                else -> withTimeout(10 * 60_000) { viaEngine(context, src, tmp, book, progress) }
            }
            withContext(Dispatchers.IO) {
                verify(tmp)
                check(tmp.renameTo(out)) { "Couldn't save the EPUB" }
            }
            progress(1f)
        } finally {
            tmp.delete()
        }
    }

    private fun merge(meta: EpubMeta, book: Book?): EpubMeta = if (book == null) meta else meta.copy(
        title = book.title.ifBlank { meta.title },
        authors = book.authors.ifEmpty { meta.authors },
        description = book.description ?: meta.description,
        series = book.series ?: meta.series,
        seriesIndex = book.seriesIndex ?: meta.seriesIndex,
        language = book.language ?: meta.language,
    )

    /** The EPUB must open as a zip, have a package document with a title and at least one chapter. */
    private fun verify(f: File) {
        check(f.length() > 200) { "The EPUB came out empty" }
        java.util.zip.ZipFile(f).use { z ->
            check(z.getEntry("OEBPS/content.opf") != null) { "The EPUB has no package document" }
            check(z.entries().asSequence().any { it.name.startsWith("OEBPS/text/") }) { "No chapters made it into the EPUB" }
        }
        check(Metadata.ebook(f).title != null) { "The EPUB's metadata can't be read" }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun viaEngine(context: Context, src: File, out: File, book: Book?, progress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val writer = EpubWriter(out)
                lateinit var web: WebView
                fun finish(error: Throwable?) {
                    main.post {
                        runCatching { web.destroy() }
                        if (!cont.isActive) return@post
                        if (error == null) cont.resume(Unit) else { writer.close(); cont.resumeWithException(error) }
                    }
                }
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .addPathHandler("/src/") { path ->
                        // The book itself, or (for HTML) files beside it — never anything outside its folder.
                        val dir = src.parentFile!!.canonicalFile
                        val f = File(dir, java.net.URLDecoder.decode(path, "UTF-8")).canonicalFile
                        if (!f.path.startsWith(dir.path) || !f.isFile) null
                        else WebResourceResponse(guessMime(f), null, FileInputStream(f))
                    }
                    .build()
                web = WebView(context.applicationContext)
                web.settings.javaScriptEnabled = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = loader.shouldInterceptRequest(request.url)
                }
                web.addJavascriptInterface(object {
                    @JavascriptInterface fun onBridge() = main.post { web.evaluateJavascript("metamorphosis(${JSONObject.quote(src.name)})", null) }.let { }
                    @JavascriptInterface fun progress(f: Double) = progress(f.toFloat())
                    @JavascriptInterface fun section(xhtml: String) { writer.addSection(xhtml) }
                    @JavascriptInterface fun resource(href: String, mime: String, b64: String, cover: Boolean) {
                        writer.addResource(href, mime, Base64.decode(b64, Base64.DEFAULT), cover)
                    }
                    @JavascriptInterface fun resourceText(href: String, mime: String, text: String) = writer.addResource(href, mime, text.toByteArray())
                    @JavascriptInterface fun log(msg: String) = android.util.Log.d("WormConvert", msg).let { }
                    @JavascriptInterface fun fail(msg: String) = finish(IllegalStateException(msg))
                    @JavascriptInterface fun done(json: String) {
                        val error = runCatching {
                            val o = JSONObject(json)
                            check(writer.sectionCount > 0) { "Nothing readable inside this file" }
                            writer.fixedLayout = o.optBoolean("fixed")
                            val toc = o.optJSONArray("toc")?.let { a ->
                                List(a.length()) { a.getJSONObject(it).let { t -> TocItem(t.optString("label"), t.optString("href"), t.optInt("depth")) } }
                            }.orEmpty()
                            val authors = o.optJSONArray("authors")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
                            val meta = merge(
                                EpubMeta(
                                    title = o.optString("title").ifBlank { src.nameWithoutExtension },
                                    authors = authors,
                                    language = o.optString("language").ifBlank { "en" },
                                    description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
                                    publisher = o.optString("publisher").takeIf { it.isNotBlank() && it != "null" },
                                ),
                                book,
                            )
                            writer.finish(meta, toc)
                        }.exceptionOrNull()
                        finish(error)
                    }
                }, "Worm")
                web.loadUrl("https://appassets.androidplatform.net/assets/reader/convert.html")
                cont.invokeOnCancellation { main.post { runCatching { web.destroy() }; writer.close() } }
            }
        }

    private fun guessMime(f: File) = when (f.extension.lowercase()) {
        "html", "htm" -> "text/html"; "xhtml" -> "application/xhtml+xml"; "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    /**
     * Full job for a library book: convert, rescan, move your progress/edits/shelves to the EPUB,
     * and remove the original if asked.
     */
    suspend fun convertBook(context: Context, book: Book, deleteOriginal: Boolean, progress: (Float) -> Unit): File {
        val library = Library.get(context)
        val src = File(book.path)
        val out = destinationFor(src)
        convert(context, src, out, book, progress)
        library.scan()
        library.books.value.firstOrNull { it.path == out.absolutePath }?.let { library.migrate(book, it, keepChapters = true) }
        if (deleteOriginal) { library.deleteBook(book); library.scan() }
        library.notifyMediaStore(out.absolutePath)
        return out
    }
}
