package dev.krishgoel.theftguard.pause

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.krishgoel.theftguard.ui.MainActivity

/**
 * Quick-settings tile: "Pay mode".
 *
 *  - Not paused → tap → (phone must be unlocked first) → TheftGuard asks for
 *    its PIN → gate pauses for up to 10 minutes.
 *  - Paused → tap → resume immediately.
 *
 * unlockAndRun means a thief can't use the tile from the lock screen, and the
 * PIN means they can't use it on a snatched, unlocked phone either.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val paused = PauseController.isPaused(this)
        qsTile?.apply {
            state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Pay mode"
            subtitle = if (paused) "Gate paused" else "Pause gate"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (PauseController.isPaused(this)) {
            PauseController.resume(this, "tile tapped")
            onStartListening()
            return
        }
        unlockAndRun {
            val intent = Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_PAUSE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        }
    }
}
