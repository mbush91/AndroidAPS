package app.aaps.core.interfaces.alerts

import app.aaps.core.data.iob.InMemoryGlucoseValue

interface GlucoseAlarms {
    fun start()
    /** Only live, fully calibrated/smoothed snapshots; never history-browser data. */
    fun update(data: List<InMemoryGlucoseValue>, generation: Long)
    fun test(phoneAlarm: Boolean)
    fun stopTest()
}
