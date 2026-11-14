package dev.ii.tools.screen

import android.content.Context
import android.view.WindowManager
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * How the PC sends the screen. [res] scales the phone's own pixels for the extended
 * output, [scale] is that output's in Hyprland, [side] where it goes beside the focused
 * screen; [fps] and [bitrate] (Mbps) are the stream's. Mirror uses only the bitrate.
 */
data class ScreenPrefs(
    val res: Float = 1f,
    val fps: Int = 60,
    val scale: Float = 1.5f,
    val side: String = "right",
    val bitrate: Int = 16,
    /** The PC screen to mirror by its output name, "" for whichever has focus. */
    val mirror: String = "",
)

/** One of the PC's screens, for mirror's choice. */
data class PcMonitor(val name: String, val label: String, val width: Int, val height: Int, val focused: Boolean)

/** Where the phone as a monitor is (PROTOCOL.md, "Second screen"). */
sealed interface ScreenState {
    data object Idle : ScreenState
    data class Starting(val mode: String) : ScreenState
    /** The PC is serving the output: connect to 127.0.0.1:[port] (adb reverse) with [token]. */
    data class Ready(
        val mode: String,
        val port: Int,
        val token: String,
        val width: Int,
        val height: Int,
        /** The PC screen shown (its output name), and how many mirror could switch between. */
        val output: String = "",
        val screens: Int = 1,
    ) : ScreenState
    /** [code]: disabled, adb, usb, unauthorized, busy, encoder, timeout, output, bind, encoder_exit, stream. */
    data class Failed(val code: String) : ScreenState
}

/**
 * The phone as a monitor for the PC, over USB: extend (a new output the phone's size)
 * or mirror (the focused screen). The phone asks over the link; the PC makes the
 * output and serves it on a port `adb reverse` brings here, and [ScreenStream] shows it.
 */
object SecondScreen {
    private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    /** Set by the home card's buttons, so the screen starts in that mode as it opens. */
    @Volatile var pendingMode: String? = null

    private val _monitors = MutableStateFlow<List<PcMonitor>>(emptyList())
    val monitors: StateFlow<List<PcMonitor>> = _monitors.asStateFlow()

    /** Asks the PC for its screens (answered with `monitors`). */
    fun requestMonitors() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "monitors"))
    }

    /** Mirror the PC's next screen, without stopping. */
    fun switchNext() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "switch").put("output", "next"))
    }

    private const val PREFS = "second_screen"
    private val _prefs = MutableStateFlow<ScreenPrefs?>(null)

    fun prefs(ctx: Context): StateFlow<ScreenPrefs?> {
        if (_prefs.value == null) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _prefs.value = ScreenPrefs(
                res = p.getFloat("res", 1f),
                fps = p.getInt("fps", 60),
                scale = p.getFloat("scale", 1.5f),
                side = p.getString("side", "right") ?: "right",
                bitrate = p.getInt("bitrate", 16),
                mirror = p.getString("mirror", "") ?: "",
            )
        }
        return _prefs.asStateFlow()
    }

    fun setPrefs(ctx: Context, v: ScreenPrefs) {
        _prefs.value = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("res", v.res).putInt("fps", v.fps).putFloat("scale", v.scale)
            .putString("side", v.side).putInt("bitrate", v.bitrate).putString("mirror", v.mirror).apply()
    }

    /** Live draw on the phone's screen, toggled by the shell. */
    fun draw() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "draw"))
    }

    fun start(ctx: Context, mode: String) {
        // The whole display, cutout included, held sideways: the extended output's size.
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val p = prefs(ctx).value ?: ScreenPrefs()
        _state.value = ScreenState.Starting(mode)
        LinkHub.sendJson(
            JSONObject().put("t", "screen").put("a", "start").put("mode", mode)
                .put("w", maxOf(b.width(), b.height())).put("h", minOf(b.width(), b.height()))
                .put("res", p.res.toDouble()).put("fps", p.fps).put("scale", p.scale.toDouble())
                .put("side", p.side).put("bitrate", p.bitrate).put("output", p.mirror),
        )
    }

    fun stop() {
        if (_state.value is ScreenState.Idle) return
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "stop"))
        _state.value = ScreenState.Idle
    }

    /** The stream broke on this side (the socket, the decoder). */
    fun failed(code: String) {
        stop()
        _state.value = ScreenState.Failed(code)
    }

    fun onMessage(m: JSONObject) {
        m.optString("err").ifEmpty { null }?.let {
            _state.value = ScreenState.Failed(it)
            return
        }
        m.optJSONArray("monitors")?.let { a ->
            _monitors.value = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PcMonitor(o.optString("name"), o.optString("label"), o.optInt("w"), o.optInt("h"), o.optBoolean("focused"))
            }
            return
        }
        val cur = _state.value
        when (m.optString("state")) {
            "starting" -> if (cur !is ScreenState.Ready) _state.value = ScreenState.Starting(m.optString("mode", "extend"))
            "ready" -> _state.value = ScreenState.Ready(
                mode = m.optString("mode", "extend"),
                port = m.optInt("port"),
                token = m.optString("token"),
                width = m.optInt("w", 1920),
                height = m.optInt("h", 1080),
                output = m.optString("output"),
                screens = m.optInt("screens", 1),
            )
            // Another mode from the Displays page, or another screen mirrored: the picture's shape follows.
            "size" -> if (cur is ScreenState.Ready) _state.value = cur.copy(
                width = m.optInt("w", cur.width),
                height = m.optInt("h", cur.height),
                output = m.optString("output", cur.output),
            )
            "stopped" -> if (cur !is ScreenState.Failed) _state.value = ScreenState.Idle
        }
    }
}
