package dev.charaly.app

import android.content.Context

/**
 * Small, explicit app preferences.
 *
 * Deliberately not a data class in the runtime module: these are device-local
 * flags with no influence on world state. Keeping them here means the story engine
 * cannot accidentally read a UI preference.
 *
 * Defaults are chosen for a first run that feels finished: onboarding is shown
 * once, the app is dark (it is a night-time app), and developer diagnostics are
 * hidden.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** First launch has not been completed. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /**
     * Developer Mode: reveals WorldState JSON, prompt output, timings and the event
     * log. Off unless the user explicitly turns it on.
     */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER, value).apply()

    /** Dark-first theme, independent of the system setting. */
    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK, true)
        set(value) = prefs.edit().putBoolean(KEY_DARK, value).apply()

    /** Honours the system animation scale for Charaly's own transitions. */
    var reduceMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    /** Threads used by the local inference engine. 0 means "let llama.cpp decide". */
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 0)
        set(value) = prefs.edit().putInt(KEY_THREADS, value.coerceIn(0, 16)).apply()

    companion object {
        const val FILE = "charaly_prefs"
        const val KEY_ONBOARDING = "onboarding_complete"
        const val KEY_DEVELOPER = "developer_mode"
        const val KEY_DARK = "dark_theme"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_THREADS = "inference_threads"
    }
}