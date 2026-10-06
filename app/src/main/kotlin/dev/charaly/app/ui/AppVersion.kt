package dev.charaly.app.ui

import android.content.Context

/**
 * The app's version, for the one place it is shown.
 *
 * ## Why it is read rather than hard-coded
 *
 * The Settings screen prints "Charaly 0.6.0". If that string were written by hand it would
 * be wrong the day the version changed, and it is exactly the kind of small lie that makes
 * a bug report unreproducible. So it is read from the package manager, and a failure to
 * read it yields an empty string rather than a guess.
 */
fun appVersionName(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName.orEmpty()
}.getOrDefault("")

/** The version and its build number, for a developer-facing string. */
fun appVersionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    "${info.versionName.orEmpty()} ($code)"
}.getOrDefault("")
