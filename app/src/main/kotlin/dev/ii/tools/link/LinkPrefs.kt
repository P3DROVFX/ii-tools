package dev.ii.tools.link

import android.content.Context
import android.net.Uri

/** What a pairing QR code (or `iilink://pair` link) carries. */
data class PairingUri(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val secret: String,
    val pcName: String,
) {
    companion object {
        fun parse(uri: Uri?): PairingUri? {
            if (uri == null || uri.scheme != "iilink" || uri.host != "pair") return null
            if (uri.getQueryParameter("v") != "1") return null
            val hosts = uri.getQueryParameter("h").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val fp = uri.getQueryParameter("fp")?.lowercase() ?: return null
            val secret = uri.getQueryParameter("s") ?: return null
            if (hosts.isEmpty() || fp.length != 64 || port !in 1..65535) return null
            return PairingUri(hosts, port, fp, secret, uri.getQueryParameter("n").orEmpty())
        }

        fun parse(text: String?): PairingUri? = text?.let { parse(Uri.parse(it.trim())) }
    }
}

/** A PC this phone is paired with. */
data class PairedPc(
    val id: String,
    val token: String,
    val fingerprint: String,
    /** Last address that answered first, then the ones from the QR code. */
    val hosts: List<String>,
    val port: Int,
    val name: String,
)

/** The one paired PC, in app-private storage (backups are off for the whole app). */
object LinkPrefs {
    private const val FILE = "link"

    fun load(ctx: Context): PairedPc? {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = sp.getString("id", null) ?: return null
        val token = sp.getString("token", null) ?: return null
        val fp = sp.getString("fp", null) ?: return null
        return PairedPc(
            id = id,
            token = token,
            fingerprint = fp,
            hosts = sp.getString("hosts", "").orEmpty().split(',').filter { it.isNotEmpty() },
            port = sp.getInt("port", 47210),
            name = sp.getString("name", "").orEmpty(),
        )
    }

    fun save(ctx: Context, pc: PairedPc) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("id", pc.id)
            .putString("token", pc.token)
            .putString("fp", pc.fingerprint)
            .putString("hosts", pc.hosts.joinToString(","))
            .putInt("port", pc.port)
            .putString("name", pc.name)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
