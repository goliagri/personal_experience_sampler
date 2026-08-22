package pes.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * One exact alarm at the engine's next wake instant (spec §11). The engine's
 * `nextWake` already folds together the next planned ping, pending expiries,
 * snooze re-fires, and the re-materialization horizon, so a single
 * `setExactAndAllowWhileIdle` covers the spec's "next ping" and "next expiry"
 * alarms. No polling, no foreground service: each receiver ticks the engine
 * briefly and schedules the next alarm.
 */
object Alarms {
    fun schedule(context: Context, wakeEpoch: Long?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, PingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (wakeEpoch == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(wakeEpoch * 1000, System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Exact-alarm permission revoked: degrade to inexact rather than
            // crash; the Settings checklist surfaces the missing grant.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class PingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.pesHost().tickAndReschedule { pending.finish() }
    }
}

/** Re-materialize, backfill, and reschedule after reboot / clock or timezone
 * changes / app update (spec §11). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val host = context.pesHost()
        host.post { engine ->
            engine.start()
            Alarms.schedule(context, engine.nextWake(engine.clock.now()))
            pending.finish()
        }
    }
}
