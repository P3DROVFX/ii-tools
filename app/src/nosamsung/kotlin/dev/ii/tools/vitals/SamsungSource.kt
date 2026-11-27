package dev.ii.tools.vitals

import android.app.Activity
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Built without the Samsung Health Data SDK (no AAR in app/libs): Health Connect reads every type. */
object SamsungSource {
    suspend fun status(c: Context): String = "notBuiltIn"
    suspend fun requestPermissions(a: Activity) {}
    suspend fun collect(c: Context, from: LocalDate, to: LocalDate, out: Collected, routes: Boolean) {}
    suspend fun route(c: Context, ref: RouteRef): List<List<Any?>>? = null
    suspend fun oldest(c: Context, zone: ZoneId): LocalDate? = null
    suspend fun goals(c: Context): Map<String, Any?> = emptyMap()
    suspend fun profile(c: Context): Map<String, Any?> = emptyMap()
    suspend fun devices(c: Context, ids: Set<String>): List<Map<String, Any?>> = emptyList()
}
