package com.bonevane.bridge

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap pauses Bridge for 15 minutes (USB debugging
 * off, for banking apps); tap again to end the pause early.
 */
class PauseTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val policy = TunnelService.current?.policy
        Thread {
            when {
                policy == null -> DaemonManager.stop(this, force = true)
                policy.isPaused -> policy.resume()
                else -> policy.pause(15)
            }
            render()
        }.start()
    }

    private fun render() {
        val tile = qsTile ?: return
        val paused = Prefs.pausedUntil(this) > System.currentTimeMillis()
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (paused) "Bridge paused" else "Pause Bridge"
        tile.subtitle = if (paused) "USB debugging off" else "15 min, for banking"
        tile.updateTile()
    }
}
