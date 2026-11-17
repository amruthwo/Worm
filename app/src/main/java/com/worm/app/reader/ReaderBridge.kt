package com.worm.app.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.worm.app.data.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** One entry in a book's table of contents. */
data class TocEntry(val label: String, val href: String, val depth: Int)

data class ReaderLocation(
    val cfi: String = "",
    val fraction: Float = 0f,
    val chapter: String = "",
    val chapterHref: String = "",
    val page: Int = 0,
    val pages: Int = 0,
    val sectionPage: Int = 0,
    val sectionPages: Int = 0,
    val pageLabel: String = "",
    /** KOReader-style XPointer for the same spot (for KOSync / Readest). */
    val xpointer: String? = null,
)

data class SearchHit(val label: String, val cfi: String, val pre: String, val match: String, val post: String)

/** Reader page colours. */
enum class ReaderTheme(val key: String, val label: String, val bg: Long, val fg: Long, val link: Long, val dark: Boolean) {
    Paper("paper", "Paper", 0xFFFBF7EF, 0xFF26201B, 0xFF8E1E82, false),
    Sepia("sepia", "Sepia", 0xFFF1E3C6, 0xFF4A3622, 0xFF8A4B2B, false),
    Mint("mint", "Mint", 0xFFE6F6F1, 0xFF1D3B36, 0xFF0E7366, false),
    Lilac("lilac", "Lilac", 0xFFF3EAF8, 0xFF2E1F3A, 0xFF8E1EA2, false),
    Dusk("dusk", "Dusk", 0xFF2A2033, 0xFFE8DCEB, 0xFFFF9BD1, true),
    Night("night", "Night", 0xFF16121B, 0xFFCFC6D4, 0xFF98E8DE, true),
    Amoled("amoled", "AMOLED", 0xFF000000, 0xFFB9B3BD, 0xFFD552A3, true);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Paper
    }
}

/** Fonts offered in "Page look". Bundled ones are OFL-licensed (see assets/fonts). */
enum class ReaderFont(val key: String, val label: String, val stack: String) {
    Publisher("publisher", "Book's own", "inherit"),
    Literata("literata", "Literata", "'Worm Literata', serif"),
    Lora("lora", "Lora", "'Worm Lora', serif"),
    Garamond("garamond", "Garamond", "'Worm Garamond', serif"),
    Atkinson("atkinson", "Atkinson", "'Worm Atkinson', sans-serif"),
    Serif("serif", "System serif", "'Noto Serif', serif"),
    Sans("sans", "System sans", "'Roboto', 'Noto Sans', sans-serif"),
    Mono("mono", "Mono", "'Roboto Mono', monospace");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: Publisher }
}

/** Standard text sizes (CSS px), like a word processor's size list. */
val FontSizes = listOf(12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 24, 26, 28, 32, 36, 40)

data class ReaderSettings(
    val theme: ReaderTheme,
    val fontSize: Int,
    val lineHeight: Float,
    val margin: Int,
    val fontFamily: String,
    val justify: Boolean,
    val hyphenate: Boolean,
    val scrolled: Boolean,
    /** Two pages side by side when the screen is wide enough (landscape, tablets, foldables). */
    val twoPage: Boolean = false,
    /** E-ink: pages swap instantly instead of sliding. Follows the app theme, not saved per book. */
    val instant: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)
        put("bg", hex(theme.bg)); put("fg", hex(theme.fg)); put("link", hex(theme.link)); put("dark", theme.dark)
        put("selection", hex(theme.link) + "55")
        put("forceColors", theme.dark || theme == ReaderTheme.Sepia)
        put("dimImages", theme.dark)
        put("fontSize", fontSize); put("lineHeight", lineHeight.toDouble()); put("margin", margin)
        put("fontFamily", fontFamily)
        put("fontStack", ReaderFont.of(fontFamily).stack)
        put("justify", justify); put("hyphenate", hyphenate); put("scrolled", scrolled)
        put("columns", if (twoPage) 2 else 1)
        put("instant", instant)
    }

    /** Saved form (per book, or the global default). */
    fun encode(): String = JSONObject().put("t", theme.key).put("fs", fontSize).put("lh", lineHeight.toDouble()).put("m", margin)
        .put("ff", fontFamily).put("j", justify).put("h", hyphenate).put("sc", scrolled).put("tp", twoPage).toString()

    companion object {
        fun decode(raw: String?, fallback: ReaderSettings): ReaderSettings = raw?.let {
            runCatching {
                val o = JSONObject(it)
                ReaderSettings(
                    ReaderTheme.of(o.optString("t", fallback.theme.key)), o.optInt("fs", fallback.fontSize),
                    o.optDouble("lh", fallback.lineHeight.toDouble()).toFloat(), o.optInt("m", fallback.margin),
                    o.optString("ff", fallback.fontFamily), o.optBoolean("j", fallback.justify),
                    o.optBoolean("h", fallback.hyphenate), o.optBoolean("sc", fallback.scrolled),
                    o.optBoolean("tp", fallback.twoPage),
                )
            }.getOrNull()
        } ?: fallback
    }
}

interface ReaderEvents {
    fun onReady(title: String, toc: List<TocEntry>, sectionFractions: List<Float>)
    fun onRelocate(loc: ReaderLocation)
    fun onTap(zone: String)
    fun onSelection(active: Boolean)
    fun onAnnotationTap(cfi: String)
    fun onSearchResult(hit: SearchHit)
    fun onSearchProgress(fraction: Float)
    fun onSearchDone()
    fun onLink(url: String)
    fun onError(message: String)
    fun onPinch(direction: Int)
    fun onBrightness(delta: Float, end: Boolean)
    fun onMeasured(id: String, fraction: Float, cfi: String) {}
    /** Speed reading: words of one section, and where the current page starts in it. */
    fun onRsvp(section: Int, start: Int, words: List<String>, end: Boolean) {}
}

/**
 * The WebView that hosts foliate-js. Serves the app's reader assets and the book file from a
 * private https origin (no file:// access), and adds Worm's own items to the text-selection menu.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(context: Context, private val book: Book, private val events: ReaderEvents) : WebView(context) {
    private val main = Handler(Looper.getMainLooper())
    var onSelectionAction: ((action: String) -> Unit)? = null
    private var pendingOpen: String? = null
    private var bridgeReady = false

    init {
        // WRAP_CONTENT puts WebView in "size to content" mode → a 0px-tall viewport for our fixed layout.
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = true
        settings.textZoom = 100
        setBackgroundColor(0)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/") { _ ->
                val f = File(book.path)
                WebResourceResponse(mimeOf(f), null, FileInputStream(f)).apply {
                    responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                }
            }
            .build()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // foliate loads every chapter into an iframe from a blob: URL — those must go through.
                val url = request.url.toString()
                val scheme = request.url.scheme.orEmpty()
                if (scheme == "blob" || scheme == "data" || scheme == "about") return false
                if (url.startsWith("https://appassets.androidplatform.net/")) return false
                if (request.isForMainFrame || request.hasGesture()) main.post { events.onLink(url) }
                return true
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("WormReader", "${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                return true
            }
        }
        addJavascriptInterface(Bridge(), "Worm")
        loadUrl("https://appassets.androidplatform.net/assets/reader/reader.html")
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "cbz" -> "application/vnd.comicbook+zip"
        "fb2" -> "application/x-fictionbook+xml"
        "fbz" -> "application/x-zip-compressed-fb2"
        "mobi", "azw", "azw3", "kf8", "prc" -> "application/x-mobipocket-ebook"
        else -> "application/octet-stream"
    }

    fun open(settings: ReaderSettings, cfi: String?, highlights: List<Pair<String, String>>, fraction: Float = 0f, xpointer: String? = null) {
        val cfg = JSONObject().apply {
            put("fileName", File(book.path).name)
            put("settings", settings.toJson())
            put("cfi", cfi ?: "")
            put("fraction", fraction.toDouble())
            put("xpointer", xpointer ?: "")
            put("highlights", JSONArray(highlights.map { JSONObject().put("cfi", it.first).put("color", it.second) }))
        }
        val js = "worm.open($cfg)"
        if (bridgeReady) evaluateJavascript(js, null) else pendingOpen = js
    }

    fun js(code: String, result: ((String?) -> Unit)? = null) = evaluateJavascript(code) { result?.invoke(it) }
    fun applySettings(s: ReaderSettings) = js("worm.settings(${s.toJson()})")
    fun quote(s: String) = JSONObject.quote(s)

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(WormSelectionCallback(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(WormSelectionCallback(callback))

    /** Puts Highlight / Note / Define / Search in front of the system's Copy / Share / Select all. */
    private inner class WormSelectionCallback(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val ok = wrapped.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, ID_HIGHLIGHT, 0, "Highlight")
            menu.add(Menu.NONE, ID_NOTE, 1, "Note")
            menu.add(Menu.NONE, ID_DEFINE, 2, "Define")
            menu.add(Menu.NONE, ID_SEARCH, 3, "Search")
            return ok
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = wrapped.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = when (item.itemId) {
                ID_HIGHLIGHT -> "highlight"; ID_NOTE -> "note"; ID_DEFINE -> "define"; ID_SEARCH -> "search"
                else -> return wrapped.onActionItemClicked(mode, item)
            }
            onSelectionAction?.invoke(action)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private inner class Bridge {
        private fun obj(json: String) = runCatching { JSONObject(json) }.getOrDefault(JSONObject())

        @JavascriptInterface fun onBridge() = main.post {
            bridgeReady = true
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) evaluateJavascript("window.wormDebug = true", null)
            pendingOpen?.let { evaluateJavascript(it, null) }
            pendingOpen = null
        }

        @JavascriptInterface fun onReady(json: String) {
            val o = obj(json)
            val toc = o.optJSONArray("toc")?.let { a ->
                List(a.length()) { a.getJSONObject(it).let { t -> TocEntry(t.optString("label"), t.optString("href"), t.optInt("depth")) } }
            }.orEmpty()
            val fr = o.optJSONArray("fractions")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } }.orEmpty()
            main.post { events.onReady(o.optString("title"), toc, fr) }
        }

        @JavascriptInterface fun onRelocate(json: String) {
            val o = obj(json)
            val loc = ReaderLocation(
                o.optString("cfi"), o.optDouble("fraction").toFloat(), o.optString("chapter"), o.optString("chapterHref"),
                o.optInt("page"), o.optInt("pages"), o.optInt("sectionPage"), o.optInt("sectionPages"), o.optString("pageLabel"),
                o.optString("xpointer").takeIf { it.isNotBlank() && it != "null" },
            )
            main.post { events.onRelocate(loc) }
        }

        @JavascriptInterface fun onTap(zone: String) = main.post { events.onTap(zone) }
        @JavascriptInterface fun onSelection(active: Int) = main.post { events.onSelection(active == 1) }
        @JavascriptInterface fun onAnnotationTap(cfi: String) = main.post { events.onAnnotationTap(cfi) }
        @JavascriptInterface fun onSearchResult(json: String) {
            val o = obj(json)
            val hit = SearchHit(o.optString("label"), o.optString("cfi"), o.optString("pre"), o.optString("match"), o.optString("post"))
            main.post { events.onSearchResult(hit) }
        }
        @JavascriptInterface fun onSearchProgress(f: Double) = main.post { events.onSearchProgress(f.toFloat()) }
        @JavascriptInterface fun onSearchDone() = main.post { events.onSearchDone() }
        @JavascriptInterface fun openLink(url: String) = main.post { events.onLink(url) }
        @JavascriptInterface fun onError(msg: String) = main.post { events.onError(msg) }
        @JavascriptInterface fun onPinch(direction: Int) = main.post { events.onPinch(direction) }
        @JavascriptInterface fun onBrightness(delta: Double) = main.post { events.onBrightness(delta.toFloat(), false) }
        @JavascriptInterface fun onMeasured(id: String, fraction: Double, cfi: String) = main.post { events.onMeasured(id, fraction.toFloat(), cfi) }.let { }
        @JavascriptInterface fun onRsvp(json: String) {
            val o = org.json.JSONObject(json)
            val a = o.optJSONArray("words")
            val words = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
            main.post { events.onRsvp(o.optInt("section"), o.optInt("start"), words, o.optBoolean("end")) }
        }
        @JavascriptInterface fun onBrightnessEnd() = main.post { events.onBrightness(0f, true) }
        @JavascriptInterface fun log(msg: String) = android.util.Log.d("WormReader", msg).let { }
    }

    companion object {
        private const val ID_HIGHLIGHT = 0x5701
        private const val ID_NOTE = 0x5702
        private const val ID_DEFINE = 0x5703
        private const val ID_SEARCH = 0x5704
    }
}
