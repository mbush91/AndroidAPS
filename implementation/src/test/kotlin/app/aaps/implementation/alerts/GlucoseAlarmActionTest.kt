package app.aaps.implementation.alerts

import android.app.NotificationManager as AndroidNotificationManager
import android.content.Context
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.AlarmSoundPlayer
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.R
import app.aaps.implementation.androidNotification.AlarmNotificationManager
import app.aaps.implementation.notifications.NotificationManagerImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Use Robolectric rather than the JVM Android stubs: production actionIntent() builds real
 * PendingIntents, and NotificationManagerImpl owns both the OS notification and phone audio.
 * JUnit Vintage runs these JUnit4 Robolectric tests alongside the module's Jupiter tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class GlucoseAlarmActionTest {

    private val now = 10_000_000L

    @Test
    fun `dismiss cancels the Android notification and stops phone audio`() = checkAction(snooze = false)

    @Test
    fun `snooze cancels the Android notification and stops phone audio`() = checkAction(snooze = true)

    private fun checkAction(snooze: Boolean) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = createFixture(scope)
            fixture.triggerLow()
            runCurrent()

            // These action callbacks are created alongside real Android PendingIntents, and
            // NotificationManagerImpl actually posts an Android notification via Robolectric.
            val notification = fixture.notifications.notifications.value.single { it.id == NotificationId.GLUCOSE_ALARM }
            assertThat(notification.actions).hasSize(2)
            assertThat(notification.actions.all { it.pendingIntent != null }).isTrue()
            assertThat(fixture.androidNotifications.activeNotifications.map { it.id })
                .contains(NotificationId.GLUCOSE_ALARM.ordinal)
            verify(fixture.soundPlayer).play(
                eq(app.aaps.core.ui.R.raw.urgentalarm), eq(AlarmSoundPlayer.OWNER_INTERNAL),
                eq(0L), eq(true), eq(false)
            )

            notification.actions[if (snooze) 0 else 1].action()
            runCurrent()

            assertThat(fixture.notifications.notifications.value.none { it.id == NotificationId.GLUCOSE_ALARM }).isTrue()
            assertThat(fixture.androidNotifications.activeNotifications.map { it.id })
                .doesNotContain(NotificationId.GLUCOSE_ALARM.ordinal)
            verify(fixture.soundPlayer).stop(AlarmSoundPlayer.OWNER_INTERNAL)
            if (snooze) {
                assertThat(fixture.storage.getLong("low_snooze", 0)).isEqualTo(now + 15 * 60_000L)
                assertThat(fixture.storage.getBoolean("low_dismissed", false)).isFalse()
            } else {
                assertThat(fixture.storage.getBoolean("low_dismissed", false)).isTrue()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `acknowledging glucose alarm keeps other urgent alarms and their audio active`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = createFixture(scope)
            // Post a separate pump alarm first. After the glucose alarm supersedes it, the
            // notification manager must hand audio back to the pump alarm on acknowledgement.
            fixture.notifications.post(
                id = NotificationId.PUMP_ERROR,
                text = "Pump error",
                validMinutes = 0,
                soundRes = app.aaps.core.ui.R.raw.urgentalarm
            )
            fixture.triggerLow()
            runCurrent()
            val glucose = fixture.notifications.notifications.value.single { it.id == NotificationId.GLUCOSE_ALARM }
            glucose.actions[1].action()
            runCurrent()

            assertThat(fixture.notifications.notifications.value.map { it.id })
                .containsExactly(NotificationId.PUMP_ERROR)
            assertThat(fixture.androidNotifications.activeNotifications.map { it.id })
                .doesNotContain(NotificationId.GLUCOSE_ALARM.ordinal)
            // Audio is handed back to the surviving pump alarm, not globally muted.
            verify(fixture.soundPlayer, never()).stop(AlarmSoundPlayer.OWNER_INTERNAL)
            verify(fixture.soundPlayer, times(2)).play(
                eq(app.aaps.core.ui.R.raw.urgentalarm), eq(AlarmSoundPlayer.OWNER_INTERNAL),
                eq(0L), eq(null), eq(null)
            )
        } finally {
            scope.cancel()
        }
    }

    private data class Fixture(
        val runtime: GlucoseAlarmRuntime,
        val notifications: NotificationManagerImpl,
        val androidNotifications: AndroidNotificationManager,
        val soundPlayer: AlarmSoundPlayer,
        val storage: android.content.SharedPreferences
    ) {
        fun triggerLow() {
            runtime.start()
            runtime.update(listOf(InMemoryGlucoseValue(timestamp = 10_000_000L, value = 55.0)), generation = 1L)
        }
    }

    private fun createFixture(scope: CoroutineScope): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("glucose_alarm_episodes", Context.MODE_PRIVATE)
        storage.edit().clear().commit()

        val preferences = mock<Preferences>()
        whenever(preferences.observe(BooleanKey.GlucoseLowEnabled)).thenReturn(MutableStateFlow(true))
        whenever(preferences.observe(BooleanKey.GlucoseFallingEnabled)).thenReturn(MutableStateFlow(false))
        whenever(preferences.observe(BooleanKey.GlucoseLowPhoneAlarm)).thenReturn(MutableStateFlow(true))
        whenever(preferences.observe(BooleanKey.GlucoseFallingPhoneAlarm)).thenReturn(MutableStateFlow(false))
        whenever(preferences.observe(UnitDoubleKey.GlucoseLowThreshold)).thenReturn(MutableStateFlow(70.0))
        whenever(preferences.observe(UnitDoubleKey.GlucoseFallingThreshold)).thenReturn(MutableStateFlow(70.0))
        whenever(preferences.observe(DoubleKey.GlucoseFallRate)).thenReturn(MutableStateFlow(1.0))
        whenever(preferences.observe(StringKey.GeneralUnits)).thenReturn(MutableStateFlow("mg/dl"))
        whenever(preferences.get(BooleanKey.GlucoseLowEnabled)).thenReturn(true)
        whenever(preferences.get(BooleanKey.GlucoseFallingEnabled)).thenReturn(false)
        whenever(preferences.getRaw(UnitDoubleKey.GlucoseLowThreshold)).thenReturn(70.0)
        whenever(preferences.getRaw(UnitDoubleKey.GlucoseFallingThreshold)).thenReturn(70.0)
        whenever(preferences.get(BooleanKey.GlucoseLowPhoneAlarm)).thenReturn(true)
        whenever(preferences.get(IntKey.GlucoseLowSnooze)).thenReturn(15)
        whenever(preferences.get(IntKey.GlucoseFallingSnooze)).thenReturn(15)

        val calculator = mock<IobCobCalculator>()
        val autosens = mock<AutosensDataStore>()
        whenever(calculator.ads).thenReturn(autosens)
        whenever(autosens.getBgReadingsDataTableCopy()).thenReturn(emptyList())
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.now()).thenReturn(now)
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(any())).thenReturn("Alarm")
        whenever(rh.gs(R.string.glucose_low_reason)).thenReturn("Low")
        whenever(rh.gs(eq(R.string.glucose_alarm_text), any(), any(), any())).thenReturn("Low alarm")
        val profileUtil = mock<ProfileUtil>()
        whenever(profileUtil.fromMgdlToStringWithUnits(55.0)).thenReturn("55 mg/dL")

        val logger = mock<AAPSLogger>()
        val iconsProvider = mock<IconsProvider>()
        whenever(iconsProvider.getNotificationIcon()).thenReturn(android.R.drawable.stat_notify_error)
        val soundPlayer = mock<AlarmSoundPlayer>()
        val notifications = NotificationManagerImpl(
            logger, rh, context, preferences, iconsProvider, mock<NotificationHolder>(),
            mock<AlarmNotificationManager>(), soundPlayer
        )
        val runtime = GlucoseAlarmRuntime(
            context, preferences, notifications, calculator, mock<ActivePlugin>(),
            profileUtil, rh, dateUtil, logger, scope
        )
        return Fixture(
            runtime, notifications, context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager,
            soundPlayer, storage
        )
    }
}
