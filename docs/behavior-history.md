# Behavior and battery history

This contribution adds recording and session reconstruction only. It does not
change the UI, foreground UI refresh, sleep/wake policy, Helper protocol or existing
battery averages. A later dashboard can consume this history after the UI work is rebased.

## Recorded observations

`BehaviorHistoryRecorder` is owned by `SleepManagerService` while SleepManager is
enabled. It records:

- Initial observed state, accepted awake/standby transitions and tracking stop.
- Protected closed-lid wake decisions and preserved re-sleep callbacks, without
  creating an ordinary usage session for a suppressed wake.
- Fresh lid observations from the existing enabled lid monitor. Missing, stopped
  or failed monitoring stays unknown; persisted/recovered lid guesses are not
  promoted to observed positions. The history does not start another lid monitor.
- External display connected/active observations from a separate display listener,
  even when lid protection is disabled. The detection uses the existing recognized
  display names; it is a dock context proxy, not a universal USB dock detector.
- External power and actual charging/discharging/full/not-charging status as
  separate facts. The existing battery-statistics charging policy is unchanged.
- Battery percentage and available charge/capacity readings at boundaries and
  battery percentage or status changes. Counter provenance is preserved, including
  normalization scale. App identification, gameplay and wake causes are not inferred.

The sticky battery intent and fallback charge counter are captured when the event
arrives. Normalization and SQLite writes run on a single worker in arrival order.
There is no additional timer, polling loop, alarm, wake lock or permission. History
is independent of advanced diagnostics and of whether a battery level is available.

## Persistence and retention

`BehaviorHistoryStore` uses a separate versioned SQLite journal (`behavior_history.db`)
with WAL. Reads must also run off the main thread. Records retain append sequence,
wall time, elapsed realtime, available boot count, recorder run identity and a
versioned payload. The journal covers today and the preceding six local dates,
including DST days, with one older predecessor and a hard limit of 4,096 observations.
The cap can shorten coverage during a burst of events; it never implies older data
was observed. Pruning is transactional with insertion.

Tracking starts fresh on each service lifetime. Startup in an awake state is not
counted as a new wake. Disabling recording stops listeners immediately; already
queued writes drain without waiting on the main looper. A process kill can lose
queued observations, so recovery must never extend the last persisted state through
the intervening gap. A failed write likewise gives the next record a fresh run ID.
Malformed/unsupported payloads become explicit gap observations rather than being
silently skipped.

## Reading sessions

Read `BehaviorHistoryStore.observations(context)` off-main, then call
`BehaviorTimeline.build(observations)`. Whole sessions keep their recorded start/end,
elapsed duration, battery boundaries, protection count, context observations and
whether both boundaries were actual state transitions. The first retained session,
recovery fragments and tracking stop are partial sessions.

Only a live recorder can justify extending the final interval to now. For an ongoing
view, pass `BehaviorHistoryRecorder.activeRunId`, current wall time and elapsed
realtime to `build`. This ID is process-local and available only after persistence;
a run ID read back from disk does not establish liveness. No battery reading is
invented at now. Recorded samples remain available in the observation stream.

Reconstruction breaks coverage at a new run/boot, an explicit gap or a material
wall/elapsed clock discontinuity. Duration uses elapsed realtime, not local clock
subtraction. A future chart must clip intervals to its display range without
allocating a whole-session battery change proportionally to unmeasured fragments.

`batteryChangePercent` and `chargeChangeMah` are signed net changes. Compatible
counter sources/scales are required for mAh and counter-derived percentage change;
otherwise integer battery percentage is used where available. `drainMah` is only
available for a positive measured loss across known battery-powered discharging
observations. Zero or unresolved loss is not a precise zero-consumption claim.
Powered/mixed-session net battery change is not total device or charger energy use.

Existing deep-sleep estimates remain in `BatterySleepStore`; this journal does not
invent a CPU sleep-stage timeline. It does not backfill old logs or infer awake time
from missing battery sessions.

## Validation

JVM tests cover short usage, battery boundaries, protected wakes, dock/lid context,
unknown battery/power, counter compatibility, zero loss, recovery, clock changes,
live intervals, append order, DST retention and payload validation. Android tests
cover SQLite reopening/order, retention/cap, corrupt payload gaps and recorder
integration. Existing sleep/protection tests remain applicable.

Useful checks:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradle :app:connectedDebugAndroidTest
```

Real-device standby battery impact and device-specific lid/display signals still
need physical-device testing. A build/test result does not establish overnight
hardware behavior.
