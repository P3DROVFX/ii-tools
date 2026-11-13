package dev.ii.tools

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ii.tools.deck.DeckStore
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PairingUri
import dev.ii.tools.live.LiveListener
import dev.ii.tools.ui.AudioScreen
import dev.ii.tools.ui.CallsScreen
import dev.ii.tools.ui.ClipboardScreen
import dev.ii.tools.ui.DeckScreen
import dev.ii.tools.ui.HomeScreen
import dev.ii.tools.ui.LinkScreen
import dev.ii.tools.ui.LiveScreen
import dev.ii.tools.ui.NotifsScreen
import dev.ii.tools.ui.PcLinkScreen
import dev.ii.tools.ui.PerfScreen
import dev.ii.tools.ui.PhoneDataScreen
import dev.ii.tools.ui.RemoteScreen
import dev.ii.tools.ui.ShareScreen
import dev.ii.tools.ui.ShotsScreen
import dev.ii.tools.ui.Shared
import dev.ii.tools.ui.WallpaperScreen
import dev.ii.tools.ui.theme.IiToolsTheme

/** Every screen is its own activity, as on the rest of Android. */
abstract class ScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IiToolsTheme { Screen() } }
    }

    @Composable
    abstract fun Screen()
}

/** The hub: the PC, then each integration. */
class MainActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LiveListener.ensureChannel(this)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Screen() = HomeScreen(
        onOpenLink = { open(LinkActivity::class.java) },
        onOpenRemote = { open(RemoteActivity::class.java) },
        onOpenAudio = { open(AudioActivity::class.java) },
        onOpenDeck = { open(DeckActivity::class.java) },
        onOpenWallpaper = { open(WallpaperActivity::class.java) },
        onOpenCalls = { open(CallsActivity::class.java) },
        onOpenNotifs = { open(NotifsActivity::class.java) },
        onOpenClipboard = { open(ClipboardActivity::class.java) },
        onOpenShots = { open(ShotsActivity::class.java) },
        onOpenPhoneData = { open(PhoneDataActivity::class.java) },
        onOpenPcLink = { open(PcLinkActivity::class.java) },
        onOpenPerf = { open(PerfActivity::class.java) },
        onOpenTouchpad = { open(TouchpadActivity::class.java) },
        onOpenDraw = { open(DrawActivity::class.java) },
        onOpenPresenter = { open(PresenterActivity::class.java) },
        onOpenLive = { open(LiveActivity::class.java) },
    )

    private fun open(screen: Class<out ScreenActivity>) = startActivity(Intent(this, screen))
}

/** Connect to PC: pairing and the link. Also where `iilink://pair` links land. */
class LinkActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePairingLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    private fun handlePairingLink(intent: Intent?) {
        PairingUri.parse(intent?.data)?.let(LinkHub::pair)
    }

    @Composable
    override fun Screen() = LinkScreen(onBack = ::finish)
}

class LiveActivity : ScreenActivity() {
    @Composable
    override fun Screen() = LiveScreen(onBack = ::finish)
}

class RemoteActivity : ScreenActivity() {
    @Composable
    override fun Screen() = RemoteScreen(onBack = ::finish)
}

class AudioActivity : ScreenActivity() {
    @Composable
    override fun Screen() = AudioScreen(onBack = ::finish)
}

/**
 * The deck. Dock mode is a window state, so it lives here: sideways, screen kept on and
 * dimmed, system bars hidden. Nothing runs in the background for it: the deck is only
 * alive while it is on screen.
 */
class DeckActivity : ScreenActivity() {
    private var dock by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Loaded here, not at app start: only the deck needs its layout.
        DeckStore.init(this)
        super.onCreate(savedInstanceState)
        dock = savedInstanceState?.getBoolean(DOCK) ?: false
        applyDock()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DOCK, dock)
    }

    @Composable
    override fun Screen() = DeckScreen(onBack = ::finish, dock = dock, onDock = {
        dock = it
        applyDock()
    })

    private fun applyDock() {
        requestedOrientation = if (dock) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (dock) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = if (dock) DOCK_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (dock) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val DOCK = "dock"
        /** Low enough to sit on a desk all day, still readable at a glance. */
        const val DOCK_BRIGHTNESS = 0.08f
    }
}

/** Wallpaper: from the home, or from the share sheet as "Set on PC". */
class WallpaperActivity : ScreenActivity() {
    @Composable
    override fun Screen() = WallpaperScreen(
        shared = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java),
        onBack = ::finish,
        onDone = ::finish,
    )
}

class CallsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = CallsScreen(onBack = ::finish)
}

class NotifsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = NotifsScreen(onBack = ::finish)
}

/** "Send to PC" in the share sheet. */
class ShareActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ShareScreen(shared = Shared.from(intent), onBack = ::finish, onDone = ::finish)
}

class ClipboardActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ClipboardScreen(onBack = ::finish)
}

class ShotsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ShotsScreen(onBack = ::finish)
}

class PhoneDataActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PhoneDataScreen(onBack = ::finish)
}

class PcLinkActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PcLinkScreen(onBack = ::finish)
}

/** The drawing pad: held sideways, like the screen it stands for. */
class DrawActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.DrawScreen(onBack = ::finish)
}

/** The slide clicker, held sideways: the volume keys change slides while it is open and the PC listens. */
class PresenterActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.PresenterScreen(onBack = ::finish)

    private fun slideKey(keyCode: Int): String? {
        val key = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "Right"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "Left"
            else -> return null
        }
        val live = dev.ii.tools.link.LinkHub.status.value is dev.ii.tools.link.LinkStatus.Online &&
            dev.ii.tools.link.LinkHub.shell.value?.caps?.contains("present") == true &&
            dev.ii.tools.present.Presenter.problem.value == null
        return if (live) key else null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val key = slideKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) dev.ii.tools.present.Presenter.key(key)
        return true
    }

    // Swallowed on the way up too, or the system shows its volume panel.
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        slideKey(keyCode) != null || super.onKeyUp(keyCode, event)
}

/** The touchpad; turning sideways resizes the PC's touchpad instead of rebuilding. */
class TouchpadActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.TouchpadScreen(onBack = ::finish)
}

/** The performance panel; turning sideways keeps it, instead of rebuilding it. */
class PerfActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PerfScreen(onBack = ::finish)
}
