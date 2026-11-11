package dev.ii.tools.deck

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One key on the deck. [kind] says how a tap reaches the shell:
 * - `action`: a deck catalog id (`g:…`, `c:…`, `discordMute`…), run as `deck`;
 * - `app`: a desktop id, run as `deck` `app:<id>`;
 * - `mode`, `preset`, `workspace`: the remote's own commands.
 * [label] and [icon] are what the key showed when it was added, kept so a key still
 * reads right while the PC is away; the live catalog wins when it has the key.
 */
data class DeckKey(val kind: String, val ref: String, val label: String, val icon: String) {
    val key: String get() = "$kind:$ref"
}

/**
 * The deck's keys, in order, kept on the phone (the catalog comes from the shell, the
 * layout is the user's). Pages are not stored: the list is cut into pages to fit the
 * screen, so the same deck works upright and in dock mode.
 */
object DeckStore {
    private const val PREFS = "deck"
    private const val KEYS = "keys"

    private lateinit var app: Context
    private val _keys = MutableStateFlow<List<DeckKey>>(emptyList())
    val keys: StateFlow<List<DeckKey>> = _keys.asStateFlow()

    /** False until the user (or the first catalog) has put something on the deck. */
    var seeded = false
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEYS, null)
        seeded = raw != null
        _keys.value = raw?.let(::parse).orEmpty()
    }

    fun set(keys: List<DeckKey>) {
        _keys.value = keys
        seeded = true
        val json = JSONArray(keys.map {
            JSONObject().put("kind", it.kind).put("ref", it.ref).put("label", it.label).put("icon", it.icon)
        })
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEYS, json.toString()).apply()
    }

    fun add(key: DeckKey) {
        if (_keys.value.none { it.key == key.key }) set(_keys.value + key)
    }

    fun remove(key: DeckKey) = set(_keys.value.filterNot { it.key == key.key })

    fun move(from: Int, to: Int) {
        val list = _keys.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        set(list)
    }

    /** A first deck from the catalog, so the screen is useful before any editing. */
    fun seed(catalog: Map<String, Pair<String, String>>) {
        if (seeded) return
        val first = listOf(
            "g:mediaPrev", "g:mediaPlayPause", "g:mediaNext", "g:audioMute",
            "g:micMute", "g:regionScreenshot", "g:regionOcr", "g:regionRecord",
            "g:overview", "g:scratchpad", "g:toggleLightDark", "g:lock",
        )
        set(first.mapNotNull { id -> catalog[id]?.let { (name, icon) -> DeckKey("action", id, name, icon) } })
    }

    private fun parse(raw: String): List<DeckKey> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DeckKey(o.optString("kind"), o.optString("ref"), o.optString("label"), o.optString("icon"))
        }
    }.getOrDefault(emptyList())
}
