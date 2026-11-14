package dev.ii.tools.link

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Keeps the phone's Wi-Fi radio awake during wireless scrcpy mirroring so power save
 * does not stall frame delivery or touch round-trips. Acquires both the low-latency lock
 * and high-perf fallback while active, trickles a 4-byte UDP keep-awake datagram to the
 * PC helper's discovery port every 50 ms, and tears everything down on mirror end, link
 * loss, opt-out, or after a hard 3-hour safety timeout.
 */
object MirrorRadio {
    private const val PREFS = "mirror_radio"
    private const val KEY_ENABLED = "enabled"
    private const val TRICKLE_INTERVAL_MS = 50L
    private const val MAX_HOLD_MS = 3L * 60L * 60L * 1000L

    // Must not start with "iilink?" so the helper's discovery loop ignores it silently.
    private val TRICKLE_PAYLOAD = "iim\n".toByteArray(Charsets.US_ASCII)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lowLatencyLock: WifiManager.WifiLock? = null
    private var highPerfLock: WifiManager.WifiLock? = null
    private var trickleJob: Job? = null
    private var timeoutJob: Job? = null
    private var activeTarget: Pair<String, Int>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) stop()
        LinkHub.announceCaps()
    }

    fun caps(ctx: Context): List<String> = if (isEnabled(ctx)) listOf("mirrorRadio") else emptyList()

    @Synchronized
    fun onMirror(ctx: Context, on: Boolean, host: String?, port: Int, shellHasCap: Boolean) {
        if (!on || !isEnabled(ctx) || !shellHasCap) {
            stopLocked()
            return
        }
        acquireLocksLocked(ctx.applicationContext)

        val target = if (!host.isNullOrBlank() && port in 1..65535) host to port else null
        if (target != null && (trickleJob?.isActive != true || activeTarget != target)) {
            trickleJob?.cancel()
            activeTarget = target
            trickleJob = scope.launch { runTrickle(target.first, target.second) }
        } else if (target == null) {
            trickleJob?.cancel()
            trickleJob = null
            activeTarget = null
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(MAX_HOLD_MS)
            stop()
        }
    }

    @Synchronized
    fun onLinkLost() {
        stopLocked()
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        trickleJob?.cancel()
        trickleJob = null
        activeTarget = null
        timeoutJob?.cancel()
        timeoutJob = null
        releaseLock(lowLatencyLock)
        lowLatencyLock = null
        releaseLock(highPerfLock)
        highPerfLock = null
    }

    private fun acquireLocksLocked(appCtx: Context) {
        val wm = appCtx.getSystemService(WifiManager::class.java) ?: return
        if (lowLatencyLock == null) {
            lowLatencyLock = runCatching {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ii:mirror_low_latency").apply {
                    setReferenceCounted(false)
                }
            }.getOrNull()
        }
        runCatching { lowLatencyLock?.let { if (!it.isHeld) it.acquire() } }

        if (highPerfLock == null) {
            @Suppress("DEPRECATION")
            highPerfLock = runCatching {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ii:mirror_high_perf").apply {
                    setReferenceCounted(false)
                }
            }.getOrNull()
        }
        runCatching { highPerfLock?.let { if (!it.isHeld) it.acquire() } }
    }

    private fun releaseLock(lock: WifiManager.WifiLock?) {
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
    }

    private suspend fun runTrickle(host: String, port: Int) = coroutineScope {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@coroutineScope
        val pkt = DatagramPacket(TRICKLE_PAYLOAD, TRICKLE_PAYLOAD.size, addr, port)
        runCatching { DatagramSocket() }.getOrNull()?.use { sock ->
            while (isActive) {
                runCatching { sock.send(pkt) }
                delay(TRICKLE_INTERVAL_MS)
            }
        }
    }
}
