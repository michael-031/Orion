package com.orion.proxy

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.proxy.ui.DirectProxyScreen
import com.orion.proxy.ui.theme.DirectProxyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: DirectProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        val autoStartRequested = intent?.getBooleanExtra(DirectProxyTileService.EXTRA_AUTO_START, false) == true

        setContent {
            DirectProxyTheme {
                val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()
                var pendingStartAfterPermission by remember { mutableStateOf(autoStartRequested) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    viewModel.refreshSystemChecks(this@MainActivity)
                    if (pendingStartAfterPermission && DirectProxyPermissions.hasWifiDirectPermission(this@MainActivity)) {
                        pendingStartAfterPermission = false
                        DirectProxyService.start(this@MainActivity)
                    } else if (!DirectProxyPermissions.hasWifiDirectPermission(this@MainActivity)) {
                        pendingStartAfterPermission = false
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Wi-Fi Direct permission is required to start.")
                        }
                    }
                }

                // Refresh permissions & battery optimization status whenever the activity resumes
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshSystemChecks(this@MainActivity)
                }

                // If launched from the Quick Settings tile while permissions were missing, prompt right away
                LaunchedEffect(autoStartRequested) {
                    viewModel.refreshSystemChecks(this@MainActivity)
                    if (autoStartRequested) {
                        if (DirectProxyPermissions.hasWifiDirectPermission(this@MainActivity)) {
                            pendingStartAfterPermission = false
                            DirectProxyService.start(this@MainActivity)
                        } else {
                            permissionLauncher.launch(DirectProxyPermissions.allRuntimePermissions())
                        }
                    }
                }

                DirectProxyScreen(
                    proxyState = proxyState,
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onToggleProxy = {
                        if (proxyState.isRunning) {
                            DirectProxyService.stop(this@MainActivity)
                        } else {
                            if (DirectProxyPermissions.hasWifiDirectPermission(this@MainActivity)) {
                                // Request POST_NOTIFICATIONS opportunistically on Android 13+ if not yet granted
                                if (!DirectProxyPermissions.hasNotificationPermission(this@MainActivity)) {
                                    pendingStartAfterPermission = true
                                    permissionLauncher.launch(DirectProxyPermissions.allRuntimePermissions())
                                } else {
                                    DirectProxyService.start(this@MainActivity)
                                }
                            } else {
                                pendingStartAfterPermission = true
                                permissionLauncher.launch(DirectProxyPermissions.allRuntimePermissions())
                            }
                        }
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(DirectProxyPermissions.allRuntimePermissions())
                    },
                    onOpenAppSettings = { openAppDetailsSettings() },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() },
                    onDismissBatteryBanner = { viewModel.dismissBatteryBanner() },
                    onAddQuickTile = {
                        requestPinQuickSettingsTile { message ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    },
                    onTogglePasswordVisibility = { viewModel.togglePassphraseVisibility() },
                    onCopyValue = { label, value ->
                        copyToClipboard(label, value)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(getString(R.string.copied_format, label))
                        }
                    },
                    onOpenHelp = { viewModel.openHelpSheet() },
                    onCloseHelp = { viewModel.closeHelpSheet() },
                    onOpenEditCredentials = { viewModel.openCredentialsSheet() },
                    onCloseEditCredentials = { viewModel.closeCredentialsSheet() },
                    onSaveCustomCredentials = { tag, name, pass ->
                        viewModel.saveCustomCredentials(tag, name, pass) { restarting ->
                            coroutineScope.launch {
                                val msg = if (restarting) {
                                    getString(R.string.creds_saved_restarting_msg)
                                } else {
                                    getString(R.string.creds_saved_msg)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    onResetToAutoCredentials = {
                        viewModel.resetToAutoCredentials { restarting ->
                            coroutineScope.launch {
                                val msg = if (restarting) {
                                    getString(R.string.creds_reset_restarting_msg)
                                } else {
                                    getString(R.string.creds_reset_auto_msg)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    onSelectWifiBand = { band ->
                        viewModel.selectWifiBand(band) { restarting ->
                            coroutineScope.launch {
                                val msg = if (restarting) {
                                    getString(R.string.band_switched_restarting_msg, band.shortLabel)
                                } else {
                                    getString(R.string.band_switched_msg, band.shortLabel)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(DirectProxyTileService.EXTRA_AUTO_START, false) &&
            DirectProxyPermissions.hasWifiDirectPermission(this)
        ) {
            DirectProxyService.start(this)
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(intent) }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            openAppDetailsSettings()
        }
    }

    private fun requestPinQuickSettingsTile(onResultMessage: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            if (statusBarManager != null) {
                statusBarManager.requestAddTileService(
                    ComponentName(this, DirectProxyTileService::class.java),
                    getString(R.string.qs_tile_label),
                    Icon.createWithResource(this, R.drawable.ic_qs_direct_proxy),
                    mainExecutor
                ) { resultCode ->
                    when (resultCode) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            onResultMessage(getString(R.string.qs_tile_added_msg))
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            onResultMessage(getString(R.string.qs_tile_exists_msg))
                        else -> Unit
                    }
                }
                return
            }
        }
        onResultMessage(getString(R.string.qs_tile_manual_msg))
    }
}
