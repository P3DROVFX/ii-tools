package dev.ii.tools.vitals

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.device.DeviceGroup
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalDateGroup
import com.samsung.android.sdk.health.data.request.LocalDateGroupUnit
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The Samsung Health Data SDK (built in: its AAR was in app/libs at build time). Read-only. */
object SamsungSource {
    private const val TAG = "iiVitals"
    private const val SHEALTH = "com.sec.android.app.shealth"

    private val types: List<DataType> = listOf(
        DataTypes.STEPS, DataTypes.ACTIVITY_SUMMARY, DataTypes.FLOORS_CLIMBED, DataTypes.WATER_INTAKE, DataTypes.NUTRITION,
        DataTypes.HEART_RATE, DataTypes.BLOOD_OXYGEN, DataTypes.SKIN_TEMPERATURE, DataTypes.SLEEP, DataTypes.EXERCISE,
        DataTypes.EXERCISE_LOCATION, DataTypes.BODY_COMPOSITION, DataTypes.BLOOD_PRESSURE, DataTypes.BLOOD_GLUCOSE,
        DataTypes.BODY_TEMPERATURE, DataTypes.ENERGY_SCORE, DataTypes.USER_PROFILE, DataTypes.STEPS_GOAL,
        DataTypes.ACTIVE_TIME_GOAL, DataTypes.ACTIVE_CALORIES_BURNED_GOAL, DataTypes.SLEEP_GOAL,
        DataTypes.WATER_INTAKE_GOAL, DataTypes.NUTRITION_GOAL,
    )
    private val permissions = types.map { Permission.of(it, AccessType.READ) }.toSet()

    private fun store(c: Context) = HealthDataService.getStore(c.applicationContext)

    private fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(SHEALTH, 0) }.isSuccess

    private fun status(e: Throwable): String = when {
        e is ResolvablePlatformException -> "error"
        e !is HealthDataException -> "error"
        e.errorCode == 3000 -> "notInstalled"
        e.errorCode == 2000 -> "noPermission"
        // An app Samsung has not approved reads only with Samsung Health's developer mode on.
        e.errorCode == 1002 || e.errorCode == 2003 || e.errorCode == 2004 -> "devModeOff"
        else -> "error"
    }.also { if (it == "error" || it == "devModeOff") Log.w(TAG, "samsung: ${e.javaClass.simpleName} code=${(e as? HealthDataException)?.errorCode}") }

    private suspend fun granted(c: Context): Set<Permission> = store(c).getGrantedPermissions(permissions)

    /** CONTRACT §4 `sources.samsung`; "on" only after a real read succeeds (developer mode is checked by reads, not grants). */
    suspend fun status(c: Context): String {
        if (!installed(c)) return "notInstalled"
        return try {
            val g = granted(c)
            if (g.isEmpty()) return "noPermission"
            val probe = types.first { Permission.of(it, AccessType.READ) in g && it is DataType.Readable<*, *> && it != DataTypes.USER_PROFILE }
            if (probe == DataTypes.ENERGY_SCORE) {
                store(c).readData(DataTypes.ENERGY_SCORE.readDataRequestBuilder.setLocalDateFilter(LocalDateFilter.since(LocalDate.now())).setLimit(1).build())
            } else {
                dual(probe)?.let { store(c).readData(it().setInstantTimeFilter(InstantTimeFilter.since(Instant.now())).setLimit(1).build()) }
            }
            "on"
        } catch (e: NoSuchElementException) {
            "on" // only goals or the profile granted: nothing to probe, the reads say the rest
        } catch (e: Exception) {
            status(e)
        }
    }

    /** Samsung's own permission sheet (the routes' type included; the switch decides whether they leave the phone). */
    suspend fun requestPermissions(a: Activity) {
        try {
            store(a).requestPermissions(permissions, a)
        } catch (e: ResolvablePlatformException) {
            if (e.hasResolution) e.resolve(a)
        } catch (e: Exception) {
            // Called from the screen's coroutine: anything else the SDK throws would crash the app.
            Log.w(TAG, "samsung permissions: ${e.javaClass.simpleName} code=${(e as? HealthDataException)?.errorCode}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dual(t: DataType): (() -> ReadDataRequest.DualTimeBuilder<HealthDataPoint>)? =
        (t as? DataType.Readable<*, *>)?.takeIf { it.readDataRequestBuilder is ReadDataRequest.DualTimeBuilder<*> }?.let {
            { (t as DataType.Readable<HealthDataPoint, ReadDataRequest.DualTimeBuilder<HealthDataPoint>>).readDataRequestBuilder }
        }

    private suspend fun points(c: Context, t: DataType, from: Instant, to: Instant): List<HealthDataPoint> {
        val b = dual(t) ?: return emptyList()
        val out = ArrayList<HealthDataPoint>()
        var token: String? = null
        do {
            val r = store(c).readData(b().setInstantTimeFilter(InstantTimeFilter.of(from, to)).setPageSize(1000).also { if (token != null) it.setPageToken(token) }.build())
            out += r.dataList
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** Hour buckets of one aggregate over [from, to), paged. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> hourly(c: Context, op: AggregateOperation<T, *>, from: LocalDateTime, to: LocalDateTime, each: (LocalDateTime, T) -> Unit) {
        var token: String? = null
        do {
            val filter = LocalTimeFilter.of(from, to)
            val group = LocalTimeGroup.of(LocalTimeGroupUnit.HOURLY, 1)
            val req: AggregateRequest<T> = when (val b = op.requestBuilder) {
                is AggregateRequest.LocalTimeBuilder<*> -> (b as AggregateRequest.LocalTimeBuilder<T>).setLocalTimeFilterWithGroup(filter, group)
                    .also { if (token != null) it.setPageToken(token) }.build()
                is AggregateRequest.DualTimeBuilder<*> -> (b as AggregateRequest.DualTimeBuilder<T>).setLocalTimeFilterWithGroup(filter, group)
                    .also { if (token != null) it.setPageToken(token) }.build()
                else -> return
            }
            val r = store(c).aggregateData(req)
            r.dataList.forEach { d -> d.value?.let { each(LocalDateTime.ofInstant(d.startTime, ZoneId.systemDefault()), it) } }
            token = r.pageToken
        } while (!token.isNullOrEmpty())
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "samsung $what: ${e.javaClass.simpleName} code=${(e as? HealthDataException)?.errorCode}")
        }
    }

    suspend fun collect(c: Context, from: LocalDate, to: LocalDate, out: Collected, routes: Boolean) {
        val g = try { granted(c) } catch (e: Exception) { return }
        fun has(t: DataType) = Permission.of(t, AccessType.READ) in g
        val fromL = from.atStartOfDay()
        val toL = to.plusDays(1).atStartOfDay()
        // A day earlier: a night that began the evening before belongs to the first day too, and
        // what the extra day reads lands on a day the caller does not build.
        val fromI = from.minusDays(1).atStartOfDay(out.zone).toInstant()
        val toI = to.plusDays(1).atStartOfDay(out.zone).toInstant()

        if (has(DataTypes.STEPS)) guarded("steps") { hourly(c, DataType.StepsType.TOTAL, fromL, toL) { t, v -> out.totalLocal("steps", t, v.toDouble()) } }
        if (has(DataTypes.ACTIVITY_SUMMARY)) guarded("activity") {
            hourly(c, DataType.ActivitySummaryType.TOTAL_DISTANCE, fromL, toL) { t, v -> out.totalLocal("distanceM", t, v.toDouble()) }
            hourly(c, DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED, fromL, toL) { t, v -> out.totalLocal("activeKcal", t, v.toDouble()) }
            hourly(c, DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED, fromL, toL) { t, v -> out.totalLocal("totalKcal", t, v.toDouble()) }
            hourly(c, DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME, fromL, toL) { t, v -> out.totalLocal("activeMin", t, v.seconds / 60.0) }
        }
        if (has(DataTypes.FLOORS_CLIMBED)) guarded("floors") { hourly(c, DataType.FloorsClimbedType.TOTAL, fromL, toL) { t, v -> out.totalLocal("floors", t, v.toDouble()) } }
        if (has(DataTypes.WATER_INTAKE)) guarded("water") { hourly(c, DataType.WaterIntakeType.TOTAL, fromL, toL) { t, v -> out.totalLocal("hydrationMl", t, v.toDouble()) } }
        if (has(DataTypes.NUTRITION)) guarded("nutrition") {
            val fields = mapOf(
                "intakeKcal" to DataType.NutritionType.CALORIES, "intakeProteinG" to DataType.NutritionType.PROTEIN,
                "intakeFatG" to DataType.NutritionType.TOTAL_FAT, "intakeCarbsG" to DataType.NutritionType.CARBOHYDRATE,
                "intakeSugarG" to DataType.NutritionType.SUGAR, "intakeFiberG" to DataType.NutritionType.DIETARY_FIBER,
                "intakeSodiumMg" to DataType.NutritionType.SODIUM,
            )
            for (p in points(c, DataTypes.NUTRITION, fromI, toI)) for ((k, f) in fields) p.getValue(f)?.let { out.total(k, p.startTime, it.toDouble()) }
        }
        if (has(DataTypes.HEART_RATE)) guarded("heartRate") {
            for (p in points(c, DataTypes.HEART_RATE, fromI, toI)) {
                p.dataSource?.deviceId?.let { out.deviceIds += it }
                val series = p.getValue(DataType.HeartRateType.SERIES_DATA).orEmpty()
                if (series.isEmpty()) p.getValue(DataType.HeartRateType.HEART_RATE)?.let { v ->
                    out.sample("heartRate", p.startTime, v.toDouble(), (p.getValue(DataType.HeartRateType.MIN_HEART_RATE) ?: v).toDouble(), (p.getValue(DataType.HeartRateType.MAX_HEART_RATE) ?: v).toDouble())
                }
                series.forEach { out.sample("heartRate", it.startTime, it.heartRate.toDouble(), it.min.toDouble(), it.max.toDouble()) }
            }
        }
        if (has(DataTypes.BLOOD_OXYGEN)) guarded("spo2") {
            for (p in points(c, DataTypes.BLOOD_OXYGEN, fromI, toI)) {
                val series = p.getValue(DataType.BloodOxygenType.SERIES_DATA).orEmpty()
                if (series.isEmpty()) p.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION)?.let { out.sample("spo2Pct", p.startTime, it.toDouble()) }
                series.forEach { out.sample("spo2Pct", it.startTime, it.oxygenSaturation.toDouble(), it.min.toDouble(), it.max.toDouble()) }
            }
        }
        if (has(DataTypes.SKIN_TEMPERATURE)) guarded("skinTemp") {
            for (p in points(c, DataTypes.SKIN_TEMPERATURE, fromI, toI)) {
                val series = p.getValue(DataType.SkinTemperatureType.SERIES_DATA).orEmpty()
                if (series.isEmpty()) p.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE)?.let { out.sample("skinTempC", p.startTime, it.toDouble()) }
                series.forEach { out.sample("skinTempC", it.startTime, it.skinTemperature.toDouble(), it.min.toDouble(), it.max.toDouble()) }
            }
        }
        if (has(DataTypes.SLEEP)) guarded("sleep") {
            for (p in points(c, DataTypes.SLEEP, fromI, toI)) {
                p.dataSource?.deviceId?.let { out.deviceIds += it }
                val sessions = p.getValue(DataType.SleepType.SESSIONS).orEmpty()
                val longest = sessions.maxByOrNull { it.duration }
                for (s in sessions) {
                    val start = s.startTime.toEpochMilli()
                    val stages = s.stages.orEmpty().sortedBy { it.startTime }.map {
                        val kind = when (it.stage) {
                            DataType.SleepType.StageType.DEEP -> "deep"
                            DataType.SleepType.StageType.LIGHT -> "light"
                            DataType.SleepType.StageType.REM -> "rem"
                            DataType.SleepType.StageType.AWAKE -> "awake"
                            else -> "asleep"
                        }
                        Triple(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), kind)
                    }
                    // Samsung scores the night, not each part of it: the score goes on its longest session.
                    val score = if (s === longest) p.getValue(DataType.SleepType.SLEEP_SCORE) else null
                    out.session("sleep", out.date(s.endTime), sleepJson(start, s.endTime.toEpochMilli(), score, stages))
                }
            }
        }
        if (has(DataTypes.EXERCISE)) guarded("exercise") {
            for (p in points(c, DataTypes.EXERCISE, fromI, toI)) {
                p.dataSource?.deviceId?.let { out.deviceIds += it }
                val title = p.getValue(DataType.ExerciseType.CUSTOM_TITLE)?.takeIf { it.isNotBlank() }
                p.getValue(DataType.ExerciseType.SESSIONS).orEmpty().forEachIndexed { i, s ->
                    val id = Days.sha16("samsung:${p.uid}" + if (i > 0) ":$i" else "")
                    val route = routes && s.route?.isNotEmpty() == true
                    if (route) out.routes[id] = RouteRef("samsung", p.uid, i, p.startTime, p.endTime ?: s.endTime)
                    val m = linkedMapOf<String, Any?>(
                        "id" to id, "type" to exerciseType(s.exerciseType ?: p.getValue(DataType.ExerciseType.EXERCISE_TYPE)),
                        "start" to s.startTime.toEpochMilli(), "end" to s.endTime.toEpochMilli(), "route" to route,
                        "kcal" to Days.num(s.calories.toDouble(), 0),
                    )
                    title?.let { m["title"] = it }
                    s.distance?.let { m["distanceM"] = Days.num(it.toDouble(), 0) }
                    if (s.countType == DataType.ExerciseType.CountType.STRIDE) s.count?.let { m["steps"] = it }
                    s.meanHeartRate?.let { m["hrAvg"] = Days.num(it.toDouble(), 0) }
                    s.maxHeartRate?.let { m["hrMax"] = Days.num(it.toDouble(), 0) }
                    s.minHeartRate?.let { m["hrMin"] = Days.num(it.toDouble(), 0) }
                    s.altitudeGain?.let { m["elevationM"] = Days.num(it.toDouble(), 1) }
                    s.meanSpeed?.let { m["speedAvgMps"] = Days.num(it.toDouble(), 2) }
                    out.session("exercise", out.date(s.startTime), m)
                }
            }
        }
        if (has(DataTypes.BODY_COMPOSITION)) guarded("body") {
            val fields = mapOf(
                "weightKg" to DataType.BodyCompositionType.WEIGHT, "bodyFatPct" to DataType.BodyCompositionType.BODY_FAT,
                "skeletalMuscleKg" to DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS, "leanMassKg" to DataType.BodyCompositionType.FAT_FREE_MASS,
                "bodyWaterKg" to DataType.BodyCompositionType.TOTAL_BODY_WATER, "bmi" to DataType.BodyCompositionType.BODY_MASS_INDEX,
            )
            for (p in points(c, DataTypes.BODY_COMPOSITION, fromI, toI)) {
                val m = fields.mapNotNull { (k, f) -> p.getValue(f)?.let { k to Days.num(it.toDouble(), 2) } }.toMap(linkedMapOf<String, Any?>())
                p.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE)?.let { m["bmrKcal"] = it }
                if (m.isNotEmpty()) out.measurement("body", p.startTime, m)
            }
        }
        if (has(DataTypes.BLOOD_PRESSURE)) guarded("bloodPressure") {
            for (p in points(c, DataTypes.BLOOD_PRESSURE, fromI, toI)) {
                val sys = p.getValue(DataType.BloodPressureType.SYSTOLIC) ?: continue
                val dia = p.getValue(DataType.BloodPressureType.DIASTOLIC) ?: continue
                out.measurement("bloodPressure", p.startTime, buildMap {
                    put("systolic", Days.num(sys.toDouble(), 0)); put("diastolic", Days.num(dia.toDouble(), 0))
                    p.getValue(DataType.BloodPressureType.PULSE_RATE)?.let { put("pulse", it) }
                })
            }
        }
        if (has(DataTypes.BLOOD_GLUCOSE)) guarded("bloodGlucose") {
            for (p in points(c, DataTypes.BLOOD_GLUCOSE, fromI, toI)) {
                p.getValue(DataType.BloodGlucoseType.GLUCOSE_LEVEL)?.let { out.measurement("bloodGlucose", p.startTime, mapOf("mmolL" to Days.num(it.toDouble(), 2))) }
            }
        }
        if (has(DataTypes.BODY_TEMPERATURE)) guarded("bodyTemp") {
            for (p in points(c, DataTypes.BODY_TEMPERATURE, fromI, toI)) {
                p.getValue(DataType.BodyTemperatureType.BODY_TEMPERATURE)?.let { out.measurement("bodyTemp", p.startTime, mapOf("c" to Days.num(it.toDouble(), 2))) }
            }
        }
        if (has(DataTypes.ENERGY_SCORE)) guarded("energy") {
            var token: String? = null
            do {
                val r = store(c).readData(DataTypes.ENERGY_SCORE.readDataRequestBuilder.setLocalDateFilter(LocalDateFilter.of(from, to.plusDays(1)))
                    .setPageSize(1000).also { if (token != null) it.setPageToken(token) }.build())
                // A day's score is stamped at that date's midnight UTC.
                r.dataList.forEach { p -> p.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.let { out.score("energy", LocalDate.ofInstant(p.startTime, ZoneOffset.UTC), it.toDouble()) } }
                token = r.pageToken
            } while (!token.isNullOrEmpty())
        }
    }

    private fun exerciseType(t: DataType.ExerciseType.PredefinedExerciseType?): String = when (t) {
        null, DataType.ExerciseType.PredefinedExerciseType.UNDEFINED, DataType.ExerciseType.PredefinedExerciseType.OTHER -> "other"
        // Health Connect's names where Samsung's differ.
        DataType.ExerciseType.PredefinedExerciseType.POOL_SWIMMING -> "swimming_pool"
        DataType.ExerciseType.PredefinedExerciseType.OPEN_WATER_SWIMMING -> "swimming_open_water"
        DataType.ExerciseType.PredefinedExerciseType.STATIONARY_BIKING -> "biking_stationary"
        DataType.ExerciseType.PredefinedExerciseType.TREADMILL -> "running_treadmill"
        DataType.ExerciseType.PredefinedExerciseType.STAIR_CLIMBING_MACHINE -> "stair_climbing_machine"
        DataType.ExerciseType.PredefinedExerciseType.ALPINE_SKIING, DataType.ExerciseType.PredefinedExerciseType.CROSS_COUNTRY_SKIING -> "skiing"
        DataType.ExerciseType.PredefinedExerciseType.WEIGHT_MACHINE -> "strength_training"
        DataType.ExerciseType.PredefinedExerciseType.CIRCUIT_TRAINING -> "high_intensity_interval_training"
        DataType.ExerciseType.PredefinedExerciseType.DANCING -> "dancing"
        else -> t.name.lowercase()
    }

    suspend fun route(c: Context, ref: RouteRef): List<List<Any?>>? {
        val p = points(c, DataTypes.EXERCISE, ref.start.minusSeconds(1), ref.end.plusSeconds(1)).firstOrNull { it.uid == ref.uid } ?: return null
        val route = p.getValue(DataType.ExerciseType.SESSIONS)?.getOrNull(ref.index)?.route ?: return null
        return route.map { listOf(it.timestamp.toEpochMilli(), it.latitude.toDouble(), it.longitude.toDouble(), it.altitude?.toDouble()) }
    }

    /** The first day any readable type has data: one ascending read per type, and steps narrowed year, month, day. */
    suspend fun oldest(c: Context, zone: ZoneId): LocalDate? {
        val g = try { granted(c) } catch (e: Exception) { return null }
        var first: LocalDate? = null
        fun seen(d: LocalDate) { if (first == null || d < first) first = d }
        for (t in types.filter { Permission.of(it, AccessType.READ) in g }) {
            val b = dual(t) ?: continue
            guarded("oldest") {
                store(c).readData(b().setInstantTimeFilter(InstantTimeFilter.to(Instant.now())).setOrdering(Ordering.ASC).setLimit(1).build())
                    .dataList.firstOrNull()?.let { seen(LocalDate.ofInstant(it.startTime, zone)) }
            }
        }
        if (Permission.of(DataTypes.STEPS, AccessType.READ) in g) guarded("oldest steps") {
            suspend fun firstBucket(from: LocalDateTime, to: LocalDateTime, unit: LocalTimeGroupUnit): LocalDateTime? {
                val req = DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilterWithGroup(LocalTimeFilter.of(from, to), LocalTimeGroup.of(unit, 1)).setOrdering(Ordering.ASC).build()
                return store(c).aggregateData(req).dataList.firstOrNull { (it.value ?: 0L) > 0L }?.let { LocalDateTime.ofInstant(it.startTime, zone) }
            }
            val now = LocalDateTime.now(zone)
            firstBucket(LocalDateTime.of(2010, 1, 1, 0, 0), now, LocalTimeGroupUnit.YEARLY)?.let { y ->
                firstBucket(y, minOf(y.plusYears(1), now), LocalTimeGroupUnit.MONTHLY)?.let { m ->
                    firstBucket(m, minOf(m.plusMonths(1), now), LocalTimeGroupUnit.DAILY)?.let { seen(it.toLocalDate()) }
                }
            }
        }
        return first
    }

    /** CONTRACT §4 goals: Samsung Health's current targets. */
    @Suppress("UNCHECKED_CAST")
    suspend fun goals(c: Context): Map<String, Any?> {
        val g = try { granted(c) } catch (e: Exception) { return emptyMap() }
        val today = LocalDate.now()
        suspend fun <T : Any> last(t: DataType, op: AggregateOperation<T, *>): T? {
            if (Permission.of(t, AccessType.READ) !in g) return null
            val b = op.requestBuilder as AggregateRequest.AllSourceLocalDateBuilder<T>
            val req = b.setLocalDateFilterWithGroup(LocalDateFilter.of(today.minusDays(1), today.plusDays(1)), LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1)).build()
            return try { store(c).aggregateData(req).dataList.lastOrNull { it.value != null }?.value } catch (e: Exception) { null }
        }
        val out = sortedMapOf<String, Any?>()
        last(DataTypes.STEPS_GOAL, DataType.StepsGoalType.LAST)?.let { out["steps"] = it }
        last(DataTypes.ACTIVE_TIME_GOAL, DataType.ActiveTimeGoalType.LAST)?.let { out["activeMin"] = it.toMinutes() }
        last(DataTypes.ACTIVE_CALORIES_BURNED_GOAL, DataType.ActiveCaloriesBurnedGoalType.LAST)?.let { out["activeKcal"] = it }
        last(DataTypes.WATER_INTAKE_GOAL, DataType.WaterIntakeGoalType.LAST)?.let { out["waterMl"] = Days.num(it.toDouble(), 0) }
        last(DataTypes.NUTRITION_GOAL, DataType.NutritionGoalType.LAST_CALORIES)?.let { out["intakeKcal"] = Days.num(it.toDouble(), 0) }
        val bed = last(DataTypes.SLEEP_GOAL, DataType.SleepGoalType.LAST_BED_TIME)
        val wake = last(DataTypes.SLEEP_GOAL, DataType.SleepGoalType.LAST_WAKE_UP_TIME)
        if (bed != null && wake != null) {
            val min = Duration.between(bed, wake).toMinutes().let { if (it <= 0) it + 1440 else it }
            out["sleep"] = sortedMapOf("bed" to hhmm(bed), "wake" to hhmm(wake), "durationMin" to min)
        }
        return out
    }

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /** CONTRACT §4 profile from Samsung's user profile. */
    suspend fun profile(c: Context): Map<String, Any?> {
        val g = try { granted(c) } catch (e: Exception) { return emptyMap() }
        if (Permission.of(DataTypes.USER_PROFILE, AccessType.READ) !in g) return emptyMap()
        val p = try {
            store(c).readData(DataTypes.USER_PROFILE.readDataRequestBuilder.build()).dataList.firstOrNull()
        } catch (e: Exception) { null } ?: return emptyMap()
        val out = sortedMapOf<String, Any?>()
        p.getValue(DataType.UserProfileDataType.DATE_OF_BIRTH)?.let { s ->
            val d = runCatching { LocalDate.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            if (d != null) out["birthDate"] = d.toString() else Log.w(TAG, "samsung: birth date in an unknown format (${s.length} chars)")
        }
        when (p.getValue(DataType.UserProfileDataType.GENDER)) {
            DataType.UserProfileDataType.Gender.GENDER_MALE -> out["sex"] = "male"
            DataType.UserProfileDataType.Gender.GENDER_FEMALE -> out["sex"] = "female"
            else -> {}
        }
        p.getValue(DataType.UserProfileDataType.HEIGHT)?.let { out["heightCm"] = Days.num(it.toDouble(), 1) }
        p.getValue(DataType.UserProfileDataType.WEIGHT)?.let { out["weightKg"] = Days.num(it.toDouble(), 1) }
        return out
    }

    /** CONTRACT §4 devices: the watch and phone that wrote the data read. */
    suspend fun devices(c: Context, ids: Set<String>): List<Map<String, Any?>> = ids.mapNotNull { id ->
        val d = try { store(c).getDeviceManager().getDevice(id) } catch (e: Exception) { null } ?: return@mapNotNull null
        val kind = when (d.deviceType) {
            DeviceGroup.WATCH -> "watch"
            DeviceGroup.MOBILE -> "phone"
            DeviceGroup.RING -> "ring"
            else -> "other"
        }
        sortedMapOf("name" to (d.name?.ifBlank { null } ?: d.model ?: kind), "kind" to kind)
    }.sortedBy { it["name"] as String }
}
