package dev.ii.tools.link

import org.json.JSONArray
import org.json.JSONObject

/*
 * The live topics the shell pushes whole on every change (PROTOCOL.md, "Topics"). Each
 * is parsed once on the socket thread into an immutable value, so the UI only compares.
 */

data class Workspace(val id: Int, val windows: Int)
data class Workspaces(val active: Int, val list: List<Workspace>)

data class ShellMode(val id: String, val name: String, val icon: String)
data class Modes(val active: String, val list: List<ShellMode>)

data class Presets(val active: String, val list: List<String>)

/** A device or an app stream: [id] is its PipeWire node id, [vol] 0–1. */
data class AudioNode(val id: Int, val name: String, val vol: Float, val mute: Boolean)
data class EasyEffectsState(val on: Boolean, val preset: String, val presets: List<String>)
data class PcAudio(
    val out: Int,
    val input: Int,
    val outputs: List<AudioNode>,
    val inputs: List<AudioNode>,
    val apps: List<AudioNode>,
    val ee: EasyEffectsState?,
) {
    val output: AudioNode? get() = outputs.firstOrNull { it.id == out }
    val mic: AudioNode? get() = inputs.firstOrNull { it.id == input }
}

internal object Topics {
    fun workspaces(m: JSONObject) = Workspaces(
        active = m.optInt("active"),
        list = m.optJSONArray("list").objects { Workspace(it.optInt("id"), it.optInt("windows")) },
    )

    fun modes(m: JSONObject) = Modes(
        active = m.optString("active"),
        list = m.optJSONArray("list").objects { ShellMode(it.optString("id"), it.optString("name"), it.optString("icon")) },
    )

    fun presets(m: JSONObject) = Presets(
        active = m.optString("active"),
        list = m.optJSONArray("list").strings(),
    )

    fun audio(m: JSONObject) = PcAudio(
        out = m.optInt("out", -1),
        input = m.optInt("in", -1),
        outputs = m.optJSONArray("outputs").objects(::node),
        inputs = m.optJSONArray("inputs").objects(::node),
        apps = m.optJSONArray("apps").objects(::node),
        ee = m.optJSONObject("ee")?.let {
            EasyEffectsState(it.optBoolean("on"), it.optString("preset"), it.optJSONArray("presets").strings())
        },
    )

    private fun node(o: JSONObject) =
        AudioNode(o.optInt("id"), o.optString("name"), o.optDouble("vol", 0.0).toFloat(), o.optBoolean("mute"))

    private inline fun <T> JSONArray?.objects(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) optJSONObject(i)?.let { add(map(it)) } }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it) }
    }
}

/** A key the shell offers for the deck; [toggle] keys light up from `state`. */
data class DeckAction(val id: String, val name: String, val icon: String, val group: String, val toggle: Boolean)

/** An installed app on the PC, listed only while the deck's picker asks. */
data class PcApp(val id: String, val name: String)

internal object DeckTopics {
    fun deck(m: JSONObject): List<DeckAction> {
        val list = m.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                add(DeckAction(a.optString("id"), a.optString("name"), a.optString("icon"), a.optString("group"), a.optBoolean("toggle")))
            }
        }
    }

    fun apps(m: JSONObject): List<PcApp> {
        val list = m.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                add(PcApp(a.optString("id"), a.optString("name")))
            }
        }
    }

    fun paths(m: JSONObject): Map<String, String> {
        val o = m.optJSONObject("paths") ?: return emptyMap()
        return buildMap { o.keys().forEach { put(it, o.optString(it)) } }
    }
}
