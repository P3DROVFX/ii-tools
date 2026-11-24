package dev.ii.tools.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.ii.tools.R
import dev.ii.tools.UpdateActivity
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateError {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    PARSE,
    CHECKSUM,
}

data class UpdateState(
    val currentVersion: String = "",
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val lastCheckMs: Long = 0L,
    val releases: List<ReleaseInfo> = emptyList(),
    val dismissedTag: String? = null,
    val error: UpdateError? = null,
    val lastHttpCode: Int = 0,
    /** 0..1 while an APK download runs, null otherwise. */
    val downloadProgress: Float? = null,
) {
    val latest: ReleaseInfo? get() = releases.firstOrNull()
    val visibleOnHome: Boolean get() = latest != null && dismissedTag != latest?.tagName
}

/**
 * Opportunistic GitHub release checker (no background polling, no WorkManager/AlarmManager).
 * Checks at most once every 12 hours, when the app comes into the foreground or the link comes up, using ETag
 * conditional requests so 304 responses do not consume GitHub's unauthenticated rate limit.
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    private const val PREFS = "app_update"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_ETAG = "etag"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_LATEST_TAG = "latest_tag"
    private const val KEY_RELEASES_JSON = "releases_json"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val KEY_NOTIFIED_TAG = "notified_tag"

    private const val RELEASES_URL = "https://api.github.com/repos/P3DROVFX/ii-tools/releases?per_page=10"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val AUTO_FAILURE_BACKOFF_MS = 15L * 60 * 1000

    private const val UPDATE_DIR = "updates"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private const val CHANNEL = "app_updates"
    private const val NOTIF_ID = 0x7570
    /** The download's own channel and notice: progress, then ready to install or failed. */
    private const val DL_CHANNEL = "app_update_download"
    private const val DL_NOTIF_ID = 0x7571

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val client by lazy {
        dev.ii.tools.link.Http.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    // An APK takes longer than the 20 s call budget of the release check.
    private val downloadClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var initialized = false
    @Volatile
    private var lastAutoAttemptMs = 0L

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = ctx.applicationContext
            val version = readVersionName(app)
            val currentSemVer = SemVer.parse(version) ?: SemVer(0, 0, 0)
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = runCatching {
                ReleaseParser.fromCacheJson(sp.getString(KEY_RELEASES_JSON, "").orEmpty(), currentSemVer)
            }.getOrDefault(emptyList())
            val dismissed = sp.getString(KEY_DISMISSED_TAG, null)
            _state.value = UpdateState(
                currentVersion = version,
                autoCheck = sp.getBoolean(KEY_AUTO_CHECK, true),
                lastCheckMs = sp.getLong(KEY_LAST_CHECK, 0L),
                releases = cached,
                dismissedTag = dismissed,
            )
            // Drop any stale update notification once the app has been updated past it.
            val notified = sp.getString(KEY_NOTIFIED_TAG, null)
            if (cached.isEmpty() && notified != null) {
                cancelNotification(app)
            }
            // The update this APK carried is installed (or obsolete): drop it. Otherwise keep
            // only the latest release's finished APK, so a second tap installs without
            // downloading again; a half download or an older release's APK goes.
            scope.launch {
                if (cached.isEmpty()) cleanup(app) else pruneDownloads(app, cached.first())
            }
            initialized = true
            // The link's service keeps the process alive in the background, so a check when
            // the link comes up lets the notification reach someone who has not opened the app.
            scope.launch { LinkHub.status.collect { if (it is LinkStatus.Online) checkIfDue(app) } }
        }
    }

    fun setAutoCheck(ctx: Context, on: Boolean) {
        init(ctx)
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_CHECK, on)
            .apply()
        _state.update { it.copy(autoCheck = on) }
        if (on) checkIfDue(app)
    }

    fun dismiss(ctx: Context, tagName: String) {
        init(ctx)
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISMISSED_TAG, tagName)
            .apply()
        _state.update { it.copy(dismissedTag = tagName) }
        cancelNotification(app)
        if (_state.value.downloadProgress == null) scope.launch { cleanup(app) }
    }

    /**
     * Deletes every downloaded APK and the download's notice: the app was updated (see
     * [UpdateCleanup]), the update was dismissed, or there is none left to install.
     */
    fun cleanup(ctx: Context) {
        val app = ctx.applicationContext
        File(app.cacheDir, UPDATE_DIR).deleteRecursively()
        app.getSystemService(NotificationManager::class.java)?.cancel(DL_NOTIF_ID)
    }

    private fun pruneDownloads(app: Context, latest: ReleaseInfo) {
        val keep = apkFile(app, latest).name
        File(app.cacheDir, UPDATE_DIR).listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
    }

    private fun apkFile(app: Context, release: ReleaseInfo): File =
        File(File(app.cacheDir, UPDATE_DIR), "ii-tools-${release.version}.apk")

    /** A finished download of [release] that still matches GitHub's size. */
    private fun downloaded(app: Context, release: ReleaseInfo): File? =
        apkFile(app, release).takeIf { it.isFile && (release.apkSize <= 0L || it.length() == release.apkSize) }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL,
            ctx.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.update_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** Opportunistic foreground check; obeys the 12-hour cadence and fails silently. */
    fun checkIfDue(ctx: Context) {
        init(ctx)
        val snap = _state.value
        if (!snap.autoCheck || snap.checking) return
        val now = System.currentTimeMillis()
        if (now - snap.lastCheckMs < CHECK_INTERVAL_MS) return
        if (now - lastAutoAttemptMs < AUTO_FAILURE_BACKOFF_MS) return
        launchCheck(ctx.applicationContext, manual = false)
    }

    /** User-requested check; bypasses the 12-hour interval and surfaces failures. */
    fun checkNow(ctx: Context) {
        init(ctx)
        launchCheck(ctx.applicationContext, manual = true)
    }

    private fun launchCheck(app: Context, manual: Boolean) {
        scope.launch {
            if (!checkMutex.tryLock()) return@launch
            try {
                val now = System.currentTimeMillis()
                if (!manual) {
                    val snap = _state.value
                    if (!snap.autoCheck || now - snap.lastCheckMs < CHECK_INTERVAL_MS || now - lastAutoAttemptMs < AUTO_FAILURE_BACKOFF_MS) {
                        return@launch
                    }
                    lastAutoAttemptMs = now
                }
                _state.update { it.copy(checking = true, error = if (manual) null else it.error) }
                runCheck(app, manual)
            } finally {
                _state.update { it.copy(checking = false) }
                checkMutex.unlock()
            }
        }
    }

    private fun runCheck(app: Context, manual: Boolean) {
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = _state.value.currentVersion.ifEmpty { readVersionName(app) }
        val currentSemVer = SemVer.parse(currentVersion) ?: SemVer(0, 0, 0)
        val storedEtag = sp.getString(KEY_ETAG, null)

        val req = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ii-tools/$currentVersion")
            .apply { if (!storedEtag.isNullOrEmpty()) header("If-None-Match", storedEtag) }
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val now = System.currentTimeMillis()
                when (resp.code) {
                    304 -> {
                        val newEtag = resp.header("ETag") ?: storedEtag
                        sp.edit()
                            .putLong(KEY_LAST_CHECK, now)
                            .apply { if (!newEtag.isNullOrEmpty()) putString(KEY_ETAG, newEtag) }
                            .apply()
                        val filtered = _state.value.releases.filter { it.version > currentSemVer }
                        _state.update { it.copy(lastCheckMs = now, releases = filtered, error = null, lastHttpCode = 304) }
                    }
                    in 200..299 -> {
                        val body = resp.body.string()
                        val releases = ReleaseParser.parseGitHubReleases(body, currentSemVer)
                        val newEtag = resp.header("ETag")
                        val latestTag = releases.firstOrNull()?.tagName
                        val cachedJson = ReleaseParser.toCacheJson(releases)
                        sp.edit()
                            .putLong(KEY_LAST_CHECK, now)
                            .putString(KEY_LATEST_TAG, latestTag)
                            .putString(KEY_RELEASES_JSON, cachedJson)
                            .apply {
                                if (!newEtag.isNullOrEmpty()) putString(KEY_ETAG, newEtag)
                            }
                            .apply()
                        _state.update {
                            it.copy(
                                lastCheckMs = now,
                                releases = releases,
                                error = null,
                                lastHttpCode = resp.code,
                            )
                        }
                        if (!manual) maybeNotify(app, releases.firstOrNull(), sp.getString(KEY_DISMISSED_TAG, null), sp.getString(KEY_NOTIFIED_TAG, null))
                    }
                    403, 429 -> {
                        Log.w(TAG, "GitHub rate limited (${resp.code})")
                        if (manual) _state.update { it.copy(error = UpdateError.RATE_LIMITED, lastHttpCode = resp.code) }
                    }
                    else -> {
                        Log.w(TAG, "GitHub returned HTTP ${resp.code}")
                        if (manual) _state.update { it.copy(error = UpdateError.HTTP, lastHttpCode = resp.code) }
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse release JSON", e)
            if (manual) _state.update { it.copy(error = UpdateError.PARSE) }
        } catch (e: IOException) {
            Log.w(TAG, "Update check failed", e)
            if (manual) _state.update { it.copy(error = UpdateError.NETWORK) }
        }
    }

    /**
     * Downloads the release APK into the cache, checks its size and SHA-256 against GitHub's
     * metadata, then hands it to the system installer. The first time, it sends the user to
     * the "Install unknown apps" setting instead and they tap Download again.
     */
    fun install(ctx: Context, release: ReleaseInfo) {
        init(ctx)
        val app = ctx.applicationContext
        val url = release.apkUrl ?: return
        if (_state.value.downloadProgress != null) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, R.string.update_install_permission, Toast.LENGTH_LONG).show()
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        // Already downloaded and checked (the installer was closed): install it again.
        downloaded(app, release)?.let { apk ->
            notifyDownload(app, release, ready = installIntent(app, apk))
            openInstaller(app, apk)
            return
        }
        _state.update { it.copy(downloadProgress = 0f, error = null) }
        notifyDownload(app, release, progress = 0)
        scope.launch {
            val dir = File(app.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
            val apk = apkFile(app, release)
            val part = File(dir, "${apk.name}.part")
            val err = download(app, url, release, part)
            if (err != null || !part.renameTo(apk)) {
                part.delete()
                val e = err ?: UpdateError.NETWORK
                _state.update { it.copy(downloadProgress = null, error = e) }
                notifyDownload(app, release, failed = e)
                return@launch
            }
            _state.update { it.copy(downloadProgress = null) }
            // The notice installs too: the installer can't open over another app, and
            // closing it leaves the APK for a second try.
            notifyDownload(app, release, ready = installIntent(app, apk))
            openInstaller(app, apk)
        }
    }

    private fun installIntent(app: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun openInstaller(app: Context, apk: File) {
        runCatching { app.startActivity(installIntent(app, apk)) }
            .onFailure { Log.w(TAG, "Couldn't open the installer", it) }
    }

    private fun ensureDownloadChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(DL_CHANNEL, ctx.getString(R.string.update_dl_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.update_dl_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /**
     * The download's notice: [progress] (0–100) while it runs, as an Android 16 Live Update;
     * then [ready] (tap to install) or [failed] (tap to open the update page).
     */
    private fun notifyDownload(app: Context, release: ReleaseInfo, progress: Int? = null, ready: Intent? = null, failed: UpdateError? = null) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureDownloadChannel(app)
        val version = release.version.toString()
        val openPage = PendingIntent.getActivity(
            app, 1,
            Intent(app, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(app, DL_CHANNEL)
            .setSmallIcon(R.drawable.rounded_download)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        when {
            ready != null -> b
                .setContentTitle(app.getString(R.string.update_dl_ready_title, version))
                .setContentText(app.getString(R.string.update_dl_ready_body))
                .setContentIntent(PendingIntent.getActivity(app, 2, ready, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
            failed != null -> b
                .setContentTitle(app.getString(R.string.update_dl_failed_title, version))
                .setContentText(updateErrorMessage(app, failed))
                .setContentIntent(openPage)
                .setAutoCancel(true)
            else -> {
                val p = (progress ?: 0).coerceIn(0, 100)
                b.setContentTitle(app.getString(R.string.update_dl_title, version))
                    .setContentText(app.getString(R.string.update_downloading, p))
                    .setContentIntent(openPage)
                    .setOngoing(true)
                if (Build.VERSION.SDK_INT >= 36) {
                    b.setStyle(Notification.ProgressStyle().setStyledByProgress(true).setProgress(p))
                        .setShortCriticalText("$p%")
                        .setRequestPromotedOngoing(true)
                } else {
                    b.setProgress(100, p, false)
                }
            }
        }
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(DL_NOTIF_ID, b.build()) }
    }

    private fun updateErrorMessage(app: Context, e: UpdateError): String = when (e) {
        UpdateError.RATE_LIMITED -> app.getString(R.string.update_err_rate_limit)
        UpdateError.HTTP -> app.getString(R.string.update_err_http, _state.value.lastHttpCode)
        UpdateError.PARSE -> app.getString(R.string.update_err_parse)
        UpdateError.CHECKSUM -> app.getString(R.string.update_err_checksum)
        UpdateError.NETWORK -> app.getString(R.string.update_err_network)
    }

    /** Streams [url] into [out]; returns null on success or the reason it failed. */
    private fun download(app: Context, url: String, release: ReleaseInfo, out: File): UpdateError? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ii-tools/${_state.value.currentVersion}")
            .build()
        return try {
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "APK download returned HTTP ${resp.code}")
                    _state.update { it.copy(lastHttpCode = resp.code) }
                    return UpdateError.HTTP
                }
                val total = release.apkSize.takeIf { it > 0L } ?: resp.body.contentLength()
                val sha = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                resp.body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            done += n
                            if (total > 0L) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _state.update { it.copy(downloadProgress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                                    notifyDownload(app, release, progress = pct)
                                }
                            }
                        }
                    }
                }
                if (release.apkSize > 0L && done != release.apkSize) {
                    Log.w(TAG, "APK size mismatch: got $done, expected ${release.apkSize}")
                    return UpdateError.NETWORK
                }
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                if (release.apkSha256 != null && hex != release.apkSha256) {
                    Log.w(TAG, "APK SHA-256 mismatch: got $hex, expected ${release.apkSha256}")
                    return UpdateError.CHECKSUM
                }
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "APK download failed", e)
            UpdateError.NETWORK
        }
    }

    private fun maybeNotify(app: Context, latest: ReleaseInfo?, dismissedTag: String?, notifiedTag: String?) {
        if (latest == null) return
        if (latest.tagName == dismissedTag || latest.tagName == notifiedTag) return
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        ensureChannel(app)
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = app.getString(R.string.update_notif_title, latest.version.toString())
        val text = latest.name.takeIf { it.isNotEmpty() } ?: app.getString(R.string.update_notif_body)
        val notif = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.rounded_system_update)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .build()
        val posted = runCatching {
            app.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
            true
        }.getOrDefault(false)
        if (posted) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NOTIFIED_TAG, latest.tagName)
                .apply()
        }
    }

    private fun readVersionName(ctx: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
        info.versionName.orEmpty()
    }.getOrDefault("")
}
