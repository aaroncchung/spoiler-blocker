package io.github.aaroncchung.spoilerblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.aaroncchung.spoilerblocker.ui.blockers.BlockerEditorScreen
import io.github.aaroncchung.spoilerblocker.ui.blockers.BlockerListScreen
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpoilerBlockerTheme {
                // One screen fades into the next, and what is behind them
                // shows through meanwhile. Without this that is the window's
                // own background, which is light even in dark mode.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SpoilerBlockerNavHost()
                }
            }
        }
    }
}

// Each screen has a route: a small serializable object that names the screen
// and carries what it needs to be told.

/** The blocker list, which is the start screen. */
@Serializable
private object BlockerListRoute

/** The editor. [blockerId] is null to create a blocker. */
@Serializable
private data class BlockerEditorRoute(val blockerId: String? = null)

/** Decides which screen is showing, and keeps the stack that Back walks down. */
@Composable
private fun SpoilerBlockerNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = BlockerListRoute) {
        composable<BlockerListRoute> {
            BlockerListScreen(
                onOpenEditor = { blockerId ->
                    navController.navigate(BlockerEditorRoute(blockerId)) {
                        // If a second tap gets in before the editor is drawn,
                        // this stops it from opening a second editor on top.
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<BlockerEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BlockerEditorRoute>()
            BlockerEditorScreen(
                blockerId = route.blockerId,
                // Goes back to the list, not just back one screen. The editor
                // can still be tapped while it fades out, and a second plain
                // "back" would close the list too and leave a blank screen.
                // Going back to the list twice is harmless.
                onClose = { navController.popBackStack<BlockerListRoute>(inclusive = false) },
            )
        }
    }
}
