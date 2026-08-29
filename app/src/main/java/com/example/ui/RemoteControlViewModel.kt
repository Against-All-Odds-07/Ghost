package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.device.DeviceInfo
import com.example.device.DeviceInfoManager
import com.example.network.TailscaleManager
import com.example.remote.RemoteControlService
import com.example.remote.SettingsManager
import com.example.security.PairingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteControlViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceInfoManager = DeviceInfoManager(application)
    private val pairingManager = PairingManager.getInstance(application)
    private val settingsManager = SettingsManager(application)

    val serverState: StateFlow<ServerState> = RemoteControlService.serverState

    private val _tailscaleIp = MutableStateFlow<String?>("Detecting...")
    val tailscaleIp: StateFlow<String?> = _tailscaleIp.asStateFlow()

    private val _localIp = MutableStateFlow<String?>("Detecting...")
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _pairingCode = MutableStateFlow<String>(pairingManager.currentPairingCode)
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _deviceInfo = MutableStateFlow(deviceInfoManager.getDeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    private val _pairedClients = MutableStateFlow<Set<String>>(pairingManager.getAuthenticatedClients())
    val pairedClients: StateFlow<Set<String>> = _pairedClients.asStateFlow()

    private val _bootStartEnabled = MutableStateFlow(settingsManager.isBootStartEnabled)
    val bootStartEnabled: StateFlow<Boolean> = _bootStartEnabled.asStateFlow()

    private val _isBatteryOptimizationIgnored = MutableStateFlow(settingsManager.isBatteryOptimizationIgnored())
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    init {
        detectIps()
        startDevicePolling()
        refreshSettingsState()
    }

    private fun startDevicePolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _deviceInfo.value = deviceInfoManager.getDeviceInfo()
                _pairedClients.value = pairingManager.getAuthenticatedClients()
                detectIpsInternal()
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    fun detectIps() {
        viewModelScope.launch(Dispatchers.IO) {
            detectIpsInternal()
        }
    }

    private fun detectIpsInternal() {
        val tsIp = TailscaleManager.getTailscaleIp()
        _tailscaleIp.value = tsIp
        val locIp = TailscaleManager.getLocalIp()
        _localIp.value = locIp
    }

    fun startServer() {
        RemoteControlService.startService(getApplication())
    }

    fun stopServer() {
        RemoteControlService.stopService(getApplication())
    }

    fun refreshPairingCode() {
        val newCode = pairingManager.generatePairingCode()
        _pairingCode.value = newCode
    }

    fun revokeClient(clientId: String) {
        pairingManager.revokeClient(clientId)
        _pairedClients.value = pairingManager.getAuthenticatedClients()
    }

    fun setBootStartEnabled(enabled: Boolean) {
        settingsManager.isBootStartEnabled = enabled
        _bootStartEnabled.value = enabled
    }

    fun refreshSettingsState() {
        _bootStartEnabled.value = settingsManager.isBootStartEnabled
        _isBatteryOptimizationIgnored.value = settingsManager.isBatteryOptimizationIgnored()
    }

    fun getBatteryOptimizationIntent() = settingsManager.getBatteryOptimizationIntent()
}

sealed class ServerState {
    object Stopped : ServerState()
    object Starting : ServerState()
    data class Running(val port: Int) : ServerState()
    data class Error(val message: String) : ServerState()
}
