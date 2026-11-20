package com.orion.proxy

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android Quick Settings Tile allowing one-tap toggle of DirectProxy
 * directly from the system Quick Settings shade.
 */
class DirectProxyTileService : TileService() {

    companion object {
        const val EXTRA_AUTO_START = "com.orion.proxy.extra.AUTO_START"

        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, DirectProxyTileService::class.java)
                )
            } catch (_: Exception) {
                // Safe no-op if tile service is not yet pinned
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTileUi()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        syncTileUi()
    }

    override fun onClick() {
        super.onClick()
        val currentState = DirectProxyStateHolder.state.value

        if (currentState.isRunning) {
            DirectProxyService.stop(this)
            updateTileAppearance(ProxyLifecycleStatus.INACTIVE, null)
            return
        }

        if (DirectProxyPermissions.hasWifiDirectPermission(this)) {
            DirectProxyService.start(this)
            updateTileAppearance(ProxyLifecycleStatus.STARTING, null)
        } else {
            launchAppForPermissions()
        }
    }

    private fun syncTileUi() {
        val state = DirectProxyStateHolder.state.value
        updateTileAppearance(state.status, state.ssid)
    }

    private fun updateTileAppearance(status: ProxyLifecycleStatus, ssid: String?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_direct_proxy)
        tile.label = getString(R.string.qs_tile_label)

        when (status) {
            ProxyLifecycleStatus.ACTIVE -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = ssid ?: getString(R.string.qs_tile_subtitle_active)
                }
            }
            ProxyLifecycleStatus.STARTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_starting)
                }
            }
            ProxyLifecycleStatus.INACTIVE, ProxyLifecycleStatus.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_inactive)
                }
            }
        }
        tile.updateTile()
    }

    private fun launchAppForPermissions() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AUTO_START, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                8283,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
