package com.sikamikaniko.sonora.ui

import androidx.navigation.NavController

/**
 * Navigate, but no-op when the target route is already the current destination —
 * kills duplicate back-stack entries from "Go to album" on that same album,
 * re-taps, and fast double-taps (each of which used to cost an extra back press).
 */
fun NavController.navigateDistinct(route: String) {
    val entry = currentBackStackEntry
    val pattern = entry?.destination?.route
    val concrete = pattern?.let { p ->
        Regex("\\{(\\w+)\\}").replace(p) { m ->
            entry.arguments?.getString(m.groupValues[1]) ?: m.value
        }
    }
    // Nav stores args DECODED while callers pass encoded routes (genre/Hip%20Hop%2FRap),
    // so compare the decoded form too or encoded routes always look "different".
    if (concrete == route || concrete == android.net.Uri.decode(route)) return
    navigate(route)
}
