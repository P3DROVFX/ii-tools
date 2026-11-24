package dev.ii.tools.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The app was just updated: the APK that carried the update and its "ready to install"
 * notice go, even if the app is not opened afterwards.
 */
class UpdateCleanup : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                AppUpdate.cleanup(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
