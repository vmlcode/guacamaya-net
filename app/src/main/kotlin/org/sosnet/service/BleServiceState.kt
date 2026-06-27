package org.sosnet.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared runtime state between [SosForegroundService] and the UI.
 */
object BleServiceState {

    data class Snapshot(
        val broadcasting: Boolean = false,
        val observing: Boolean = false,
        val lastRssi: Int? = null,
        val serviceRunning: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    internal fun update(
        broadcasting: Boolean? = null,
        observing: Boolean? = null,
        lastRssi: Int? = null,
        serviceRunning: Boolean? = null,
    ) {
        val cur = _state.value
        _state.value = cur.copy(
            broadcasting = broadcasting ?: cur.broadcasting,
            observing = observing ?: cur.observing,
            lastRssi = lastRssi ?: cur.lastRssi,
            serviceRunning = serviceRunning ?: cur.serviceRunning,
        )
    }

    internal fun recordRssi(rssi: Int) {
        _state.value = _state.value.copy(lastRssi = rssi)
    }

    internal fun reset() {
        _state.value = Snapshot()
    }
}
