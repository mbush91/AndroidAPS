package app.aaps.implementation.alerts

import android.content.Context
import android.content.Intent
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

/** Explicit, non-exported receiver; alarm actions also work after process recreation. */
class GlucoseAlarmActionReceiver : DaggerBroadcastReceiver() {
    @Inject lateinit var alarms: GlucoseAlarmRuntime

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        alarms.start()
        alarms.acknowledge(
            intent.getLongExtra("lowEpisode", 0).takeIf { it != 0L },
            intent.getLongExtra("fallEpisode", 0).takeIf { it != 0L },
            intent.getBooleanExtra("snooze", false),
            onComplete = { pending.finish() }
        )
    }
}
