package app.aaps.plugins.aps.openAPS

import app.aaps.core.data.iob.GlucoseDeltaCalculator
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.logging.AAPSLogger
import dagger.Reusable
import javax.inject.Inject

@Reusable
class DeltaCalculator @Inject constructor(@Suppress("UNUSED_PARAMETER") aapsLogger: AAPSLogger) {
    data class DeltaResult(val delta: Double, val shortAvgDelta: Double, val longAvgDelta: Double)

    fun calculateDeltas(data: MutableList<InMemoryGlucoseValue>): DeltaResult =
        GlucoseDeltaCalculator.calculateDeltas(data).let { DeltaResult(it.delta, it.shortAvgDelta, it.longAvgDelta) }

    companion object {
        fun average(array: List<Double>): Double = GlucoseDeltaCalculator.average(array)
    }
}
