package dev.ii.tools.link

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN for the paired PC. The shell lists its adapters' MACs and subnet
 * broadcasts in every `hello`; they are kept with the pairing, since they are needed
 * exactly when the PC is off and cannot say them.
 */
object Wake {
    private const val PREFS = "link"
    private const val KEY = "wake"

    fun remember(ctx: Context, targets: JSONArray?) {
        if (targets == null) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, targets.toString()).apply()
    }

    fun available(ctx: Context): Boolean = targets(ctx).length() > 0

    private fun targets(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())

    /** The magic packet (6 × FF, then the MAC 16 times) to each adapter, on ports 9 and 7. */
    suspend fun send(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val targets = targets(ctx)
        var sent = false
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                for (i in 0 until targets.length()) {
                    val t = targets.optJSONObject(i) ?: continue
                    val mac = t.optString("mac").split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
                    if (mac.size != 6) continue
                    val packet = ByteArray(6) { 0xFF.toByte() } + List(16) { mac }.flatten()
                    val addresses = listOfNotNull(t.optString("broadcast").ifEmpty { null }, "255.255.255.255").distinct()
                    for (addr in addresses) for (port in intArrayOf(9, 7)) {
                        runCatching {
                            sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(addr), port))
                            sent = true
                        }
                    }
                }
            }
        }
        sent
    }
}
