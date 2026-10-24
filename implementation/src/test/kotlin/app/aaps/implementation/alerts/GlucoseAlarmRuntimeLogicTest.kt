package app.aaps.implementation.alerts

import app.aaps.core.data.glucose.GlucoseAlarmEvaluator
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.smoothing.Smoothing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GlucoseAlarmRuntimeLogicTest {

    private fun glucose(timestamp: Long, value: Double) = GV(
        timestamp = timestamp,
        raw = null,
        value = value,
        trendArrow = TrendArrow.FLAT,
        noise = null,
        sourceSensor = SourceSensor.UNKNOWN
    )

    @Test
    fun `one or two stored readings produce a calibrated smoothed singleton with no rate`() = runTest {
        val activePlugin = mock<ActivePlugin>()
        val calibration = mock<Calibration>()
        val smoothing = mock<Smoothing>()
        whenever(activePlugin.activeCalibration).thenReturn(calibration)
        whenever(activePlugin.activeSmoothing).thenReturn(smoothing)
        whenever(calibration.calibrate(any(), any())).thenAnswer { invocation ->
            invocation.getArgument<MutableList<InMemoryGlucoseValue>>(0).also { it.first().calibrated = 62.0 }
        }
        whenever(smoothing.smooth(any())).thenAnswer { invocation ->
            invocation.getArgument<MutableList<InMemoryGlucoseValue>>(0).also { it.first().smoothed = 58.0 }
        }

        for (stored in listOf(
            listOf(glucose(1_000L, 55.0)),
            listOf(glucose(500L, 80.0), glucose(1_000L, 55.0))
        )) {
            val source = latestUsableGlucoseSource(stored)
            assertThat(source).isNotNull()
            val prepared = prepareSingleGlucoseAlarmReading(source!!, activePlugin)
            assertThat(prepared).hasSize(1)
            assertThat(prepared.single().timestamp).isEqualTo(1_000L)
            assertThat(prepared.single().recalculated).isEqualTo(58.0)
            assertThat(GlucoseAlarmEvaluator.reading(prepared, 1_000L, source.timestamp)?.rate).isNull()
        }
    }

    @Test
    fun `blocked notification group disables phone alarm delivery`() {
        assertThat(glucosePhoneDeliveryAllowed(appEnabled = true, channelBlocked = false, groupBlocked = false)).isTrue()
        assertThat(glucosePhoneDeliveryAllowed(appEnabled = true, channelBlocked = false, groupBlocked = true)).isFalse()
        assertThat(glucosePhoneDeliveryAllowed(appEnabled = true, channelBlocked = true, groupBlocked = false)).isFalse()
        assertThat(glucosePhoneDeliveryAllowed(appEnabled = false, channelBlocked = false, groupBlocked = false)).isFalse()
    }
    @Test
    fun `smoothing failure retains calibrated singleton rather than reverting to raw`() = runTest {
        val activePlugin = mock<ActivePlugin>()
        val calibration = mock<Calibration>()
        val smoothing = mock<Smoothing>()
        whenever(activePlugin.activeCalibration).thenReturn(calibration)
        whenever(activePlugin.activeSmoothing).thenReturn(smoothing)
        whenever(calibration.calibrate(any(), any())).thenAnswer { invocation ->
            invocation.getArgument<MutableList<InMemoryGlucoseValue>>(0).also { it.first().calibrated = 91.0 }
        }
        whenever(smoothing.smooth(any())).thenThrow(IllegalStateException("plugin failure"))
        val data = prepareSingleGlucoseAlarmReading(glucose(1_000L, 55.0), activePlugin)
        assertThat(data.single().recalculated).isEqualTo(91.0)
        assertThat(GlucoseAlarmEvaluator.evaluate(
            GlucoseAlarmEvaluator.Rule(true, 70.0), GlucoseAlarmEvaluator.State(),
            GlucoseAlarmEvaluator.reading(data, 1_000L), 1_000L
        ).alert).isFalse()
    }

    @Test
    fun `glucose check deadlines are event driven and cover snooze and stale data`() {
        val at = 10_000_000L
        val current = GlucoseAlarmEvaluator.Reading(at, 55.0, null)
        val active = GlucoseAlarmEvaluator.State(active = true, episodeId = at, lastReadingTimestamp = at)
        assertThat(nextGlucoseAlarmCheck(at, current, GlucoseAlarmEvaluator.State(), GlucoseAlarmEvaluator.State(), false)).isNull()
        assertThat(nextGlucoseAlarmCheck(at, current, active, GlucoseAlarmEvaluator.State(), true))
            .isEqualTo(at + GlucoseAlarmEvaluator.MAX_AGE_MS + 1)
        val snoozed = active.copy(snoozeUntil = at + 60_000L)
        assertThat(nextGlucoseAlarmCheck(at, current, snoozed, GlucoseAlarmEvaluator.State(), false))
            .isEqualTo(at + 60_000L)
        assertThat(nextGlucoseAlarmCheck(at, null, snoozed, GlucoseAlarmEvaluator.State(), true)).isNull()
    }

    @Test
    fun `phone channel must keep snooze controls discoverable`() {
        assertThat(glucosePhoneChannelVisible(null)).isFalse()
        assertThat(glucosePhoneChannelVisible(android.app.NotificationManager.IMPORTANCE_NONE)).isFalse()
        assertThat(glucosePhoneChannelVisible(android.app.NotificationManager.IMPORTANCE_MIN)).isFalse()
        assertThat(glucosePhoneChannelVisible(android.app.NotificationManager.IMPORTANCE_LOW)).isTrue()
        assertThat(glucosePhoneChannelVisible(android.app.NotificationManager.IMPORTANCE_HIGH)).isTrue()
    }

}
