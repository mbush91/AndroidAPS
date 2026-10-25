package app.aaps.implementation.alerts

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseAlarmActionTest {

    @Test
    fun `dismissing a low alarm removes its visible notification and stops its audio`() = checkAction(snooze = false)

    @Test
    fun `snoozing a low alarm removes its visible notification and stops its audio`() = checkAction(snooze = true)

    private fun checkAction(snooze: Boolean) = runTest {
        val now = 10_000_000L
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val context = mock<Context>()
            val storage = mock<SharedPreferences>()
            val editor = mock<SharedPreferences.Editor>()
            whenever(context.getSharedPreferences("glucose_alarm_episodes", Context.MODE_PRIVATE)).thenReturn(storage)
            whenever(storage.edit()).thenReturn(editor)
            whenever(editor.putBoolean(any(), any())).thenReturn(editor)
            whenever(editor.putLong(any(), any())).thenReturn(editor)
            whenever(editor.commit()).thenReturn(true)

            val preferences = mock<Preferences>()
            whenever(preferences.observe(BooleanKey.GlucoseLowEnabled)).thenReturn(MutableStateFlow(true))
            whenever(preferences.observe(BooleanKey.GlucoseFallingEnabled)).thenReturn(MutableStateFlow(false))
            whenever(preferences.observe(BooleanKey.GlucoseLowPhoneAlarm)).thenReturn(MutableStateFlow(false))
            whenever(preferences.observe(BooleanKey.GlucoseFallingPhoneAlarm)).thenReturn(MutableStateFlow(false))
            whenever(preferences.observe(UnitDoubleKey.GlucoseLowThreshold)).thenReturn(MutableStateFlow(70.0))
            whenever(preferences.observe(UnitDoubleKey.GlucoseFallingThreshold)).thenReturn(MutableStateFlow(70.0))
            whenever(preferences.observe(DoubleKey.GlucoseFallRate)).thenReturn(MutableStateFlow(1.0))
            whenever(preferences.observe(StringKey.GeneralUnits)).thenReturn(MutableStateFlow("mg/dl"))
            whenever(preferences.get(BooleanKey.GlucoseLowEnabled)).thenReturn(true)
            whenever(preferences.get(BooleanKey.GlucoseFallingEnabled)).thenReturn(false)
            whenever(preferences.getRaw(UnitDoubleKey.GlucoseLowThreshold)).thenReturn(70.0)
            whenever(preferences.getRaw(UnitDoubleKey.GlucoseFallingThreshold)).thenReturn(70.0)
            whenever(preferences.get(IntKey.GlucoseLowSnooze)).thenReturn(15)
            whenever(preferences.get(IntKey.GlucoseFallingSnooze)).thenReturn(15)

            val calculator = mock<IobCobCalculator>()
            val autosens = mock<AutosensDataStore>()
            whenever(calculator.ads).thenReturn(autosens)
            whenever(autosens.getBgReadingsDataTableCopy()).thenReturn(emptyList())
            val dateUtil = mock<DateUtil>()
            whenever(dateUtil.now()).thenReturn(now)
            val rh = mock<ResourceHelper>()
            whenever(rh.gs(R.string.glucose_low_reason)).thenReturn("Low")
            whenever(rh.gs(eq(R.string.glucose_alarm_text), any(), any(), any())).thenReturn("Low alarm")
            val profileUtil = mock<ProfileUtil>()
            whenever(profileUtil.fromMgdlToStringWithUnits(55.0)).thenReturn("55 mg/dL")
            val notifications = mock<NotificationManager>()

            val runtime = GlucoseAlarmRuntime(
                context, preferences, notifications, calculator, mock<ActivePlugin>(),
                profileUtil, rh, dateUtil, mock<AAPSLogger>(), appScope
            )
            runtime.start()
            runCurrent()
            runtime.update(listOf(InMemoryGlucoseValue(timestamp = now, value = 55.0)), generation = 1L)
            runCurrent()

            val actions = argumentCaptor<List<NotificationAction>>()
            verify(notifications).postGlucoseAlarm(any(), eq(false), actions.capture(), any(), eq(false))
            clearInvocations(notifications, editor)

            // Exercise the same callbacks used by the in-app notification card.
            // Android's notification actions dispatch to the same runtime acknowledge() method.
            actions.firstValue[if (snooze) 0 else 1].action()
            runCurrent()

            // Must cancel the system notification AND remove it from the registry, which
            // makes NotificationManagerImpl stop any ongoing glucose-alarm sound.
            verify(notifications).dismiss(NotificationId.GLUCOSE_ALARM, userInitiated = false)
            verify(notifications, never()).postGlucoseAlarm(any(), any(), any(), any(), any())
            if (snooze) {
                verify(editor).putLong("low_snooze", now + 15 * 60_000L)
            } else {
                verify(editor).putBoolean("low_dismissed", true)
            }
        } finally {
            appScope.cancel()
        }
    }
}
