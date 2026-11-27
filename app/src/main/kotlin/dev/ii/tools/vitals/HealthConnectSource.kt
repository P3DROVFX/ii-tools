@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package dev.ii.tools.vitals

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.reflect.KClass

/** Health Connect: every type the contract names, read in one window per type and bucketed into days. */
object HealthConnectSource {
    private const val TAG = "iiVitals"
    const val ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
    /** The phone's own step counter writes under this package (and sub-packages). */
    private const val PHONE_COUNTER = "com.android.healthconnect.phone"
    private const val PAGE = 5000
    /** Hour buckets per aggregate call. */
    private val CHUNK = Duration.ofDays(60)

    private val records: List<KClass<out Record>> = listOf(
        HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class, RespiratoryRateRecord::class, SkinTemperatureRecord::class,
        SpeedRecord::class, PowerRecord::class, StepsCadenceRecord::class, CyclingPedalingCadenceRecord::class,
        SleepSessionRecord::class, ExerciseSessionRecord::class, MindfulnessSessionRecord::class, MenstruationPeriodRecord::class,
        WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class,
        BodyWaterMassRecord::class, BasalMetabolicRateRecord::class, HeightRecord::class, BloodPressureRecord::class,
        BloodGlucoseRecord::class, BodyTemperatureRecord::class, BasalBodyTemperatureRecord::class, Vo2MaxRecord::class,
        MenstruationFlowRecord::class, OvulationTestRecord::class, CervicalMucusRecord::class,
        IntermenstrualBleedingRecord::class, SexualActivityRecord::class,
    )

    /** Totals come from the aggregate API: connect-client 1.1.0 throws on Samsung's zero-length step, distance and calorie records. */
    private val totals: Map<String, Pair<AggregateMetric<*>, (Any) -> Double>> = mapOf(
        "distanceM" to (DistanceRecord.DISTANCE_TOTAL to { v -> (v as androidx.health.connect.client.units.Length).inMeters }),
        "floors" to (FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL to { v -> v as Double }),
        "elevationM" to (ElevationGainedRecord.ELEVATION_GAINED_TOTAL to { v -> (v as androidx.health.connect.client.units.Length).inMeters }),
        "activeKcal" to (ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL to ::kcal),
        "totalKcal" to (TotalCaloriesBurnedRecord.ENERGY_TOTAL to ::kcal),
        "wheelchairPushes" to (WheelchairPushesRecord.COUNT_TOTAL to { v -> (v as Long).toDouble() }),
        "hydrationMl" to (HydrationRecord.VOLUME_TOTAL to { v -> (v as androidx.health.connect.client.units.Volume).inMilliliters }),
        "intakeKcal" to (NutritionRecord.ENERGY_TOTAL to ::kcal),
        "intakeProteinG" to (NutritionRecord.PROTEIN_TOTAL to ::grams),
        "intakeFatG" to (NutritionRecord.TOTAL_FAT_TOTAL to ::grams),
        "intakeCarbsG" to (NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL to ::grams),
        "intakeSugarG" to (NutritionRecord.SUGAR_TOTAL to ::grams),
        "intakeFiberG" to (NutritionRecord.DIETARY_FIBER_TOTAL to ::grams),
        "intakeSodiumMg" to (NutritionRecord.SODIUM_TOTAL to { v -> (v as androidx.health.connect.client.units.Mass).inMilligrams }),
        "intakeCaffeineMg" to (NutritionRecord.CAFFEINE_TOTAL to { v -> (v as androidx.health.connect.client.units.Mass).inMilligrams }),
    )
    private val totalTypes: Map<String, KClass<out Record>> = mapOf(
        "distanceM" to DistanceRecord::class, "floors" to FloorsClimbedRecord::class, "elevationM" to ElevationGainedRecord::class,
        "activeKcal" to ActiveCaloriesBurnedRecord::class, "totalKcal" to TotalCaloriesBurnedRecord::class,
        "wheelchairPushes" to WheelchairPushesRecord::class, "hydrationMl" to HydrationRecord::class,
    ).withDefault { NutritionRecord::class }

    private fun kcal(v: Any) = (v as androidx.health.connect.client.units.Energy).inKilocalories
    private fun grams(v: Any) = (v as androidx.health.connect.client.units.Mass).inGrams

    fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

    private fun client(c: Context) = HealthConnectClient.getOrCreate(c)

    private fun read(cls: KClass<out Record>) = HealthPermission.getReadPermission(cls)

    /** Types the installed Health Connect knows (skin temperature and mindfulness are feature-gated). */
    private fun known(c: Context): List<KClass<out Record>> {
        val f = client(c).features
        fun on(feature: Int) = f.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return (records + listOf(StepsRecord::class, TotalCaloriesBurnedRecord::class, DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class, ElevationGainedRecord::class,
            WheelchairPushesRecord::class, HydrationRecord::class, NutritionRecord::class)).filter {
            when (it) {
                SkinTemperatureRecord::class -> on(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
                MindfulnessSessionRecord::class -> on(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
                else -> true
            }
        }
    }

    /** Every read permission the screen asks for (routes have their own switch). */
    fun permissions(c: Context): Set<String> {
        if (!available(c)) return emptySet()
        val f = client(c).features
        fun on(feature: Int) = f.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return known(c).map(::read).toSet() +
            listOfNotNull(
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY.takeIf { on(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) },
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND.takeIf { on(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) },
            )
    }

    suspend fun granted(c: Context): Set<String> =
        if (available(c)) client(c).permissionController.getGrantedPermissions() else emptySet()

    /** CONTRACT §4 `sources.healthConnect`. */
    suspend fun status(c: Context): String {
        if (!available(c)) return "unavailable"
        val types = known(c).map(::read).toSet()
        val g = granted(c)
        val have = types.count { it in g }
        return when {
            have == 0 -> "denied"
            have == types.size && permissions(c).all { it in g } -> "granted"
            else -> "partial"
        }
    }

    /** Health Connect refused for its rate limit or quota: the caller backs off. */
    class QuotaException(e: Throwable) : Exception(e)

    private fun quota(e: Throwable) = e.message?.let { "quota" in it.lowercase() || "rate limit" in it.lowercase() } == true

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (quota(e)) throw QuotaException(e)
        Log.w(TAG, "healthConnect $what: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun <T : Record> readAll(c: Context, cls: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val r = client(c).readRecords(ReadRecordsRequest(cls, TimeRangeFilter.between(from, to), pageSize = PAGE, pageToken = token))
            out += r.records
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** The origin "Steps from: Health Connect" means: the non-phone origin with the most steps over 30 days. */
    private suspend fun stepsOrigins(c: Context, stepsFrom: String): Set<DataOrigin> {
        val now = Instant.now()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)
        val all = client(c).aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range)).dataOrigins
        val (phone, apps) = all.partition { it.packageName.startsWith(PHONE_COUNTER) }
        if (stepsFrom == "phone") return phone.toSet()
        if (apps.size <= 1) return (apps.ifEmpty { phone }).take(1).toSet()
        // connect-client cannot read Samsung's step records (zero-length), so "most records" is approximated by most steps.
        return setOf(apps.maxBy { o ->
            client(c).aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range, setOf(o)))[StepsRecord.COUNT_TOTAL] ?: 0L
        })
    }

    /** Hour-sliced aggregates over [from, to), in chunks. */
    private suspend fun hourly(c: Context, metrics: Set<AggregateMetric<*>>, from: Instant, to: Instant, origins: Set<DataOrigin>, each: (Instant, AggregationResult) -> Unit) {
        var start = from
        while (start < to) {
            val end = minOf(start.plus(CHUNK), to)
            client(c).aggregateGroupByDuration(
                AggregateGroupByDurationRequest(metrics, TimeRangeFilter.between(start, end), Duration.ofHours(1), origins),
            ).forEach { each(it.startTime, it.result) }
            start = end
        }
    }

    suspend fun collect(c: Context, from: LocalDate, to: LocalDate, out: Collected, stepsFrom: String, routes: Boolean) {
        if (!available(c)) return
        // Unguarded, a refusal here would fail the whole build, Samsung's reads included.
        val g = guarded("permissions") { granted(c) } ?: return
        val start = from.atStartOfDay(out.zone).toInstant()
        val end = minOf(to.plusDays(1).atStartOfDay(out.zone).toInstant(), Instant.now().plus(Duration.ofHours(1)))
        if (end <= start) return

        val clock = Clock()
        val allowed = totals.filterKeys { read(totalTypes.getValue(it)) in g }
        // Hour slicing costs per metric and bucket: slice only the metrics the window has at all.
        val whole = if (allowed.isEmpty()) null else guarded("totals") {
            client(c).aggregate(AggregateRequest(allowed.values.map { it.first }.toSet(), TimeRangeFilter.between(start, end)))
        }
        val metrics = allowed.filterValues { whole?.get(it.first) != null }
        // Total calories come back for every hour, filled from the BMR, even with no records, and
        // the slices' dataOrigins name the app for those too: only days a record touches keep the key.
        val plain = metrics - "totalKcal"
        if (plain.isNotEmpty()) guarded("totals") {
            hourly(c, plain.values.map { it.first }.toSet(), start, end, emptySet()) { t, r ->
                for ((key, m) in plain) r[m.first]?.let { out.total(key, t, m.second(it)) }
            }
        }
        metrics["totalKcal"]?.let { m ->
            guarded("totalKcal") {
                val real = readAll(c, TotalCaloriesBurnedRecord::class, start, end)
                    .flatMap { listOf(out.date(it.startTime), out.date(maxOf(it.startTime, it.endTime.minusMillis(1)))) }.toSet()
                if (real.isNotEmpty()) hourly(c, setOf(m.first), start, end, emptySet()) { t, r ->
                    if (out.date(t) in real) r[m.first]?.let { out.total("totalKcal", t, m.second(it)) }
                }
            }
        }
        clock.lap("totals")
        if (read(StepsRecord::class) in g) guarded("steps") {
            val origins = stepsOrigins(c, stepsFrom)
            if (origins.isNotEmpty()) hourly(c, setOf(StepsRecord.COUNT_TOTAL), start, end, origins) { t, r ->
                r[StepsRecord.COUNT_TOTAL]?.let { out.total("steps", t, it.toDouble()) }
            }
        }
        clock.lap("steps")
        // Records are matched by their start: a night that began the evening before belongs to the
        // first day too. What the extra day reads lands on a day the caller does not build.
        val since = start.minus(Duration.ofDays(1))
        for (cls in known(c).filter { it in records && read(it) in g }) {
            val list = guarded(cls.simpleName.orEmpty()) { readAll(c, cls, since, end) } ?: continue
            for (r in list) add(c, out, r, g, routes)
            if (list.isNotEmpty()) clock.lap("${cls.simpleName}:${list.size}")
        }
        saveStats(c)
        clock.lap("rest")
        Log.i(TAG, "healthConnect: $clock")
    }

    /** Phase timings for the log (names and counts only). */
    private class Clock {
        private var t = System.currentTimeMillis()
        private val laps = StringBuilder()
        fun lap(name: String) {
            val now = System.currentTimeMillis()
            laps.append(name).append(' ').append(now - t).append(" ms, ")
            t = now
        }
        override fun toString() = laps.toString()
    }

    private suspend fun add(c: Context, out: Collected, r: Record, g: Set<String>, routes: Boolean) {
        when (r) {
            is HeartRateRecord -> r.samples.forEach { out.sample("heartRate", it.time, it.beatsPerMinute.toDouble()) }
            is RestingHeartRateRecord -> out.sample("restingHeartRate", r.time, r.beatsPerMinute.toDouble())
            is HeartRateVariabilityRmssdRecord -> out.sample("hrvRmssdMs", r.time, r.heartRateVariabilityMillis)
            is OxygenSaturationRecord -> out.sample("spo2Pct", r.time, r.percentage.value)
            is RespiratoryRateRecord -> out.sample("respiratoryRate", r.time, r.rate)
            is SkinTemperatureRecord -> {
                val base = r.baseline?.inCelsius
                r.deltas.forEach {
                    if (base != null) out.sample("skinTempC", it.time, base + it.delta.inCelsius)
                    else out.sample("skinTempDeltaC", it.time, it.delta.inCelsius)
                }
            }
            is SpeedRecord -> r.samples.forEach { out.sample("speedMps", it.time, it.speed.inMetersPerSecond) }
            is PowerRecord -> r.samples.forEach { out.sample("powerW", it.time, it.power.inWatts) }
            is StepsCadenceRecord -> r.samples.forEach { out.sample("stepsCadence", it.time, it.rate) }
            is CyclingPedalingCadenceRecord -> r.samples.forEach { out.sample("cyclingCadenceRpm", it.time, it.revolutionsPerMinute) }
            is SleepSessionRecord -> out.session("sleep", out.date(r.endTime), sleep(r))
            is ExerciseSessionRecord -> exercise(c, out, r, g, routes)
            is MindfulnessSessionRecord -> out.session("mindfulness", out.date(r.startTime), buildMap {
                put("start", r.startTime.toEpochMilli()); put("end", r.endTime.toEpochMilli())
                MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_INT_TO_STRING_MAP[r.mindfulnessSessionType]?.let { put("type", it) }
            })
            is MenstruationPeriodRecord -> out.session("menstruationPeriod", out.date(r.startTime), mapOf("start" to r.startTime.toEpochMilli(), "end" to r.endTime.toEpochMilli()))
            is WeightRecord -> out.measurement("body", r.time, mapOf("weightKg" to Days.num(r.weight.inKilograms, 2)))
            is BodyFatRecord -> out.measurement("body", r.time, mapOf("bodyFatPct" to Days.num(r.percentage.value, 1)))
            is LeanBodyMassRecord -> out.measurement("body", r.time, mapOf("leanMassKg" to Days.num(r.mass.inKilograms, 2)))
            is BoneMassRecord -> out.measurement("body", r.time, mapOf("boneMassKg" to Days.num(r.mass.inKilograms, 2)))
            is BodyWaterMassRecord -> out.measurement("body", r.time, mapOf("bodyWaterKg" to Days.num(r.mass.inKilograms, 2)))
            is BasalMetabolicRateRecord -> out.measurement("body", r.time, mapOf("bmrKcal" to Days.num(r.basalMetabolicRate.inKilocaloriesPerDay, 0)))
            is HeightRecord -> out.measurement("height", r.time, mapOf("m" to Days.num(r.height.inMeters, 2)))
            is BloodPressureRecord -> out.measurement("bloodPressure", r.time, buildMap {
                put("systolic", Days.num(r.systolic.inMillimetersOfMercury, 0)); put("diastolic", Days.num(r.diastolic.inMillimetersOfMercury, 0))
                if (r.bodyPosition != 0) put("position", r.bodyPosition)
                if (r.measurementLocation != 0) put("site", r.measurementLocation)
            })
            is BloodGlucoseRecord -> out.measurement("bloodGlucose", r.time, buildMap {
                put("mmolL", Days.num(r.level.inMillimolesPerLiter, 2))
                if (r.mealType != 0) put("meal", r.mealType)
                if (r.relationToMeal != 0) put("relation", r.relationToMeal)
                if (r.specimenSource != 0) put("specimen", r.specimenSource)
            })
            is BodyTemperatureRecord -> out.measurement("bodyTemp", r.time, buildMap {
                put("c", Days.num(r.temperature.inCelsius, 2))
                if (r.measurementLocation != 0) put("site", r.measurementLocation)
            })
            is BasalBodyTemperatureRecord -> out.measurement("basalBodyTemp", r.time, mapOf("c" to Days.num(r.temperature.inCelsius, 2)))
            is Vo2MaxRecord -> out.measurement("vo2Max", r.time, mapOf("mlKgMin" to Days.num(r.vo2MillilitersPerMinuteKilogram, 1)))
            is MenstruationFlowRecord -> out.measurement("menstruationFlow", r.time, mapOf("flow" to r.flow))
            is OvulationTestRecord -> out.measurement("ovulationTest", r.time, mapOf("result" to r.result))
            is CervicalMucusRecord -> out.measurement("cervicalMucus", r.time, mapOf("appearance" to r.appearance, "sensation" to r.sensation))
            is IntermenstrualBleedingRecord -> out.measurement("intermenstrualBleeding", r.time, emptyMap())
            is SexualActivityRecord -> out.measurement("sexualActivity", r.time, mapOf("protection" to r.protectionUsed))
        }
    }

    private fun sleep(r: SleepSessionRecord): Map<String, Any?> {
        val start = r.startTime.toEpochMilli()
        val stages = r.stages.sortedBy { it.startTime }.map {
            val kind = when (it.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
                else -> "asleep"
            }
            Triple(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), kind)
        }
        return sleepJson(start, r.endTime.toEpochMilli(), null, stages)
    }

    private suspend fun exercise(c: Context, out: Collected, r: ExerciseSessionRecord, g: Set<String>, routes: Boolean) {
        val id = Days.sha16("healthConnect:${r.metadata.id}")
        val m = linkedMapOf<String, Any?>(
            "id" to id,
            "type" to (ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[r.exerciseType] ?: "other"),
            "start" to r.startTime.toEpochMilli(),
            "end" to r.endTime.toEpochMilli(),
        )
        r.title?.takeIf { it.isNotBlank() }?.let { m["title"] = it }
        // The session's own origin only: the phone's step counter must not add its steps to a watch workout.
        val per = listOf(
            DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX,
            HeartRateRecord.BPM_MIN, ElevationGainedRecord.ELEVATION_GAINED_TOTAL, SpeedRecord.SPEED_AVG,
        )
        val types = mapOf<AggregateMetric<*>, KClass<out Record>>(
            DistanceRecord.DISTANCE_TOTAL to DistanceRecord::class, StepsRecord.COUNT_TOTAL to StepsRecord::class,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL to TotalCaloriesBurnedRecord::class,
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL to ActiveCaloriesBurnedRecord::class,
            ElevationGainedRecord.ELEVATION_GAINED_TOTAL to ElevationGainedRecord::class, SpeedRecord.SPEED_AVG to SpeedRecord::class,
        ).withDefault { HeartRateRecord::class }
        val metrics = per.filter { read(types.getValue(it)) in g }.toSet()
        // One aggregate call per session is the slowest part of a backfill (about a second each
        // with the phone dozing), so each session's figures are kept until the record changes.
        val key = "${r.metadata.id}@${r.metadata.lastModifiedTime.toEpochMilli()}/${metrics.size}"
        val stats = exerciseStats(c)[key] ?: if (metrics.isEmpty()) emptyMap() else guarded("exercise totals") {
            client(c).aggregate(AggregateRequest(metrics, TimeRangeFilter.between(r.startTime, r.endTime), setOf(r.metadata.dataOrigin)))
        }?.let { a ->
            buildMap {
                (a[TotalCaloriesBurnedRecord.ENERGY_TOTAL] ?: a[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL])?.let { put("kcal", Days.num(it.inKilocalories, 0)) }
                a[DistanceRecord.DISTANCE_TOTAL]?.let { put("distanceM", Days.num(it.inMeters, 0)) }
                a[StepsRecord.COUNT_TOTAL]?.takeIf { it > 0 }?.let { put("steps", it) }
                a[HeartRateRecord.BPM_AVG]?.let { put("hrAvg", it) }
                a[HeartRateRecord.BPM_MAX]?.let { put("hrMax", it) }
                a[HeartRateRecord.BPM_MIN]?.let { put("hrMin", it) }
                a[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.let { put("elevationM", Days.num(it.inMeters, 1)) }
                a[SpeedRecord.SPEED_AVG]?.let { put("speedAvgMps", Days.num(it.inMetersPerSecond, 2)) }
            }.also { statsCache?.put(key, it); statsDirty = true }
        }
        stats?.let { m.putAll(it) }
        val route = routes && ROUTES in g && (r.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route?.isNotEmpty() == true
        m["route"] = route
        if (route) out.routes[id] = RouteRef("healthConnect", r.metadata.id, 0, r.startTime, r.endTime)
        out.session("exercise", out.date(r.startTime), m)
    }

    private var statsCache: HashMap<String, Map<String, Any?>>? = null
    private var statsDirty = false
    private fun statsFile(c: Context) = java.io.File(c.filesDir, "vitals/hc-exercise.json")

    private fun exerciseStats(c: Context): Map<String, Map<String, Any?>> = statsCache ?: HashMap<String, Map<String, Any?>>().also { map ->
        runCatching {
            val o = org.json.JSONObject(statsFile(c).readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                map[k] = e.keys().asSequence().associateWith { e.get(it) }
            }
        }
        statsCache = map
    }

    private fun saveStats(c: Context) {
        val map = statsCache ?: return
        if (!statsDirty) return
        val f = statsFile(c)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.path + ".tmp")
        tmp.writeText(org.json.JSONObject(map.mapValues { org.json.JSONObject(it.value) } as Map<*, *>).toString())
        tmp.renameTo(f)
        statsDirty = false
    }

    suspend fun route(c: Context, ref: RouteRef): List<List<Any?>>? {
        val rec = client(c).readRecord(ExerciseSessionRecord::class, ref.uid).record
        val pts = (rec.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route ?: return null
        return pts.map { listOf(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters) }
    }

    /** The first day with data: the first record of the session and sample types, and steps narrowed year, month, day. */
    suspend fun oldest(c: Context, zone: java.time.ZoneId): LocalDate? {
        if (!available(c)) return null
        val g = granted(c)
        val firsts = listOf(HeartRateRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class, WeightRecord::class)
            .filter { read(it) in g }.mapNotNull { cls ->
                guarded("oldest") {
                    client(c).readRecords(ReadRecordsRequest(cls, TimeRangeFilter.before(Instant.now()), ascendingOrder = true, pageSize = 1)).records.firstOrNull()
                }?.let { r ->
                    when (r) {
                        is HeartRateRecord -> r.startTime
                        is SleepSessionRecord -> r.endTime
                        is ExerciseSessionRecord -> r.startTime
                        is WeightRecord -> r.time
                        else -> null
                    }?.let { LocalDate.ofInstant(it, zone) }
                }
            }
        val steps = if (read(StepsRecord::class) !in g) null else guarded("oldest steps") {
            suspend fun first(from: java.time.LocalDateTime, to: java.time.LocalDateTime, by: Period) =
                client(c).aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to), by))
                    .firstOrNull { (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) > 0L }?.startTime
            val now = java.time.LocalDateTime.now(zone)
            val year = first(java.time.LocalDateTime.of(2010, 1, 1, 0, 0), now, Period.ofYears(1)) ?: return@guarded null
            val month = first(year, minOf(year.plusYears(1), now), Period.ofMonths(1)) ?: return@guarded null
            first(month, minOf(month.plusMonths(1), now), Period.ofDays(1))?.toLocalDate()
        }
        return (firsts + listOfNotNull(steps)).minOrNull()
    }

    /** Height and weight for `meta.profile` when Samsung's profile is not readable: the newest records. */
    suspend fun profile(c: Context): Map<String, Any?> {
        if (!available(c)) return emptyMap()
        val g = granted(c)
        val all = TimeRangeFilter.before(Instant.now())
        val out = sortedMapOf<String, Any?>()
        if (read(HeightRecord::class) in g) guarded("height") {
            client(c).readRecords(ReadRecordsRequest(HeightRecord::class, all, ascendingOrder = false, pageSize = 1)).records.firstOrNull()
        }?.let { out["heightCm"] = Days.num(it.height.inMeters * 100, 1) }
        if (read(WeightRecord::class) in g) guarded("weight") {
            client(c).readRecords(ReadRecordsRequest(WeightRecord::class, all, ascendingOrder = false, pageSize = 1)).records.firstOrNull()
        }?.let { out["weightKg"] = Days.num(it.weight.inKilograms, 1) }
        return out
    }
}

/** CONTRACT §2 Sleep, shared by both sources; [raw] stages are (start ms, end ms, kind). */
fun sleepJson(start: Long, end: Long, score: Int?, raw: List<Triple<Long, Long, String>>): Map<String, Any?> = buildMap {
    put("start", start)
    put("end", end)
    score?.let { put("score", it) }
    // Boundaries are rounded to whole minutes, not each duration: rounding durations alone
    // leaves 0-minute stages and offsets that drift from the session. Overlaps are cut at the
    // previous stage's end, what collapses to nothing is dropped, and touching stages of the
    // same kind become one.
    fun minute(t: Long) = (t.coerceIn(start, maxOf(start, end)) - start + 30_000) / 60_000
    val stages = ArrayList<MutableList<Any>>()
    var prevEnd = 0L
    for ((s, e, kind) in raw.sortedBy { it.first }) {
        val a = maxOf(minute(s), prevEnd)
        val b = minute(e)
        if (b <= a) continue
        val last = stages.lastOrNull()
        if (last != null && last[2] == kind && (last[0] as Long) + (last[1] as Long) == a) last[1] = b - (last[0] as Long)
        else stages += mutableListOf<Any>(a, b - a, kind)
        prevEnd = b
    }
    if (stages.isNotEmpty() && end > start) {
        val awake = stages.filter { it[2] == "awake" }.sumOf { it[1] as Long }
        put("efficiency", (100 - awake * 100 * 60_000 / (end - start)).coerceIn(0, 100))
    }
    put("stages", stages)
}
