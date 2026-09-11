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
}
