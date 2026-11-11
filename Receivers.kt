package app.pejman.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (!Core.prefs(c).getBoolean("active", false)) return
        try {
            c.startForegroundService(Intent(c, OverlayService::class.java))
        } catch (e: Exception) {
            // ignore, next alarm is still scheduled below
        }
        Core.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Core.schedule(c)
    }
}
