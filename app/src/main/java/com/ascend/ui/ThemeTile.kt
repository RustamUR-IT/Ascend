package com.ascend.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: tap to cycle through Ascend themes. */
class ThemeTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        Prefs.cycleTheme(this)
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val theme = Prefs.theme(this)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.label = "Ascend"
            tile.subtitle = theme.name
        } else {
            tile.label = "Ascend · ${theme.name}"
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
