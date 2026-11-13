package dev.ii.tools.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.files.FileServer
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PhoneMedia
import dev.ii.tools.pcactions.PcActions
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.shots.ShotSync
import dev.ii.tools.live.LiveHub
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.phonedata.LauncherIcons
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface LinkStatus {
    data object Unpaired : LinkStatus
    /** Paired, and nothing on screen needs the link: no socket is open. */
    data object Idle : LinkStatus
    data object Pairing : LinkStatus
    data object Connecting : LinkStatus
    data class Online(val host: String) : LinkStatus
    data class Offline(val problem: LinkProblem) : LinkStatus
}

enum class LinkProblem {
    /** No address answered, and discovery found nothing. */
    UNREACHABLE,
    /** The PC no longer knows this phone. */
    REJECTED,
    /** The pairing code expired, was used, or the PC was not pairing. */
    PAIRING_FAILED,
    /** Android 17+: nearby devices / local network access not granted. */
    NO_PERMISSION,
}

/** [toggle] actions have an on/off state the shell keeps the phone told about. */
data class ShellAction(val id: String, val name: String, val icon: String, val toggle: Boolean)

/** What the shell announced in its `hello`: what it can do right now. */
data class ShellInfo(val name: String, val caps: Set<String>, val actions: List<ShellAction>)

/**
 * The ii link (see the shell's scripts/iiLink/PROTOCOL.md): one TLS WebSocket to the
 * paired PC, open only while the app is in front (and for a short grace after), so an
 * idle phone holds no socket, thread or wakelock for it.
 *
 * The PC's certificate is self-signed; it is trusted only when its SHA-256 matches the
 * fingerprint from the pairing QR code.
 */
object LinkHub {
    private const val PATH = "/iilink/1"
    private const val BACKGROUND_GRACE_MS = 30_000L
    /** After a call: long enough for the PC to see the end and a call back. */
    private const val HOLD_GRACE_MS = 10_000L
    private const val BURST_MS = 120_000L
    private const val STAY_PREFS = "link"
    private const val STAY = "stayConnected"
    private const val ALWAYS = "always"
    private const val QUIET_MS = 300_000L
    private const val REPLY_TIMEOUT_MS = 5_000L
    private const val FILE_CHUNK = 256 * 1024
    /** OkHttp closes a socket whose queue passes 16 MiB; stay well under it. */
    private const val FILE_QUEUE = 2L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Unpaired)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _pc = MutableStateFlow<PairedPc?>(null)
    val pc: StateFlow<PairedPc?> = _pc.asStateFlow()

    private val _shell = MutableStateFlow<ShellInfo?>(null)
    val shell: StateFlow<ShellInfo?> = _shell.asStateFlow()

    /** The toggle actions that are on right now (the PC locked, media mode open…). */
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val _workspaces = MutableStateFlow<Workspaces?>(null)
    val workspaces: StateFlow<Workspaces?> = _workspaces.asStateFlow()
    private val _modes = MutableStateFlow<Modes?>(null)
    val modes: StateFlow<Modes?> = _modes.asStateFlow()
    private val _presets = MutableStateFlow<Presets?>(null)
    val presets: StateFlow<Presets?> = _presets.asStateFlow()
    private val _audio = MutableStateFlow<PcAudio?>(null)
    val audio: StateFlow<PcAudio?> = _audio.asStateFlow()
    private val _deck = MutableStateFlow<List<DeckAction>?>(null)
    val deck: StateFlow<List<DeckAction>?> = _deck.asStateFlow()
    private val _apps = MutableStateFlow<List<PcApp>?>(null)
    val apps: StateFlow<List<PcApp>?> = _apps.asStateFlow()
    private val _perf = MutableStateFlow<PcPerf?>(null)
    val perf: StateFlow<PcPerf?> = _perf.asStateFlow()
    @Volatile private var perfWanted = false

    /** The performance panel is on screen: the PC samples only while it is. */
    fun subscribePerf(on: Boolean) {
        perfWanted = on
        if (!on) _perf.value = null
        socket?.send(JSONObject().put("t", "sub").put("what", "perf").put("on", on).toString())
    }

    private lateinit var app: Context
    private var session: Job? = null
    private var stopJob: Job? = null
    private var foreground = false

    @Volatile private var socket: WebSocket? = null
    @Volatile private var unpairedByPc = false
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val nextId = AtomicInteger(1)

    // Shared pool and dispatcher; each PC gets a thin copy carrying its pinned trust.
    private val baseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            // Kept open in the background now: a ping a minute is enough for the PC to
            // notice a phone gone (it drops a socket silent for 200 s) and cheap on battery.
            .pingInterval(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Symbols.init(app)
        scope.launch { Look.load(app) }
        _pc.value = LinkPrefs.load(app)
        _status.value = if (_pc.value == null) LinkStatus.Unpaired else LinkStatus.Idle
        watchNetwork()
        // Any start of the process (the notification listener binding at boot included)
        // brings the link up when the user wants it kept.
        if (_pc.value != null && staysConnected()) hold(ALWAYS)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun onForeground() {
        foreground = true
        stopJob?.cancel()
        ensureSession()
    }

    fun onBackground() {
        foreground = false
        scheduleStop(BACKGROUND_GRACE_MS)
    }

    private fun scheduleStop(after: Long) {
        stopJob?.cancel()
        if (holds.isNotEmpty()) return
        stopJob = scope.launch {
            delay(after)
            if (!foreground && holds.isEmpty()) endSession()
        }
    }

    /**
     * Reasons, besides the app being in front, to keep the link up: a call ringing or
     * in progress. Each comes from a part of the app that is alive in the background
     * anyway (the notification listener), so nothing extra is kept running for it.
     */
    private val holds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun hold(reason: String) {
        if (holds.add(reason)) {
            stopJob?.cancel()
            ensureSession()
        }
    }

    fun release(reason: String) {
        if (holds.remove(reason) && !foreground) scheduleStop(HOLD_GRACE_MS)
    }

    // ── Calls ─────────────────────────────────────────────────────────────

    /** The call state changed: tell the PC, opening the link for it if needed. */
    fun reportCall(state: String, name: String) {
        if (_pc.value == null) return
        socket?.send(JSONObject().put("t", "call").put("state", state).put("name", name).toString())
        if (state == "idle") release("call") else hold("call")
    }

    // ── Notifications ─────────────────────────────────────────────────────

    /** The link is up, whatever the PC takes. */
    fun isConnected(): Boolean = socket != null

    /** The PC takes notifications right now. */
    fun isOnline(): Boolean = socket != null && _shell.value?.caps?.contains("notifs") == true

    fun sendJson(m: JSONObject) {
        socket?.send(m.toString())
    }

    private var burstJob: Job? = null
    /** After the PC could not be reached for a burst: no new attempt before this. */
    @Volatile private var quietUntil = 0L

    /**
     * A notification (or a live update) wants the PC: bring the link up, or keep it,
     * until [BURST_MS] after the last one. A PC away from the phone is not retried for
     * every notification: one failed attempt quiets bursts for [QUIET_MS].
     */
    fun notifBurst() {
        if (_pc.value == null || (!foreground && System.currentTimeMillis() < quietUntil)) return
        hold("notifs")
        burstJob?.cancel()
        burstJob = scope.launch {
            delay(BURST_MS)
            release("notifs")
        }
    }

    private val readyCallbacks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Runs [block] now if the PC takes notifications, else on the next link that does. */
    fun onLinkReady(block: () -> Unit) {
        if (isOnline()) block() else readyCallbacks += block
    }

    /** What this phone can do for the PC, sent on connect and when it changes. */
    fun announceCaps() {
        val caps = org.json.JSONArray()
        if (CallWatcher.running.value) caps.put("calls")
        if (NotifMirror.running.value) caps.put("notifs")
        if (ClipSync.receives(app)) caps.put("clip")
        if (ShotSync.receives(app)) caps.put("shots")
        PhoneData.caps(app).forEach { caps.put(it) }
        DndSync.caps(app).forEach { caps.put(it) }
        PcLive.caps(app).forEach { caps.put(it) }
        PhoneMedia.caps(app).forEach { caps.put(it) }
        PcMedia.caps(app).forEach { caps.put(it) }
        caps.put("launcherIcons")
        Look.caps().forEach { caps.put(it) }
        FileServer.caps(app).forEach { caps.put(it) }
        socket?.send(JSONObject().put("t", "caps").put("caps", caps).toString())
    }

    /** Wakes the PC (Wake-on-LAN), then keeps trying to reach it while it boots. */
    suspend fun wake(): Boolean {
        val sent = Wake.send(app)
        if (sent) {
            networkEpoch.value++
            if (session?.isActive != true) retry()
        }
        return sent
    }

    /** Try again now, skipping whatever backoff is running. */
    fun retry() {
        endSession()
        _status.value = if (_pc.value == null) LinkStatus.Unpaired else LinkStatus.Connecting
        if (foreground) ensureSession() else _status.value = LinkStatus.Idle.takeIf { _pc.value != null } ?: LinkStatus.Unpaired
    }

    private fun ensureSession() {
        if (session?.isActive == true || _pc.value == null) return
        session = scope.launch { runSession() }
    }

    private fun endSession() {
        session?.cancel()
        session = null
        socket?.close(1000, null)
        socket = null
        clearShell()
        if (_pc.value != null && _status.value !is LinkStatus.Offline) _status.value = LinkStatus.Idle
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    fun pair(uri: PairingUri) {
        session?.cancel()
        socket?.close(1000, null)
        session = scope.launch { runPairing(uri) }
    }

    /** Forgets the PC here, and asks it to forget this phone too when it can hear us. */
    fun forget() {
        socket?.send(JSONObject().put("t", "unpair").toString())
        scope.launch {
            delay(300)
            forgetLocally()
        }
    }

    private fun forgetLocally() {
        session?.cancel()
        session = null
        socket?.close(1000, null)
        socket = null
        LinkPrefs.clear(app)
        holds.clear()
        _pc.value = null
        clearShell()
        _status.value = LinkStatus.Unpaired
    }

    /** A pairing link that arrived before the network permission did. */
    private var pendingPairing: PairingUri? = null

    /** The UI got the network permission: carry on with whatever was waiting for it. */
    fun onPermissionGranted() {
        val uri = pendingPairing
        pendingPairing = null
        if (uri != null) pair(uri) else retry()
    }

    private suspend fun runPairing(uri: PairingUri) {
        if (!hasNetworkPermission(app)) {
            pendingPairing = uri
            _status.value = LinkStatus.Offline(LinkProblem.NO_PERMISSION)
            return
        }
        _status.value = LinkStatus.Pairing
        val client = clientFor(uri.fingerprint)
        val hello = JSONObject()
            .put("t", "pair")
            .put("secret", uri.secret)
            .put("name", deviceName())
            .put("model", Build.MODEL)
        for (host in uri.hosts) {
            val s = open(client, host, uri.port)
            s.ws.send(hello.toString())
            val reply = withTimeoutOrNull(6_000) { s.first.await() }
            s.ws.close(1000, null)
            when (reply?.optString("t")) {
                "paired" -> {
                    val pc = PairedPc(
                        id = reply.getString("id"),
                        token = reply.getString("token"),
                        fingerprint = uri.fingerprint,
                        hosts = listOf(host) + uri.hosts.filter { it != host },
                        port = uri.port,
                        name = reply.optString("name").ifEmpty { uri.pcName },
                    )
                    LinkPrefs.save(app, pc)
                    _pc.value = pc
                    if (staysConnected()) holds.add(ALWAYS)
                    runSession()
                    return
                }
                "authErr" -> {
                    _status.value = LinkStatus.Offline(LinkProblem.PAIRING_FAILED)
                    return
                }
            }
        }
        _status.value = LinkStatus.Offline(LinkProblem.UNREACHABLE)
    }

    // ── Session ───────────────────────────────────────────────────────────

    private enum class Outcome { ENDED, UNREACHABLE, REJECTED, UNPAIRED }

    private suspend fun runSession() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val pc = _pc.value ?: return
            if (!hasNetworkPermission(app)) {
                _status.value = LinkStatus.Offline(LinkProblem.NO_PERMISSION)
                return
            }
            if (_status.value !is LinkStatus.Online) _status.value = LinkStatus.Connecting
            when (connectOnce(pc)) {
                Outcome.ENDED -> failures = 0
                Outcome.REJECTED -> {
                    _status.value = LinkStatus.Offline(LinkProblem.REJECTED)
                    return
                }
                Outcome.UNPAIRED -> {
                    forgetLocally()
                    return
                }
                Outcome.UNREACHABLE -> {
                    // The PC may have a new address: ask the LAN before backing off.
                    val found = discover(pc)
                    if (found != null && found != pc.hosts.firstOrNull()) {
                        remember(pc, found)
                        continue
                    }
                    failures++
                    _status.value = LinkStatus.Offline(LinkProblem.UNREACHABLE)
                    // Only notifications wanted it, from the background: the PC is not
                    // around, so stop here instead of retrying for every notification.
                    if (!foreground && holds.isNotEmpty() && holds.all { it == "notifs" }) {
                        quietUntil = System.currentTimeMillis() + QUIET_MS
                        holds.remove("notifs")
                        session = null
                        return
                    }
                }
            }
            // Away from the PC, in the background: slow down to every few minutes, but try
            // again at once when the network changes (home Wi-Fi coming back).
            val cap = if (foreground) 30_000L else 300_000L
            val wait = if (failures == 0) 1_000L else minOf(cap, 2_000L shl minOf(failures - 1, 7))
            val epoch = networkEpoch.value
            withTimeoutOrNull(wait) { networkEpoch.first { it != epoch } }
        }
    }

    // ── Staying connected ─────────────────────────────────────────────────

    /** Bumped when the default network changes, to cut a reconnect wait short. */
    private val networkEpoch = MutableStateFlow(0)

    /**
     * Keep the link open in the background, so the PC can reach the phone at any time
     * (its live updates, Do Not Disturb, screenshots, the clipboard). On by default.
     */
    fun staysConnected(): Boolean = app.getSharedPreferences(STAY_PREFS, Context.MODE_PRIVATE).getBoolean(STAY, true)

    fun setStaysConnected(on: Boolean) {
        app.getSharedPreferences(STAY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(STAY, on).apply()
        if (on && _pc.value != null) hold(ALWAYS) else release(ALWAYS)
    }

    /** Doze cuts a background app's sockets unless it is let off battery optimisation. */
    fun batteryUnrestricted(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

    private fun watchNetwork() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                networkEpoch.value++
            }
        })
    }

    private suspend fun connectOnce(pc: PairedPc): Outcome {
        val client = clientFor(pc.fingerprint)
        val auth = JSONObject().put("t", "auth").put("id", pc.id).put("token", pc.token).toString()
        for (host in pc.hosts) {
            val s = open(client, host, pc.port)
            s.ws.send(auth)
            val reply = withTimeoutOrNull(5_000) { s.first.await() }
            when (reply?.optString("t")) {
                "authOk" -> {
                    if (host != pc.hosts.first()) remember(pc, host)
                    unpairedByPc = false
                    socket = s.ws
                    _status.value = LinkStatus.Online(host)
                    Symbols.flush()
                    announceCaps()
                    CallWatcher.current()?.let { (state, name) ->
                        s.ws.send(JSONObject().put("t", "call").put("state", state).put("name", name).toString())
                    }
                    try {
                        s.closed.await()
                    } finally {
                        if (socket === s.ws) socket = null
                        s.ws.close(1000, null)
                        clearShell()
                        PcLive.linkLost(app)
                        incoming?.discard()
                        incoming = null
                        // Live updates go back to the relay copy while the link is down.
                        LiveHub.listener?.onLinkChanged()
                        pending.values.forEach { it.complete("offline") }
                        pending.clear()
                    }
                    return if (unpairedByPc) Outcome.UNPAIRED else Outcome.ENDED
                }
                "authErr" -> {
                    s.ws.cancel()
                    return Outcome.REJECTED
                }
                else -> s.ws.cancel()
            }
        }
        return Outcome.UNREACHABLE
    }

    /** What the shell told us lasts only as long as the socket it came over. */
    private fun clearShell() {
        _shell.value = null
        _active.value = emptySet()
        _workspaces.value = null
        _modes.value = null
        _presets.value = null
        _audio.value = null
        _deck.value = null
        _apps.value = null
        PcMedia.clear()
        FileServer.stop()
    }

    private fun remember(pc: PairedPc, host: String) {
        val updated = pc.copy(hosts = listOf(host) + pc.hosts.filter { it != host })
        LinkPrefs.save(app, updated)
        _pc.value = updated
    }

    // ── Files from the PC ─────────────────────────────────────────────────

    @Volatile private var incoming: Incoming? = null

    private fun startFile(header: JSONObject) {
        incoming?.discard()
        val inc = Incoming(java.io.File(app.cacheDir, "incoming"), header)
        if (!inc.valid) {
            inc.discard()
            socket?.send(JSONObject().put("t", "err").put("id", inc.id).put("code", "too_large").toString())
            return
        }
        incoming = inc
    }

    private fun receiveChunk(bytes: ByteArray) {
        val inc = incoming ?: return
        val whole = runCatching { inc.write(bytes) }.getOrElse {
            inc.discard()
            incoming = null
            return
        }
        if (!whole) return
        incoming = null
        scope.launch {
            val ok = when (inc.purpose) {
                "screenshot" -> ShotSync.savePcShot(app, inc.file, inc.name)
                "share" -> PcActions.saveDownload(app, inc.file, inc.name)
                "pcMediaArt" -> PcMedia.artReceived(app, inc.file, inc.name)
                "look" -> Look.received(app, inc.file, inc.name)
                else -> false
            }
            inc.file.delete()
            socket?.send(JSONObject().put("t", if (ok) "ok" else "err").put("id", inc.id).apply { if (!ok) put("code", "write") }.toString())
        }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "file" -> startFile(m)
            "hello" -> {
                val actions = m.optJSONArray("actions")
                val list = buildList {
                    if (actions != null) for (i in 0 until actions.length()) {
                        val a = actions.optJSONObject(i) ?: continue
                        add(ShellAction(a.optString("id"), a.optString("name"), a.optString("icon"), a.optBoolean("toggle")))
                    }
                }
                Wake.remember(app, m.optJSONArray("wake"))
                val caps = m.optJSONArray("caps")
                _shell.value = ShellInfo(
                    name = m.optString("name"),
                    caps = buildSet { if (caps != null) for (i in 0 until caps.length()) add(caps.optString(i)) },
                    actions = list,
                )
                if (_shell.value?.caps?.contains("phoneData") == true) PhoneData.sendStatus()
                if (_shell.value?.caps?.contains("dnd") == true) DndSync.report()
                if (_shell.value?.caps?.contains("media") == true) PhoneMedia.sendAll()
                // A panel open across a reconnect asks again.
                if (perfWanted) socket?.send(JSONObject().put("t", "sub").put("what", "perf").put("on", true).toString())
                // The PC takes notifications: give it the whole list, and the live
                // updates their link path.
                if (isOnline()) {
                    NotifMirror.sendAll()
                    LiveHub.listener?.onLinkChanged()
                    readyCallbacks.forEach { it() }
                    readyCallbacks.clear()
                }
            }
            "state" -> {
                val on = m.optJSONArray("on")
                _active.value = buildSet { if (on != null) for (i in 0 until on.length()) add(on.optString(i)) }
            }
            "workspaces" -> _workspaces.value = Topics.workspaces(m)
            "modes" -> _modes.value = Topics.modes(m)
            "presets" -> _presets.value = Topics.presets(m)
            "audio" -> _audio.value = Topics.audio(m)
            "deck" -> _deck.value = DeckTopics.deck(m)
            "apps" -> _apps.value = DeckTopics.apps(m)
            "perf" -> if (perfWanted) _perf.value = PcPerf.from(m)
            "icons" -> Symbols.put(DeckTopics.paths(m))
            "ok" -> pending.remove(m.optInt("id"))?.complete(null)
            "err" -> pending.remove(m.optInt("id"))?.complete(m.optString("code").ifEmpty { "failed" })
            "unpaired" -> unpairedByPc = true
            "phone" -> CallWatcher.command(m.optString("a"), m.optString("arg").ifEmpty { null })
            "get" -> when (m.optString("what")) {
                "contacts" -> PhoneData.onContactsAsked(m.optString("have"))
                "notifs" -> NotifMirror.sendAll()
                "launcherIcons" -> m.optJSONArray("pkgs")?.let { k -> LauncherIcons.want(app, List(k.length()) { k.optString(it) }) }
                "photos" -> m.optJSONArray("keys")?.let { k -> PhoneData.onPhotosAsked(List(k.length()) { k.optString(it) }) }
            }
            "notifAct" -> NotifMirror.command(m)
            "clip" -> ClipSync.receive(app, m)
            "act" -> PcActions.handle(app, m)
            "mediaCmd" -> PhoneMedia.command(m)
            "pcMedia" -> PcMedia.update(app, m)
            "files" -> FileServer.command(app, m)
            "pad" -> dev.ii.tools.touchpad.Touchpad.onReply(m)
            "draw" -> dev.ii.tools.draw.DrawPad.onMessage(m)
            "present" -> dev.ii.tools.present.Presenter.onMessage(m)
            "dnd" -> DndSync.apply(m.optBoolean("on"))
            "pcLive" -> PcLive.update(app, m.optJSONArray("items"))
            "look" -> Look.update(app, m.optString("wall"))
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** Asks the shell to run [actionId]: null once it confirms, else why not ("locked"…). */
    suspend fun run(actionId: String, arg: Any? = null): String? {
        val ws = socket ?: return "offline"
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<String?>()
        pending[id] = reply
        ws.send(command(id, actionId, arg))
        // Wrapped: a null reply means "done", which a bare withTimeoutOrNull would confuse with a timeout.
        val outcome = withTimeoutOrNull(REPLY_TIMEOUT_MS) { Reply(reply.await()) }
        pending.remove(id)
        return if (outcome == null) "timeout" else outcome.code
    }

    private class Reply(val code: String?)

    /**
     * Runs [actionId] from outside the app (a Quick Settings tile, a widget): brings the
     * link up if needed, waits for the shell's hello, runs it, and lets the link go.
     */
    suspend fun runWhenReady(actionId: String, arg: Any? = null): String? {
        if (_pc.value == null) return "unpaired"
        hold("remote")
        try {
            val ready = withTimeoutOrNull(8_000) {
                status.first { it is LinkStatus.Online }
                shell.first { it != null }
            }
            return if (ready == null) "offline" else run(actionId, arg)
        } finally {
            release("remote")
        }
    }

    /**
     * Fire and forget, for what is sent many times a second (a volume being dragged):
     * the pushed topic is the answer.
     */
    fun send(actionId: String, arg: Any? = null) {
        socket?.send(command(nextId.getAndIncrement(), actionId, arg))
    }

    /**
     * Sends a file for [purpose] (PROTOCOL.md, "Files"): a header, then binary frames,
     * paced by OkHttp's send queue so a large photo never sits whole in memory. Waits a
     * moment for the link when the app was just opened by a share. Null once the shell
     * has done what the file was for, else why not.
     */
    suspend fun upload(
        name: String,
        size: Long,
        open: () -> java.io.InputStream?,
        purpose: String,
        target: String,
        waitForLink: Boolean = true,
        onProgress: (Float) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        if (!waitForLink && socket == null) return@withContext "offline"
        if (withTimeoutOrNull(15_000) { status.first { it is LinkStatus.Online } } == null) return@withContext "offline"
        val ws = socket ?: return@withContext "offline"
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<String?>()
        pending[id] = reply
        try {
            val header = JSONObject().put("t", "file").put("id", id).put("name", name).put("size", size)
                .put("purpose", purpose).put("target", target)
            if (!ws.send(header.toString())) return@withContext "offline"
            val input = open() ?: return@withContext "read"
            var sent = 0L
            input.use { stream ->
                val buf = ByteArray(FILE_CHUNK)
                while (sent < size) {
                    val n = stream.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                    if (n <= 0) break
                    while (ws.queueSize() > FILE_QUEUE) delay(15)
                    if (!ws.send(okio.ByteString.of(*buf.copyOf(n)))) return@withContext "offline"
                    sent += n
                    onProgress(sent.toFloat() / size)
                }
            }
            if (sent != size) return@withContext "read"
            val outcome = withTimeoutOrNull(60_000) { Reply(reply.await()) }
            if (outcome == null) "timeout" else outcome.code
        } finally {
            pending.remove(id)
        }
    }

    /** The PC's installed apps; they arrive in [apps]. */
    fun requestApps() {
        socket?.send(JSONObject().put("t", "get").put("what", "apps").toString())
    }

    /** Outlines of Material Symbols the app does not carry; they arrive in [Symbols]. */
    internal fun requestSymbols(names: Collection<String>): Boolean {
        val ws = socket ?: return false
        return ws.send(JSONObject().put("t", "icons").put("names", org.json.JSONArray(names)).toString())
    }

    private fun command(id: Int, actionId: String, arg: Any?): String {
        val m = JSONObject().put("t", "cmd").put("id", id).put("a", actionId)
        if (arg != null) m.put("arg", arg)
        return m.toString()
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private class Session(
        val ws: WebSocket,
        val first: CompletableDeferred<JSONObject?>,
        val closed: CompletableDeferred<Unit>,
    )

    private fun open(client: OkHttpClient, host: String, port: Int): Session {
        val first = CompletableDeferred<JSONObject?>()
        val closed = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (!first.isCompleted) first.complete(m) else handle(m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                receiveChunk(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                first.complete(null)
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                first.complete(null)
                closed.complete(Unit)
            }
        }
        val host6 = if (':' in host) "[$host]" else host
        val request = Request.Builder().url("https://$host6:$port$PATH").build()
        return Session(client.newWebSocket(request, listener), first, closed)
    }

    private fun clientFor(fingerprint: String): OkHttpClient {
        val trust = PinnedTrust(fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return baseClient.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // The certificate names nothing; the pinned fingerprint is the identity.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** Trusts exactly one certificate: the one whose SHA-256 the QR code carried. */
    private class PinnedTrust(private val fingerprint: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(hex.toByteArray(), fingerprint.toByteArray())) {
                throw CertificateException("certificate does not match the paired PC")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("not a server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** "iilink?" to the LAN broadcast; the PC answers with its fingerprint and port. */
    private suspend fun discover(pc: PairedPc): String? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 1_500
                val probe = "iilink?".toByteArray()
                sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), pc.port))
                val buf = ByteArray(512)
                var found: String? = null
                while (found == null) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val reply = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    if (reply.optString("fp") == pc.fingerprint) found = packet.address.hostAddress
                }
                found
            }
        }.getOrNull()
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Android 17 puts the LAN behind a runtime permission (nearby devices group). */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun needsNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

    fun hasNetworkPermission(ctx: Context): Boolean =
        !needsNetworkPermission() || ctx.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
