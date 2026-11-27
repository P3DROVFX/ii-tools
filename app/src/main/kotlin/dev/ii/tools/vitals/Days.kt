package dev.ii.tools.vitals

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.round

/** One sample key on one day: running min/max/mean, the newest value, and per-minute bins. */
class SampleAcc {
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    var sum = 0.0
    var n = 0
    var lastT = Long.MIN_VALUE
    var lastV = 0.0
    val binSum = DoubleArray(1440)
    val binN = IntArray(1440)
}

/** What one source read for one local day, by kind and key (CONTRACT §2). */
class DayAcc {
    val totals = HashMap<String, DoubleArray>()
    val samples = HashMap<String, SampleAcc>()
    val sessions = HashMap<String, MutableList<Map<String, Any?>>>()
    val measurements = HashMap<String, MutableList<MutableMap<String, Any?>>>()
    val scores = HashMap<String, Double>()
}

/** Where a workout's route can be read again for `get vitalsRoute`. */
data class RouteRef(val source: String, val uid: String, val index: Int, val start: Instant, val end: Instant)

/** One source's reads over a window, bucketed into local days as they arrive. */
class Collected(val zone: ZoneId) {
    val days = HashMap<LocalDate, DayAcc>()
    val deviceIds = HashSet<String>()
    val routes = HashMap<String, RouteRef>()

    private fun acc(date: LocalDate) = days.getOrPut(date) { DayAcc() }
    fun date(t: Instant): LocalDate = LocalDate.ofInstant(t, zone)

    fun total(key: String, t: Instant, v: Double) = totalLocal(key, LocalDateTime.ofInstant(t, zone), v)

    fun totalLocal(key: String, t: LocalDateTime, v: Double) {
        if (v == 0.0 || v.isNaN()) return
        acc(t.toLocalDate()).totals.getOrPut(key) { DoubleArray(24) }[t.hour] += v
    }

    fun sample(key: String, t: Instant, v: Double, lo: Double = v, hi: Double = v) {
        if (v.isNaN()) return
        val local = LocalDateTime.ofInstant(t, zone)
        val s = acc(local.toLocalDate()).samples.getOrPut(key) { SampleAcc() }
        s.min = minOf(s.min, lo)
        s.max = maxOf(s.max, hi)
        s.sum += v
        s.n++
        val ms = t.toEpochMilli()
        if (ms >= s.lastT) {
            s.lastT = ms
            s.lastV = v
        }
        val minute = local.hour * 60 + local.minute
        s.binSum[minute] += v
        s.binN[minute]++
    }

    fun session(key: String, date: LocalDate, m: Map<String, Any?>) {
        acc(date).sessions.getOrPut(key) { mutableListOf() } += m
    }

    /** Body records taken at the same moment (weight, fat, water…) are one entry. */
    fun measurement(key: String, t: Instant, fields: Map<String, Any?>) {
        val list = acc(date(t)).measurements.getOrPut(key) { mutableListOf() }
        val ms = t.toEpochMilli()
        val same = if (key == "body") list.firstOrNull { it["time"] == ms } else null
        if (same != null) same.putAll(fields) else list += (linkedMapOf<String, Any?>("time" to ms) + fields).toMutableMap()
    }

    fun score(key: String, date: LocalDate, v: Double) {
        acc(date).scores[key] = v
    }
}

/** Builds the contract's Day JSON from what each source read: one source per key, Samsung first. */
object Days {
    /** Totals kept as whole numbers; the rest get one decimal. */
    private val wholeTotals = setOf("steps", "floors", "wheelchairPushes", "activeMin", "distanceM", "hydrationMl", "intakeSodiumMg", "intakeCaffeineMg")

    /** A built day: its JSON text exactly as sent, and its hash. */
    class Day(val date: LocalDate, val json: String, val hash: String, val hasData: Boolean)

    /**
     * Steps from "health" or "phone" keeps Samsung's steps out, so the key falls to the
     * Health Connect origin [HealthConnectSource] picked for it.
     */
    fun build(date: LocalDate, zone: ZoneId, samsung: DayAcc?, hc: DayAcc?, stepsFrom: String): Day {
        val samsungSteps = stepsFrom == "auto"
        val source = sortedMapOf<String, Any?>()
        fun <V> pick(kind: (DayAcc) -> Map<String, V>, key: String): V? {
            samsung?.let(kind)?.get(key)?.takeIf { key != "steps" || samsungSteps }?.let { source[key] = "samsung"; return it }
            hc?.let(kind)?.get(key)?.let { source[key] = "healthConnect"; return it }
            return null
        }
        fun keys(kind: (DayAcc) -> Map<String, *>) = (samsung?.let(kind)?.keys.orEmpty() + hc?.let(kind)?.keys.orEmpty()).toSortedSet()

        val totals = sortedMapOf<String, Any?>()
        for (k in keys { it.totals }) {
            val h = pick({ it.totals }, k) ?: continue
            val dp = if (k in wholeTotals) 0 else 1
            totals[k] = sortedMapOf("day" to num(h.sum(), dp), "hourly" to h.map { num(it, dp) })
        }
        val samples = sortedMapOf<String, Any?>()
        for (k in keys { it.samples }) {
            val s = pick({ it.samples }, k) ?: continue
            samples[k] = sampleJson(k, s)
        }
        val sessions = sortedMapOf<String, Any?>()
        for (k in keys { it.sessions }) {
            val list = pick({ it.sessions }, k) ?: continue
            sessions[k] = list.sortedWith(compareBy({ it["start"] as Long }, { it["id"] as String? })).map { it.toSortedMap() }
        }
        val measurements = sortedMapOf<String, Any?>()
        for (k in keys { it.measurements }) {
            val list = pick({ it.measurements }, k) ?: continue
            measurements[k] = list.sortedBy { it["time"] as Long }.map { it.toSortedMap() }
        }
        val scores = sortedMapOf<String, Any?>()
        for (k in keys { it.scores }) {
            scores[k] = num(pick({ it.scores }, k) ?: continue, 0)
        }

        val day = sortedMapOf<String, Any?>(
            "date" to date.toString(),
            "tz" to zone.rules.getOffset(date.atTime(12, 0)).id.let { if (it == "Z") "+00:00" else it },
        )
        if (totals.isNotEmpty()) day["totals"] = totals
        if (samples.isNotEmpty()) day["samples"] = samples
        if (sessions.isNotEmpty()) day["sessions"] = sessions
        if (measurements.isNotEmpty()) day["measurements"] = measurements
        if (scores.isNotEmpty()) day["scores"] = scores
        if (stepsFrom == "phone" && source["steps"] == "healthConnect") source["steps"] = "phone"
        if (source.isNotEmpty()) day["source"] = source
        val hash = sha16(json(day))
        day["hash"] = hash
        return Day(date, json(day), hash, source.isNotEmpty())
    }

    private fun sampleJson(key: String, s: SampleAcc): Map<String, Any?> {
        val dp = if (key == "heartRate" || key == "restingHeartRate") 1 else 2
        // Minute bins only where the source writes about one value a minute (the watch's heart
        // rate); sparser series would be mostly empty minutes, so they get 5-minute bins.
        var occupied5 = 0
        var occupied1 = 0
        for (b in 0 until 288) {
            var any = false
            for (m in b * 5 until b * 5 + 5) if (s.binN[m] > 0) { occupied1++; any = true }
            if (any) occupied5++
        }
        val binMin = if (key == "heartRate" && occupied5 > 0 && occupied1 >= occupied5 * 2.5) 1 else 5
        val bins = ArrayList<List<Any>>()
        var m = 0
        while (m < 1440) {
            var sum = 0.0
            var n = 0
            for (i in m until m + binMin) { sum += s.binSum[i]; n += s.binN[i] }
            if (n > 0) bins += listOf(m, num(sum / n, dp))
            m += binMin
        }
        return sortedMapOf(
            "min" to num(s.min, dp), "max" to num(s.max, dp), "avg" to num(s.sum / s.n, dp), "count" to s.n,
            "last" to listOf(s.lastT, num(s.lastV, dp)), "binMin" to binMin, "bins" to bins,
        )
    }

    fun num(v: Double, dp: Int): Number = if (dp == 0) round(v).toLong() else {
        val f = if (dp == 1) 10.0 else 100.0
        val r = round(v * f) / f
        if (r == round(r) && abs(r) < 1e15) r.toLong() else r
    }

    fun sha16(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Compact JSON with keys in the order given (sorted maps above), so equal days hash equal. */
    fun json(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> sb.append(org.json.JSONObject.quote(v))
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> if (v == round(v) && abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(org.json.JSONObject.quote(k.toString())).append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> sb.append(org.json.JSONObject.quote(v.toString()))
        }
    }
}
