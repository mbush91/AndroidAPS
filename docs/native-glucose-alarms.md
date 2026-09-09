# Native glucose alarms

Settings: **Alerts → Glucose alarms**. Both rules are disabled by default.

- Below threshold: current processed glucose strictly below X (initial value 70 mg/dL).
- Below threshold and falling: glucose below X (initial value 90 mg/dL) and short average rate at most −Y (initial value 2 mg/dL/min).
- Each rule has its own phone-alarm toggle and snooze duration (initial value 15 minutes).
- Thresholds and rates display in the selected glucose units; storage/evaluation uses mg/dL and mg/dL/min.

These are configuration defaults, not individualized treatment recommendations.

## Data and lifecycle

`PrepareGraphDataWorker` sends copied live snapshots after calibration and smoothing, before IOB/COB calculation. History-browser jobs do not send snapshots. `GlucoseAlarmRuntime` is started from MainApp and serializes data, preference changes, timer checks, and user actions on the application scope. It has no dependency on an active dosing algorithm, Automation, or Nightscout. Offline operation requires the local glucose source to continue delivering readings.

Readings older than seven minutes, future timestamps, gap-filled latest values, non-finite values, and nonpositive values cannot start or sustain delivery. Positive sensor LOW values can trigger the plain-low rule even when a rate cannot be calculated. The falling rule requires real, ordered data with no gaps exceeding 7.5 minutes in the short-delta window. The extracted shared delta calculation preserves APS behavior, with its five-minute result divided by five for alarm evaluation.

Episodes rearm after glucose reaches X + 5 mg/dL; falling episodes also rearm when the rate rises to at least −Y + 0.2 mg/dL/min. Unknown data stops delivery without treating missing data as recovery. The existing missed-reading alarm remains independently configurable.

Dismiss silences matching episodes until recovery. Snooze uses each matching rule's configured duration and requires fresh qualifying data before delivery resumes. Recovery during snooze does not cancel the snooze deadline. Episode IDs reject stale actions. Operational state is persisted locally and excluded from settings sync/export. A second unsnoozed rule can still alert. Both matching rules share one displayed notification and one sound.

## Android delivery

The separate `Glucose alarms` Android group contains `Glucose notifications` and `Glucose phone alarms`. Standard mode uses the notification channel sound. Phone mode uses a silent visual channel plus the existing looping audio player, explicitly selecting the alarm stream at its current system volume without the global notification-volume ramp. Channel/app blocking suppresses glucose audio. Explicit receiver actions work after process recreation.

Alarm-stream playback is independent of ringer silence, but does not bypass a DND mode that disallows alarms. Channel bypass controls notification delivery and does not grant a MediaPlayer exemption. Setup links to channel, DND, and sound settings and shows notification/channel blocking and alarm volume. No full-screen permission is needed for glucose playback; v1 uses the notification shade/lock-screen actions and the in-app notification card.

Tests use real delivery paths and stop after 30 seconds. They do not replace an active audible internal alarm. The shared audio player retains a pending internal request while a full-screen alarm owns playback and resumes it when that owner stops. Global mute still dismisses all audible alarms.

## Verification

Automated checks:

```sh
./gradlew :core:data:test --tests '*GlucoseAlarmEvaluatorTest'
./gradlew :plugins:aps:testFullDebugUnitTest --tests '*DeltaCalculatorTest'
./gradlew :workflow:testFullDebugUnitTest --tests '*PrepareGraphDataWorkerTest'
./gradlew :app:compileFullDebugKotlin
```

Device acceptance checks before relying on this feature:

- Both rule boundaries and both unit systems; loop suspended; Internet disconnected with local CGM reception.
- Locked screen, silent ringer, DND allowing alarms, DND blocking alarms, alarm volume zero, and disabled notification channels.
- Snooze/dismiss with duplicate readings, recovery, simultaneous rules, and process recreation.
- A pump/internal alarm and a full-screen alarm overlapping a glucose alarm; confirm handoff and global mute.
- No alarm from history browsing, imported old readings, future timestamps, or synthetic gap fills.
- Notification and phone-alarm test buttons, automatic timeout, and blocked-permission feedback.

The local development environment could not download the repository's Gradle distribution; the PR includes CI for the commands above. Android device validation is still required.
