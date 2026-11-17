package com.worm.app.wormhole

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worm.app.data.Book
import com.worm.app.data.Library
import com.worm.app.data.ReadingProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

enum class WormholeStage { Idle, Starting, Hosting, Joining, Connecting, Connected }

data class HostInfo(val urls: List<String>, val port: Int, val code: String, val invite: WormholeInvite, val hotspot: HotspotInfo?)
data class Nearby(val name: String, val host: String, val port: Int)
enum class TransferState { Queued, Active, Done, Skipped, Failed }

data class Transfer(
    val id: Long,
    val title: String,
    val file: String,
    val sending: Boolean,
    val total: Long,
    val done: Long = 0,
    val state: TransferState = TransferState.Queued,
    val error: String? = null,
)

private sealed interface Work {
    val transfer: Transfer
    data class Pull(override val transfer: Transfer, val bookId: String, val index: Int, val file: RemoteFile, val dest: File) : Work
    data class Push(override val transfer: Transfer, val file: File, val folder: String?, val offerId: String) : Work
}

/** Everything about the Wormhole: our server, the other device, and the transfer queue. */
private const val NOTIF = 8080

class WormholeViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library.get(app)
    private val prefs = library.prefs
    private val rnd = SecureRandom()

    private val _stage = MutableStateFlow(WormholeStage.Idle)
    val stage: StateFlow<WormholeStage> = _stage.asStateFlow()
    private val _host = MutableStateFlow<HostInfo?>(null)
    val host: StateFlow<HostInfo?> = _host.asStateFlow()
    private val _peers = MutableStateFlow<List<WormholePeer>>(emptyList())
    val peers: StateFlow<List<WormholePeer>> = _peers.asStateFlow()
    private val _nearby = MutableStateFlow<List<Nearby>>(emptyList())
    val nearby: StateFlow<List<Nearby>> = _nearby.asStateFlow()

    private val _remote = MutableStateFlow<WormholeClient?>(null)
    val remote: StateFlow<WormholeClient?> = _remote.asStateFlow()
    private val _remoteName = MutableStateFlow("")
    val remoteName: StateFlow<String> = _remoteName.asStateFlow()
    private val _remoteBooks = MutableStateFlow<List<RemoteBook>>(emptyList())
    val remoteBooks: StateFlow<List<RemoteBook>> = _remoteBooks.asStateFlow()

    /** Incoming offers waiting for your yes/no. */
    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()
    /** Senders you chose to trust for this session: their offers are accepted automatically. */
    private val trusted = mutableSetOf<String>()

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val myName: String get() = prefs.wormholeName
    fun setMyName(n: String) { if (n.isNotBlank()) prefs.wormholeName = n.trim().take(40) }

    private var server: WormholeServer? = null

    /** Off by default: great between your own devices, not something to share with everyone. */
    private val _shareSpots = MutableStateFlow(false)
    val shareSpots: StateFlow<Boolean> = _shareSpots.asStateFlow()
    fun setShareSpots(on: Boolean) { _shareSpots.value = on }
    @Volatile private var shareBurrow = false

    /** Burrow books are shared only while the Burrow is open AND you switched this on. */
    fun setShareBurrow(on: Boolean) {
        shareBurrow = on && library.burrowOpen.value
        server?.shareHidden = shareBurrow
    }

    /** Where received books go: a "Wormhole" folder inside your first library folder. */
    private fun inbox(): File {
        val base = library.sources.value.firstOrNull()?.path?.let(::File)
            ?: File(Environment.getExternalStorageDirectory(), "Books").also { dir ->
                dir.mkdirs(); library.addSource(dir.absolutePath)
            }
        return File(base, "Wormhole").apply { mkdirs() }
    }

    private var rescan: Job? = null
    private fun rescanSoon() {
        rescan?.cancel()
        rescan = viewModelScope.launch { delay(1500); library.refresh() }
    }

    private suspend fun ensureServer(): WormholeServer {
        server?.let { return it }
        val token = List(24) { "abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)] }.joinToString("")
        val code = (100000 + rnd.nextInt(900000)).toString()
        val port = WormholeNet.freePort()
        val s = WormholeServer(
            getApplication(), port, token, code, myName,
            catalog = { library.books.value.filter { !it.hidden || (shareBurrow && library.burrowOpen.value) } },
            progress = { library.progress.value },
            uploadRoot = ::inbox,
            onPeer = ::onPeer,
            onReceived = { f -> library.notifyMediaStore(f.absolutePath); say("Received ${f.name}"); rescanSoon() },
            onProgressIn = ::mergeProgress,
            onOffer = { o ->
                // Browsers already needed the code shown on this phone, so their drag-and-drop goes straight in.
                if (o.from in trusted || o.from.startsWith("Web browser")) { server?.decide(o.id, true); say("Receiving ${plural(o.titles.size)} from ${o.from}") }
                else _offers.update { it + o }
            },
            spotsAllowed = { _shareSpots.value },
            onIncoming = ::incoming,
        )
        s.shareHidden = shareBurrow
        s.startServer()
        server = s
        return s
    }

    /** Files someone is sending us show up in Transfers too, with live progress. */
    private val incomingIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun incoming(key: String, title: String, done: Long, total: Long, finished: Boolean) = viewModelScope.launch {
        val id = incomingIds.getOrPut(key) {
            val t = Transfer(nextId++, title, key.substringAfterLast('/'), sending = false, total = total, state = TransferState.Active)
            _transfers.update { it + t }; t.id
        }
        update(id) { it.copy(done = done, total = total, state = if (finished) TransferState.Done else TransferState.Active) }
        if (finished) {
            incomingIds.remove(key)
            if (_transfers.value.none { it.state == TransferState.Active }) finishTransferNotice()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastNotice > 700) { lastNotice = now; showTransferProgress() }
        }
    }

    private fun myUrls(port: Int) = WormholeNet.localAddresses().map { "http://$it:$port" }

    // ----------------------------------------------------------------- hosting

    fun host(useHotspot: Boolean) = viewModelScope.launch {
        _stage.value = WormholeStage.Starting
        try {
            val s = ensureServer()
            var hotspot: HotspotInfo? = null
            WormholeNet.stopHotspot()
            if (useHotspot) {
                hotspot = WormholeNet.startHotspot(getApplication())
                delay(1200) // let the hotspot interface come up and get its address
            }
            val addrs = WormholeNet.localAddresses()
            val invite = WormholeInvite(addrs, s.listeningPort, s.token, myName, hotspot?.ssid, hotspot?.pass, hotspot?.wpa3 == true)
            _host.value = HostInfo(addrs.map { "http://$it:${s.listeningPort}" }, s.listeningPort, s.code, invite, hotspot)
            if (!useHotspot) WormholeNet.advertise(getApplication(), myName, s.listeningPort)
            _stage.value = WormholeStage.Hosting
        } catch (e: Exception) {
            say(e.message ?: "Couldn't open the Wormhole")
            _stage.value = WormholeStage.Idle
        }
    }

    private fun onPeer(peer: WormholePeer) {
        _peers.update { list -> list.filterNot { it.name == peer.name && it.isBrowser == peer.isBrowser } + peer }
        if (peer.isBrowser) { say("${peer.name} came through the Wormhole"); return }
        // Another Worm joined us: connect back so we can browse theirs too.
        if (_remote.value == null && peer.token != null) viewModelScope.launch {
            val hosts = peer.urls.mapNotNull { runCatching { java.net.URL(it) }.getOrNull() }
            val base = WormholeClient.reach(hosts.map { it.host }, hosts.firstOrNull()?.port ?: return@launch) ?: return@launch
            connected(WormholeClient(base, peer.token), peer.name)
        }
    }

    // ----------------------------------------------------------------- joining

    fun startJoining() {
        _stage.value = WormholeStage.Joining
        _nearby.value = emptyList()
        WormholeNet.discover(getApplication()) { name, host, port ->
            if (name != myName) _nearby.update { l -> l.filterNot { it.host == host && it.port == port } + Nearby(name, host, port) }
        }
    }

    /** From a scanned QR code. */
    fun join(raw: String) = viewModelScope.launch {
        val invite = WormholeInvite.parse(raw) ?: run { say("That QR code isn't a Wormhole invite"); return@launch }
        _stage.value = WormholeStage.Connecting
        WormholeNet.stopDiscovery(getApplication())
        var base = WormholeClient.reach(invite.hosts, invite.port)
        if (base == null && invite.ssid != null) {
            say("Joining ${invite.name}'s hotspot…")
            val net = WormholeNet.joinHotspot(getApplication(), invite.ssid, invite.pass.orEmpty(), invite.wpa3)
            if (net == null) {
                say("Join Wi-Fi “${invite.ssid}” (password ${invite.pass}) in your phone's settings, then scan again")
                _stage.value = WormholeStage.Joining
                return@launch
            }
            delay(800)
            base = WormholeClient.reach(invite.hosts, invite.port)
        }
        if (base == null) {
            say("Couldn't reach ${invite.name}. Are you on the same Wi-Fi?")
            _stage.value = WormholeStage.Joining
            return@launch
        }
        finishJoin(WormholeClient(base, invite.token), invite.name)
    }

    /** From the "nearby" list + the 6-digit code. False for a wrong code. */
    suspend fun joinWithCode(n: Nearby, code: String): Boolean {
        val base = "http://${n.host}:${n.port}"
        val token = WormholeClient.login(base, code) ?: return false
        _stage.value = WormholeStage.Connecting
        WormholeNet.stopDiscovery(getApplication())
        finishJoin(WormholeClient(base, token), n.name)
        return true
    }

    private suspend fun finishJoin(client: WormholeClient, name: String) {
        val s = ensureServer()
        runCatching { client.introduce(myName, myUrls(s.listeningPort), s.token) }
        connected(client, name)
    }

    private suspend fun connected(client: WormholeClient, name: String) {
        _remote.value = client
        _remoteName.value = name
        _stage.value = WormholeStage.Connected
        loadRemote()
        say("Wormhole open to $name 🪱🌀🪱")
    }

    fun loadRemote() = viewModelScope.launch {
        val c = _remote.value ?: return@launch
        runCatching { c.books() }
            .onSuccess { (name, books) -> _remoteName.value = name; _remoteBooks.value = books }
            .onFailure { say("Lost the connection: ${it.message}") }
    }

    // ------------------------------------------------------------- offers in

    fun decide(offer: Offer, accept: Boolean, trustSender: Boolean = false) {
        server?.decide(offer.id, accept)
        if (accept && trustSender) trusted += offer.from
        _offers.update { list -> list.filterNot { it.id == offer.id } }
        say(if (accept) "Accepted — ${offer.from} is sending" else "Declined ${offer.from}'s books")
    }

    private fun plural(n: Int) = com.worm.app.util.plural(n, "book")

    // ------------------------------------------------------------ reading spots

    /**
     * Takes positions that are newer than ours (by last page turn / listen). Ebook ids are content
     * hashes, so they match everywhere; audiobook ids come from folder names and sizes, which can
     * differ between devices, so those also match by title + author + length.
     */
    private fun mergeProgress(incoming: Map<String, Spot>): Int {
        var n = 0
        val ours = library.progress.value
        val books = library.books.value
        val byId = books.associateBy { it.id }
        fun norm(s: String?) = s.orEmpty().lowercase().replace(Regex("""\(.*?\)|\[.*?]|unabridged|[^a-z0-9]"""), "")
        incoming.forEach { (rid, spot) ->
            val local = byId[rid] ?: spot.title?.let { t ->
                books.filter { it.isAudio && norm(it.title) == norm(t) && it.durationMs > 0 }
                    .filter { spot.author == null || norm(it.author) == norm(spot.author) || it.author.isBlank() }
                    .minByOrNull { kotlin.math.abs(it.durationMs - spot.durationMs) }
                    ?.takeIf { spot.durationMs <= 0 || kotlin.math.abs(it.durationMs - spot.durationMs) < maxOf(120_000L, spot.durationMs / 50) }
            } ?: return@forEach
            val theirs = spot.progress
            val mine = ours[local.id]
            val theirAt = theirs.movedAt.takeIf { it > 0 } ?: theirs.updatedAt
            val myAt = mine?.movedAt?.takeIf { it > 0 } ?: mine?.updatedAt ?: 0L
            if (theirAt <= myAt + 2_000) return@forEach
            // Same audio split differently here (one file vs. tracks): place it by whole-book time.
            var track = theirs.trackIndex; var pos = theirs.positionMs
            if (local.isAudio && local.tracks.size != spot.tracks && spot.tracks > 0) {
                var at = (theirs.fraction.toDouble() * local.durationMs).toLong()
                track = 0
                for ((i, t) in local.tracks.withIndex()) { if (at < t.durationMs || i == local.tracks.lastIndex) { track = i; break }; at -= t.durationMs }
                pos = at.coerceAtLeast(0)
            }
            library.saveProgress(local.id) {
                it.copy(
                    fraction = theirs.fraction, locator = theirs.locator, xpointer = theirs.xpointer,
                    trackIndex = track, positionMs = pos, finished = theirs.finished,
                    movedAt = theirAt,
                )
            }
            n++
        }
        if (n > 0) library.flushProgress()
        return n
    }

    /** Both ways: they get our newer spots, we get theirs. Books and audiobooks alike. */
    fun swapSpots() = viewModelScope.launch {
        val c = _remote.value ?: return@launch
        if (!_shareSpots.value) { say("Turn on “Share reading spots” first"); return@launch }
        runCatching {
            val took = mergeProgress(c.progress())
            c.sendProgress(library.progress.value.filterKeys { id -> library.book(id)?.hidden == false }, library::book)
            took
        }.onFailure { if ("403" in (it.message ?: "")) { say("${_remoteName.value} hasn't turned on reading-spot sharing"); return@launch } }.onSuccess { took -> say(if (took == 0) "Reading spots swapped; you were already up to date here" else "Reading spots swapped: ${com.worm.app.util.plural(took, "book")} moved here") }
            .onFailure { say("Couldn't swap spots: ${it.message}") }
    }

    // --------------------------------------------------------------- transfers

    private val queue = Channel<Work>(Channel.UNLIMITED)
    private var nextId = 1L
    private var runner: Job? = null
    private val work = mutableMapOf<Long, Work>()

    /** Pull books from the other device. Their Burrow books land in our Burrow if [burrowToBurrow]. */
    fun take(books: List<RemoteBook>, burrowToBurrow: Boolean) {
        val inbox = inbox()
        books.forEach { b ->
            val root = if (b.hidden && burrowToBurrow) library.burrow else inbox
            val dest = b.folder?.let { File(root, it.replace(Regex("""[\\/:*?"<>|]"""), "_")) } ?: root
            b.files.forEachIndexed { i, f ->
                enqueue(Work.Pull(Transfer(nextId++, b.title, f.name, sending = false, total = f.size), b.id, i, f, dest))
            }
        }
        if (books.isNotEmpty()) say("Pulling ${com.worm.app.util.plural(books.size, "book")} through the Wormhole")
    }

    /**
     * Push books to the other device: first an offer they can accept or decline, then the files.
     * Multi-file audiobooks keep their folder.
     */
    fun send(books: List<Book>) = viewModelScope.launch {
        val c = _remote.value ?: return@launch
        if (books.isEmpty()) return@launch
        val items = books.map { b ->
            val folder = if (b.isAudio && b.tracks.size > 1) File(b.path).name.substringBefore('#') else null
            val files = if (b.isAudio) b.tracks.map { File(it.path) } else listOf(File(b.path))
            Triple(b.title, folder, files)
        }
        val offerId = runCatching { c.offer(myName, items) }.getOrElse { say("Couldn't offer the books: ${it.message}"); return@launch }
        say("Waiting for ${_remoteName.value} to accept ${plural(books.size)}…")
        val ok = runCatching { c.awaitDecision(offerId) }.getOrDefault(false)
        if (!ok) { say("${_remoteName.value} didn't accept the books"); return@launch }
        items.forEach { (title, folder, files) ->
            files.forEach { f -> enqueue(Work.Push(Transfer(nextId++, title, f.name, sending = true, total = f.length()), f, folder, offerId)) }
        }
        say("Accepted! Sending ${plural(books.size)}")
    }

    private fun enqueue(w: Work) {
        work[w.transfer.id] = w
        _transfers.update { it + w.transfer }
        queue.trySend(w)
        if (runner?.isActive != true) runner = viewModelScope.launch { runQueue() }
    }

    private fun update(id: Long, f: (Transfer) -> Transfer) = _transfers.update { l -> l.map { if (it.id == id) f(it) else it } }

    private val notifier get() = androidx.core.app.NotificationManagerCompat.from(getApplication())
    private fun canNotify() = com.worm.app.convert.SilkSpinner.canNotify(getApplication())

    /** One notification for the whole batch: a segment per file, the worm crawling along. */
    private var lastNotice = 0L

    private fun showTransferProgress() {
        if (!canNotify()) return
        val list = _transfers.value.filter { it.state != TransferState.Skipped }
        val active = list.firstOrNull { it.state == TransferState.Active } ?: return
        val total = list.sumOf { it.total }.coerceAtLeast(1)
        val done = list.sumOf { if (it.state == TransferState.Done) it.total else it.done }
        val app = getApplication<Application>()
        @Suppress("MissingPermission")
        notifier.notify(
            NOTIF, com.worm.app.notify.WormNotify.progress(
                app, com.worm.app.notify.WormNotify.CH_WORMHOLE,
                title = (if (active.sending) "Sending to " else "Receiving from ") + _remoteName.value,
                text = "${active.title} · ${active.file}",
                fraction = done.toFloat() / total,
                segments = list.map { it.total.coerceAtLeast(1) },
                contentIntent = com.worm.app.notify.WormNotify.open(app, 80),
            ),
        )
    }

    private fun finishTransferNotice() {
        if (!canNotify()) return
        val got = _transfers.value.filter { !it.sending && it.state == TransferState.Done }.map { it.title }.distinct()
        val sent = _transfers.value.filter { it.sending && it.state == TransferState.Done }.map { it.title }.distinct()
        notifier.cancel(NOTIF)
        if (got.isEmpty() && sent.isEmpty()) return
        val app = getApplication<Application>()
        @Suppress("MissingPermission")
        notifier.notify(
            NOTIF + 1, com.worm.app.notify.WormNotify.done(
                app, com.worm.app.notify.WormNotify.CH_WORMHOLE,
                if (got.isNotEmpty()) "${com.worm.app.util.plural(got.size, "book")} came through the Wormhole 🌀" else "Sent ${com.worm.app.util.plural(sent.size, "book")} through the Wormhole",
                (got.ifEmpty { sent }).joinToString(", "),
                contentIntent = com.worm.app.notify.WormNotify.open(app, 81),
            ),
        )
    }

    private suspend fun runQueue() {
        com.worm.app.notify.WormNotify.channels(getApplication())
        for (w in queue) {
            val c = _remote.value ?: break
            val id = w.transfer.id
            update(id) { it.copy(state = TransferState.Active) }
            var last = 0L
            val progress: (Long) -> Unit = { b ->
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; update(id) { it.copy(done = b) } }
                if (now - lastNotice > 700) { lastNotice = now; showTransferProgress() }
            }
            showTransferProgress()
            runCatching {
                when (w) {
                    is Work.Pull -> c.download(w.bookId, w.index, w.file, w.dest, progress)
                    is Work.Push -> c.upload(w.file, w.folder, w.offerId, progress)
                }
            }.onSuccess { did ->
                if (w is Work.Pull && did) { library.notifyMediaStore(File(w.dest, w.file.name).absolutePath); rescanSoon() }
                update(id) { it.copy(state = if (did) TransferState.Done else TransferState.Skipped, done = it.total) }
            }.onFailure { e -> update(id) { it.copy(state = TransferState.Failed, error = e.message) } }
            if (queue.isEmpty) finishTransferNotice()
        }
    }

    /** Re-queues failed transfers; partial files resume where they stopped. */
    fun retryFailed() {
        val failed = _transfers.value.filter { it.state == TransferState.Failed }
        failed.forEach { t -> update(t.id) { it.copy(state = TransferState.Queued, error = null) }; work[t.id]?.let { queue.trySend(it) } }
        if (failed.isNotEmpty() && runner?.isActive != true) runner = viewModelScope.launch { runQueue() }
    }

    fun clearFinished() = _transfers.update { l ->
        l.filter { it.state == TransferState.Active || it.state == TransferState.Queued || it.state == TransferState.Failed }
            .also { keep -> work.keys.retainAll(keep.map { it.id }.toSet()) }
    }

    // ------------------------------------------------------------------- close

    fun leave() {
        server?.stop(); server = null
        WormholeNet.stopHotspot()
        WormholeNet.stopAdvertising(getApplication())
        WormholeNet.stopDiscovery(getApplication())
        WormholeNet.leaveHotspot(getApplication())
        runner?.cancel()
        _remote.value = null
        _host.value = null
        _peers.value = emptyList()
        _remoteBooks.value = emptyList()
        _offers.value = emptyList()
        trusted.clear()
        _shareSpots.value = false
        _stage.value = WormholeStage.Idle
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    private fun say(m: String) { _messages.tryEmit(m) }
}
