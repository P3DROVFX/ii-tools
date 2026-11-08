package dev.ii.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ii.tools.live.LiveListener
import dev.ii.tools.ui.HomeScreen
import dev.ii.tools.ui.LiveScreen
import dev.ii.tools.ui.theme.IiToolsTheme

/** Every shell integration the app offers; the home screen lists them. */
enum class Destination { HOME, LIVE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LiveListener.ensureChannel(this)
        setContent {
            IiToolsTheme { App() }
        }
    }
}

@Composable
private fun App() {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    BackHandler(enabled = destination != Destination.HOME) { destination = Destination.HOME }
    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            val forward = targetState != Destination.HOME
            slideInHorizontally { w -> if (forward) w else -w / 3 } togetherWith
                slideOutHorizontally { w -> if (forward) -w / 3 else w }
        },
        label = "destination",
    ) { current ->
        when (current) {
            Destination.HOME -> HomeScreen(onOpen = { destination = it })
            Destination.LIVE -> LiveScreen(onBack = { destination = Destination.HOME })
        }
    }
}
