package app.aaps.core.data.glucose

import app.aaps.core.data.glucose.GlucoseAlarmEvaluator as E
import app.aaps.core.data.iob.InMemoryGlucoseValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseAlarmEvaluatorTest {
    private val now = 10_000_000L
    private val low = E.Rule(true, 70.0)
    private val falling = E.Rule(true, 90.0, 2.0)
    private fun reading(bg: Double, rate: Double? = null) = E.Reading(now, bg, rate)
    private fun sample(bg: Double, minutesAgo: Double = 0.0) = InMemoryGlucoseValue(timestamp = now - (minutesAgo * 60_000).toLong(), value = bg)

    @Test fun `below is strict while falling at least includes equality`() {
        assertThat(E.evaluate(low, E.State(), reading(70.0), now).alert).isFalse()
        assertThat(E.evaluate(low, E.State(), reading(69.0), now).alert).isTrue()
        assertThat(E.evaluate(falling, E.State(), reading(89.0, -2.0), now).alert).isTrue()
        assertThat(E.evaluate(falling, E.State(), reading(90.0, -3.0), now).alert).isFalse()
        assertThat(E.evaluate(falling, E.State(), reading(89.0, -1.99), now).alert).isFalse()
    }

    @Test fun `missing trend suppresses only the falling rule`() {
        assertThat(E.evaluate(low, E.State(), reading(60.0), now).alert).isTrue()
        assertThat(E.evaluate(falling, E.State(), reading(60.0), now).alert).isFalse()
    }

    @Test fun `rates use timestamps and convert five minute delta to per minute`() {
        val data = listOf(sample(80.0), sample(92.0, 6.0), sample(104.0, 12.0))
        assertThat(E.reading(data, now)?.rate).isWithin(0.000001).of(-2.0)
    }

    @Test fun `low uses calibrated then smoothed glucose consistently`() {
        val data = listOf(sample(100.0).copy(calibrated = 80.0, smoothed = 65.0))
        assertThat(E.reading(data, now)?.glucose).isEqualTo(65.0)
        assertThat(E.evaluate(low, E.State(), E.reading(data, now), now).alert).isTrue()
    }

    @Test fun `sensor LOW floor still triggers low without a usable rate`() {
        val reading = E.reading(listOf(sample(38.0), sample(60.0, 5.0)), now)
        assertThat(reading?.rate).isNull()
        assertThat(E.evaluate(low, E.State(), reading, now).alert).isTrue()
    }

    @Test fun `stale future nonfinite and nonpositive readings cannot alarm`() {
        for (data in listOf(listOf(sample(60.0, 8.0)), listOf(sample(60.0, -1.0)), listOf(sample(Double.NaN)), listOf(sample(0.0)), emptyList())) {
            assertThat(E.reading(data, now)).isNull()
        }
    }

    @Test fun `duplicate reversed interpolated and widely separated samples have no rate`() {
        for (data in listOf(
            listOf(sample(60.0), sample(80.0)),
            listOf(sample(60.0), sample(80.0, 10.0)),
            listOf(sample(60.0), sample(80.0, 5.0).copy(filledGap = true)),
            listOf(sample(60.0), sample(80.0, 6.0), sample(90.0, 4.0))
        )) assertThat(E.reading(data, now)?.rate).isNull()
        assertThat(E.reading(listOf(sample(60.0).copy(filledGap = true)), now)).isNull()
    }

    @Test fun `duplicate evaluations preserve episode identity`() {
        val first = E.evaluate(low, E.State(), reading(60.0), now)
        assertThat(E.evaluate(low, first.state, reading(60.0), now).state).isEqualTo(first.state)
    }

    @Test fun `dismiss survives stale data and reconstructed state until recovery`() {
        val active = E.evaluate(low, E.State(), reading(60.0), now).state
        val dismissed = E.acknowledge(active, active.episodeId)
        val reconstructed = E.State(dismissed.active, dismissed.dismissed, dismissed.snoozeUntil, dismissed.episodeId)
        val stale = E.evaluate(low, reconstructed, null, now + 600_000)
        assertThat(stale.state).isEqualTo(reconstructed)
        assertThat(stale.alert).isFalse()
        assertThat(E.evaluate(low, stale.state, reading(60.0), now).alert).isFalse()
        val recovered = E.evaluate(low, stale.state, reading(75.0), now).state
        assertThat(E.evaluate(low, recovered, reading(60.0), now).alert).isTrue()
    }

    @Test fun `hysteresis prevents repeated episodes around threshold`() {
        val active = E.evaluate(low, E.State(), reading(69.0), now).state
        assertThat(E.evaluate(low, active, reading(72.0), now).alert).isTrue()
        assertThat(E.evaluate(low, active, reading(75.0), now).state.active).isFalse()
    }

    @Test fun `snooze rechecks freshness and persists across recovery`() {
        val active = E.evaluate(low, E.State(), reading(60.0), now).state
        val snoozed = E.acknowledge(active, active.episodeId, now + 60_000)
        assertThat(E.evaluate(low, snoozed, reading(60.0), now + 59_999).alert).isFalse()
        assertThat(E.evaluate(low, snoozed, reading(60.0), now + 60_000).alert).isTrue()
        assertThat(E.evaluate(low, snoozed, null, now + 60_000).alert).isFalse()
        val recovered = E.evaluate(low, snoozed, reading(80.0), now).state
        assertThat(E.evaluate(low, recovered, reading(60.0), now + 1).alert).isFalse()
    }

    @Test fun `old notification action cannot silence a later episode`() {
        val active = E.evaluate(low, E.State(), reading(60.0), now).state
        assertThat(E.acknowledge(active, active.episodeId - 1)).isEqualTo(active)
        assertThat(E.acknowledge(active, null)).isEqualTo(active)
    }

    @Test fun `disabled rule resets only its own state`() {
        val active = E.evaluate(low, E.State(), reading(60.0), now).state
        assertThat(E.evaluate(low.copy(enabled = false), active, reading(60.0), now).state).isEqualTo(E.State(episodeId = active.episodeId))
        assertThat(E.evaluate(falling, E.State(), reading(60.0, -3.0), now).alert).isTrue()
    }

    @Test fun `invalid imported thresholds and rates cannot trigger`() {
        for (rule in listOf(low.copy(threshold = Double.NaN), low.copy(threshold = 500.0), falling.copy(fallRate = 0.0), falling.copy(fallRate = Double.POSITIVE_INFINITY))) {
            assertThat(E.evaluate(rule, E.State(), reading(60.0, -3.0), now).alert).isFalse()
        }
    }
    @Test fun `rearming on the same glucose timestamp creates a new episode`() {
        val active = E.evaluate(low, E.State(), reading(60.0), now).state
        val recovered = E.evaluate(low, active, reading(80.0), now).state
        val next = E.evaluate(low, recovered, reading(60.0), now).state
        assertThat(next.episodeId).isGreaterThan(active.episodeId)
        assertThat(E.acknowledge(next, active.episodeId)).isEqualTo(next)
    }

    @Test fun `numeric overflow in trend cannot trigger the falling rule`() {
        val value = E.reading(
            listOf(
                sample(60.0),
                sample(Double.MAX_VALUE, 5.0),
                sample(Double.MAX_VALUE, 10.0),
                sample(Double.MAX_VALUE, 15.0)
            ),
            now
        )
        assertThat(value?.rate).isNull()
        assertThat(E.evaluate(falling, E.State(), value, now).alert).isFalse()
    }

}
