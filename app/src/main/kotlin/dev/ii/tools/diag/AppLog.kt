package dev.ii.tools.diag

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.ii.tools.BuildConfig
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStats
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.live.LiveHub
import dev.ii.tools.pclive.PcLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LogSummary(
    val crashCount: Int = 0,
    val lastCrashAt: Long = 0L,
    val sizeBytes: Long = 0L,
)

/**
 * In-app crash and diagnostic log recorder.
 *
 * Captures uncaught exceptions and key caught errors/events into small rotating files under
 * `filesDir/diag/` (no extra dependencies, no background thread, never records passwords,
 * tokens, clipboard contents, or notification text). Settings can preview, copy, share as a
 * `.txt` file via [FileProvider], or clear it.
 */
object AppLog {
    private const val DIR = "diag"
    private const val CRASHES = "crashes.log"
    private const val EVENTS = "events.log"
    private const val EVENTS_PREV = "events.prev.log"
    private const val CRASH_MARKER = "=== CRASH "
    private const val MAX_CRASH_BYTES = 64 * 1024
    private const val MAX_EVENT_BYTES = 64 * 1024
    private const val MAX_LOGCAT_BYTES = 32 * 1024

    private val lock = Any()
    private lateinit var app: Context
    private var installed = false

    private val _summary = MutableStateFlow(LogSummary())
    val summary: StateFlow<LogSummary> = _summary.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (installed) return
            app = ctx.applicationContext
            installed = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { recordCrashLocked(thread, throwable) }
                prev?.uncaughtException(thread, throwable)
            }
            refreshSummaryLocked()
        }
        i("App", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}) Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        appendEvent("I", tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        appendEvent("W", tag, msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        appendEvent("E", tag, msg, t)
    }

    fun buildReport(ctx: Context): String {
        val c = ctx.applicationContext
        val dm = c.resources.displayMetrics
        val status = LinkHub.status.value
        val statusText = when (status) {
            is LinkStatus.Online -> "Online"
            is LinkStatus.Offline -> "Offline(${status.problem}${status.failure?.let { "/${it.reason}" }.orEmpty()})"
            LinkStatus.Connecting -> "Connecting"
            LinkStatus.Pairing -> "Pairing"
            LinkStatus.Idle -> "Idle"
            LinkStatus.Unpaired -> "Unpaired"
        }
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val shell = LinkHub.shell.value

        val sb = StringBuilder(4096)
        sb.appendLine("# ii tools diagnostic report")
        sb.appendLine("Generated: ${isoNow()}")
        sb.appendLine()
        sb.appendLine("## App & Device")
        sb.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        sb.appendLine("Package: ${c.packageName}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, security ${Build.VERSION.SECURITY_PATCH})")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND}/${Build.DEVICE})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("Display: ${dm.widthPixels}x${dm.heightPixels} @ ${"%.2f".format(Locale.ROOT, dm.density)}x (${dm.densityDpi} dpi)")
        sb.appendLine("Locale: ${Locale.getDefault()}")
        sb.appendLine()
        sb.appendLine("## Runtime State")
        sb.appendLine("Link: $statusText (paired=${LinkHub.machines.value.size}, stayConnected=${LinkHub.staysConnected()})")
        sb.appendLine("Shell caps: ${shell?.caps?.sorted()?.joinToString()?.ifEmpty { "none" } ?: "none"}")
        sb.appendLine("Permissions: postNotif=$notifGranted, notifListener=${LiveHub.connected.value}, batteryUnrestricted=${LinkHub.batteryUnrestricted()}")
        sb.appendLine("PcLive enabled: ${PcLive.isEnabled(c)}, active items: ${PcLive.items.value.size}")
        sb.appendLine()
        sb.appendLine("## Link Stats")
        val statsOut = StringWriter()
        LinkStats.dump(PrintWriter(statsOut))
        sb.append(statsOut.toString().trimEnd())
        sb.appendLine()

        val (crashesText, eventsText) = synchronized(lock) {
            val dir = diagDir(c)
            val crashes = File(dir, CRASHES).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull().orEmpty()
            val prev = File(dir, EVENTS_PREV).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull().orEmpty()
            val curr = File(dir, EVENTS).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull().orEmpty()
            crashes to (prev + curr)
        }

        sb.appendLine()
        sb.appendLine("## Process Exit Reasons")
        val exits = readExitReasons(c)
        if (exits.isBlank()) {
            sb.appendLine("(none)")
        } else {
            sb.append(exits.trimEnd())
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("## Recorded Crashes")
        if (crashesText.isBlank()) {
            sb.appendLine("(none)")
        } else {
            sb.append(crashesText.trimEnd())
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("## App Events")
        if (eventsText.isBlank()) {
            sb.appendLine("(none)")
        } else {
            sb.append(eventsText.trimEnd())
            sb.appendLine()
        }

        val logcat = readProcessLogcat()
        if (logcat.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("## Process Logcat (PID ${Process.myPid()})")
            sb.append(logcat.trimEnd())
            sb.appendLine()
        }
        return sb.toString()
    }

    /** Writes a fresh report into `cacheDir/diag/` and opens the system share sheet. */
    fun shareReport(ctx: Context): Boolean = runCatching {
        val report = buildReport(ctx)
        val outDir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(outDir, "ii-tools-log-$stamp.txt")
        file.writeText(report)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ii tools diagnostic log ($stamp)")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.diag_share_title)))
        true
    }.getOrElse {
        e("AppLog", "share failed", it)
        false
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            val dir = diagDir(ctx)
            File(dir, CRASHES).delete()
            File(dir, EVENTS).delete()
            File(dir, EVENTS_PREV).delete()
            File(ctx.cacheDir, DIR).listFiles()?.forEach { it.delete() }
            refreshSummaryLocked()
        }
    }

    private fun appendEvent(level: String, tag: String, msg: String, t: Throwable?) {
        if (!installed) return
        val line = buildString {
            append(shortNow()).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (t != null) append(t.stackTraceToString()).append('\n')
        }
        synchronized(lock) {
            runCatching {
                val dir = diagDir(app)
                val file = File(dir, EVENTS)
                if (file.length() + line.length > MAX_EVENT_BYTES) {
                    val prev = File(dir, EVENTS_PREV)
                    prev.delete()
                    file.renameTo(prev)
                }
                file.appendText(line)
                refreshSummaryLocked()
            }
        }
    }

    private fun recordCrashLocked(thread: Thread, throwable: Throwable) {
        synchronized(lock) {
            val dir = diagDir(app)
            val file = File(dir, CRASHES)
            val now = System.currentTimeMillis()
            val entry = buildString {
                append(CRASH_MARKER).append(iso(now)).append(" ===\n")
                append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(", ").append(BuildConfig.BUILD_TYPE).append(")\n")
                append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") ")
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("Thread: ").append(thread.name).append('\n')
                append(throwable.stackTraceToString()).append('\n')
            }
            val existing = if (file.isFile) file.readText() else ""
            val combined = existing + entry
            val trimmed = if (combined.length > MAX_CRASH_BYTES) {
                val cut = combined.indexOf(CRASH_MARKER, combined.length - MAX_CRASH_BYTES)
                if (cut >= 0) combined.substring(cut) else combined.takeLast(MAX_CRASH_BYTES)
            } else combined
            file.writeText(trimmed)
            File(dir, EVENTS).appendText("${shortNow()} F/Crash: ${throwable.javaClass.simpleName}: ${throwable.message}\n")
            refreshSummaryLocked()
        }
    }

    private fun refreshSummaryLocked() {
        val dir = diagDir(app)
        val crashes = File(dir, CRASHES)
        val events = File(dir, EVENTS)
        val prev = File(dir, EVENTS_PREV)
        val crashText = crashes.takeIf { it.isFile }?.runCatching { readText() }?.getOrNull().orEmpty()
        var count = 0
        var idx = 0
        while (true) {
            val found = crashText.indexOf(CRASH_MARKER, idx)
            if (found < 0) break
            count++
            idx = found + CRASH_MARKER.length
        }
        val size = (if (crashes.isFile) crashes.length() else 0L) +
            (if (events.isFile) events.length() else 0L) +
            (if (prev.isFile) prev.length() else 0L)
        _summary.value = LogSummary(
            crashCount = count,
            lastCrashAt = if (count > 0) crashes.lastModified() else 0L,
            sizeBytes = size,
        )
    }

    private fun readExitReasons(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return ""
        val reasons = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
        buildString {
            for (info in reasons) {
                append(iso(info.timestamp))
                    .append("  reason=").append(exitReasonName(info.reason))
                    .append("  status=").append(info.status)
                    .append("  rss=").append(info.rss / 1024).append("MB")
                info.description?.takeIf { it.isNotBlank() }?.let { append("  desc=").append(it) }
                append('\n')
                if (info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == ApplicationExitInfo.REASON_ANR
                ) {
                    runCatching {
                        info.traceInputStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { trace ->
                        append(if (trace.length > 8192) trace.take(8192) + "\n...(truncated)\n" else trace)
                        if (!trace.endsWith('\n')) append('\n')
                    }
                }
            }
        }
    }.getOrDefault("")

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private fun readProcessLogcat(): String = runCatching {
        val proc = ProcessBuilder("logcat", "-d", "-t", "200", "--pid=${Process.myPid()}", "-v", "time")
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(2, TimeUnit.SECONDS)
        proc.destroy()
        if (text.length > MAX_LOGCAT_BYTES) text.takeLast(MAX_LOGCAT_BYTES) else text
    }.getOrDefault("")

    private fun diagDir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun isoNow(): String = iso(System.currentTimeMillis())
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(Date(ms))
    private fun shortNow(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
}
