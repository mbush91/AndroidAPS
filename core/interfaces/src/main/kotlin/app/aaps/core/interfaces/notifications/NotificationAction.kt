package app.aaps.core.interfaces.notifications

import android.app.PendingIntent
import androidx.annotation.StringRes

data class NotificationAction(
    @StringRes val buttonTextRes: Int,
    val action: () -> Unit
) {
    /** Optional durable Android action, used by glucose alarms after process recreation. */
    var pendingIntent: PendingIntent? = null
        private set

    constructor(buttonTextRes: Int, pendingIntent: PendingIntent, action: () -> Unit) : this(buttonTextRes, action) {
        this.pendingIntent = pendingIntent
    }
}
