package com.sikamikaniko.sonora.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the stream is doing, published from [StreamGuard] inside the playback service and
 * read by whatever UI happens to be alive.
 *
 * A process-wide object rather than something on the ViewModel because playback routinely
 * outlives the UI — Bluetooth autoplay in the car starts the service with no Activity at
 * all, and that is precisely the situation the guard exists for.
 */
object PlaybackHealth {

    private val _reconnecting = MutableStateFlow(false)
    /** True while a dropped stream is being transparently re-established. */
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Set when playback gave up in a way worth telling the user about. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    internal fun setReconnecting(value: Boolean) { _reconnecting.value = value }
    internal fun report(message: String?) { _problem.value = message }

    fun consumeProblem() { _problem.value = null }
}
