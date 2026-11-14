package dev.ii.tools.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.Wake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime

/** The ready-made routines: a phone trigger and what it does on the PC. */
enum class Routine { LEAVE, BEDTIME, ARRIVE, CALL }

/** What a routine (or a shortcut) can do on the PC. */
enum class PcAction(val id: String) {
    LOCK("lock"), LOCK_AOD("lockAod"), PAUSE_MEDIA("pauseMedia"), WAKE("wake");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Routines of the phone that change the PC (ROADMAP 5.12): a trigger here, an action
 * there, over the same link as everything else. Nothing is kept running for them: the
 * triggers are a network callback, a power receiver and the call state, all registered
 * from the notification listener's process, which the system keeps bound anyway.
 *
 * - **Leaving home:** the Wi-Fi the PC was reachable on is gone for a minute → lock it
 *   (the link opens again over whatever route is left, Tailscale if the PC is on it).
 * - **Bedtime:** the charger goes in during the night window → pause media, lock, AOD.
 * - **Arriving home:** back on the home network after leaving it → Wake-on-LAN.
 * - **In a call:** ringing or talking → the PC's volume goes down, and back after.
 *
 * "Home" is the network the link was online on (its gateway and subnet), learned, not
 * typed: reading a Wi-Fi's name would need the location permission.
 */
object Routines {
    private const val PREFS = "routines"
    private const val HOME = "home"
    private const val AWAY = "away"
    private const val BED_FROM = "bedFrom"
    private const val BED_TO = "bedTo"
    private const val DUCK_TO = "duckTo"
    private const val DUCK_WAS = "duckWas"
    private const val LEAVE_DELAY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var app: Context? = null
    private var started = false
    private var cm: ConnectivityManager? = null
    private var leaveJob: Job? = null
    private val wifi = HashMap<Network, String>()
    @Volatile private var wifiUp = false

    /** When a routine last ran ("12:40") and why it failed, if it did. */
    data class Ran(val at: String, val failed: String?)

    private val _last = MutableStateFlow<Map<Routine, Ran>>(emptyMap())
    /** Each routine's last outcome, for its row. */
    val last: StateFlow<Map<Routine, Ran>> = _last.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context, r: Routine) = prefs(c).getBoolean("on:${r.name}", false)

    fun setEnabled(c: Context, r: Routine, on: Boolean) {
        prefs(c).edit().putBoolean("on:${r.name}", on).apply()
    }

    /** Bedtime's window, minutes from midnight (it may run past midnight). */
    fun bedtime(c: Context): Pair<Int, Int> = prefs(c).getInt(BED_FROM, 22 * 60) to prefs(c).getInt(BED_TO, 5 * 60)

    fun setBedtime(c: Context, from: Int, to: Int) {
        prefs(c).edit().putInt(BED_FROM, from).putInt(BED_TO, to).apply()
    }

    /** The PC's volume during a call, 0–100 (the level it goes down to). */
    fun duckTo(c: Context): Int = prefs(c).getInt(DUCK_TO, 20)

    fun setDuckTo(c: Context, percent: Int) {
        prefs(c).edit().putInt(DUCK_TO, percent.coerceIn(0, 100)).apply()
    }

    fun anyEnabled(c: Context) = Routine.entries.any { isEnabled(c, it) }

    // ── Triggers ──────────────────────────────────────────────────────────

    /** From the notification listener, once: registers the triggers that are on. */
    fun start(c: Context) {
        val ctx = c.applicationContext
        app = ctx
        if (started) return
        started = true
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        cm = manager
        manager?.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )
        ctx.registerReceiver(power, IntentFilter(Intent.ACTION_POWER_CONNECTED), Context.RECEIVER_NOT_EXPORTED)
        // The network the link is online on is home.
        scope.launch {
            LinkHub.status.collect { s ->
                if (s is LinkStatus.Online) wifi.values.firstOrNull()?.let { prefs(ctx).edit().putString(HOME, it).apply() }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val key = networkKey(lp) ?: return
            wifi[network] = key
            wifiUp = true
            leaveJob?.cancel()
            val c = app ?: return
            val home = prefs(c).getString(HOME, null)
            if (home != null && key == home && prefs(c).getBoolean(AWAY, false)) {
                prefs(c).edit().putBoolean(AWAY, false).apply()
                if (isEnabled(c, Routine.ARRIVE)) run(c, Routine.ARRIVE, listOf(PcAction.WAKE))
            }
        }

        override fun onLost(network: Network) {
            val key = wifi.remove(network)
            wifiUp = wifi.isNotEmpty()
            val c = app ?: return
            if (key == null || key != prefs(c).getString(HOME, null) || wifiUp) return
            // Away from home: leaving only counts if the network stays gone.
            prefs(c).edit().putBoolean(AWAY, true).apply()
            leaveJob?.cancel()
            leaveJob = scope.launch {
                delay(LEAVE_DELAY_MS)
                if (!wifiUp && isEnabled(c, Routine.LEAVE)) run(c, Routine.LEAVE, listOf(PcAction.LOCK))
            }
        }
    }

    private fun networkKey(lp: LinkProperties): String? {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: return null
        val prefix = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.prefixLength ?: return null
        return "$gateway/$prefix"
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isEnabled(context, Routine.BEDTIME)) return
            val (from, to) = bedtime(context)
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val inside = if (from <= to) now in from until to else now >= from || now < to
            if (inside) run(context, Routine.BEDTIME, listOf(PcAction.PAUSE_MEDIA, PcAction.LOCK_AOD))
        }
    }

    /** The call state, from LinkHub.reportCall (idle, ringing, talking). */
    fun onCall(state: String) {
        val c = app ?: return
        if (!isEnabled(c, Routine.CALL)) return
        scope.launch {
            if (state == "idle") restoreVolume(c) else duckVolume(c)
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    /** Runs a routine now (the "Run now" test, or a trigger). */
    fun runNow(c: Context, r: Routine) {
        when (r) {
            Routine.LEAVE -> run(c, r, listOf(PcAction.LOCK))
            Routine.BEDTIME -> run(c, r, listOf(PcAction.PAUSE_MEDIA, PcAction.LOCK_AOD))
            Routine.ARRIVE -> run(c, r, listOf(PcAction.WAKE))
            Routine.CALL -> scope.launch { duckVolume(c.applicationContext) }
        }
    }

    private fun run(c: Context, r: Routine, actions: List<PcAction>) {
        val ctx = c.applicationContext
        scope.launch {
            val failed = actions.firstNotNullOfOrNull { exec(ctx, it) }
            record(r, failed)
        }
    }

    /** One action, from a trigger or a shortcut; null when done, else why not. */
    suspend fun exec(c: Context, a: PcAction): String? = when (a) {
        PcAction.LOCK -> LinkHub.runWhenReady("lock")
        PcAction.LOCK_AOD -> LinkHub.runWhenReady("lockAod")
        PcAction.PAUSE_MEDIA -> LinkHub.runWhenReady("pcMedia", JSONObject().put("a", "pause"))
        PcAction.WAKE -> if (Wake.send(c.applicationContext)) null else "no_target"
    }

    private suspend fun duckVolume(c: Context) {
        val out = withAudio { a -> a.output?.let { it.id to it.vol } } ?: return
        val to = duckTo(c) / 100f
        // A second ring while already lowered must not overwrite the level to restore.
        if (!prefs(c).contains(DUCK_WAS)) prefs(c).edit().putFloat(DUCK_WAS, out.second).apply()
        if (out.second > to) sendVolume(out.first, to)
        record(Routine.CALL, null)
    }

    private suspend fun restoreVolume(c: Context) {
        val was = prefs(c).takeIf { it.contains(DUCK_WAS) }?.getFloat(DUCK_WAS, 1f) ?: return
        prefs(c).edit().remove(DUCK_WAS).apply()
        val out = withAudio { a -> a.output?.id } ?: return
        sendVolume(out, was)
    }

    /** The link up (held for the moment), and the PC's audio topic that comes with its hello. */
    private suspend fun <T> withAudio(block: (dev.ii.tools.link.PcAudio) -> T?): T? {
        LinkHub.hold("routine")
        try {
            val audio = withTimeoutOrNull(8_000) {
                LinkHub.status.first { it is LinkStatus.Online }
                LinkHub.audio.first { it != null }
            } ?: return null
            return block(audio!!)
        } finally {
            LinkHub.release("routine")
        }
    }

    private suspend fun sendVolume(id: Int, vol: Float) {
        LinkHub.runWhenReady("volume", JSONObject().put("id", id).put("vol", vol.toDouble()))
    }

    private fun record(r: Routine, failed: String?) {
        val now = LocalTime.now()
        val stamp = "%02d:%02d".format(now.hour, now.minute)
        _last.value = _last.value + (r to Ran(stamp, failed))
    }
}
