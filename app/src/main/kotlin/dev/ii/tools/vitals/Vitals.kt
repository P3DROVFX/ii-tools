package dev.ii.tools.vitals

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.ii.tools.BuildConfig
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * ii Vitals, the phone side (the shell's PROTOCOL.md, "Vitals"): health data from Samsung's SDK
 * and Health Connect, one source per type and day, as one JSON Day per local day.
 *
 * Nothing runs until a PC that advertised `vitals` is connected. Then every 10 minutes the
 * last 8 days are rebuilt and the days whose hash changed go out; once a day (and right after a
 * source becomes readable) every day is rebuilt the same way, which also catches edits and
 * deletions of older days. What each PC was sent is kept per PC, so a reconnect sends only
 * what changed meanwhile. Read-only: nothing is ever written to either store.
 */
object Vitals {
    private const val TAG = "iiVitals"
    private const val PREFS = "vitals"
    private const val SHARE = "share"
    private const val ROUTES = "routes"
    private const val LAST_FULL = "lastFull"
    /** A frame is capped at 1 MiB; a message stays under this. */
    const val MAX_BYTES = 900_000
    private const val CHECK_MS = 10 * 60_000L
    private const val MAX_BACKOFF_MS = 160 * 60_000L
    private const val FULL_MS = 24 * 3600_000L
    private const val RECENT_DAYS = 7L
    private const val MAX_ROUTE_POINTS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Mutex()

    @Volatile private var ctx: Context? = null
    @Volatile private var readable = false
    @Volatile private var oldest: LocalDate? = null
    /** meta.oldest per Steps-from choice: the first day with data a build from [oldest] found. */
    private val firstData = ConcurrentHashMap<String, LocalDate>()
    @Volatile private var sources: Pair<String, String>? = null
    private var loop: Job? = null
    private var interval = CHECK_MS
    private val routeRefs = ConcurrentHashMap<String, RouteRef>()

    /** One PC's view: what it was sent, its Steps-from choice, and an answer's pages still to go. */
    class Peer(val key: String, private val persist: Boolean) {
        @Volatile var stepsFrom = "auto"
        @Volatile var lastMeta: String? = null
        var pending: List<Days.Day> = emptyList()
        var pendingKey = ""
        val sent = HashMap<String, String>()

        private fun file(c: Context) = File(c.filesDir, "vitals/sent-${Days.sha16(key)}.json")

        fun load(c: Context) {
            if (!persist) return
            val f = file(c).takeIf { it.exists() } ?: return
            runCatching { JSONObject(f.readText()).let { o -> o.keys().forEach { sent[it] = o.getString(it) } } }
        }

        fun save(c: Context) {
            if (!persist) return
            val f = file(c)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject(sent as Map<*, *>).toString())
            tmp.renameTo(f)
        }
    }
    private val peers = ConcurrentHashMap<String, Peer>()

    /** What the screen shows. */
    data class State(
        val samsung: String = "",
        val healthConnect: String = "",
        val pcs: Set<String> = emptySet(),
        val sentAt: Long = 0L,
    )
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isShared(c: Context) = prefs(c).getBoolean(SHARE, false)
    fun routesOn(c: Context) = prefs(c).getBoolean(ROUTES, false)

    fun setShared(c: Context, on: Boolean) {
        prefs(c).edit { putBoolean(SHARE, on) }
        LinkHub.announceCaps()
        if (on) refresh(c, push = true)
    }

    /** Every exercise's `route` flag changes with the switch: a full rebuild resends those days. */
    fun setRoutes(c: Context, on: Boolean) {
        prefs(c).edit { putBoolean(ROUTES, on) }
        refresh(c, push = true)
    }

    /** CONTRACT §1: `vitals` while sharing is on and some source can read. */
    fun caps(c: Context): List<String> = if (isShared(c) && readable) listOf("vitals") else emptyList()

    fun start(c: Context) {
        ctx = c.applicationContext
        refresh(c, push = false)
    }

    /** A permission or source changed (the screen, on resume): re-read the states, push if a source opened up. */
    fun onPermissionChanged(c: Context) = refresh(c, push = false)

    /** Re-reads the sources; a full rebuild and push when [push] or a source just became readable. */
    private fun refresh(c: Context, push: Boolean) {
        val app = c.applicationContext
        ctx = app
        scope.launch {
            val opened = updateSources(app)
            if ((push || opened) && peers.isNotEmpty()) io.withLock { runCatching { check(app, full = true) }.onFailure { onCheckFailure(it) } }
        }
    }

    /** Reads both sources' states; true when one just became readable. */
    private suspend fun updateSources(c: Context): Boolean {
        val s = runCatching { SamsungSource.status(c) }.getOrDefault("error")
        val h = runCatching { HealthConnectSource.status(c) }.getOrDefault("unavailable")
        val before = sources
        sources = s to h
        _state.value = _state.value.copy(samsung = s, healthConnect = h)
        val now = s == "on" || h == "granted" || h == "partial"
        if (now != readable) {
            readable = now
            LinkHub.announceCaps()
        }
        return before != null && ((s == "on" && before.first != "on") || (h != before.second && h in setOf("granted", "partial") && before.second !in setOf("granted", "partial")))
    }

    // ── Link ─────────────────────────────────────────────────────────────

    fun onShellHello(c: Context, key: String, hasVitals: Boolean) {
        ctx = c.applicationContext
        if (key.isEmpty()) return
        if (!hasVitals) {
            onLinkLost(key)
            return
        }
        peers.getOrPut(key) { Peer(key, persist = true).also { it.load(c) } }
        publishPcs()
        ensureLoop(c.applicationContext)
    }

    fun onLinkLost(key: String) {
        if (peers.remove(key) != null) publishPcs()
        if (peers.isEmpty()) {
            loop?.cancel()
            loop = null
        }
    }

    fun onGet(c: Context, key: String, m: JSONObject) {
        val peer = peers.getOrPut(key) { Peer(key, persist = true).also { it.load(c) } }
        scope.launch {
            runCatching {
                answer(c.applicationContext, peer, m.optString("since"), m.optString("stepsFrom").ifEmpty { "auto" }) { LinkHub.sendRawToPc(key, it) }
            }.onFailure { Log.w(TAG, "get: ${it.javaClass.simpleName}") }
        }
    }

    fun onRoute(c: Context, key: String, id: String) {
        val app = c.applicationContext
        scope.launch {
            val msg = JSONObject().put("t", "vitalsRoute").put("id", id)
            val off = !isShared(app) || !routesOn(app)
            // A failed read is not "none", which the PC keeps for good: unanswered, it asks again later.
            val points = if (off) null else runCatching { route(app, id) }.getOrElse {
                Log.w(TAG, "route: ${it.javaClass.simpleName}")
                return@launch
            }
            if (points == null) {
                msg.put("points", JSONObject.NULL).put("error", if (off) "off" else "none")
            } else {
                // A frame holds about 15 000 points; a longer route keeps every n-th one, plus the
                // last, so n spaces the other MAX - 1 (ceil((size - 1) / (MAX - 1)), 1 for one point).
                val step = (points.size - 2) / (MAX_ROUTE_POINTS - 1) + 1
                msg.put("points", org.json.JSONArray(points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }.map { p ->
                    org.json.JSONArray(listOf(p[0], Days.num(p[1] as Double, 6), Days.num(p[2] as Double, 6), (p[3] as Double?)?.let { Days.num(it, 1) } ?: JSONObject.NULL))
                }))
            }
            LinkHub.sendRawToPc(key, msg.toString())
        }
    }

    private suspend fun route(c: Context, id: String): List<List<Any?>>? {
        // Refs come from builds; after a restart a full read finds the session again.
        if (!routeRefs.containsKey(id)) io.withLock { build(c, oldest(c) ?: LocalDate.now(), LocalDate.now(), "auto") }
        val ref = routeRefs[id] ?: return null
        return (if (ref.source == "samsung") SamsungSource.route(c, ref) else HealthConnectSource.route(c, ref))?.takeIf { it.isNotEmpty() }
    }

    private fun publishPcs() {
        val names = LinkHub.onlinePcs().filter { it.fingerprint in peers.keys }.map { it.name.ifEmpty { "PC" } }.toSet()
        _state.value = _state.value.copy(pcs = names)
    }

    // ── Answers ──────────────────────────────────────────────────────────

    /** One answer page went out: whether more follow, and from which day the PC asks next. */
    data class Page(val more: Boolean, val next: String, val days: Int, val bytes: Int)

    /**
     * Answers `get vitals` with one page (CONTRACT §1), the same path the dev dump drives. The
     * whole range is read once per answer; the pages after the first come from what it built.
     */
    suspend fun answer(c: Context, peer: Peer, since: String, stepsFrom: String, send: (String) -> Boolean): Page = io.withLock {
        peer.stepsFrom = stepsFrom
        updateSources(c)
        val (s, h) = sources ?: ("error" to "unavailable")
        val today = LocalDate.now()
        val from = runCatching { LocalDate.parse(since) }.getOrNull()
        val cont = from != null && peer.pending.isNotEmpty() && peer.pendingKey == "$stepsFrom/$from" && peer.pending.first().date == from
        val error = when {
            !isShared(c) -> "disabled"
            !readable -> if (h == "unavailable" && s in setOf("notBuiltIn", "notInstalled")) "unavailable" else "permission"
            else -> null
        }
        if (error != null) {
            val meta = meta(c, stepsFrom, emptySet(), null)
            send("""{"t":"vitals","error":"$error","meta":$meta}""")
            return@withLock Page(false, "", 0, 0)
        }
        val days = if (cont) peer.pending else {
            val start = minOf(from ?: oldest(c) ?: today, today)
            build(c, start, today, stepsFrom).days.filter { wanted(peer, it, today) }
        }
        val meta = if (cont) null else meta(c, stepsFrom, lastDevices, days.lastOrNull { it.hasData }?.date)
        val (page, rest) = split(days, meta)
        peer.pending = rest
        peer.pendingKey = rest.firstOrNull()?.let { "$stepsFrom/${it.date}" }.orEmpty()
        val more = rest.isNotEmpty()
        val text = message(page, meta, if (more) rest.first().date.toString() else null)
        if (send(text)) {
            sent(c, peer, page, meta)
            Log.i(TAG, "answer: ${page.size} days, ${text.toByteArray().size} bytes, more=$more")
        }
        Page(more, rest.firstOrNull()?.date?.toString().orEmpty(), page.size, text.toByteArray().size)
    }

    /**
     * Days with no data under this Steps-from choice are left out (under auto, the months only
     * the phone's own counter covers). Today always goes: an answer has at least one day, and
     * early in the day it may have nothing yet. So does a day that emptied after this PC was sent
     * it, or the PC would keep the deleted data.
     */
    private fun wanted(peer: Peer, d: Days.Day, today: LocalDate) =
        d.hasData || d.date == today || peer.sent[d.date.toString()].let { it != null && it != d.hash }

    private fun message(days: List<Days.Day>, meta: String?, next: String?): String = buildString {
        append("{\"t\":\"vitals\"")
        meta?.let { append(",\"meta\":").append(it) }
        append(",\"days\":[")
        days.forEachIndexed { i, d -> if (i > 0) append(','); append(d.json) }
        append(']')
        if (next != null) append(",\"more\":true,\"next\":\"").append(next).append('"') else append(",\"more\":false")
        append('}')
    }

    /** The days that fit one message (at least one), and the rest. */
    private fun split(days: List<Days.Day>, meta: String?): Pair<List<Days.Day>, List<Days.Day>> {
        var size = 80 + (meta?.toByteArray()?.size ?: 0)
        var n = 0
        while (n < days.size) {
            val b = days[n].json.toByteArray().size + 1
            if (n > 0 && size + b > MAX_BYTES) break
            size += b
            n++
        }
        return days.take(n) to days.drop(n)
    }

    private fun sent(c: Context, peer: Peer, days: List<Days.Day>, meta: String?) {
        days.forEach { peer.sent[it.date.toString()] = it.hash }
        if (meta != null) peer.lastMeta = meta
        peer.save(c)
        _state.value = _state.value.copy(sentAt = System.currentTimeMillis())
    }

    // ── Reading ──────────────────────────────────────────────────────────

    class Built(val days: List<Days.Day>)
    @Volatile private var lastDevices: Set<String> = emptySet()

    private suspend fun build(c: Context, from: LocalDate, to: LocalDate, stepsFrom: String): Built {
        val t0 = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val sam = Collected(zone)
        val hc = Collected(zone)
        val routes = routesOn(c)
        val (s, h) = sources ?: ("" to "")
        if (s == "on") SamsungSource.collect(c, from, to, sam, routes)
        val t1 = System.currentTimeMillis()
        if (h == "granted" || h == "partial") HealthConnectSource.collect(c, from, to, hc, stepsFrom, routes)
        val t2 = System.currentTimeMillis()
        routeRefs.putAll(sam.routes)
        routeRefs.putAll(hc.routes)
        if (sam.deviceIds.isNotEmpty()) lastDevices = sam.deviceIds
        val days = ArrayList<Days.Day>()
        var d = from
        while (!d.isAfter(to)) {
            days += Days.build(d, zone, sam.days[d], hc.days[d], stepsFrom)
            d = d.plusDays(1)
        }
        if (oldest?.let { !from.isAfter(it) } == true) days.firstOrNull { it.hasData }?.let { firstData[stepsFrom] = it.date }
        Log.i(TAG, "build ${days.size} days: samsung ${t1 - t0} ms, healthConnect ${t2 - t1} ms, days ${System.currentTimeMillis() - t2} ms")
        return Built(days)
    }

    private suspend fun oldest(c: Context): LocalDate? {
        oldest?.let { return it }
        val zone = ZoneId.systemDefault()
        val (s, h) = sources ?: ("" to "")
        val a = if (s == "on") runCatching { SamsungSource.oldest(c, zone) }.getOrNull() else null
        val b = if (h == "granted" || h == "partial") runCatching { HealthConnectSource.oldest(c, zone) }.getOrNull() else null
        return listOfNotNull(a, b).minOrNull()?.also { oldest = it }
    }

    /** CONTRACT §4. */
    private suspend fun meta(c: Context, stepsFrom: String, deviceIds: Set<String>, newest: LocalDate?): String {
        val (s, h) = sources ?: ("error" to "unavailable")
        val m = sortedMapOf<String, Any?>(
            "sources" to sortedMapOf("samsung" to s, "healthConnect" to h),
            "stepsFrom" to stepsFrom,
            "routes" to routesOn(c),
            "app" to BuildConfig.VERSION_NAME,
        )
        if (readable) {
            val profile = HealthConnectSource.profile(c).toSortedMap()
            if (s == "on") {
                SamsungSource.devices(c, deviceIds).takeIf { it.isNotEmpty() }?.let { m["devices"] = it }
                SamsungSource.goals(c).takeIf { it.isNotEmpty() }?.let { m["goals"] = it }
                profile.putAll(SamsungSource.profile(c))
            }
            if (profile.isNotEmpty()) m["profile"] = profile
            firstData[stepsFrom]?.let { m["oldest"] = it.toString() }
            newest?.let { m["newest"] = it.toString() }
        }
        return Days.json(m)
    }

    // ── Pushes ───────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureLoop(c: Context) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (peers.isNotEmpty()) {
                delay(interval)
                io.withLock { runCatching { updateSources(c); check(c, full = false) }.onFailure { onCheckFailure(it) } }
            }
        }
    }

    private fun onCheckFailure(e: Throwable) {
        if (e is HealthConnectSource.QuotaException) {
            interval = minOf(interval * 2, MAX_BACKOFF_MS)
            Log.w(TAG, "check: Health Connect quota, next in ${interval / 60_000} min")
        } else {
            Log.w(TAG, "check: ${e.javaClass.simpleName}")
        }
    }

    /** Rebuilds the last days (or all of them, [full] or once a day) and pushes what changed to each PC. Holds [io]. */
    private suspend fun check(c: Context, full: Boolean) {
        if (!isShared(c) || !readable || peers.isEmpty()) return
        val today = LocalDate.now()
        val due = full || System.currentTimeMillis() - prefs(c).getLong(LAST_FULL, 0L) > FULL_MS
        if (due) oldest = null
        val from = if (due) oldest(c) ?: today else today.minusDays(RECENT_DAYS)
        val builds = HashMap<String, Built>()
        for (peer in peers.values) {
            val built = builds.getOrPut(peer.stepsFrom) { build(c, minOf(from, today), today, peer.stepsFrom) }
            val changed = built.days.filter { peer.sent[it.date.toString()] != it.hash && wanted(peer, it, today) }
            val meta = meta(c, peer.stepsFrom, lastDevices, built.days.lastOrNull { it.hasData }?.date).takeIf { it != peer.lastMeta }
            if (changed.isEmpty() && meta == null) continue
            var rest = changed
            var first = true
            do {
                val (page, next) = split(rest, if (first) meta else null)
                val text = buildString {
                    append("{\"t\":\"vitals\"")
                    if (first && meta != null) append(",\"meta\":").append(meta)
                    append(",\"days\":[")
                    page.forEachIndexed { i, d -> if (i > 0) append(','); append(d.json) }
                    append("]}")
                }
                if (!LinkHub.sendRawToPc(peer.key, text)) break
                sent(c, peer, page, if (first) meta else null)
                Log.i(TAG, "push: ${page.size} days, ${text.toByteArray().size} bytes")
                rest = next
                first = false
            } while (rest.isNotEmpty())
        }
        interval = CHECK_MS
        if (due) prefs(c).edit { putLong(LAST_FULL, System.currentTimeMillis()) }
    }
}
