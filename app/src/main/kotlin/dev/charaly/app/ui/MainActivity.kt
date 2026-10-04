package dev.charaly.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.charaly.app.CharalyApplication
import dev.charaly.app.ui.theme.CharalyTheme

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
            CharalyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: CharalyViewModel = viewModel(
                        factory = CharalyViewModel.factory(application),
                    )
                    CharalyApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun CharalyApp(viewModel: CharalyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CharalyRoot(state = state, viewModel = viewModel)
}
