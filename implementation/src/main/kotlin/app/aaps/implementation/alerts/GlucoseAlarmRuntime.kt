package app.aaps.implementation.alerts

import android.app.NotificationManager as AndroidNotificationManager
import android.content.Context
import android.os.Build
import app.aaps.core.data.glucose.GlucoseAlarmEvaluator as Evaluator
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.alerts.GlucoseAlarms
import app.aaps.core.interfaces.calibration.CalibrationContext
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal fun latestUsableGlucoseSource(data: List<GV>): GV? =
    data.asSequence()
        .filter { it.isValid && it.value.isFinite() && it.value > 0.0 }
        .maxByOrNull { it.timestamp }

internal fun glucosePhoneDeliveryAllowed(appEnabled: Boolean, channelBlocked: Boolean, groupBlocked: Boolean): Boolean =
    appEnabled && !channelBlocked && !groupBlocked

internal suspend fun prepareSingleGlucoseAlarmReading(source: GV, activePlugin: ActivePlugin): List<InMemoryGlucoseValue> {
    val raw = mutableListOf(
        InMemoryGlucoseValue(
            timestamp = source.timestamp,
            value = source.value,
            trendArrow = source.trendArrow,
            sourceSensor = source.sourceSensor
        )
    )
    val calibrated = activePlugin.activeCalibration.calibrate(raw, CalibrationContext.NONE)
    val smoothed = activePlugin.activeSmoothing.smooth(calibrated)
    return (smoothed.ifEmpty { calibrated }).map { it.copy() }
}

/** One serialized consumer owns snapshots, persisted episodes, snoozes and delivery. No loop/network dependency. */
@Singleton
class GlucoseAlarmRuntime @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val notifications: NotificationManager,
    private val iobCobCalculator: IobCobCalculator,
    private val activePlugin: ActivePlugin,
    private val profileUtil: ProfileUtil,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val logger: AAPSLogger,
    @ApplicationScope private val scope: CoroutineScope
) : GlucoseAlarms {
    // Device-local operational state, intentionally excluded from settings export and sync.
    private val storage = context.getSharedPreferences("glucose_alarm_episodes", Context.MODE_PRIVATE)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private var started = false
    private var samples: List<InMemoryGlucoseValue> = emptyList()
    private var sourceTimestamp: Long? = null
    private var lastGeneration = Long.MIN_VALUE
    private var low = load("low")
    private var falling = load("falling")
    private var displayed: Pair<String, Boolean>? = null
    private var testUntil = 0L

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        scope.launch {
            for (command in commands) {
                try { command() } catch (e: Exception) { logger.error(LTag.NOTIFICATION, "Glucose alarm evaluation failed", e) }
            }
        }
        scope.launch {
            merge(
                preferences.observe(BooleanKey.GlucoseLowEnabled).map {},
                preferences.observe(BooleanKey.GlucoseFallingEnabled).map {},
                preferences.observe(BooleanKey.GlucoseLowPhoneAlarm).map {},
                preferences.observe(BooleanKey.GlucoseFallingPhoneAlarm).map {},
                preferences.observe(UnitDoubleKey.GlucoseLowThreshold).map {},
                preferences.observe(UnitDoubleKey.GlucoseFallingThreshold).map {},
                preferences.observe(DoubleKey.GlucoseFallRate).map {},
                preferences.observe(StringKey.GeneralUnits).map {}
            ).collect { commands.send { evaluate() } }
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                commands.send { evaluate() }
            }
        }
    }

    override fun update(data: List<InMemoryGlucoseValue>, generation: Long) {
        // The main autosens store was loaded immediately before this callback. Keep its original CGM
        // timestamp alongside normalized bucket timestamps so freshness is based on the source value.
        val source = latestUsableGlucoseSource(iobCobCalculator.ads.getBgReadingsDataTableCopy())
        val sourceTime = source?.timestamp
        if (data.isNotEmpty() || source == null) {
            enqueueSnapshot(data.map { it.copy() }, generation, sourceTime)
            return
        }

        // Autosens deliberately has no buckets until roughly three readings exist. A plain-low alarm
        // must not wait for that history, so process the latest source reading as a singleton. With one
        // sample the evaluator leaves the falling rate unknown and only the plain-low rule can fire.
        scope.launch {
            val snapshot = try {
                prepareSingleGlucoseAlarmReading(source, activePlugin)
            } catch (e: Exception) {
                logger.error(LTag.NOTIFICATION, "Could not process singleton glucose alarm reading", e)
                listOf(
                    InMemoryGlucoseValue(
                        timestamp = source.timestamp,
                        value = source.value,
                        trendArrow = source.trendArrow,
                        sourceSensor = source.sourceSensor
                    )
                )
            }
            enqueueSnapshot(snapshot, generation, sourceTime)
        }
    }

    private fun enqueueSnapshot(snapshot: List<InMemoryGlucoseValue>, generation: Long, sourceTime: Long?) {
        commands.trySend {
            // A newer pipeline generation can legitimately remove/invalidate the newest reading.
            // Order by workflow generation, not glucose timestamp, so those invalidations take effect.
            if (generation >= lastGeneration) {
                lastGeneration = generation
                samples = snapshot
                sourceTimestamp = sourceTime
                evaluate()
            }
        }
    }

    private fun evaluate() {
        val now = dateUtil.now()
        if (testUntil != 0L && now >= testUntil) clearTest()
        val reading = Evaluator.reading(samples, now, sourceTimestamp)
        val lowResult = Evaluator.evaluate(
            Evaluator.Rule(preferences.get(BooleanKey.GlucoseLowEnabled), preferences.getRaw(UnitDoubleKey.GlucoseLowThreshold)), low, reading, now
        )
        val fallResult = Evaluator.evaluate(
            Evaluator.Rule(preferences.get(BooleanKey.GlucoseFallingEnabled), preferences.getRaw(UnitDoubleKey.GlucoseFallingThreshold), preferences.get(DoubleKey.GlucoseFallRate)), falling, reading, now
        )
        if (low != lowResult.state || falling != fallResult.state) {
            low = lowResult.state
            falling = fallResult.state
            persist()
        }
        if (reading == null || (!lowResult.alert && !fallResult.alert)) {
            notifications.dismiss(NotificationId.GLUCOSE_ALARM, userInitiated = false)
            displayed = null
            return
        }
        clearTest() // a real alarm always takes precedence over a test
        val reasons = buildList {
            if (lowResult.alert) add(rh.gs(R.string.glucose_low_reason))
            if (fallResult.alert) add(rh.gs(R.string.glucose_falling_reason))
        }.joinToString("; ")
        val rate = reading.rate?.let { rh.gs(R.string.glucose_rate_text, profileUtil.fromMgdlToUnits(it), profileUtil.unitLabel) }.orEmpty()
        val text = rh.gs(R.string.glucose_alarm_text, profileUtil.fromMgdlToStringWithUnits(reading.glucose), reasons, rate)
        val phoneRequested = (lowResult.alert && preferences.get(BooleanKey.GlucoseLowPhoneAlarm)) ||
            (fallResult.alert && preferences.get(BooleanKey.GlucoseFallingPhoneAlarm))
        val phone = phoneRequested && phoneAlarmDeliveryAllowed()
        if (displayed == (text to phone)) return
        val lowEpisode = low.episodeId.takeIf { lowResult.alert }
        val fallEpisode = falling.episodeId.takeIf { fallResult.alert }
        val dismiss = { acknowledge(lowEpisode, fallEpisode, snooze = false) }
        notifications.postGlucoseAlarm(
            text, phone,
            actions = listOf(
                NotificationAction(R.string.glucose_snooze, actionIntent(lowEpisode, fallEpisode, true)) { acknowledge(lowEpisode, fallEpisode, snooze = true) },
                NotificationAction(app.aaps.core.ui.R.string.dismiss, actionIntent(lowEpisode, fallEpisode, false), dismiss)
            ),
            onDismiss = dismiss
        )
        displayed = text to phone
    }

    private fun phoneAlarmDeliveryAllowed(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        val channel = manager.getNotificationChannel(GLUCOSE_PHONE_CHANNEL)
        val channelBlocked = channel == null || channel.importance == AndroidNotificationManager.IMPORTANCE_NONE
        val groupBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        } else false
        return glucosePhoneDeliveryAllowed(manager.areNotificationsEnabled(), channelBlocked, groupBlocked)
    }

    fun acknowledge(lowEpisode: Long?, fallEpisode: Long?, snooze: Boolean, onComplete: () -> Unit = {}) {
        commands.trySend {
            try {
                val now = dateUtil.now()
                low = Evaluator.acknowledge(low, lowEpisode, if (snooze) now + preferences.get(IntKey.GlucoseLowSnooze).coerceIn(1, 120) * 60_000L else null)
                falling = Evaluator.acknowledge(falling, fallEpisode, if (snooze) now + preferences.get(IntKey.GlucoseFallingSnooze).coerceIn(1, 120) * 60_000L else null)
                persist()
                displayed = null
                evaluate()
            } finally { onComplete() }
        }
    }

    private fun actionIntent(lowEpisode: Long?, fallEpisode: Long?, snooze: Boolean): android.app.PendingIntent {
        val intent = android.content.Intent(context, GlucoseAlarmActionReceiver::class.java)
            .putExtra("lowEpisode", lowEpisode ?: 0L).putExtra("fallEpisode", fallEpisode ?: 0L)
            .putExtra("snooze", snooze)
            .setData(android.net.Uri.parse("aaps://glucose-alarm/${lowEpisode ?: 0}/${fallEpisode ?: 0}/$snooze"))
        return android.app.PendingIntent.getBroadcast(context, if (snooze) 300_001 else 300_002, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    override fun test(phoneAlarm: Boolean) {
        commands.trySend {
            // Tests never replace or silence a live glucose alarm, and expire even without another reading.
            if (displayed == null && notifications.notifications.value.none { it.id != NotificationId.GLUCOSE_ALARM_TEST && it.soundRes != null }) {
                testUntil = dateUtil.now() + 30_000L
                val deadline = testUntil
                scope.launch {
                    delay(30_000L)
                    commands.send { if (testUntil == deadline) clearTest() }
                }
                notifications.postGlucoseAlarm(
                    rh.gs(R.string.glucose_test_text), phoneAlarm && phoneAlarmDeliveryAllowed(),
                    listOf(NotificationAction(app.aaps.core.ui.R.string.dismiss) { stopTest() }),
                    onDismiss = { stopTest() }, test = true
                )
            }
        }
    }

    override fun stopTest() { commands.trySend { clearTest() } }

    private fun clearTest() {
        if (testUntil != 0L) notifications.dismiss(NotificationId.GLUCOSE_ALARM_TEST, userInitiated = false)
        testUntil = 0L
    }

    private fun load(key: String) = Evaluator.State(
        storage.getBoolean("${key}_active", false), storage.getBoolean("${key}_dismissed", false),
        storage.getLong("${key}_snooze", 0), storage.getLong("${key}_episode", 0)
    )

    private fun persist() {
        val editor = storage.edit()
        for ((key, state) in listOf("low" to low, "falling" to falling)) {
            editor.putBoolean("${key}_active", state.active).putBoolean("${key}_dismissed", state.dismissed)
                .putLong("${key}_snooze", state.snoozeUntil).putLong("${key}_episode", state.episodeId)
        }
        // On the application worker: finish persistence before accepting another alarm action.
        if (!editor.commit()) logger.error(LTag.NOTIFICATION, "Could not persist glucose alarm snooze state")
    }

    companion object {
        private const val GLUCOSE_PHONE_CHANNEL = "aaps_glucose_phone_alarms"
    }
}