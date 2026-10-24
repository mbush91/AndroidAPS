package app.aaps.core.data.iob

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/** Shared APS/glucose-alarm deltas, in mg/dL per five minutes. Input is newest first. */
object GlucoseDeltaCalculator {
    data class DeltaResult(val delta: Double, val shortAvgDelta: Double, val longAvgDelta: Double)

    fun calculateDeltas(data: List<InMemoryGlucoseValue>): DeltaResult {
        val now = data.firstOrNull() ?: return DeltaResult(0.0, 0.0, 0.0)
        val last = mutableListOf<Double>()
        val short = mutableListOf<Double>()
        val long = mutableListOf<Double>()
        for (then in data.drop(1)) {
            if (!(then.recalculated > 39.0)) continue
            val minutes = (now.timestamp - then.timestamp).milliseconds.toDouble(DurationUnit.MINUTES)
            val delta = (now.recalculated - then.recalculated) / minutes * 5.0
            if (minutes in 2.5..7.5) last.add(delta)
            if (minutes in 2.5..17.5) short.add(delta)
            if (minutes in 17.5..42.5) long.add(delta)
            else if (minutes > 42.5) break
        }
        val shortAverage = average(short)
        return DeltaResult(if (last.isEmpty()) shortAverage else average(last), shortAverage, average(long))
    }

    fun average(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size
}
