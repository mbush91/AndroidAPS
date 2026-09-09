package app.aaps.ui.compose.glucoseAlarms

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.ui.R

object GlucoseAlarmSetupKey : IntentPreferenceKey {
    override val key = "glucose_alarm_setup"
    override val titleResId = R.string.glucose_alarm_setup
    override val summaryResId = R.string.glucose_alarm_setup_summary
    override val preferenceType = PreferenceType.CLICK
    override val defaultedBySM = false
    override val showInApsMode = true
    override val showInNsClientMode = true
    override val showInPumpControlMode = true
    override val dependency = null
    override val negativeDependency = null
    override val hideParentScreenIfHidden = false
    override val exportable = false
    override val composeScreen = ComposeScreenContent { onBack -> GlucoseAlarmSetupScreen(onBack) }
}
