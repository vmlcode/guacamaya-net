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
import org.sosnet.permissions.PermissionHelper
import org.sosnet.service.BleServiceState

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: MessageDao = SOSNetDatabase.get(app).messageDao()

    val messages: StateFlow<List<MessageEntity>> = dao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    val serviceState: StateFlow<BleServiceState.Snapshot> = BleServiceState.state

    private val _canOperate = MutableStateFlow(false)
    val canOperate: StateFlow<Boolean> = _canOperate.asStateFlow()

    private val _blockingReason = MutableStateFlow<PermissionHelper.BlockingReason?>(null)
    val blockingReason: StateFlow<PermissionHelper.BlockingReason?> = _blockingReason.asStateFlow()

    init {
        viewModelScope.launch {
            _identity.value = Identity.loadOrCreate(app)
        }
        refreshReadiness()
    }

    fun refreshReadiness() {
        val ctx = getApplication<Application>()
        _canOperate.value = PermissionHelper.canOperateBle(ctx)
        _blockingReason.value = PermissionHelper.blockingReason(ctx)
    }

    fun isBluetoothEnabled(): Boolean = PermissionHelper.isBluetoothEnabled(getApplication())
    fun hasAllPermissions(): Boolean = PermissionHelper.hasAllPermissions(getApplication())
    fun isIgnoringBatteryOptimizations(): Boolean =
        PermissionHelper.isIgnoringBatteryOptimizations(getApplication())
}
