package dev.ii.tools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PairingUri
import dev.ii.tools.live.LiveListener
import dev.ii.tools.ui.HomeScreen
import dev.ii.tools.ui.LinkScreen
import dev.ii.tools.ui.LiveScreen
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
        onOpenLink = { startActivity(Intent(this, LinkActivity::class.java)) },
        onOpenLive = { startActivity(Intent(this, LiveActivity::class.java)) },
    )
}

/** Connect to PC: pairing, the link, the remote. Also where `iilink://pair` links land. */
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
