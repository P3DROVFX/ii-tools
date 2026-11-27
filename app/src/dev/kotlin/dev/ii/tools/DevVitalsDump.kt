package dev.ii.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ii.tools.vitals.Vitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dev builds only: answers `get vitals` as a PC would drive it (since, then each `next` until
 * `more` is false), writing every page to files/vitals-dump/page-NNN.json and the sizes and
 * timings to summary.json. Only the shell (DUMP) can send it:
 * `adb shell am broadcast --user 0 -n dev.ii.tools.dev/dev.ii.tools.DevVitalsDump --es since "" --es stepsFrom auto`
 */
class DevVitalsDump : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val since = intent.getStringExtra("since").orEmpty()
        val stepsFrom = intent.getStringExtra("stepsFrom") ?: "auto"
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(context.filesDir, "vitals-dump").apply { deleteRecursively(); mkdirs() }
            val peer = Vitals.Peer("dev-dump", persist = false)
            val pages = JSONArray()
            val t0 = System.currentTimeMillis()
            try {
                var next = since
                var n = 0
                do {
                    val t = System.currentTimeMillis()
                    val page = Vitals.answer(context, peer, next, stepsFrom) { File(dir, "page-%03d.json".format(n)).writeText(it); true }
                    pages.put(JSONObject().put("page", n).put("days", page.days).put("bytes", page.bytes).put("ms", System.currentTimeMillis() - t))
                    next = page.next
                    n++
                } while (page.more)
                val summary = JSONObject().put("since", since).put("stepsFrom", stepsFrom).put("samsungBuiltIn", BuildConfig.SAMSUNG_HEALTH).put("pages", pages).put("totalMs", System.currentTimeMillis() - t0)
                File(dir, "summary.json").writeText(summary.toString(2))
                Log.i("iiVitals", "dump: $n pages in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                Log.w("iiVitals", "dump failed: $e")
            } finally {
                done.finish()
            }
        }
    }
}
