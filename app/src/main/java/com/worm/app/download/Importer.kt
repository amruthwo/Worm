package com.worm.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.worm.app.data.Formats
import com.worm.app.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What other apps can hand to Worm (share menu, "Open with…"). */
sealed interface Incoming {
    data class Magnet(val uri: String) : Incoming
    data class Files(val uris: List<Uri>, val openAfter: Boolean) : Incoming
    data class Link(val url: String) : Incoming
}

object Importer {
    fun parse(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { u ->
                when {
                    u.scheme == "magnet" -> Incoming.Magnet(u.toString())
                    u.scheme == "http" || u.scheme == "https" -> Incoming.Link(u.toString())
                    else -> Incoming.Files(listOf(u), openAfter = true)
                }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    stream != null -> Incoming.Files(listOf(stream), openAfter = false)
                    else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                        Regex("""magnet:\?\S+""").find(t)?.value?.let(Incoming::Magnet)
                            ?: Regex("""https?://\S+""").find(t)?.value?.let(Incoming::Link)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (list.isEmpty()) null else Incoming.Files(list, openAfter = false)
            }
            else -> null
        }
    }

    /** Looks like a direct link to a book or audio file (vs. a web page). */
    fun isDirectFile(url: String): Boolean {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in Formats.EBOOK || ext in Formats.AUDIO || ext in setOf("zip", "pdf", "azw3", "azw", "mobi", "fb2", "fbz")
    }

    /**
     * Copies shared/opened files into the library (Worm Downloads). Files already inside a library
     * folder are used where they are. Returns the library paths.
     */
    suspend fun importFiles(c: Context, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val lib = Library.get(c)
        val dir by lazy { Downloads.destination(c) } // only created if we actually copy something
        val roots = lib.sources.value.map { it.path }
        var copied = false
        val out = uris.mapNotNull { uri ->
            runCatching {
                // A real path we can already read (file://, or a shared-storage document)?
                val direct = when (uri.scheme) {
                    "file" -> uri.path?.let(::File)
                    else -> null
                }?.takeIf { it.isFile }
                if (direct != null && roots.any { direct.absolutePath.startsWith(it) }) return@runCatching direct.absolutePath
                val name = Downloads.cleanName(displayName(c, uri) ?: direct?.name ?: uri.lastPathSegment ?: "book")
                var target = File(dir, name)
                var n = 2
                while (target.exists()) target = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}".trimEnd('.')).also { n++ }
                (direct?.inputStream() ?: c.contentResolver.openInputStream(uri))!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                lib.notifyMediaStore(target.absolutePath)
                copied = true
                target.absolutePath
            }.onFailure { android.util.Log.w("Worm", "Import failed for $uri", it) }.getOrNull()
        }
        // Only new copies need a rescan; files already in the library are known.
        if (copied) lib.scan()
        out
    }

    private fun displayName(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
    }.getOrNull()
}
