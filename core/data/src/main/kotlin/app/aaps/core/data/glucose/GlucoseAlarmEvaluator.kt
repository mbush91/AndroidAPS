package app.aaps.core.data.glucose

import app.aaps.core.data.iob.GlucoseDeltaCalculator
import app.aaps.core.data.iob.InMemoryGlucoseValue

/** Pure evaluator. All quantities are mg/dL and minutes; callers serialize and persist State. */
object GlucoseAlarmEvaluator {
    const val MAX_AGE_MS = 7 * 60_000L
    private const val MAX_GAP_MS = 450_000L
    const val RECOVERY_MARGIN = 5.0
    /** Missing CGM data must not make a dismissal suppress a separate low hours later. */
    const val MAX_EPISODE_DATA_GAP_MS = 30 * 60_000L

    data class Rule(val enabled: Boolean, val threshold: Double, val fallRate: Double? = null)
    data class State(val active: Boolean = false, val dismissed: Boolean = false, val snoozeUntil: Long = 0, val episodeId: Long = 0, val lastReadingTimestamp: Long = 0)
    data class Reading(val timestamp: Long, val glucose: Double, val rate: Double?)
    data class Result(val state: State, val alert: Boolean)

    /**
     * Build an alarm reading from processed/bucketed glucose. Trend math intentionally uses the
     * normalized bucket timestamps, while freshness uses [sourceTimestamp] when supplied so bucket
     * alignment can neither reject a current CGM value as future nor make an old source value fresh.
     */
    fun reading(data: List<InMemoryGlucoseValue>, now: Long, sourceTimestamp: Long? = null): Reading? {
        val latest = data.firstOrNull() ?: return null
        val freshnessTimestamp = sourceTimestamp ?: latest.timestamp
        if (now - freshnessTimestamp !in 0..MAX_AGE_MS || latest.filledGap ||
            !latest.recalculated.isFinite() || latest.recalculated <= 0.0) return null
        val recent = data.takeWhile { latest.timestamp - it.timestamp <= 1_050_000L }
        val trustworthy = recent.size >= 2 && recent.all {
            !it.filledGap && it.recalculated.isFinite() && it.recalculated > 39.0
        } && recent.zipWithNext().all { (a, b) -> a.timestamp - b.timestamp in 1..MAX_GAP_MS } &&
            recent.any { latest.timestamp - it.timestamp in 150_000L..1_050_000L }
        val rate = if (trustworthy) (GlucoseDeltaCalculator.calculateDeltas(recent).shortAvgDelta / 5.0).takeIf { it.isFinite() } else null
        return Reading(freshnessTimestamp, latest.recalculated, rate)
    }

    fun acknowledge(state: State, episode: Long?, snoozeUntil: Long? = null): State =
        if (episode == null || !state.active || state.episodeId != episode) state
        else if (snoozeUntil != null) state.copy(snoozeUntil = snoozeUntil)
        else state.copy(dismissed = true)

    fun evaluate(rule: Rule, previous: State, reading: Reading?, now: Long): Result {
        if (!rule.enabled) return Result(State(episodeId = previous.episodeId), false)
        if (!rule.threshold.isFinite() || rule.threshold !in 40.0..250.0 ||
            rule.fallRate?.let { !it.isFinite() || it <= 0 } == true) return Result(previous, false)
        // A short outage preserves acknowledgement; a long outage starts a new episode when
        // fresh glucose returns, even if the prior low was never observed to recover.
        if (reading == null) return Result(previous, false)
        val continuous = previous.lastReadingTimestamp > 0L &&
            reading.timestamp >= previous.lastReadingTimestamp &&
            reading.timestamp - previous.lastReadingTimestamp <= MAX_EPISODE_DATA_GAP_MS
        val prior = if (previous.active && !continuous) State(episodeId = previous.episodeId) else previous
        val recovered = reading.glucose >= rule.threshold + RECOVERY_MARGIN ||
            (rule.fallRate != null && reading.rate?.let { it >= -rule.fallRate + 0.2 } == true)
        if (recovered) return Result(State(episodeId = prior.episodeId), false)
        val matches = reading.glucose < rule.threshold &&
            (rule.fallRate == null || reading.rate?.let { it <= -rule.fallRate } == true)
        val active = prior.active || matches
        val state = prior.copy(
            active = active,
            episodeId = if (!prior.active && matches) maxOf(now, prior.episodeId + 1) else prior.episodeId,
            lastReadingTimestamp = if (active) reading.timestamp else 0L
        )
        val knownRate = rule.fallRate == null || reading.rate != null
        return Result(state, state.active && knownRate && !state.dismissed && now >= state.snoozeUntil)
    }
}