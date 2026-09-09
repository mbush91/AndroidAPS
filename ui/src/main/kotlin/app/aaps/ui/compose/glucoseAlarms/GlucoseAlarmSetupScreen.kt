package app.aaps.ui.compose.glucoseAlarms

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aaps.core.interfaces.alerts.GlucoseAlarms
import app.aaps.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GlucoseAlarmSetupViewModel @Inject constructor(val alarms: GlucoseAlarms) : ViewModel()

@Composable
fun GlucoseAlarmSetupScreen(onBack: () -> Unit, viewModel: GlucoseAlarmSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val enabled = remember(refresh) { manager.areNotificationsEnabled() }
    val volume = remember(refresh) { audio.getStreamVolume(AudioManager.STREAM_ALARM) }
    fun open(action: String, channel: String? = null) {
        val intent = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (channel != null) intent.putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.glucose_alarm_setup), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.glucose_setup_explanation))
        Text(stringResource(if (enabled) R.string.glucose_notifications_enabled else R.string.glucose_notifications_blocked))
        Text(stringResource(R.string.glucose_alarm_volume, volume, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)))
        for ((channel, label) in listOf(
            "aaps_glucose_notifications" to R.string.glucose_notification_settings,
            "aaps_glucose_phone_alarms" to R.string.glucose_phone_settings
        )) {
            val blocked = remember(refresh, channel) { manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE }
            Button(onClick = { open(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel) }) { Text(stringResource(label)) }
            if (blocked) Text(stringResource(R.string.glucose_channel_blocked), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { open(Settings.ACTION_ZEN_MODE_SETTINGS) }) { Text(stringResource(R.string.glucose_dnd_settings)) }
        Button(onClick = { open(Settings.ACTION_SOUND_SETTINGS) }) { Text(stringResource(R.string.glucose_sound_settings)) }
        Button(onClick = { viewModel.alarms.test(false) }) { Text(stringResource(R.string.glucose_test_notification)) }
        Button(onClick = { viewModel.alarms.test(true) }) { Text(stringResource(R.string.glucose_test_phone)) }
        Button(onClick = { viewModel.alarms.stopTest() }) { Text(stringResource(R.string.glucose_stop_test)) }
        Button(onClick = onBack) { Text(stringResource(app.aaps.core.ui.R.string.back)) }
    }
}
