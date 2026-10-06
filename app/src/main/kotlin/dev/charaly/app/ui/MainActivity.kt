package dev.charaly.app.ui

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Context
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import dev.charaly.app.AppPreferences
import dev.charaly.app.CharalyApplication
import dev.charaly.app.ui.theme.CharalyTheme
import dev.charaly.app.ui.theme.ProvideMotionPolicy
import dev.charaly.runtime.presentation.MotionPolicy

/**
 * The single activity.
 *
 * ## Edge to edge, deliberately
 *
 * `enableEdgeToEdge()` because a story app is read with a thumb at the bottom of the
 * screen, and a navigation bar drawn by the system between the content and that thumb is a
 * bar the user cannot get out of the way. The insets are consumed inside the shell, once,
 * rather than by every screen guessing at them.
 *
 * ## The theme is resolved here, once
 *
 * Dark first, with the user's preference winning over the system setting - and the motion
 * policy resolved from *both* sources, so the Settings switch is real rather than a
 * preference that saves and changes nothing.
 *
 * `ValueAnimator.areAnimatorsEnabled()` is the platform's own answer to "are animators
 * enabled?", which is exactly what the system "Remove animations" accessibility setting
 * controls. It tracks Developer Options' animation scales too, unlike a raw settings read,
 * and needs no permission.
 */
class MainActivity : ComponentActivity() {

    /**
     * Applies the language the user chose, before any resource is resolved.
     *
     * ## Why this lives here and not in a composable
     *
     * This app has two localisation systems on purpose:
     *
     *  * `dev.charaly.runtime.presentation.Loc` for the presenters, which live in a plain
     *    Kotlin/JVM module and cannot call `Context.getString`. They own most of the copy.
     *  * `res/values-tr/strings.xml` for the app layer, resolved through the activity's
     *    configuration like any other Android resource.
     *
     * `attachBaseContext` is the one seam where the second system can be pointed at the
     * user's choice, and it runs before `onCreate`, so no resource is ever resolved against
     * the wrong locale first and patched afterwards.
     *
     * The stored preference wins over the device language, which is what lets somebody keep
     * a Turkish app on an English phone - and it is stored, so it survives a reboot without
     * the system setting having to change.
     */
    override fun attachBaseContext(newBase: Context) {
        val stored = runCatching {
            newBase.applicationContext
                .getSharedPreferences(AppPreferences.FILE, Context.MODE_PRIVATE)
                .getString(AppPreferences.KEY_LANGUAGE, "")
        }.getOrNull().orEmpty()
        super.attachBaseContext(if (stored.isNotBlank()) newBase.localised(stored) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val application = LocalContext.current.applicationContext as CharalyApplication
            val viewModel: CharalyViewModel = viewModel(
                factory = CharalyViewModel.factory(application),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            CharalyTheme(darkTheme = state.darkTheme) {
                ProvideMotionPolicy(
                    MotionPolicy.resolve(
                        systemRemovesAnimations = !ValueAnimator.areAnimatorsEnabled(),
                        userPrefersReducedMotion = state.reduceMotion,
                    ),
                ) {
                    CharalyApp(
                        state = state,
                        viewModel = viewModel,
                        onExit = { finish() },
                        // Changing the language recreates the activity rather than restarting
                        // the process: the standard mechanism, a few milliseconds of work,
                        // and the ViewModel survives it, so the user does not lose the open
                        // story, the imported model, or the draft they were filling in.
                        onLanguageChanged = { recreate() },
                        // The artwork cache, owned by the process so it outlives every
                        // screen. Without it the stage would re-decode a background on
                        // every navigation, which is the cost this cache exists to avoid.
                        storyAssets = application.storyAssets,
                    )
                }
            }
        }
    }
}

/**
 * A context whose resources resolve in [tag].
 *
 * `createConfigurationContext` rather than `updateConfiguration`: the latter mutates the
 * shared base configuration, which leaks the chosen language into every other component in
 * the process - including the ones holding their own configuration.
 */
private fun Context.localised(tag: String): Context {
    val locale = java.util.Locale.forLanguageTag(tag)
    val configuration = resources.configuration
    if (configuration.locales[0] == locale) return this
    val updated = android.content.res.Configuration(configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(updated)
}