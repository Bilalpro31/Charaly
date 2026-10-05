package dev.charaly.app.ui

import android.os.Bundle
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.charaly.app.CharalyApplication
import dev.charaly.app.ui.theme.CharalyTheme
import dev.charaly.app.ui.theme.ProvideMotionPolicy
import dev.charaly.runtime.presentation.MotionPolicy

/**
 * The single activity. Charaly is local-first: it never contacts a network, so
 * there is no login, no sync and no server-backed state to restore.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val application = LocalContext.current.applicationContext as CharalyApplication
            val viewModel: CharalyViewModel = viewModel(
                factory = CharalyViewModel.factory(application),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Dark-first, with the user's preference winning over the system setting.
            CharalyTheme(darkTheme = state.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Resolved here, once, so every animated affordance in the app
                    // consults the same policy. The Settings switch is therefore real:
                    // without this it would save a preference and change nothing.
                    // The platform's own answer to "are animators enabled?", which is exactly
                    // what the system "Remove animations" accessibility setting controls.
                    // It tracks Developer Options' animation scales too, unlike a raw
                    // settings read, and needs no permission.
                    val systemRemovesAnimations = !ValueAnimator.areAnimatorsEnabled()
                    ProvideMotionPolicy(
                        MotionPolicy.resolve(
                            systemRemovesAnimations = systemRemovesAnimations,
                            userPrefersReducedMotion = state.reduceMotion,
                        ),
                    ) {
                        CharalyApp(
                            state = state,
                            viewModel = viewModel,
                            onExit = { finish() },
                        )
                    }
                }
            }
        }
    }
}

/** Kept for previews and for anything that needs the tree without the activity. */
@Composable
fun CharalyAppHost(application: CharalyApplication, onExit: () -> Unit = {}) {
    val viewModel: CharalyViewModel = viewModel(factory = CharalyViewModel.factory(application))
    val state by viewModel.state.collectAsStateWithLifecycle()
    CharalyTheme(darkTheme = state.darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            CharalyApp(state = state, viewModel = viewModel, onExit = onExit)
        }
    }
}