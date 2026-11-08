package dev.ii.tools.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.live.LiveListener

const val KDE_CONNECT_PACKAGE = "org.kde.kdeconnect_tp"

/** What the device grants and has installed; re-read whenever the app comes back. */
data class DeviceStatus(
    val kdeConnectInstalled: Boolean,
    val listenerGranted: Boolean,
    val postGranted: Boolean,
) {
    val liveReady: Boolean get() = listenerGranted && postGranted
}

fun readDeviceStatus(ctx: Context): DeviceStatus {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    return DeviceStatus(
        kdeConnectInstalled = runCatching { ctx.packageManager.getPackageInfo(KDE_CONNECT_PACKAGE, 0) }.isSuccess,
        listenerGranted = nm.isNotificationListenerAccessGranted(ComponentName(ctx, LiveListener::class.java)),
        postGranted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    )
}

@Composable
fun rememberDeviceStatus(): DeviceStatus {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(readDeviceStatus(ctx)) }
    LifecycleResumeEffect(Unit) {
        status = readDeviceStatus(ctx)
        onPauseOrDispose { }
    }
    return status
}
