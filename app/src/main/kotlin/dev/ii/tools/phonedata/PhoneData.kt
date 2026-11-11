package dev.ii.tools.phonedata

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * What the shell used to read over ADB or KDE Connect (PROTOCOL.md, "Phone data"):
 * the next alarm clock, battery and network (`status`), and the contacts with photos.
 *
 * Lives in the notification listener's process. Status goes on every connect and on
 * change while the link is up; a changed alarm brings the link up for itself, since the
 * PC's clock app should not show a stale one. Contacts go only when the PC asks with a
 * version that differs, photos only those it lacks.
 */
object PhoneData {
    private const val PREFS = "phoneData"
    private const val ENABLED = "enabled"
    private const val BATTERY_EVERY_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val CONTACTS = Manifest.permission.READ_CONTACTS

    private var ctx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var lastBatterySent = 0L
    private var lastBatteryKey = ""

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
    fun canReadContacts(c: Context) = c.checkSelfPermission(CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        ctx?.let { if (on) start(it) else stop() }
        LinkHub.announceCaps()
    }

    /** What this phone offers, for `caps`. */
    fun caps(c: Context): List<String> {
        if (!isEnabled(c) || ctx == null) return emptyList()
        return if (canReadContacts(c)) listOf("status", "contacts") else listOf("status")
    }

    fun start(c: Context) {
        ctx = c
        if (receiver != null || !isEnabled(c)) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // The PC's clock should never show an alarm the phone no longer has.
                    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                        if (LinkHub.isConnected()) sendStatus() else LinkHub.notifBurst()
                    }
                    Intent.ACTION_BATTERY_CHANGED -> onBattery()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        c.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    fun stop() {
        receiver?.let { r -> ctx?.let { runCatching { it.unregisterReceiver(r) } } }
        receiver = null
    }

    /** Battery news only while the link is up, and not more than every 30 s unless plugging changed. */
    private fun onBattery() {
        if (!LinkHub.isConnected()) return
        val b = battery() ?: return
        val key = "${b.optInt("level")}/${b.optBoolean("charging")}"
        val now = System.currentTimeMillis()
        val pluggedChanged = key.substringAfter('/') != lastBatteryKey.substringAfter('/')
        if (key == lastBatteryKey || (!pluggedChanged && now - lastBatterySent < BATTERY_EVERY_MS)) return
        sendStatus()
    }

    /** On connect (LinkHub), on a changed alarm, on battery news. */
    fun sendStatus() {
        val c = ctx ?: return
        if (!isEnabled(c)) return
        scope.launch {
            val m = JSONObject().put("t", "status")
            val b = battery()
            m.put("battery", b ?: JSONObject.NULL)
            m.put("net", network(c))
            m.put("alarm", alarm(c) ?: JSONObject.NULL)
            if (b != null) {
                lastBatteryKey = "${b.optInt("level")}/${b.optBoolean("charging")}"
                lastBatterySent = System.currentTimeMillis()
            }
            LinkHub.sendJson(m)
        }
    }

    private fun alarm(c: Context): JSONObject? {
        val next = c.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        return JSONObject().put("at", next.triggerTime).put("pkg", next.showIntent?.creatorPackage.orEmpty())
    }

    private fun battery(): JSONObject? {
        val c = ctx ?: return null
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overVoltage"
            else -> "unknown"
        }
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> ""
        }
        return JSONObject()
            .put("level", level * 100 / scale)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plug", plug)
            .put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("health", health)
    }

    private fun network(c: Context): JSONObject {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        // 0–4 bars, from the mobile radio (no permission needed for the level).
        val signal = runCatching { c.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull() ?: -1
        return JSONObject().put("type", type).put("signal", signal)
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    private data class Contact(val json: JSONObject, val photoKey: String, val contactId: Long)

    /** The PC asked, with the version it has: the list only when it changed. */
    fun onContactsAsked(have: String) {
        val c = ctx ?: return
        if (!isEnabled(c) || !canReadContacts(c)) return
        scope.launch {
            val contacts = runCatching { readContacts(c) }.getOrNull() ?: return@launch
            val list = JSONArray().apply { contacts.forEach { put(it.json) } }
            val hash = sha(list.toString())
            if (hash == have) return@launch
            LinkHub.sendJson(JSONObject().put("t", "contacts").put("hash", hash).put("list", list))
        }
    }

    /** Photos the PC lacks, one file each, in turn. */
    fun onPhotosAsked(keys: List<String>) {
        val c = ctx ?: return
        if (!isEnabled(c) || !canReadContacts(c) || keys.isEmpty()) return
        scope.launch {
            val byKey = runCatching { readContacts(c) }.getOrNull()?.associateBy { it.photoKey } ?: return@launch
            for (key in keys.take(500)) {
                val contact = byKey[key] ?: continue
                val bytes = photo(c, contact.contactId) ?: continue
                LinkHub.upload("$key.jpg", bytes.size.toLong(), { bytes.inputStream() }, "contactPhoto", "", waitForLink = false) { }
            }
        }
    }

    private fun readContacts(c: Context): List<Contact> {
        val byId = LinkedHashMap<Long, JSONObject>()
        val phones = HashMap<Long, JSONArray>()
        val emails = HashMap<Long, JSONArray>()
        val birthdays = HashMap<Long, JSONObject>()
        val orgs = HashMap<Long, String>()
        val names = HashMap<Long, Pair<String, String>>()
        val cr = c.contentResolver
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE",
        )?.use { cur ->
            while (cur.moveToNext()) {
                byId[cur.getLong(0)] = JSONObject().put("id", sha(cur.getString(1).orEmpty()).take(16)).put("displayName", cur.getString(2).orEmpty())
            }
        }
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                if (id !in byId) continue
                val d1 = cur.getString(2).orEmpty()
                when (cur.getString(1)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.getOrPut(id) { JSONArray() }.put(
                        JSONObject().put("value", d1)
                            .put("normalized", cur.getString(5) ?: d1.filter { it.isDigit() || it == '+' })
                            .put("type", phoneType(cur.getInt(3))),
                    )
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.getOrPut(id) { JSONArray() }.put(
                        JSONObject().put("value", d1).put("type", if (cur.getInt(3) == ContactsContract.CommonDataKinds.Email.TYPE_WORK) "work" else "home"),
                    )
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (d1.isNotEmpty()) orgs[id] = d1
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                        names[id] = cur.getString(3).orEmpty() to cur.getString(4).orEmpty()
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                        if (cur.getInt(3) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday(d1)?.let { birthdays[id] = it }
                }
            }
        }
        return byId.map { (id, o) ->
            names[id]?.let { (given, family) -> o.put("givenName", given).put("familyName", family) }
            o.put("organization", orgs[id].orEmpty())
            o.put("phones", phones[id] ?: JSONArray())
            o.put("emails", emails[id] ?: JSONArray())
            o.put("birthday", birthdays[id] ?: JSONObject.NULL)
            // The photo's own hash: a changed photo is a new file on the PC.
            val key = photo(c, id)?.let { sha(it).take(16) }.orEmpty()
            o.put("photo", key)
            Contact(o, key, id)
        }
    }

    private fun photo(c: Context, contactId: Long): ByteArray? = runCatching {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        ContactsContract.Contacts.openContactPhotoInputStream(c.contentResolver, uri, false)?.use { it.readBytes() }
    }.getOrNull()

    private fun phoneType(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        else -> "other"
    }

    /** "1990-05-17" or "--05-17" → {year, month, day}, as the shell's vCard reader gives. */
    private fun birthday(raw: String): JSONObject? {
        val m = Regex("""(?:(\d{4})-)?-?(\d{2})-(\d{2})""").find(raw) ?: return null
        val (year, month, day) = m.destructured
        return JSONObject().put("year", year.toIntOrNull() ?: JSONObject.NULL).put("month", month.toInt()).put("day", day.toInt())
    }

    private fun sha(s: String): String = sha(s.toByteArray())
    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
