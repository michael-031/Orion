package com.orion.proxy

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DirectProxyUiState(
    val hasWifiDirectPermission: Boolean = false,
    val hasAllPermissions: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val batteryBannerDismissed: Boolean = false,
    val showPassphrase: Boolean = true,
    val showHelpSheet: Boolean = false,
    val showCredentialsSheet: Boolean = false,
    val customConfig: CustomHotspotConfig = CustomHotspotConfig(),
    val supportsCustomCredentials: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val supports5Ghz: Boolean = true,
    val uptimeFormatted: String = "00:00"
)

class DirectProxyViewModel(application: Application) : AndroidViewModel(application) {

    val proxyState: StateFlow<DirectProxyState> = DirectProxyStateHolder.state

    private val _uiState = MutableStateFlow(
        DirectProxyUiState(
            supports5Ghz = detect5GhzSupport(application)
        )
    )
    val uiState: StateFlow<DirectProxyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            CredentialsPreferences.observeConfig(getApplication()).collectLatest { config ->
                _uiState.update { it.copy(customConfig = config) }
            }
        }

        viewModelScope.launch {
            proxyState.collectLatest { state ->
                val startTime = state.startedAtElapsedMs
                if (state.isRunning && startTime != null) {
                    while (isActive) {
                        val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
                        _uiState.update { it.copy(uptimeFormatted = formatUptime(elapsed)) }
                        delay(1000L)
                    }
                } else {
                    _uiState.update { it.copy(uptimeFormatted = "00:00") }
                }
            }
        }
    }

    private fun detect5GhzSupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return true
        return wifiManager.is5GHzBandSupported
    }

    fun refreshSystemChecks(context: Context) {
        val hasWifi = DirectProxyPermissions.hasWifiDirectPermission(context)
        val hasAll = DirectProxyPermissions.hasAllPermissions(context)
        val ignoringBattery = DirectProxyPermissions.isIgnoringBatteryOptimizations(context)
        _uiState.update {
            it.copy(
                hasWifiDirectPermission = hasWifi,
                hasAllPermissions = hasAll,
                isIgnoringBatteryOptimizations = ignoringBattery,
                supports5Ghz = detect5GhzSupport(context)
            )
        }
    }

    fun dismissBatteryBanner() {
        _uiState.update { it.copy(batteryBannerDismissed = true) }
    }

    fun togglePassphraseVisibility() {
        _uiState.update { it.copy(showPassphrase = !it.showPassphrase) }
    }

    fun openHelpSheet() {
        _uiState.update { it.copy(showHelpSheet = true) }
    }

    fun closeHelpSheet() {
        _uiState.update { it.copy(showHelpSheet = false) }
    }

    fun openCredentialsSheet() {
        _uiState.update { it.copy(showCredentialsSheet = true) }
    }

    fun closeCredentialsSheet() {
        _uiState.update { it.copy(showCredentialsSheet = false) }
    }

    fun selectWifiBand(
        band: WifiBandPreference,
        onBandUpdated: (restarting: Boolean) -> Unit
    ) {
        if (band == WifiBandPreference.BAND_5GHZ && !_uiState.value.supports5Ghz) return
        if (_uiState.value.customConfig.preferredBand == band) return

        val appContext = getApplication<Application>()
        viewModelScope.launch {
            CredentialsPreferences.savePreferredBand(appContext, band)
            val wasRunning = proxyState.value.isRunning
            if (wasRunning) {
                DirectProxyService.applyConfigIfRunning(appContext)
            }
            onBandUpdated(wasRunning)
        }
    }

    fun saveCustomCredentials(
        ssidTag: String,
        ssidName: String,
        passphrase: String,
        onSaved: (restarting: Boolean) -> Unit
    ) {
        val validation = WifiDirectCredentialValidator.validateAll(ssidTag, ssidName, passphrase)
        if (!validation.isValid) return

        val appContext = getApplication<Application>()
        viewModelScope.launch {
            CredentialsPreferences.saveCustomConfig(
                context = appContext,
                ssidTag = ssidTag,
                ssidName = ssidName,
                passphrase = passphrase
            )
            _uiState.update { it.copy(showCredentialsSheet = false) }
            val wasRunning = proxyState.value.isRunning
            if (wasRunning) {
                DirectProxyService.applyConfigIfRunning(appContext)
            }
            onSaved(wasRunning)
        }
    }

    fun resetToAutoCredentials(onReset: (restarting: Boolean) -> Unit) {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            CredentialsPreferences.clearCustomConfig(appContext)
            _uiState.update { it.copy(showCredentialsSheet = false) }
            val wasRunning = proxyState.value.isRunning
            if (wasRunning) {
                DirectProxyService.applyConfigIfRunning(appContext)
            }
            onReset(wasRunning)
        }
    }
}
