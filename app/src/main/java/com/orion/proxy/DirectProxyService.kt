package com.orion.proxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Foreground service owning the Wi-Fi Direct Group Owner lifecycle (with 2.4 GHz / 5 GHz band control),
 * high-throughput local HTTP/HTTPS ProxyServer, PARTIAL_WAKE_LOCK, Low-Latency WifiLock,
 * cached cellular NetworkCallback routing, and persistent status notification.
 */
class DirectProxyService : Service() {

    companion object {
        private const val TAG = "DirectProxyService"
        private const val CHANNEL_ID = "direct_proxy_foreground_channel"
        private const val NOTIFICATION_ID = 8282

        const val ACTION_START = "com.orion.proxy.action.START_DIRECT_PROXY"
        const val ACTION_STOP = "com.orion.proxy.action.STOP_DIRECT_PROXY"
        const val ACTION_TOGGLE = "com.orion.proxy.action.TOGGLE_DIRECT_PROXY"
        const val ACTION_APPLY_CONFIG = "com.orion.proxy.action.APPLY_DIRECT_PROXY_CONFIG"

        fun start(context: Context) {
            val intent = Intent(context, DirectProxyService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DirectProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun applyConfigIfRunning(context: Context) {
            if (DirectProxyStateHolder.state.value.isRunning) {
                val intent = Intent(context, DirectProxyService::class.java).setAction(ACTION_APPLY_CONFIG)
                runCatching { context.startService(intent) }
            }
        }

        fun toggle(context: Context) {
            if (DirectProxyStateHolder.state.value.isRunning) {
                stop(context)
            } else {
                start(context)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var proxyServer: ProxyServer? = null
    private var groupPollJob: Job? = null
    private var isStopping = false
    private var isReconfiguring = false

    @Volatile
    private var activeCellularNetwork: Network? = null
    private var cellularCallbackRegistered = false

    private val cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeCellularNetwork = network
            proxyServer?.clearDnsCache()
            Log.i(TAG, "Active cellular upstream network bound: $network")
        }

        override fun onLost(network: Network) {
            if (activeCellularNetwork == network) {
                activeCellularNetwork = findInitialCellularNetwork()
                proxyServer?.clearDnsCache()
            }
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    queryGroupAndConnectionInfo()
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    if (p2pState != WifiP2pManager.WIFI_P2P_STATE_ENABLED &&
                        DirectProxyStateHolder.state.value.isRunning
                    ) {
                        failAndStop("Wi-Fi Direct was turned off. Enable Wi-Fi and try again.")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxySession(errorMessage = null)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (DirectProxyStateHolder.state.value.isRunning) {
                    stopProxySession(errorMessage = null)
                    return START_NOT_STICKY
                } else {
                    startProxySession()
                }
            }
            ACTION_APPLY_CONFIG -> {
                if (DirectProxyStateHolder.state.value.isRunning) {
                    reconfigureWifiDirectGroup()
                }
            }
            ACTION_START, null -> {
                startProxySession()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardownResources(removeGroupAsync = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProxySession() {
        if (DirectProxyStateHolder.state.value.status == ProxyLifecycleStatus.ACTIVE && proxyServer?.isRunning == true) {
            promoteToForeground()
            return
        }

        isStopping = false
        isReconfiguring = false

        DirectProxyStateHolder.update {
            DirectProxyState(
                status = ProxyLifecycleStatus.STARTING,
                proxyIp = DEFAULT_GROUP_OWNER_IP,
                proxyPort = DEFAULT_PROXY_PORT,
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
        promoteToForeground()
        DirectProxyTileService.requestTileUpdate(this)

        if (!DirectProxyPermissions.hasWifiDirectPermission(this)) {
            failAndStop("Wi-Fi Direct permission is required to start the hotspot.")
            return
        }

        acquireWakeLock()
        acquireWifiLock()
        registerCellularNetworkTracker()

        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            failAndStop("Wi-Fi Direct hardware is unavailable on this device.")
            return
        }

        val channel = manager.initialize(this, Looper.getMainLooper()) {
            Log.w(TAG, "Wi-Fi P2P channel disconnected")
            if (!isStopping && DirectProxyStateHolder.state.value.isRunning) {
                failAndStop("Wi-Fi Direct channel disconnected.")
            }
        }
        if (channel == null) {
            failAndStop("Could not initialize Wi-Fi Direct channel.")
            return
        }

        wifiP2pManager = manager
        wifiP2pChannel = channel
        registerP2pReceiver()

        // Start local HTTP/HTTPS Proxy Server first so it's listening on 0.0.0.0:8282 immediately
        if (!startLocalProxyServer()) {
            return
        }

        launchCreateGroupWithSavedConfig(manager, channel)
    }

    @SuppressLint("MissingPermission")
    private fun reconfigureWifiDirectGroup() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null || !DirectProxyPermissions.hasWifiDirectPermission(this)) {
            startProxySession()
            return
        }

        groupPollJob?.cancel()
        isReconfiguring = true
        DirectProxyStateHolder.update { current ->
            current.copy(
                status = ProxyLifecycleStatus.STARTING,
                ssid = null,
                passphrase = null,
                operatingFrequencyMhz = 0,
                errorMessage = null
            )
        }
        updateNotification()
        DirectProxyTileService.requestTileUpdate(this)

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                serviceScope.launch {
                    delay(450L)
                    isReconfiguring = false
                    if (!isStopping) {
                        launchCreateGroupWithSavedConfig(manager, channel)
                    }
                }
            }

            override fun onFailure(reason: Int) {
                serviceScope.launch {
                    delay(300L)
                    isReconfiguring = false
                    if (!isStopping) {
                        launchCreateGroupWithSavedConfig(manager, channel)
                    }
                }
            }
        })
    }

    private fun launchCreateGroupWithSavedConfig(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        serviceScope.launch {
            val savedConfig = withContext(Dispatchers.IO) {
                CredentialsPreferences.getConfig(this@DirectProxyService)
            }
            val fallbackPass = withContext(Dispatchers.IO) {
                CredentialsPreferences.getOrGenerateBandFallbackPassphrase(this@DirectProxyService)
            }
            if (isStopping) return@launch

            val effectiveConfig = if (savedConfig.enabled && savedConfig.isValid) {
                savedConfig
            } else {
                // On API 29+, WifiP2pConfig.Builder requires SSID + passphrase to lock 2.4 GHz or 5 GHz band
                savedConfig.copy(
                    ssidTag = CustomHotspotConfig.DEFAULT_SSID_TAG,
                    ssidName = CustomHotspotConfig.DEFAULT_SSID_NAME,
                    passphrase = fallbackPass
                )
            }

            val requestedBand = resolveEffectiveBand(effectiveConfig.preferredBand)
            createWifiDirectGroup(
                manager = manager,
                channel = channel,
                config = effectiveConfig,
                requestedBand = requestedBand,
                allowBandFallback = (requestedBand == WifiBandPreference.BAND_5GHZ),
                retryAfterRemove = true
            )
        }
    }

    private fun resolveEffectiveBand(requested: WifiBandPreference): WifiBandPreference {
        if (requested == WifiBandPreference.BAND_5GHZ) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && !wifiManager.is5GHzBandSupported) {
                return WifiBandPreference.BAND_2GHZ
            }
        }
        return requested
    }

    private fun startLocalProxyServer(): Boolean {
        proxyServer?.stop()
        val server = ProxyServer(
            port = DEFAULT_PROXY_PORT,
            bindAddress = "0.0.0.0",
            upstreamSocketFactoryProvider = {
                activeCellularNetwork?.socketFactory ?: SocketFactory.getDefault()
            },
            upstreamDnsResolver = { host ->
                val cellular = activeCellularNetwork
                if (cellular != null) {
                    cellular.getAllByName(host)
                } else {
                    InetAddress.getAllByName(host)
                }
            },
            onStatsChanged = { stats ->
                DirectProxyStateHolder.update { current ->
                    current.copy(
                        activeConnections = stats.activeConnections,
                        txBytes = stats.txBytes,
                        rxBytes = stats.rxBytes,
                        throughputBytesPerSec = stats.throughputBytesPerSec
                    )
                }
            },
            onFatalError = { error ->
                serviceScope.launch {
                    if (!isStopping) {
                        failAndStop("Proxy socket error: ${error.message ?: "unexpected failure"}")
                    }
                }
            }
        )

        return try {
            server.start()
            proxyServer = server
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind proxy server on port $DEFAULT_PROXY_PORT", e)
            failAndStop("Port $DEFAULT_PROXY_PORT is busy or unavailable: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createWifiDirectGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: CustomHotspotConfig,
        requestedBand: WifiBandPreference,
        allowBandFallback: Boolean,
        retryAfterRemove: Boolean
    ) {
        if (!DirectProxyPermissions.hasWifiDirectPermission(this)) {
            failAndStop("Missing Wi-Fi Direct permission.")
            return
        }

        val useBuilderApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config.isValid

        val actionListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct group created (band=${requestedBand.shortLabel}, ssid=${config.fullSsid})")
                DirectProxyStateHolder.update { current ->
                    current.copy(
                        status = ProxyLifecycleStatus.ACTIVE,
                        ssid = if (useBuilderApi) config.fullSsid else current.ssid,
                        passphrase = if (useBuilderApi) config.passphrase else current.passphrase,
                        errorMessage = null
                    )
                }
                updateNotification()
                DirectProxyTileService.requestTileUpdate(this@DirectProxyService)
                startGroupInfoPolling()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed with reason=$reason (band=${requestedBand.shortLabel})")
                if (reason == WifiP2pManager.BUSY && retryAfterRemove) {
                    manager.requestGroupInfo(channel) { existingGroup ->
                        val existingMatchesBand = existingGroup != null && (
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                                (requestedBand == WifiBandPreference.BAND_5GHZ && existingGroup.frequency >= 4900) ||
                                (requestedBand == WifiBandPreference.BAND_2GHZ && existingGroup.frequency in 2400..2500)
                            )
                        val matchesDesired = existingGroup != null &&
                            existingGroup.isGroupOwner &&
                            !existingGroup.networkName.isNullOrBlank() &&
                            existingMatchesBand &&
                            (!useBuilderApi || (existingGroup.networkName == config.fullSsid &&
                                existingGroup.passphrase == config.passphrase))

                        if (matchesDesired) {
                            Log.i(TAG, "Reusing matching Wi-Fi Direct group ${existingGroup.networkName}")
                            applyGroupInfo(existingGroup)
                            DirectProxyStateHolder.update { it.copy(status = ProxyLifecycleStatus.ACTIVE) }
                            updateNotification()
                            DirectProxyTileService.requestTileUpdate(this@DirectProxyService)
                            queryGroupAndConnectionInfo()
                        } else {
                            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    serviceScope.launch {
                                        delay(450L)
                                        if (!isStopping) {
                                            createWifiDirectGroup(
                                                manager = manager,
                                                channel = channel,
                                                config = config,
                                                requestedBand = requestedBand,
                                                allowBandFallback = allowBandFallback,
                                                retryAfterRemove = false
                                            )
                                        }
                                    }
                                }

                                override fun onFailure(removeReason: Int) {
                                    if (allowBandFallback) {
                                        Log.w(TAG, "Falling back from 5 GHz to 2.4 GHz after busy/remove failure")
                                        createWifiDirectGroup(
                                            manager = manager,
                                            channel = channel,
                                            config = config,
                                            requestedBand = WifiBandPreference.BAND_2GHZ,
                                            allowBandFallback = false,
                                            retryAfterRemove = false
                                        )
                                    } else {
                                        failAndStop(describeP2pFailure(reason))
                                    }
                                }
                            })
                        }
                    }
                } else if (allowBandFallback && requestedBand == WifiBandPreference.BAND_5GHZ) {
                    // Some devices/regions restrict 5 GHz P2P channels; gracefully retry on 2.4 GHz
                    Log.w(TAG, "5 GHz P2P group failed (reason=$reason); automatically retrying on 2.4 GHz")
                    createWifiDirectGroup(
                        manager = manager,
                        channel = channel,
                        config = config,
                        requestedBand = WifiBandPreference.BAND_2GHZ,
                        allowBandFallback = false,
                        retryAfterRemove = false
                    )
                } else {
                    failAndStop(describeP2pFailure(reason))
                }
            }
        }

        if (useBuilderApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val p2pBand = when (requestedBand) {
                    WifiBandPreference.BAND_5GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                    WifiBandPreference.BAND_2GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                }
                val p2pConfig = WifiP2pConfig.Builder()
                    .setNetworkName(config.fullSsid)
                    .setPassphrase(config.passphrase)
                    .setGroupOperatingBand(p2pBand)
                    .build()
                manager.createGroup(channel, p2pConfig, actionListener)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid WifiP2pConfig", e)
                failAndStop("Invalid Wi-Fi Direct configuration: ${e.message}")
            }
        } else {
            manager.createGroup(channel, actionListener)
        }
    }

    private fun startGroupInfoPolling() {
        groupPollJob?.cancel()
        groupPollJob = serviceScope.launch {
            repeat(12) { attempt ->
                if (!isActive || isStopping) return@launch
                queryGroupAndConnectionInfo()
                val current = DirectProxyStateHolder.state.value
                val hasFreqOrLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || current.operatingFrequencyMhz > 0
                if (current.hasCredentials && hasFreqOrLegacy) {
                    return@launch
                }
                delay(if (attempt < 4) 350L else 750L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryGroupAndConnectionInfo() {
        if (isReconfiguring || isStopping) return
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!DirectProxyPermissions.hasWifiDirectPermission(this)) return

        manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            if (group != null) {
                applyGroupInfo(group)
            }
        }

        manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                val ip = info.groupOwnerAddress?.hostAddress
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_GROUP_OWNER_IP
                DirectProxyStateHolder.update { current ->
                    current.copy(
                        proxyIp = ip,
                        status = if (current.isRunning) ProxyLifecycleStatus.ACTIVE else current.status
                    )
                }
                updateNotification()
                DirectProxyTileService.requestTileUpdate(this)
            }
        }
    }

    private fun applyGroupInfo(group: WifiP2pGroup) {
        val ssid = group.networkName?.takeIf { it.isNotBlank() }
        val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
        val freqMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else 0

        if (ssid != null || passphrase != null || freqMhz > 0) {
            DirectProxyStateHolder.update { current ->
                current.copy(
                    status = if (current.isRunning) ProxyLifecycleStatus.ACTIVE else current.status,
                    ssid = ssid ?: current.ssid,
                    passphrase = passphrase ?: current.passphrase,
                    operatingFrequencyMhz = if (freqMhz > 0) freqMhz else current.operatingFrequencyMhz
                )
            }
            updateNotification()
            DirectProxyTileService.requestTileUpdate(this)
        }
    }

    /**
     * Tracks the active cellular Network once via NetworkCallback so every proxied TCP
     * socket & DNS lookup uses mobile data directly with zero per-connection Binder IPC.
     */
    private fun registerCellularNetworkTracker() {
        activeCellularNetwork = findInitialCellularNetwork()
        if (cellularCallbackRegistered) return

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, cellularNetworkCallback)
            cellularCallbackRegistered = true
        }.onFailure {
            Log.w(TAG, "Unable to register cellular NetworkCallback", it)
        }
    }

    private fun unregisterCellularNetworkTracker() {
        if (!cellularCallbackRegistered) return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            connectivityManager?.unregisterNetworkCallback(cellularNetworkCallback)
        }
        cellularCallbackRegistered = false
        activeCellularNetwork = null
    }

    private fun findInitialCellularNetwork(): Network? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        // Check active default network first
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return active
            }
        }

        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun stopProxySession(errorMessage: String?) {
        if (isStopping) return
        isStopping = true

        teardownResources(removeGroupAsync = true)
        DirectProxyStateHolder.resetToInactive(errorMessage = errorMessage)
        DirectProxyTileService.requestTileUpdate(this)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        Log.e(TAG, message)
        stopProxySession(errorMessage = message)
    }

    private fun teardownResources(removeGroupAsync: Boolean) {
        groupPollJob?.cancel()
        groupPollJob = null

        proxyServer?.stop()
        proxyServer = null

        unregisterP2pReceiver()
        unregisterCellularNetworkTracker()

        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        wifiP2pManager = null
        wifiP2pChannel = null

        if (manager != null && channel != null) {
            if (removeGroupAsync) {
                try {
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.i(TAG, "Wi-Fi Direct group removed cleanly")
                            closeP2pChannel(channel)
                        }

                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "removeGroup returned reason=$reason")
                            closeP2pChannel(channel)
                        }
                    })
                } catch (e: SecurityException) {
                    Log.w(TAG, "removeGroup skipped due to missing permission", e)
                    closeP2pChannel(channel)
                }
            } else {
                closeP2pChannel(channel)
            }
        }

        releaseWifiLock()
        releaseWakeLock()
    }

    private fun closeP2pChannel(channel: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                channel.close()
            } catch (_: Exception) {
                // Already closed
            }
        }
    }

    private fun registerP2pReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            p2pReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterP2pReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(p2pReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver already unregistered
        }
        receiverRegistered = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DirectProxy::ProxyWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Exception) {
            // Ignore release errors
        } finally {
            wakeLock = null
        }
    }

    /**
     * Prevents Android Wi-Fi chipset 802.11 Power Save Mode (PSM) throttling during
     * active proxy streaming so Wi-Fi Direct throughput matches native tethering.
     */
    private fun acquireWifiLock() {
        val existing = wifiLock
        if (existing != null && existing.isHeld) return

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "DirectProxy::LowLatencyWifiLock").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Exception) {
            // Ignore release errors
        } finally {
            wifiLock = null
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = DirectProxyStateHolder.state.value

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DirectProxyService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (state.status == ProxyLifecycleStatus.ACTIVE) {
            val bandSuffix = state.activeBandLabel?.let { " ($it)" }.orEmpty()
            getString(R.string.notif_title_active) + bandSuffix
        } else {
            getString(R.string.notif_title_starting)
        }

        val ssidDisplay = state.ssid ?: getString(R.string.value_waiting_group)
        val contentText = getString(
            R.string.notif_text_format,
            ssidDisplay,
            state.proxyIp,
            state.proxyPort
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_direct_proxy)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .addAction(
                R.drawable.ic_qs_direct_proxy,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }

    private fun describeP2pFailure(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported on this device."
        WifiP2pManager.BUSY -> "Wi-Fi Direct framework is busy. Ensure Wi-Fi is turned on and retry."
        else -> "Could not create Wi-Fi Direct group (error $reason). Make sure Wi-Fi is enabled."
    }
}
