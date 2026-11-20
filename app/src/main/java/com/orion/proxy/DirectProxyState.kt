package com.orion.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

const val DEFAULT_GROUP_OWNER_IP = "192.168.49.1"
const val DEFAULT_PROXY_PORT = 8282

enum class ProxyLifecycleStatus {
    INACTIVE,
    STARTING,
    ACTIVE,
    ERROR
}

data class DirectProxyState(
    val status: ProxyLifecycleStatus = ProxyLifecycleStatus.INACTIVE,
    val ssid: String? = null,
    val passphrase: String? = null,
    val proxyIp: String = DEFAULT_GROUP_OWNER_IP,
    val proxyPort: Int = DEFAULT_PROXY_PORT,
    val operatingFrequencyMhz: Int = 0,
    val activeConnections: Int = 0,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val throughputBytesPerSec: Long = 0L,
    val startedAtElapsedMs: Long? = null,
    val errorMessage: String? = null
) {
    val totalBytes: Long
        get() = txBytes + rxBytes

    val isRunning: Boolean
        get() = status == ProxyLifecycleStatus.ACTIVE || status == ProxyLifecycleStatus.STARTING

    val isActive: Boolean
        get() = status == ProxyLifecycleStatus.ACTIVE

    val hasCredentials: Boolean
        get() = !ssid.isNullOrBlank() && !passphrase.isNullOrBlank()

    val activeBandLabel: String?
        get() = when {
            operatingFrequencyMhz >= 4900 -> "5 GHz"
            operatingFrequencyMhz in 2400..2500 -> "2.4 GHz"
            else -> null
        }
}

object DirectProxyStateHolder {
    private val _state = MutableStateFlow(DirectProxyState())
    val state: StateFlow<DirectProxyState> = _state.asStateFlow()

    fun update(transform: (DirectProxyState) -> DirectProxyState) {
        _state.update(transform)
    }

    fun resetToInactive(errorMessage: String? = null) {
        _state.value = DirectProxyState(
            status = if (errorMessage == null) ProxyLifecycleStatus.INACTIVE else ProxyLifecycleStatus.ERROR,
            errorMessage = errorMessage
        )
    }
}

fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f MB", mb)
}

fun formatCompactBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        bytesPerSec > 0L -> "$bytesPerSec B/s"
        else -> "0 KB/s"
    }
}

fun formatUptime(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
