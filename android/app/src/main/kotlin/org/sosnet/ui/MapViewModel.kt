package org.sosnet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sosnet.crypto.Identity
import org.sosnet.mesh.MessageDao
import org.sosnet.mesh.MessageEntity
import org.sosnet.mesh.SOSNetDatabase

/**
 * Top-level UI state. Owns:
 *  - the local [Identity] (for the node-id card)
 *  - the message store flow (recent verified SOS)
 *  - broadcast / observe toggles (UI-driven; the actual radio lifecycle is
 *    owned by [SosForegroundService] once the user starts it)
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: MessageDao = SOSNetDatabase.get(app).messageDao()

    val messages: StateFlow<List<MessageEntity>> = dao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _broadcasting = MutableStateFlow(false)
    val broadcasting: StateFlow<Boolean> = _broadcasting.asStateFlow()

    private val _observing = MutableStateFlow(false)
    val observing: StateFlow<Boolean> = _observing.asStateFlow()

    init {
        viewModelScope.launch {
            _identity.value = Identity.loadOrCreate(app)
        }
    }

    fun setBroadcasting(on: Boolean) { _broadcasting.value = on }
    fun setObserving(on: Boolean) { _observing.value = on }
}
