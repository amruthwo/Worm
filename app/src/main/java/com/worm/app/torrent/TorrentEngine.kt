package com.worm.app.torrent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.worm.app.convert.SilkSpinner
import com.worm.app.data.Formats
import com.worm.app.data.Library
import com.worm.app.download.Downloads
import com.worm.app.notify.WormNotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import java.io.File

/** One file inside a torrent, for choosing what to download. */
data class TorrentFile(val index: Int, val path: String, val size: Long, val bookish: Boolean)

/** A torrent waiting for you to pick its files. */
data class PendingTorrent(val hash: String, val name: String, val files: List<TorrentFile>, val bytes: ByteArray)

data class TorrentItem(
    val hash: String,
    val name: String,
    val progress: Float,
    val downBps: Int,
    val upBps: Int,
    val peers: Int,
    val total: Long,
    val done: Long,
    val state: String, // "metadata", "checking", "downloading", "paused", "seeding", "done", "error"
    val seed: Boolean,
    val savedTo: String?,
)

/**
 * Worm's torrent client (libtorrent via libtorrent4j). Nothing is pre-loaded: you add magnet links or
 * .torrent files yourself. Books download into a hidden folder (so half-finished files never show up
 * in your library), then move into Worm Downloads/Torrents when done. Seeding afterwards is opt-in.
 */
object TorrentEngine {
    private var session: SessionManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poller: Job? = null

    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    val items: StateFlow<List<TorrentItem>> = _items.asStateFlow()
    private val _pending = MutableStateFlow<PendingTorrent?>(null)
    val pending: StateFlow<PendingTorrent?> = _pending.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    /** "Fetching details for magnet link…" etc. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private data class Meta(val hash: String, val name: String, val seed: Boolean, val done: Boolean, val priorities: List<Int>, val finalDir: String?)
    private val metas = mutableMapOf<String, Meta>()

    private fun dir(c: Context) = File(c.filesDir, "torrents").apply { mkdirs() }
    private fun metaFile(c: Context) = File(dir(c), "torrents.json")
    private fun torrentFile(c: Context, hash: String) = File(dir(c), "$hash.torrent")
    private fun incoming(c: Context) = File(Downloads.destination(c), ".incoming").apply { mkdirs() }
    private fun finished(c: Context) = File(Downloads.destination(c), "Torrents").apply { mkdirs() }

    private fun loadMetas(c: Context) {
        if (metas.isNotEmpty()) return
        runCatching {
            val a = JSONArray(metaFile(c).readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                val p = o.optJSONArray("prio")?.let { pa -> List(pa.length()) { pa.getInt(it) } }.orEmpty()
                metas[o.getString("hash")] = Meta(o.getString("hash"), o.getString("name"), o.optBoolean("seed"), o.optBoolean("done"), p, o.optString("final").ifEmpty { null })
            }
        }
    }

    private fun saveMetas(c: Context) = metaFile(c).writeText(JSONArray(metas.values.map { m ->
        JSONObject().put("hash", m.hash).put("name", m.name).put("seed", m.seed).put("done", m.done).put("prio", JSONArray(m.priorities)).put("final", m.finalDir ?: "")
    }).toString())

    @Synchronized
    private fun ensure(c: Context): SessionManager {
        session?.let { return it }
        val s = SessionManager(false)
        s.start()
        session = s
        // Bring back anything still downloading (or seeding) from last time.
        loadMetas(c)
        metas.values.filter { !it.done || it.seed }.forEach { m ->
            val f = torrentFile(c, m.hash)
            if (f.isFile) runCatching {
                val ti = TorrentInfo(f)
                val save = if (m.done && m.finalDir != null) File(m.finalDir).parentFile ?: incoming(c) else incoming(c)
                s.download(ti, save, null, m.priorities.map { p -> if (p == 0) Priority.IGNORE else Priority.DEFAULT }.toTypedArray(), null, org.libtorrent4j.swig.torrent_flags_t())
            }
        }
        startPolling(c)
        return s
    }

    /** Called at app start: resumes unfinished torrents (if any). */
    fun resumeSaved(c: Context) {
        loadMetas(c)
        if (metas.values.any { !it.done || it.seed }) { ensure(c); TorrentService.start(c) }
    }

    // ---------------------------------------------------------------- adding

    fun addMagnet(c: Context, magnet: String) {
        val s = ensure(c)
        _status.value = "Fetching details for the magnet link…"
        scope.launch {
            val bytes = runCatching { s.fetchMagnet(magnet, 90, dir(c)) }.getOrNull()
            _status.value = null
            if (bytes == null) { _status.value = "Couldn't get details for that magnet link (no peers answered)"; return@launch }
            offer(c, bytes)
        }
    }

    fun addTorrentBytes(c: Context, bytes: ByteArray) { ensure(c); scope.launch { offer(c, bytes) } }

    private fun offer(c: Context, bytes: ByteArray) {
        val ti = runCatching { TorrentInfo.bdecode(bytes) }.getOrElse { _status.value = "That isn't a valid .torrent"; return }
        val fs = ti.files()
        val files = (0 until ti.numFiles()).map { i ->
            val path = fs.filePath(i)
            val ext = path.substringAfterLast('.', "").lowercase()
            TorrentFile(i, path, fs.fileSize(i), ext in Formats.EBOOK || ext in Formats.AUDIO || ext in setOf("jpg", "jpeg", "png", "pdf", "azw3", "mobi", "zip"))
        }
        _pending.value = PendingTorrent(ti.infoHash().toHex(), ti.name(), files, bytes)
    }

    fun cancelPending() { _pending.value = null }

    /** Starts downloading the chosen files. */
    fun start(c: Context, pending: PendingTorrent, chosen: Set<Int>, seed: Boolean) {
        val s = ensure(c)
        _pending.value = null
        torrentFile(c, pending.hash).writeBytes(pending.bytes)
        val prios = pending.files.map { if (it.index in chosen) 4 else 0 }
        metas[pending.hash] = Meta(pending.hash, pending.name, seed, false, prios, null)
        saveMetas(c)
        val ti = TorrentInfo.bdecode(pending.bytes)
        s.download(ti, incoming(c), null, prios.map { if (it == 0) Priority.IGNORE else Priority.DEFAULT }.toTypedArray(), null, org.libtorrent4j.swig.torrent_flags_t())
        TorrentService.start(c)
    }

    // -------------------------------------------------------------- controls

    private fun handle(hash: String): TorrentHandle? = session?.find(org.libtorrent4j.Sha1Hash.parseHex(hash))?.takeIf { it.isValid }

    fun pause(hash: String) = handle(hash)?.let { it.unsetFlags(org.libtorrent4j.TorrentFlags.AUTO_MANAGED); it.pause() }
    fun resume(c: Context, hash: String) { ensure(c); handle(hash)?.resume(); TorrentService.start(c) }

    fun setSeed(c: Context, hash: String, seed: Boolean) {
        metas[hash]?.let { metas[hash] = it.copy(seed = seed); saveMetas(c) }
        if (seed) handle(hash)?.resume()
    }

    /** Removes from the list. [deleteFiles] also deletes what was downloaded. */
    fun remove(c: Context, hash: String, deleteFiles: Boolean) {
        val h = handle(hash)
        if (h != null) { if (deleteFiles) session?.remove(h, SessionHandle.DELETE_FILES) else session?.remove(h) }
        if (deleteFiles) metas[hash]?.finalDir?.let { File(it).deleteRecursively() }
        metas.remove(hash); saveMetas(c)
        torrentFile(c, hash).delete()
        _items.value = _items.value.filterNot { it.hash == hash }
        Library.get(c).refresh()
    }

    // --------------------------------------------------------------- polling

    private fun startPolling(c: Context) {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                tick(c)
                delay(1000)
            }
        }
    }

    private fun tick(c: Context) {
        session ?: return
        val list = metas.values.mapNotNull { m ->
            val h = handle(m.hash)
            if (h == null) return@mapNotNull if (m.done) TorrentItem(m.hash, m.name, 1f, 0, 0, 0, 0, 0, "done", m.seed, m.finalDir) else null
            val st = h.status()
            val state = when {
                m.done && !m.seed -> "done"
                st.state() == TorrentStatus.State.DOWNLOADING_METADATA -> "metadata"
                st.state() == TorrentStatus.State.CHECKING_FILES || st.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> "checking"
                st.isFinished || st.isSeeding -> if (m.seed) "seeding" else "done"
                st.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero() -> "paused"
                else -> "downloading"
            }
            if ((st.isFinished || st.isSeeding) && !m.done) finish(c, m, h)
            TorrentItem(m.hash, m.name, st.progress(), st.downloadPayloadRate(), st.uploadPayloadRate(), st.numPeers(), st.totalWanted(), st.totalWantedDone(), state, m.seed, metas[m.hash]?.finalDir)
        }
        _items.value = list
        val active = list.filter { it.state in setOf("metadata", "checking", "downloading", "seeding") }
        if (SilkSpinner.canNotify(c)) TorrentService.update(c, active)
        if (active.isEmpty() && _pending.value == null && _status.value == null) TorrentService.stop(c)
    }

    /** Download complete: move out of the hidden folder into the library; stop unless you chose to seed. */
    private fun finish(c: Context, m: Meta, h: TorrentHandle) {
        val target = finished(c)
        val name = m.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        var dest = File(target, name)
        var n = 2
        while (dest.exists()) dest = File(target, "$name ($n)").also { n++ }
        if (m.seed) {
            h.moveStorage(target.absolutePath)
        } else {
            h.pause()
            session?.remove(h)
            File(incoming(c), m.name).let { src -> if (!src.renameTo(dest)) src.copyRecursively(dest, overwrite = false).also { src.deleteRecursively() } }
        }
        metas[m.hash] = m.copy(done = true, finalDir = if (m.seed) File(target, m.name).absolutePath else dest.absolutePath)
        saveMetas(c)
        val lib = Library.get(c)
        scope.launch {
            lib.scan()
            val folder = metas[m.hash]?.finalDir.orEmpty()
            val book = lib.books.value.firstOrNull { it.path.startsWith(folder) || it.tracks.any { t -> t.path.startsWith(folder) } }
            if (SilkSpinner.canNotify(c)) NotificationManagerCompat.from(c).notify(
                9191,
                WormNotify.done(
                    c, WormNotify.CH_DOWNLOADS, "Torrent finished", "“${m.name}” is in your library 🪱",
                    largeIcon = WormNotify.cover(book?.coverPath),
                    contentIntent = WormNotify.open(c, 91, screen = "downloads"),
                    actions = book?.let { listOf((if (it.isAudio) "Listen now" else "Read now") to WormNotify.open(c, 92, bookPath = it.path)) }.orEmpty(),
                ),
            )
        }
    }
}

/** Keeps torrents running while you use other apps; shows the crawling-worm progress notification. */
class TorrentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WormNotify.channels(this)
        val n = WormNotify.progress(this, WormNotify.CH_DOWNLOADS, "Torrents", "Starting…", -1f, contentIntent = WormNotify.open(this, 90, screen = "downloads"))
        ServiceCompat.startForeground(this, NOTIF, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        return START_STICKY
    }

    companion object {
        private const val NOTIF = 9090
        @Volatile private var running = false

        fun start(c: Context) {
            if (running) return
            running = true
            ContextCompat.startForegroundService(c, Intent(c, TorrentService::class.java))
        }

        fun stop(c: Context) {
            if (!running) return
            running = false
            c.stopService(Intent(c, TorrentService::class.java))
        }

        fun update(c: Context, active: List<TorrentItem>) {
            if (!running || active.isEmpty()) return
            val total = active.sumOf { it.total }.coerceAtLeast(1)
            val done = active.sumOf { it.done }
            val down = active.sumOf { it.downBps }
            val seeding = active.all { it.state == "seeding" }
            NotificationManagerCompat.from(c).notify(
                NOTIF,
                WormNotify.progress(
                    c, WormNotify.CH_DOWNLOADS,
                    title = if (seeding) "Sharing ${active.size} torrent${if (active.size > 1) "s" else ""}" else "Torrents · ${active.size} active",
                    text = active.joinToString(" · ") { it.name }.take(120) + if (down > 0) "  ↓ ${com.worm.app.util.formatBytes(down.toLong())}/s" else "",
                    fraction = if (seeding) 1f else done.toFloat() / total,
                    segments = active.map { it.total.coerceAtLeast(1) },
                    contentIntent = WormNotify.open(c, 90, screen = "downloads"),
                ),
            )
        }
    }
}
