package io.github.sebbejohansson.yggdrasil.hermodr

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.sebbejohansson.yggdrasil.YggApp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Focus as a Quick Settings tile: tap to focus for 50 minutes (ending before
 * your next meeting if that comes first), tap again to end it.
 */
class FocusTileService : TileService() {
    private val container get() = (application as YggApp).container

    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        val c = container
        c.appScope.launch {
            if (c.hermodr.focusNow() != null) {
                c.hermodr.endFocus()
            } else {
                val now = Instant.now()
                val events = if (c.calendar.allowed()) c.calendar.between(now, now.plus(Duration.ofHours(14))) else emptyList()
                c.hermodr.startFocus(FocusPlan.fit(now.plus(Duration.ofMinutes(50)), events, now).until)
            }
            show()
        }
    }

    private fun show() {
        val tile = qsTile ?: return
        val focus = container.hermodr.focusNow()
        tile.state = if (focus != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Focus"
        tile.subtitle = focus?.let { "until " + it.until.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "50 min"
        tile.updateTile()
    }

    companion object {
        /** Asks Android to redraw the tile, after the focus changed elsewhere. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, FocusTileService::class.java)) }
        }
    }
}
