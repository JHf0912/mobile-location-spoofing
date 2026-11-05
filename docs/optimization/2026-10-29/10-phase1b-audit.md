# 10 - Phase 1B-1 Audit (SessionState cutover)

**Date**: 2026-10-30
**Scope**: Phase 1B-1 only: SessionState model and cutover, runner phases and epoch, service/tile/notification, use cases, UI gates and status components, tests, and the deferred B-2 boundary.
**Contract**: docs/optimization/2026-10-29/08-phase1-design.md sections 3, 6, 8, 9.2 and 12, plus the approved plan section B (Phase 1 design revision).
**Method**: read-only static review of the B-1 files and tests, git status and mtimes, and the fresh JUnit XML and lint artifacts. No Gradle command was run by the auditor. Only this report was written.
**Evidence labels**: verified = read from code/tests/XML; inferred = consequence of the code path; needs-device = device or OEM required.

## 1. Baseline evidence

### 1.1 Scope and freshness

B-1 edits cluster at 2026-10-30 01:22-01:35 (core:model, store, runner, DI; app service, tile, use cases, UI, strings, tests). A small follow-up to the earlier F4/F1 findings is visible at 01:08-01:11 (fused stop failure and lease release retry); it is outside B-1 but present in the tree and its tests are included in the counts below. Files changed after 01:35 are the two audit reports only.

No UI/model/CI/Gradle/strings changes outside the declared B-1 set were found; the B-1 strings and UI files are expected edits. The working tree still contains the pre-existing Phase 0 and 1A changes; they are not reviewed here except where B-1 depends on them.

### 1.2 Test and build evidence

| Module | Tests | Failures/errors | XML time |
|---|---|---|---|
| core:location | 70 (5 coordinator, 13 composite, 9 fused, 11 LM, 21 runner, 2 store, 9 lease) | 0 / 0 | 2026-10-30 01:29:42 |
| app | 34 (4 ServiceStartGuard, 10 Format, 20 MainUiState) | 0 / 0 | 2026-10-30 01:35:33 |
| core:data | 2 (older run) | 0 / 0 | 2026-10-29 23:06:44 |
| core:geo | 47 (older run) | 0 / 0 | 2026-10-29 13:28:04 |
| **Total** | **153** | 0 / 0 | mixed runs |

- Core:location XML is after the last core source/test edit (runner test 01:29:14, XML 01:29:42). App XML is after the last app edit (Motion 01:34:56, service 01:32:57, tile 01:34:54; XML 01:35:33). The 153 total is a sum over modules; data and geo are unchanged older runs.
- Debug APK is at 01:35:32, after the last app edit. Lint report is at 01:34:09 with 0 errors and 15 warnings, but it predates the Motion.kt edit at 01:34:56 and the final app test run; the final-tree lint is therefore not proven by the artifact.
- Release APK and mapping are from 23:30/23:43, before both 1A fixes and B-1. Release has not been rebuilt.

## 2. Verdict table

| # | Audit item | Verdict |
|---|---|---|
| 1 | Model and transition matrix | **CONFIRMED** for the model shape, no per-tick Running, equality dedup, epoch and gates; **HIGH gap**: Stopping teardown-timeout does not emit Error(TeardownTimeout); **PLAUSIBLE** deviations: stop during Starting uses startedAt as the Stopping epoch; partial engine failure never produces Degraded |
| 2 | Runner behavior | **CONFIRMED** for Starting id, epoch capture, 1A preservation, onFinished and static single emission; **MEDIUM** late Running after Stopping; test gaps for the singleton epoch reset and Stopping id/epoch |
| 3 | Service and tile | **CONFIRMED** for phase-driven notification/tile, reason localization, requestListeningState and 1A guards; **MEDIUM** Starting emitted after observe/startForeground (stale Error flash); **MEDIUM** no active-phase check before starting; **LOW** tile Degraded subtitle and per-tick refresh |
| 4 | Use cases | **CONFIRMED** for preserving active phases and not overwriting Running/Degraded; **PLAUSIBLE** StopMockUseCase maps a stop failure to ServiceStartFailed and can turn Idle into Error; StartMockUseCase does not itself reject an active session |
| 5 | UI gates and components | **CONFIRMED** for canStartSession/canStop exactness, phase-driven Hero/Pill, Stopping label, previews and zh/en strings; **PLAUSIBLE/LOW** Degraded uses danger in the pill while the Hero treats it as normal; possible 320dp width pressure from longer phase labels |
| 6 | Tests | **CONFIRMED** counts 70/34/153 and 0 failures; new phase/epoch/static/gate tests are meaningful; **MEDIUM** gaps listed in F13 (no stop-timeout or late-Running test, weak epoch reset test, no service/tile/ViewModel tests) |
| 7 | New bugs and regressions | **CONFIRMED** no missing MockStatus type, no stale store property, no missing strings, Hilt binding removal complete; **MEDIUM** stale notification, start-while-active, late Running, finishSession/new-session race; no new deadlock or lost cancellation found |
| 8 | Deferred B-2 items | **CONFIRMED** SavedStateHandle, idle spec sync, favorites retry/sort/save-name and Robolectric service tests are genuinely absent and listed for B-2; not silently claimed as done |

## 3. Findings

### F1 - HIGH: a stop that exceeds the teardown budget never enters Error(TeardownTimeout)

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:155-181, :384-393; app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:251-273, :288-301.
**Contract**: design section 3.2 row Stopping -> Error(terminal=true, reason=TEARDOWN_TIMEOUT) on teardown budget exhaustion; section 6.1 stopAndJoin(3s).

stop() emits Stopping and cancels the job (:155-168). stopAndJoin() waits with withTimeoutOrNull(timeoutMs) { job.join() } and ignores whether the join succeeded (:177-181). The only TeardownTimeout emission is in the wrapper for a *new start* that waits for a still-stuck previous teardown (:129-134); the test covers only that path (MockSessionRunnerTest.kt:432-476). handleStop, onTaskRemoved and onDestroy all call stopAndJoin and then finishSession without checking the result (MockLocationService.kt:251-301). If the teardown exceeds 3s (the composite can take up to 2s fused + 2s LM, and a binder can hang indefinitely), the state remains Stopping: canStartSession false, canStop true, the service is gone, and the user is stuck until the process restarts. Tapping Stop again repeats the timeout.

The design is internally inconsistent about the follow-up: the matrix says a teardown timeout enters terminal Error but also says to refuse new starts, while the gate rule allows Start from any terminal Error. A fix needs a decision: either emit Error(TeardownTimeout) and keep canStartSession false while the teardown is stuck (for example via the existing teardownStuck flag and a gate exception), or keep Stopping and show an explicit cleanup-in-progress state. The missing emission itself is a code gap; the gate semantics is a design question.

**Fix**: make stopAndJoin return a Boolean (or emit the Error directly when the join fails and the session is still current), emit Error(TeardownTimeout, sessionId, terminal=true) when the budget expires, and define the start gate for that state. Add a test with a controllable hanging stop and assert the state after the budget and after the teardown finally completes.

### F2 - MEDIUM: Starting is emitted after the notification collector attaches, so a previous terminal Error flashes on start

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:143-145, :165, :191-196, :356-390.
**Contract**: design section 3.2 (Idle -> Starting, service emits before startForeground), section 6.3 (Starting before the first Running), plan B-3 (reset an old Error on start).

The service calls startForeground with the running text and sets lastNotificationText (:143-145), then attaches observeSessionForNotification (:165), then emits Starting (:191-196). A new service instance starts with lastNotificationText null and the store still holding the previous session terminal Error. The collector immediately receives that Error, maps it to notification_error, sees a different text, and posts it; Starting then replaces it with the running text. The user sees a stale error notification flash on a new start, which is exactly the issue the Starting phase and the reset requirement were meant to remove. The UI also continues to show the old terminal Error until Starting is emitted.

**Fix**: emit Starting after the cheap checks but before startForeground and before attaching the notification collector (the design section 3.2 order), or clear the previous terminal Error / ignore the first stale emission in the collector. Add a service-level test for the notification text sequence when the prior state is Error.

### F3 - MEDIUM: an in-flight push can publish Running after stop() published Stopping

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:155-168, :291-299, :384-386.
**Contract**: design section 3.2 (Running -> Stopping; Stopping is not left for Running) and section 3.4 (Stopping is an explicit window).

stop() sets accepting=false and emits Stopping before cancelling the job. report() only checks that the session id is current (:384-386). Cancellation is cooperative: if the loop is between the push return and the Running emission, it can call report(Running) after Stopping. The state then goes Stopping -> Running until the teardown finally emits Idle (or stays Running if the teardown hangs). The notification and tile see the same wrong active phase. The existing phase-order test (MockSessionRunnerTest.kt:185-212) stops while the loop is in delay, so it does not exercise this window.

**Fix**: suppress non-terminal active emissions when accepting is false or a stop has been requested (for example a stop epoch checked in report), and add a test with a controllable slow push that stops while the push is in flight and asserts Stopping is the last non-terminal phase.

### F4 - MEDIUM: the service and StartMockUseCase do not reject a start while a session is already active

**Files**: app/src/main/java/com/snake/mobilespoofing/service/ServiceStartGuard.kt:24-31; app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:118-235; app/src/main/java/com/snake/mobilespoofing/domain/StartMockUseCase.kt:26-47; app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:168-174; app/src/main/java/com/snake/mobilespoofing/qs/MockLocationTileService.kt:82-86.
**Contract**: design section 3.2 (Starting/Running/Degraded/Stopping plus another start keeps the current phase; UI/use case reject supersede).

ServiceStartGuard only tracks an in-flight start window and a stop request; it does not consult the phase. handleStart has no isSessionActive check before preflight. StartMockUseCase does not check either; the ViewModel checks its UI copy (which can lag one dispatch), and the tile checks a phase value that can change between the read and the service call. A second start that passes the guard (the first gate has finished) emits Starting with a new id while Running, and runner.start supersedes the session. If the second preflight fails, failEarly emits a terminal Error and stopSelf, overwriting the active session. This is a direct matrix violation on the repeat-start edge and a real double-tap/tile race, even though the normal UI button is disabled while active.

**Fix**: check statusStore.state.value.isSessionActive at the top of handleStart (after beginStart, before preflight) and in StartMockUseCase, and return or log without changing the phase. Keep the runner sessionId/join fallback for direct callers, but do not initiate a supersede from the service or the use case. Add a test for a second ACTION_START while Running.

### F5 - MEDIUM: a partial engine failure never produces Degraded

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/CompositeMockLocationEngine.kt:34-67; core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:224-235, :291-327; core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:39-44.
**Contract**: design section 3.2 (Starting plus partial engine failure but a usable engine -> Degraded), section 3.4 (Degraded is still running).

The composite returns Ok when at least one engine started (CompositeMockLocationEngine.kt:61-66), which matches design section 5.2 but hides the failed engine from the runner. The runner therefore emits Running on the first successful push and never Degraded for a partial engine failure; Degraded is only emitted for AllRejected push ticks (:301-327). SessionDegradedReason.PermissionRevoked, ProviderConflict and EngineTimeout are defined but never emitted. The health reporter logs the failed engine, but neither the state nor the UI shows it. A session can say Running while the fused engine is dead and fused consumers receive nothing. The design itself is inconsistent here: section 5.2 says any Ok is Ok, while the matrix says partial failure is Degraded. The implementation follows 5.2 and leaves the matrix row unimplemented.

**Fix**: either feed a partial-failure signal from the composite health into the runner (a callback or an EngineStartOutcome.Partial) and emit Degraded with EngineTimeout or ProviderConflict, or amend the design to say a partial engine start remains Running and is visible only in health. Add a test with one engine Ok and one Failed.

### F6 - MEDIUM/LOW: finishSession can clear or stop a newer session that started while the old one was finishing

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:368-379; app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:207-235, :275-286, :303-308.

The runner calls onFinished with no session id, and the service uses onFinished = { finishSession() } (MockLocationService.kt:230). The runner checks isCurrent(id) before onFinished (:371), but that check is not atomic with the callback: a new start can increment sessionId and emit Starting between the check and the callback. finishSession then cancels the new notificationJob, calls clearStartingPreview (which emits Idle if the current phase is Starting), and stopForeground/stopSelf, potentially stopping the new session. The new Starting preview is also exposed to this clobber because clearStartingPreview only checks the phase, not the owner. The window is narrow (the UI and tile normally read Idle and can start immediately), but it is a real state-ownership race and is untestable without a service test.

**Fix**: pass the finishing session id to onFinished and have finishSession ignore the callback if the store now holds a different session id/phase, or set a service-level stopping flag when the runner finishes and let a new start create a new service instance. Add a test for the decision helper or a Robolectric service test.

### F7 - LOW: StopMockUseCase maps a stop failure to ServiceStartFailed and can turn Idle into Error

**Files**: app/src/main/java/com/snake/mobilespoofing/domain/StopMockUseCase.kt:19-41; core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:52-62.
**Contract**: design section 6.4 (Stop failure preserves the current phase and logs; do not use Error to overwrite Running/Degraded).

The active-phase guard is correct: a Starting/Running/Degraded/Stopping session is preserved. But when the phase is Idle or Error and startService fails, the use case emits Error(ServiceStartFailed), changing Idle into a terminal Error with a start-oriented reason; the suggested ServiceStopFailed reason does not exist. The Stop button is disabled in Idle and terminal Error, so this is a race-only path, but the behavior still differs from the design. Either log only, or add ServiceStopFailed and its zh/en mapping. The same string is shared by the start and stop use cases because the enum has no stop reason.

### F8 - LOW: Degraded is painted with danger in the status pill while the Hero treats it as a normal running state

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/components/StatusPill.kt:45-52; app/src/main/java/com/snake/mobilespoofing/ui/components/StatusHero.kt:60, :76, :118.
**Contract**: design section 3.4 (Degraded is not a failure; do not turn the Hero into a terminal error).

The pill maps Degraded to colors.danger, the same tone as Error. The Hero uses the normal ink title and muted reason for Degraded, so the two surfaces disagree: one says terminal-looking red, the other says still running. The prompt asks whether danger or amber is appropriate. Using accentAmber or dangerSoft for Degraded, keeping danger for Error, would make the hierarchy explicit and match the design wording. This is a design-quality issue, not a state bug.

### F9 - PLAUSIBLE/LOW: Stopping uses the start-request time as the epoch when the session is stopped before the engine starts

**File**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:155-168.
**Contract**: design section 3.3 (sessionEpochElapsedMs is captured once after startMockMode succeeds and is constant).

stop() uses currentSessionEpochMs ?: currentSessionStartedAtMs for the Stopping state, so a Starting -> Stopping transition carries the start-request time in a field named sessionEpochElapsedMs. The Stopping type has no nullable epoch and the design matrix allows Starting -> Stopping, so some value is required; the fallback is documented. The timer during that Stopping window therefore counts from the start request, not from a real injection epoch. This is acceptable if the design confirms the fallback; otherwise Stopping should carry a nullable epoch or the state should not be emitted before the engine start.

### F10 - LOW: a mid-session terminal Error notification is removed by finishSession

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:356-390, :275-286.
The notification collector maps a terminal Error to notification_error and posts it with NOTIFY_ID, but the runner immediately calls onFinished, and finishSession cancels the collector and calls stopForeground(STOP_FOREGROUND_REMOVE), which removes that notification. A user who is not looking at the app may see the error only as a flash. Preflight failures post a non-ongoing error notification (NOTIFY_ERROR_ID) that survives; mid-session failures do not. The design requires the notification to follow the phase; it does, but the terminal phase is not persistent. Consider posting a non-ongoing error notification for terminal Error before stopping, or keeping the foreground notification until the user dismisses it. This is a UX gap, not a state bug.

### F11 - LOW: the tile shows a Degraded session as running and refreshes on every route tick

**Files**: app/src/main/java/com/snake/mobilespoofing/qs/MockLocationTileService.kt:53-67, :138-159; app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:356-363.
The tile state is correctly active for Degraded, but the subtitle maps Starting, Running and Degraded to tile_subtitle_running, so the shade cannot distinguish a degraded session from a healthy one (the notification can). The listener collector calls refreshTile on every state emission, so a route session performs a tile update at 1 Hz. Neither is a correctness failure; the first is a product/UI gap and the second is a performance concern for the later Compose/tile work. A degraded subtitle and a change-based refresh would close both.

### F12 - PLAUSIBLE/LOW: longer Starting/Stopping/Degraded labels may pressure the 320dp top bar

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/components/AppTopBar.kt:31-56; app/src/main/java/com/snake/mobilespoofing/ui/components/StatusPill.kt:56-65; app/src/main/res/values/strings.xml:6-11; values-en/strings.xml.
The top bar is a SpaceBetween Row with an unweighted left column and the status pill. B-1 introduces longer English labels (Starting, Stopping, Degraded) than the previous Idle/Running/Error set, and the pill has no width cap. The first audit already found the 320dp and 1.3x layout unverified; B-1 adds no screenshot or narrow-width test. This is a PLAUSIBLE layout regression that cannot be established without a screenshot or device run (needs-device/screenshot; Phase 3 owns the fix).

### F13 - MEDIUM: test gaps and one weak epoch test

- The epoch reset test (MockSessionRunnerTest.kt:508-532) creates a *new* MockSessionRunner for the healthy session. Production uses the singleton runner instance, so the test does not prove that the same runner clears currentSessionEpochMs and captures a new epoch after a terminal Error. Use one runner with an engine that fails five pushes and then succeeds, or expose a test seam.
- The phase-order test (MockSessionRunnerTest.kt:185-212) stops while the loop is in delay; it does not exercise the stop-during-push late Running race (F3) or the stop teardown-timeout transition (F1).
- No test asserts that Starting, Running and Stopping share the same session id and epoch. The static test checks the Running id and epoch; the phase test checks only phases.
- No test covers runner.nextSessionId matching the service preview id and the runner start id; there is no service test.
- No service, tile, notification or ViewModel test. The design section 8 expects Service/Tile Robolectric coverage in Phase 1; the coordinator lists Robolectric service tests as B-2 deferred. The ViewModel gate (startMock isSessionActive) is also untested.
- The app suite is 34 tests: 20 MainUiState, 10 Format, 4 ServiceStartGuard. MainUiStateTest is thorough for the gates; the service and tile logic is only code-reviewed.
- The lint artifact predates the last Motion.kt edit, and release artifacts predate B-1; both need a final run before the Phase 1 gate.

The new tests that do exist are meaningful, not tautological: the static single-emission test collects emissions and asserts one Running with the same epoch/point (MockSessionRunnerTest.kt:159-182); the Degraded epoch test checks the constant epoch across Degraded and Running (:275-292); the phase-order test asserts the full Idle/Starting/Running/Stopping/Idle sequence (:185-212); the store tests use Turbine and a late collector (MockStatusStoreTest.kt:17-50); MainUiStateTest covers every gate edge including non-terminal Error (:171-247). The earlier F4/F1 follow-up tests (fused stop failure through health, lease release retry) are present and meaningful.

### F14 - LOW/PLAUSIBLE: the reason enums differ from the design

**Files**: core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:39-62; app/src/main/java/com/snake/mobilespoofing/SessionReasonText.kt:16-37.
The design section 3.1 suggested SessionErrorReason values (PUSH_REJECTED, INTERRUPTED, PERMISSION_REVOKED, MOCK_APP_REVOKED, PROVIDER_LOST, SERVICE_STOP_FAILED, MISSING_SPEC) and typed Degraded.reason as SessionErrorReason. The implementation adds a separate SessionDegradedReason enum and a different SessionErrorReason set. The design calls the enum a suggested minimum set and requires the UI/service mapping to be updated; both mappings are exhaustive and the zh/en strings are complete, so this is a reasonable refinement rather than a functional break. It should still be recorded as a design sign-off item, and ServiceStopFailed is the one missing reason that causes F7.

### F15 - CONFIRMED: the B-2 items are genuinely deferred

- SavedStateHandle: MainViewModel has no SavedStateHandle; mode/point/route/speed/endBehavior are lost on process death. Not claimed as B-1.
- Idle spec sync: SessionConfigStore.set is called only in StartMockUseCase; idle edits do not update the pending spec, so a tile restart can reuse a stale spec. Not claimed as B-1.
- Favorites retry/id sort/save-name: observeFavorites still catches around the collect and ends the stream on one error; no retry/backoff; the repository order and the save-name behavior are unchanged. Not claimed as B-1.
- Robolectric service tests: none exist; the app suite is the three files above. The coordinator explicitly defers them to B-2.

These are absent from the B-1 code and tests and are explicitly listed as B-2; they were not silently dropped.

## 4. Item-by-item answers

### 4.1 Model and transition matrix

- **Running has no per-tick field**: CONFIRMED (Models.kt:87-94; runner emits Running with only id, constant epoch and lastPoint at MockSessionRunner.kt:295-299). A static source produces equal Running values and the StateFlow collapses them; the test collects four pushes and exactly one Running (:159-182).
- **Equality dedup**: CONFIRMED. The store is a MutableStateFlow (MockStatusStore.kt:24-29); no distinctUntilChanged is needed; the test proves one emission and the late-collector case (MockStatusStoreTest.kt:17-50).
- **Transitions implemented**: Idle -> Starting (service preview :191-196 and runner :118); Starting -> Running/Degraded/Error (runner :224-235, :291-327); Running/Degraded -> Degraded/Running (:291-327); Running/Degraded -> Stopping on stop (:155-168); Stopping -> Idle on clean teardown (:371-379); terminal Error on start failure, 5 failures, AppOp, source error, unexpected exception (:224-235, :250-279, :301-314, :341-352); Starting -> Stopping allowed. Confirmed.
- **Transitions missing or deviating**: Stopping -> Error(TEARDOWN_TIMEOUT) on a stop budget expiry is missing (F1); Running -> Starting is possible on a direct supersede (F4; service/use case do not reject); partial engine failure does not produce Degraded (F5); late Running after Stopping is possible (F3).
- **Epoch**: captured once after startMockMode succeeds (:237-241); Running/Degraded/Stopping carry the same value; Idle and terminal Error clear it (:371-379, :388-391). The Degraded test asserts the constant epoch (:275-292). The Stopping fallback to startedAt when stopped before the engine starts is F9. The terminal-epoch reset test is weak (F13).
- **terminal=false**: never emitted by main code; all Error sites are terminal=true or use the default; the only terminal=false is the MainUiStateTest fixture (MainUiStateTest.kt:47-51). CONFIRMED.
- **Gates**: canStartSession exactly matches design 3.5 (MainUiState.kt:49-53); canStop uses isSessionActive, which is Starting/Running/Degraded/Stopping (Models.kt:133-137; MainUiState.kt:59-60). MainUiStateTest covers Idle, terminal and non-terminal Error, all active phases, permission, AppOp and selection (215-247). CONFIRMED.
- **Error startability and canStop**: terminal Error allows start, Stop disabled; non-terminal Error blocks start; test confirms (MainUiStateTest.kt:171-188). CONFIRMED.

### 4.2 Runner

- **Starting id matches the eventual Running/Stopping id**: CONFIRMED by code. The service preview uses nextSessionId = sessionId + 1 (MockSessionRunner.kt:94; MockLocationService.kt:191-196); start increments to that id and emits Starting (:109-118); Running and Stopping use the same id (:298, :163). No test covers the service preview or the id match (F13).
- **Epoch captured after engine start**: CONFIRMED (:215-241). The engine start timeout/failure returns terminal Error before the epoch is set; the finally clears timestamps. The Degraded/Running epoch test is meaningful (:275-292).
- **1A behavior preserved**: the 12s start timeout (:215), previous.join + teardownStuck refusal (:191-201), five consecutive failures (:303-314), AppOp every 10 ticks (:250-261), onFinished on every exit path (:139-150, :368-379), lease engine untouched, stopAndJoin bounded (:177-181). Tests for each remain (MockSessionRunnerTest.kt:408-476, :479-505, :536-566, :647-660). CONFIRMED.
- **No path skips onFinished**: CONFIRMED for the runner. Every run exit goes through the outer finally; the wrapper covers a start cancelled while waiting for the previous teardown; the teardown catch plus outer finally covers a throwing engine (F5 of the 1A round). The service-side race where a new session starts during the callback is F6, not a skipped onFinished.
- **Static single-emission test meaningful**: CONFIRMED. It asserts four pushes, one Running, id 1, epoch 0 and the expected point (:159-182); it would fail if a per-tick timestamp or a differing point were reintroduced.
- **Weak test**: the terminal-epoch reset test uses a new runner rather than the singleton (F13).

### 4.3 Service and tile

- **Starting emission point**: emitted after startForeground and after the notification collector attaches (MockLocationService.kt:143-165, :191-196). The design 3.2 says before startForeground; the later order causes the stale Error notification flash (F2).
- **Stopping**: emitted by runner.stop() from stopAndJoin (:155-168); handleStop, onTaskRemoved and onDestroy call it and then finishSession (:251-301). The normal sequence is correct; the timeout path is F1.
- **Notification per phase**: collector maps Idle, Starting, Running, Degraded, Stopping, Error (MockLocationService.kt:365-378); text only reposts on change; reason localization is complete. The mid-session terminal Error notification is removed by finishSession (F10). The stale Error flash is F2.
- **Reason localization**: SessionReasonText maps both enums exhaustively (SessionReasonText.kt:16-37); all 9 error and 4 degraded keys exist in zh and en (verified by grep). The service uses the Context variants for notifications and the UI uses the resource variants. CONFIRMED.
- **requestListeningState**: called on phase changes, finishSession and failEarly (MockLocationService.kt:282, :360-363, :392-401, :420). CONFIRMED.
- **Tile observes phase and cancels**: onStartListening collects statusStore.state and cancels on stop/destroy (MockLocationTileService.kt:53-77); onClick uses isSessionActive; refreshTile maps phases. No runner.isRunning remains in production (only a historical comment). The Degraded subtitle and per-tick refresh are F11. The start/stop race is F4.
- **No stale/incorrect active state**: the normal paths are phase-driven; F3 (late Running), F4 (start while active), F6 (finish vs new start) are the remaining races. CONFIRMED with those exceptions.
- **1A guards preserved**: beginStart at the top of handleStart, requestStop/cancel on every preflight/stop path, canStartRunner before runner.start (MockLocationService.kt:118-235, :251-301, :410-422; ServiceStartGuard.kt:24-50). The null and unknown intent branches now call stopWithoutStart with requestStop/cancel (MockLocationService.kt:89-116), closing the 1A re-audit LOW gap. onTaskRemoved/onDestroy behavior is unchanged apart from the phase reset. CONFIRMED.

### 4.4 Use cases

- **Start preserves active phases**: if startForegroundService throws while the store is Starting/Running/Degraded/Stopping, StartMockUseCase only logs and does not emit (StartMockUseCase.kt:26-47). CONFIRMED.
- **Stop preserves active phases**: the same guard uses isSessionActive and does not overwrite Running/Degraded (StopMockUseCase.kt:19-41). CONFIRMED.
- **No Error overwrite of an active session by the use cases**: CONFIRMED. The service can still overwrite on a duplicate start (F4), but that is the service path, not the use case.
- **terminal Error semantics**: the use cases only emit when the session is not active; a terminal Error remains terminal; Idle can be turned into Error only by the StopMockUseCase path (F7). CONFIRMED with that deviation.
- **Neither use case advances the phase**: CONFIRMED; they only start the service or log.

### 4.5 UI

- **canStartSession and canStop**: exact match to design 3.5 (MainUiState.kt:49-60). canStartSession requires canStart + permission + canMock + (Idle or terminal Error); canStop is Starting/Running/Degraded/Stopping. MainUiStateTest covers every branch including non-terminal Error (171-247). CONFIRMED.
- **StatusHero and StatusPill phase-driven**: both use SessionState exhaustively (StatusHero.kt:40-87; StatusPill.kt:35-65). The Hero title/point/reason/timer follow the phase; the pill label and dot follow it. Stopping has a label and muted tone. CONFIRMED.
- **Degraded tone**: the pill uses danger, the Hero uses normal ink/muted (F8). The design says Degraded is still running; this is the main UI semantic concern. PLAUSIBLE/LOW.
- **Timer**: rememberElapsedLabel consumes only sessionEpochElapsedMs (Motion.kt:79-96); the ViewModel copies the session verbatim and does not infer a start time (MainViewModel.kt:252-258); previews use a fixed inspection label. CONFIRMED.
- **Previews**: MainScreenPreviews and ComponentPreviews compile and construct SessionState values (MainScreenPreviews.kt:55-88; ComponentPreviews.kt:31-35). They cover Idle, Running and Error only; Starting, Degraded and Stopping are not previewed. This is a low coverage gap, not a compile issue.
- **Strings**: all reason, status, notification and tile keys exist in zh and en (verified by grep). The old service_missing_* and error_* strings are removed and have no remaining references. CONFIRMED.
- **Layout/token regressions**: no token or spacing changes were introduced by B-1. The longer Starting/Stopping/Degraded labels add width pressure to the unweighted top-bar row at 320dp and 1.3x; no screenshot test exists (F12, needs-device/screenshot).

### 4.6 Tests

- Counts are correct: 70 core:location, 34 app, 153 total, all 0 failures/errors from the fresh XML. The module sums are consistent with the new test files.
- The new tests are meaningful: static single emission, phase order, Degraded epoch, terminal Error fields, previous-teardown refusal, store order/late collector, and the full MainUiState gate matrix. The earlier fused stop-failure and lease release-retry tests are present and meaningful.
- Gaps and the weak epoch test are F13. The biggest gap is the absence of service/tile/notification tests, explicitly deferred to B-2; the design section 8 expects them before the Phase 1 gate.

### 4.7 New bugs and regressions

- No MockStatus type, alias or store property remains; grep finds only the MockStatusStore class name and historical comments. No dead code from the old status model. CONFIRMED.
- No stale R.string references; no missing reason/status keys; Hilt AppModule/MockErrorStrings removal is complete and the graph compiles (app tests/build). CONFIRMED.
- No new deadlock was found. The store is a StateFlow; the runner has no new locks; the service guard is a leaf monitor; the earlier lease/startGate/stateLock ordering is unchanged.
- No lost cancellation: the runner still rethrows caller CancellationException from the loop/start; the teardown catch remains scoped to NonCancellable.
- The remaining regressions are F2 (stale notification order), F3 (late Running), F4 (start while active), F6 (finish vs new start), with F1 the high transition gap. None of them leaks a provider; F4 can restart or stop an active session, and F1 can leave the UI stuck.

### 4.8 Deferred B-2 items

CONFIRMED as genuinely deferred and not silently dropped: SavedStateHandle, idle spec sync, favorites retry/id sort/save-name, and Robolectric service tests are all absent and explicitly listed for B-2 (F15). The B-1 code and tests do not claim them.

## 5. Residual risks and needs-device

- F1: a hung teardown leaves Stopping indefinitely; the binder behavior and the correct gate for a TEARDOWN_TIMEOUT state need a design decision and a device test.
- F2: the stale notification flash depends on dispatch timing; the fix is deterministic, but the OEM notification behavior is needs-device.
- F3: the stop-during-push window is timing dependent; a virtual-time test can make it deterministic.
- F4: the double-tap/tile race is timing dependent; the service check makes it deterministic.
- F6: the finish-vs-start race is timing dependent and needs a service test.
- F8/F12: Degraded color and 320dp width need a screenshot review (Phase 3 for the width).
- F10: notification persistence across OEMs is needs-device.
- Release build, final lint after the last edit, and the two-OEM dumpsys matrix are still owed.

## 6. Recommendation

**BLOCK on F1 (HIGH) until it is fixed or the design is amended.** The design transition matrix requires Stopping -> Error(TEARDOWN_TIMEOUT) when the teardown budget expires, and the implementation only emits that error on a later start attempt, leaving a normal stop stuck in Stopping with Start disabled and Stop still enabled. That is a phase-matrix violation and a user-visible trap. The design is itself inconsistent about whether that Error should allow a new start; the coordinator must choose one behavior and record it. If the intended behavior is to keep Stopping until the teardown actually completes, amend design 3.2 and the gate rules, and document the stuck-window behavior; otherwise implement the Error emission and the gate exception.

Also fix the MEDIUM state issues before or together with B-2: F2 (emit Starting before startForeground/observe), F3 (suppress active emissions after stop), F4 (reject a start while isSessionActive at the service and use case), and F6 (make finishSession id-aware). F5 needs a design decision on partial-engine Degraded. The LOW items (F7-F12) can follow in B-2 or a cleanup pass. Add the F13 tests, especially the stop-timeout, late-Running, singleton epoch reset and service/tile tests, and rerun lint after the last edit plus the release build and the device matrix.

The B-1 core cutover itself is sound: the model and store are clean, the static session emits once, the epoch is captured and preserved correctly in the normal paths, the gates match design 3.5, the UI/tile/notification are phase-driven, the reason localization is complete, and the 1A guards survived. Once the phase-matrix gap and the state-order races are closed, proceed to B-2.

---

# Re-audit (fix round)

**Date**: 2026-10-30 (after the B-1 fix round)
**Scope**: F1, F2, F3, F4, F5, F6, F7, F8 and F11 fixes plus regressions and docs/code consistency. Read-only; only this report was updated.
**Method**: re-read the changed B-1 files/tests, the amended design 08, git status/mtimes, and the fresh JUnit XML/lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- Fix-round edits cluster at 02:00-02:09: Models.kt, MockSessionRunner.kt, MockLocationService.kt, Start/StopMockUseCase, MainUiState/ViewModel, tile, StatusPill, strings, the runner/MainUiState tests, the new SessionStateGatesTest and SessionFinishOwnership/Test, and the amended design 08. No UI/model/CI/Gradle changes outside the B-1 set and the design amendment.
- Tests: core:location 73 (5 coordinator, 13 composite, 9 fused, 11 LM, 24 runner, 2 store, 9 lease), app 40 (4 ServiceStartGuard, 2 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 162, 0 failures/errors. Core XML is 02:09:02 after the runner edit (02:08:24); app XML is 02:09:08 after the service/ownership edit (02:08:36).
- Lint at 02:09:51 reports 0 errors and 15 warnings, after the last app edit. Debug APK is at 02:09:07. Release APK/mapping are still 23:30/23:43, before 1A/B-1 and the fix round; release is not rebuilt.
- Design 08 was amended at 02:04 with the TeardownTimeout and partial-engine decisions, but its 3.1 enum surface still differs from the code (G7) and line 687 still says the code fix is in progress.

## R1. Verdicts

| Item | Verdict |
|---|---|
| F1 stopAndJoin / stuck teardown | **CONFIRMED** for the primary behavior: stopAndJoin returns Boolean, publishes non-terminal Error(TeardownTimeout), sets teardownStuck, repeated stop does not re-emit, completion emits Idle, the gates block Start and Stop, and the recovery test is meaningful. **MEDIUM residuals G1/G2/G5** |
| F2 Starting before startForeground | **CONFIRMED fixed** |
| F3 stop-during-push suppression | **CONFIRMED** for the tested Ok push; **PLAUSIBLE/MEDIUM G4** for a failing push after stop |
| F4 start-while-active rejection | **CONFIRMED** for service, use case, ViewModel, tile and UI gates; **MEDIUM residuals G1/G2** in the service refusal paths |
| F5 partial engine start | **CONFIRMED** per the amended design: partial start stays Running with health; Degraded is push-only; code comment matches. Docs/code enum names differ (G7) |
| F6 finish ownership | **CONFIRMED** for published newer states; **PLAUSIBLE/MEDIUM G3** for the Idle and in-flight-start window |
| F7 StopMockUseCase log only | **CONFIRMED fixed** |
| F8 Degraded amber | **CONFIRMED fixed** |
| F11 tile phase-change render and degraded subtitle | **CONFIRMED fixed**; **LOW G6** tile snapshot race |
| Tests | **CONFIRMED** 73/40/162 and meaningful new tests; **MEDIUM** service/concurrency test gaps (R3) |
| Docs/code consistency | **MEDIUM G7**: amended 08 reason enums/types do not match the code; stale in-progress note |

No HIGH item remains. The recommendation is at the end.

## R2. Findings

### G1 - MEDIUM: a refused start can leave a startForegroundService request without startForeground or stopSelf

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:120-135, :450-462.
**Contract**: design 6.3 (service start/gate ordering) and the existing failEarly mitigation (post an error notification or startForeground before stopping).

handleStart calls beginStart, then reads the store; when the phase is active or is the non-terminal teardown window it logs, calls startGuard.finishStart(), and returns. It does not call startForeground and does not call stopSelf. failEarly has a similar defensive branch: when the current phase is active it requests stop/cancels the gate and returns without stopSelf (:455-462). The normal StartMockUseCase, ViewModel and tile checks make this race-only, and an already running session usually means the same service instance is already foreground. But if the service was newly created by startForegroundService (for example the state changed to the non-terminal TeardownTimeout between the use-case check and onStartCommand), the service is left started without a foreground notification. Android 12+ gives a startForegroundService caller five seconds to call startForeground; missing it can raise a RemoteServiceException or ANR. This is the same class of risk the earlier failEarly paths mitigate with a notification plus stopSelf. Needs-device/OEM confirmation.

**Fix**: on every refusal path, either call startForeground before returning or stopSelf (ideally post a non-ongoing error/status notification first, as failEarly does). Ensure the startGuard is finished and no gate is left running.

### G2 - MEDIUM: failEarly checks isSessionActive, not canStartNewSession, so a race can overwrite the teardown-timeout window

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:450-464; core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:139-148.
failEarly refuses only when current.isSessionActive. A non-terminal Error(TeardownTimeout) is not active, so while the top-level canStartNewSession check passes (Idle or terminal Error at that instant), a concurrent old teardown can publish the non-terminal Error; failEarly then overwrites it with a terminal Error such as MissingSpec or PermissionDenied and calls stopSelf. The terminal Error makes canStartNewSession true, while the teardownStuck flag still refuses every start in the runner; the user sees Start enabled but each attempt produces another TeardownTimeout. The top check prevents the common case, but the defensive branch is incomplete.

**Fix**: in failEarly, refuse to emit when current.canStartNewSession is false (not just when isSessionActive), log, and stop the service per G1. Add a test for a preflight failure racing a teardown-timeout publication.

### G3 - MEDIUM/PLAUSIBLE: finish ownership treats bare Idle as owned, so an in-flight new start can still be stopped

**Files**: app/src/main/java/com/snake/mobilespoofing/service/SessionFinishOwnership.kt:20-33; app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:120-163, :299-318; app/src/test/kotlin/com/snake/mobilespoofing/service/SessionFinishOwnershipTest.kt:25-78.
The helper returns true for SessionState.Idle, for Error(null), and for any non-terminal TeardownTimeout. That correctly protects the final state of the runner and a newer session that has already published Starting/Running/Degraded/Stopping (the test covers those). It does not distinguish Idle as the old final state from Idle because a new start has begun but not yet published Starting. handleStart can begin (beginStart, preflight) while the store is still Idle; the old runner completion then reads Idle, calls finishSessionInternal, and cancels the notification collector or calls stopSelf under the new start. The check and the finish actions are also a snapshot: a new start can publish Starting immediately after the check. The startGuard is not consulted. The window is narrow and the user can retry, but a newer session can still be torn down, which is the exact F6 race.

**Fix**: make the finish decision aware of an in-flight start (startGuard) or a service generation; do not treat Idle as owned while startGuard holds a start window. Add a service-level test or at least a helper test that includes the in-flight-start input. The pure helper test is meaningful for published states but does not prove the service call-site race.


### G4 - MEDIUM/PLAUSIBLE: a failing push after stop can publish a terminal Error after Stopping

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:366-432, :460-469, :501-507; core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunnerTest.kt:293-330.
The stopping flag suppresses Running and Degraded after stop, but Error is deliberately allowed. The new stop-during-push test completes an Ok push under NonCancellable and proves no Running reappears; it does not cover an AllRejected push. If the in-flight push is the fifth consecutive failure (or an AppOp, source or unexpected exception fires after the stop), report(Error) is emitted after Stopping. The session then shows a terminal Error (canStart true) instead of Idle or the teardown-timeout window, and the stop outcome is lost. The design says Idle and Error are final outcomes, but a push failure is not a teardown outcome; the matrix has Stopping going to Idle or TeardownTimeout, not to a push-failure Error.

**Fix**: decide whether all Error publications after stop should be suppressed except the teardown-timeout path; if so, gate report on the stop state for terminal session failures too, or check stopping before the terminal failure branches. Add a failing-push-after-stop test.

### G5 - LOW/MEDIUM: the stuck-teardown resolver is not id-safe, and stopAndJoin can overwrite a terminal Error

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:231-253, :296-313.
onStuckTeardownFinished clears teardownStuck before it reads the state and emits Idle. A direct runner start that sees the cleared flag can emit Starting between the read and the Idle emission, so the resolver can clobber a newer Starting. Production service, use-case and UI gates keep the store in the non-terminal Error until the Idle emission, so this is a direct-caller window, but the runner is documented as the fallback authority. The resolver also has no session id or generation token and can be invoked twice (the immediate isCompleted check plus invokeOnCompletion), increasing the window.

stopAndJoin reports a non-terminal TeardownTimeout whenever isTeardownTimeoutReported is false, even if the current state is a terminal Error from a failed session. The stop path therefore overwrites the failure reason with TeardownTimeout; when the job completes, the resolver emits Idle because the state is the non-terminal Timeout, so the original failure is forgotten. The comment in onStuck says a terminal Error is preserved, but the stopAndJoin overwrite usually prevents that. The design decision says the teardown completion emits Idle, so this may be intended for the timeout window, but it conflicts with terminal-Error preservation and loses the original reason.

**Fix**: capture the stuck session id and only resolve if the state still belongs to that teardown window; clear the flag after the Idle emission or use an atomic transition. In stopAndJoin, skip the TeardownTimeout report when the current state is a terminal Error, or document that the timeout reason supersedes it.

### G6 - LOW: the tile can miss a phase change between the refresh in onStartListening and the lastRenderedPhase snapshot

**File**: app/src/main/java/com/snake/mobilespoofing/qs/MockLocationTileService.kt:58-75.
onStartListening calls refreshTile(), which reads the store, then reads statusStore.state.value.phase again to seed lastRenderedPhase. If the phase changes between the two reads, the tile rendered the old phase but records the new one, so the collector initial emission is considered already rendered and the tile stays stale until the next phase change. The service requestListeningState may restart the listener on some changes, but not all. This is narrow; take one snapshot for the render and the seed, or start the collector and render the initial emission.

### G7 - MEDIUM (docs/code): the reason enum surface of the amended design does not match the code

**Files**: docs/optimization/2026-10-29/08-phase1-design.md:61-76, :108, :457, :687; core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:39-62, :96-104, :118-124; app/src/main/java/com/snake/mobilespoofing/SessionReasonText.kt:16-37.
The amended design declares SessionErrorReason with StartFailed, SourceFailed, PushRejected, Interrupted, PermissionRevoked, ProviderConflict, EngineTimeout, TeardownTimeout, ServiceStartFailed, ServiceStopFailed and MissingSpec, and uses SessionErrorReason as the Degraded reason. The code has a separate SessionDegradedReason (PushRejected, PermissionRevoked, ProviderConflict, EngineTimeout) and a SessionErrorReason with MissingSpec, PermissionDenied, MockAppNotSelected, EngineStartFailed, SourceInvalid, TooManyConsecutiveFailures, ServiceStartFailed, TeardownTimeout and Unknown. The UI mapping and zh/en strings follow the code, so the runtime is coherent, but the design contract is not the code: SourceFailed vs SourceInvalid, StartFailed vs EngineStartFailed, PushRejected and Interrupted are in a different type, ServiceStopFailed is absent, and PermissionDenied, TooManyConsecutiveFailures and Unknown are not in the design. Design 6.2 says SourceFailed while the runner emits SourceInvalid (MockSessionRunner.kt:390). Design line 687 also says the code fix is in progress, which is stale after this round.

**Fix**: align one side before B-2. If the split enum in the code is the intended model, update 08 section 3.1/6.2 and the coordinator decisions to the code names and remove the stale in-progress sentence. If the unified enum is intended, update the code and all mappings/tests. Do not leave the contract and the compiler-facing names divergent.

### G8 - LOW: the teardown-timeout reason may only be visible in the app, not in a persistent notification

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:290-341, :396-430; app/src/main/java/com/snake/mobilespoofing/qs/MockLocationTileService.kt:157-178.
The non-terminal TeardownTimeout Error is mapped to notification_error by the collector, but handleStop, onTaskRemoved and onDestroy call finishSession after stopAndJoin, which cancels the collector and calls stopForeground(STOP_FOREGROUND_REMOVE). The tile maps Error to inactive/idle. A user who is not looking at the app may see only a brief error and then an inactive tile while Start remains blocked until the stuck teardown completes. The app UI shows the reason. This is the prior F10 UX gap; consider posting a non-ongoing notification for the teardown window or a distinct tile subtitle.


## R3. Tests

The counts are correct and fresh: 73 core:location (5 coordinator, 13 composite, 9 fused, 11 LM, 24 runner, 2 store, 9 lease) and 40 app (4 ServiceStartGuard, 2 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), plus the older data/geo 2/47; total 162, 0 failures/errors. Core XML is 02:09:02 after the runner edit; app XML is 02:09:08 after the service/ownership edit; lint is 02:09:51 with 0 errors and 15 warnings. Debug APK is 02:09:07; release APK/mapping are still 23:30/23:43 and were not rebuilt.

Meaningful new tests (verified, not tautological):

- Stop-timeout and recovery: stopAndJoin false, non-terminal Error(TeardownTimeout), isSessionActive false, canStartNewSession false, repeated stop does not re-emit, recovery to Idle after the teardown completes, and the same runner accepts a new session (MockSessionRunnerTest.kt:330-370). This is the primary F1 evidence.
- Stop-during-push: a NonCancellable push genuinely returns Ok after stop, and the emissions are Idle, Starting, Stopping, Idle with no Running (MockSessionRunnerTest.kt:293-330). The engine flag proves the push really completed, so the test is not a no-op.
- Stuck previous teardown refusal: the state is non-terminal TeardownTimeout, canStartNewSession false, startCount stays 1, and later starts are refused (MockSessionRunnerTest.kt:592-650). Meaningful.
- nextSessionId matches the next Starting id (247-260); the phase test asserts one session id and one epoch across Starting, Running and Stopping (240-273); the same-runner epoch reset test now uses MutablePushEngine instead of a second runner (673-710), closing the earlier weak test.
- SessionStateGatesTest covers Idle/terminal start, every active phase blocking start and offering stop, and the non-terminal Timeout blocking both (3 tests). SessionFinishOwnershipTest covers Idle, matching terminal/non-terminal errors, Error(null), and newer published session ids (2 tests). MainUiStateTest adds the non-terminal teardown gate case.
- The F1/F6 helper tests are pure and meaningful for the states they cover, but they do not exercise the service call-site races.

Remaining gaps:

1. No service, tile or ViewModel integration/Robolectric test (still deferred to B-2). G1/G2/G3 are service call-site behaviors that the pure helper cannot prove.
2. No test for a failing push after stop (G4); the stop-during-push test uses an Ok push.
3. No test for the stuck-teardown resolver racing a direct new start (G5).
4. No test for stopAndJoin timing out after a session already reached a terminal Error (G5 overwrite).
5. No test for a start refusal on a newly created startForegroundService instance (G1 FGS deadline).
6. No tile onStartListening snapshot race test (G6).
7. Release is not rebuilt; the final release verification remains owed.

## R4. Residual risks and needs-device

- G1: the five-second startForegroundService deadline on a refused start is an OS/OEM behavior; the normal gates make it a race, but the crash consequence is real (needs-device/OEM).
- F1 completion and F6 ownership races are narrow and require a direct start or a start that has begun but not published Starting; they need a service test, not a device.
- The teardown binder can hang indefinitely; the non-terminal Error and stuck flag are the intended recovery, but whether the completion callback always fires is needs-device.
- The teardown-timeout notification removal (G8) and the FGS behavior are OEM-visible.
- Release APK/mapping and the two-OEM dumpsys matrix are still owed.

## R5. Recommendation

**No HIGH item remains. PROCEED to B-2.**

The primary F1, F2, F3 (Ok push), F4 (gates), F5, F6 (published states), F7, F8 and F11 fixes are confirmed by code and the new tests; the stop-timeout recovery and stop-during-push tests are exactly the missing evidence from the first B-1 audit. The remaining items are MEDIUM edge/contract issues: G1 (refusal path without startForeground or stopSelf), G2 (failEarly should use canStartNewSession, not isSessionActive), G3 (finish ownership vs an in-flight start), G4 (failing push after stop can publish a terminal Error), and G7 (docs/code reason enums and the stale in-progress note). G5, G6 and G8 are LOW/MEDIUM residuals.

Recommended handling before or at the start of B-2:

1. G1: call startForeground or stopSelf (with a notification) on every refusal path; this removes the FGS crash window.
2. G2: change failEarly to use canStartNewSession, so the non-terminal teardown window cannot be overwritten.
3. G3: include startGuard or a service generation in the finish decision; do not treat Idle as owned while a start is in flight.
4. G4: suppress terminal session Errors after stop (except the teardown-timeout path), or decide and document; add a failing-push-after-stop test.
5. G7: align 08 section 3.1/6.2 and the coordinator decisions with the code enum names (or the code with the design) and remove the stale in-progress sentence.
6. Add the missing tests in R3; rebuild release and rerun the final lint/device matrix before the Phase 1 gate.

If the coordinator treats the G1 five-second deadline as blocking, fix G1/G2 before starting B-2; otherwise B-2 can begin in parallel. The B-1 state model, epoch, gates, static single-emission behavior, phase-driven UI/tile/notification and the previous 1A guards are confirmed.

---

# Re-audit (G1-G6)

**Date**: 2026-10-30 (after the G1-G6 fix round)
**Scope**: G1-G7 fixes, the new ServiceStartGuard/SessionFinishOwnership and runner token/stateLock changes, the amended design, and any new HIGH/MEDIUM.
**Method**: read-only review of the changed files and tests, git status/mtimes, and fresh JUnit XML and lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- G1-G6 edits are at 02:29-02:39: MockSessionRunner.kt, ServiceStartGuard.kt and its test, SessionFinishOwnership.kt and its test, MockLocationTileService.kt, MockSessionRunnerTest.kt, Models.kt (KDoc), MockLocationService.kt. Design 08 was aligned at 02:31. The audit report is the only other file touched.
- Tests: core:location 75 (5 coordinator, 13 composite, 9 fused, 11 LM, 26 runner, 2 store, 9 lease), app 42 (5 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 166, 0 failures/errors. Core XML is 02:34:58 after the runner/test edits; app XML is 02:40:09 after the service edit (02:39:39).
- Lint at 02:40:54 reports 0 errors and 15 warnings, after the last edits. Debug APK is 02:40:08. Release APK/mapping are still 23:30/23:43 and were not rebuilt.

## R1. Verdicts

| Item | Verdict |
|---|---|
| G1 startForeground obligation on refusal | **PLAUSIBLE/MEDIUM remaining**: all canStartNewSession/failEarly refusals use refuseStart, but the beginStart=false branch still returns without startForeground or stopSelf (B1-R1) |
| G2 failEarly non-terminal preservation | **CONFIRMED fixed** |
| G3 start/finish serialization and ownership | **CONFIRMED fixed**; lock order and no suspend/binder inside the lock verified |
| G4 stop suppression and failurePublished | **CONFIRMED fixed**; failing-push-after-stop test is genuine |
| G5 token/stateLock and terminal precedence | **CONFIRMED** for sequential generation and terminal precedence; **PLAUSIBLE/LOW** token increment atomicity and terminal-Error gate mismatch (B1-R3/B1-R4) |
| G6 tile snapshot seeding | **CONFIRMED fixed** |
| G7 docs/code enum alignment | **CONFIRMED fixed**; enum names/types match, stale in-progress sentence removed; one completion-semantics docs note remains (B1-R4) |
| Tests | **CONFIRMED** 75/42/166 and meaningful new tests; **MEDIUM** gaps for B1-H1 and service integration |
| New safety review | **HIGH B1-H1**: cancelling a start that is waiting for a stuck previous teardown orphans that teardown; a later start can overlap it |

Recommendation: **BLOCK on B1-H1** until fixed; the G2/G3/G4/G6/G7 fixes are confirmed. Details below.

## R2. Findings

### B1-H1 - HIGH: cancelling a start that is waiting for a stuck previous teardown orphans that teardown

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:141-162, :203-213, :225-241, :303-311.
This is the one remaining high-risk gap in the F1 design. The scenario:

1. Session A ends (for example, five push failures) and publishes a terminal Error, so canStartNewSession is true. Its NonCancellable teardown then hangs; A remains an active, cancelled job inside the runner.
2. The user starts B. start() stores previous = A, sets job = B, emits Starting and launches B. B has not run the engine yet; it is inside awaitPrevious, joining A with the three-second budget (:307).
3. The user stops B before that budget expires (B is in Starting, so Stop is offered). stop() sets stopping and cancels B (:225-241). The join in awaitPrevious is cancelled and propagates out of B.
4. B reaches the start-wrapper finally with ranSession false and errorReported false. It clears the timestamps, emits Idle (:211) and calls onFinished (:212). It never inspects previous (A), never calls markTeardownStuck, and leaves job = B (completed).
5. The store is now Idle, so the user starts C. C joins job B (already complete), sees teardownStuck false, and launches. A is still running its NonCancellable teardown in the cancelled service scope and is no longer referenced by job or by any completion handler.

A and C are now touching the same singleton LocationManager engine and the same global providers. A can remove or disable C providers and can release the lease for them. This is the exact old-teardown-unregisters-new-providers failure that the bounded previous.join and the stuck-teardown flag were introduced to prevent. The existing tests (MockSessionRunnerTest.kt:592-650 and 772-820) let B time out, so they set the flag; none cancel B before the timeout. The cancelling-during-start test is a first session, not a waiter with a stuck previous.

**Fix**: in the start-wrapper finally, when !ranSession and the previous job is still active, treat it as a stuck teardown instead of Idle. Either call markTeardownStuck(previous) (reusing the token/handler machinery), publish a non-terminal Error(TeardownTimeout, previousSessionId), and keep Start blocked until A completes, or retain a pendingTeardown job reference that every later start must join. Do not clear to Idle while a previous teardown is still running. Add a test: A stop hangs, start B, cancel B before the three-second budget, assert the state is the non-terminal Timeout, a new start is refused and no engine start for C occurs, and A completion resolves to Idle.

### B1-R1 - MEDIUM: the beginStart=false refusal still returns without ending the startForegroundService obligation

**File**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:147-160.
The synchronized start-decision block calls startGuard.beginStart() and, when it is false, uses a non-local return from handleStart. That path does not call refuseStart and does not call stopSelf. The other refusals (the canStartNewSession or finishing branch and failEarly) now call refuseStart, which checks foregroundStarted and either returns because the instance is already foreground or posts a non-ongoing notification and calls stopSelf. The beginStart=false path is the one exception. It occurs when a start is already in flight or when the service instance has a stop request. The start-in-flight case usually means the instance is already foreground (the earlier start reached startForeground), so returning is harmless. The stopping case can occur on a service instance that is being torn down after a stop, with foregroundStarted already false; a new startForegroundService intent delivered in that window just returns, leaving the five-second obligation unsatisfied. That is the same RemoteServiceException/ANR risk G1 was meant to close.

**Fix**: handle beginStart=false without touching the guard (do not finish an in-flight start) but still end the service obligation: if !foregroundStarted, post a status/error notification and stopSelf, or restructure the branch to use a refuseStart variant that does not call requestStop/finishStart. Add a service-level test for a start arriving during the stopping window.


### B1-R3 - LOW/MEDIUM: the stuck-teardown token increment is not atomic

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:88-95, :320-325, :336-347.
stuckTeardownToken is @Volatile, but markTeardownStuck performs a plain ++ on it. awaitPrevious runs on the runner dispatcher and stopAndJoin runs on the service scope, so two timeout detections can call markTeardownStuck concurrently. A volatile read-modify-write is not atomic: two calls can receive the same token, and a handler captured for an older teardown can then compare equal to the current token and act on a newer generation. The stateLock correctly serializes the resolver read/emit with a new start Starting emission, and the sequential tests pass, but the generation identity itself is not guaranteed under concurrent marks. Use AtomicLong.incrementAndGet or synchronize markTeardownStuck and the token check. Add a concurrency-oriented test or at least an invariant note; this is a narrow race, not a blocker.

### B1-R4 - LOW: a terminal Error kept during a stuck teardown still passes the start gate while the runner refuses

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:259-278, :336-347; core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt:147-148; app/src/test/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunnerTest.kt:468-520.
When a failed session already published a terminal Error and its teardown then times out, stopAndJoin preserves the terminal Error and sets teardownStuck. The test asserts afterStop.canStartNewSession is true and only starts a new session after the cleanup completes. The UI, service and use case gates use canStartNewSession, so Start is enabled during the stuck window; the runner awaitPrevious sees teardownStuck and refuses with another non-terminal Timeout. The first Start attempt is therefore a wasted action that stops the service and replaces the terminal Error with the timeout window. The design says the stuck flag refuses new starts; the gate cannot see the flag. Either expose the stuck state to the gate (so Start is disabled for the whole window) or make the terminal Error non-startable while the flag is set; add a test for a Start attempt during the stuck window.

Also, design 08 line 695 and the 3.2 completion row say the stuck teardown completes to Idle, while the code and the new test deliberately preserve a terminal Error. The code behavior is better for diagnostics, but the design should document the terminal-Error precedence; otherwise this is a docs/code mismatch.

### Confirmed G2-G7

- **G2 CONFIRMED**: failEarly uses canStartNewSession (MockLocationService.kt:499-520); a non-terminal TeardownTimeout goes to refuseStart and is not overwritten. The earlier active-phase test is now canStartNewSession-based.
- **G3 CONFIRMED**: ServiceStartGuard.isStartInFlight (ServiceStartGuard.kt:52-60); finishStillOwnsSession treats Idle as not owned while a start is in flight (SessionFinishOwnership.kt:23-40); the service serializes the start decision and the finishing ownership under startDecisionLock (MockLocationService.kt:147-160, :329-344), sets finishing before teardown, and takes the guard lock inside the decision lock only. The guard never takes the decision lock, so there is no inversion. The critical section is non-suspend and contains no binder call; the lock order is consistent. The updated ServiceStartGuardTest and SessionFinishOwnershipTest cover the new predicates.
- **G4 CONFIRMED**: report suppresses Running, Degraded and terminal Error while stopping, and allows Idle, Stopping and non-terminal TeardownTimeout (MockSessionRunner.kt:559-575). failurePublished plus the finally emits Idle for a failure that was suppressed after stop, so Stopping cannot be stranded (:531-543). The FailingPushAfterStopEngine test genuinely completes the fifth push after stop and asserts no Error and a final Idle (MockSessionRunnerTest.kt:434-465).
- **G5 CONFIRMED**: generation token, stateLock and the completion resolver make sequential stale completions safe; onStuckTeardownFinished reads the state before clearing the flag and emits Idle under stateLock, and the start Starting emission uses the same lock (MockSessionRunner.kt:88-103, :320-347). stopAndJoin preserves a terminal Error (259-278), and the test covers it (468-520). The residual issues are R3 (token atomicity) and R4 (terminal gate/docs), not the core behavior.
- **G6 CONFIRMED**: refreshTile renders one snapshot and returns the phase it rendered (MockLocationTileService.kt:157-180); onStartListening seeds lastRenderedPhase from that return value and the collector refreshes with the returned phase, so the two-read stale window is closed.
- **G7 CONFIRMED**: design 08 section 3.1 now uses SessionDegradedReason and the exact code SessionErrorReason names, and the stale in-progress sentence is gone; 08 section 6.2 now says SourceInvalid. The enum and type surfaces match Models.kt.


## R3. Tests

- Counts are correct and fresh: core:location 75 (5 coordinator, 13 composite, 9 fused, 11 LM, 26 runner, 2 store, 9 lease), app 42 (5 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 166, 0 failures/errors. Core XML is 02:34:58 after the runner/test edits; app XML is 02:40:09 after the service edit. Lint at 02:40:54 is 0 errors and 15 warnings. Debug APK is 02:40:08; release APK/mapping are still 23:30/23:43 and were not rebuilt.
- Meaningful new tests:
  - The fifth failing push that returns after stop genuinely blocks in the fake, is released after stop, sets fifthPushReturned, and the state ends Idle with no Error (MockSessionRunnerTest.kt:434-465).
  - stopAndJoin preserves the terminal Error of a failed session, then the stuck flag clears and the same runner accepts a new session after the cleanup (468-520). The state and the engine start count are asserted.
  - ServiceStartGuard.isStartInFlight is tested independently (ServiceStartGuardTest.kt fifth test); finishStillOwnsSession covers Idle with startInFlight true and false, matching and non-matching Error, and newer Starting (SessionFinishOwnershipTest.kt third test). These are meaningful predicates, not pass-through fakes.
  - The G6 tile change is code-reviewed; there is no Robolectric tile test.
- Gaps:
  1. No test for B1-H1: a start waiting for a stuck previous teardown that is cancelled before the budget. This is the main missing safety test.
  2. No service-level test for the beginStart=false refusal and the startForeground obligation (B1-R1); the guard/ownership tests are pure and do not run onStartCommand.
  3. No concurrency test for the token increment (B1-R3); the tests are sequential virtual time.
  4. No test for a Start attempt while the terminal-Error plus stuck-flag window is active (B1-R4).
  5. No service, tile or ViewModel integration/Robolectric test; still deferred to B-2.
  6. Release is not rebuilt.

## R4. Residual risks and needs-device

- B1-H1 is deterministic, not a device-only timing issue: the cancelled waiter leaves the previous job active and untracked, and a later start can overlap its teardown. A virtual-time test can reproduce it.
- B1-R1 depends on the Android service startForegroundService deadline and OEM scheduling; needs-device/OEM confirmation, but the missing stopSelf is visible in code.
- A binder call in the old teardown can still block indefinitely; the stuck flag and the completion resolver are the intended recovery, but whether every engine stop eventually returns is needs-device.
- The release APK/mapping and the two-OEM dumpsys matrix remain owed.

## R5. Recommendation

**BLOCK on B1-H1 until it is fixed.** The G2, G3, G4, G5 (sequential), G6 and G7 fixes are confirmed and the new tests are meaningful, but the cancelled-waiter path can leave a previous NonCancellable teardown running while the next session starts, which is the exact old-teardown-unregisters-new-providers failure Phase 1A exists to prevent. This is a HIGH safety gap, not a cosmetic race.

Required before B-2:

1. Fix B1-H1: in the start-wrapper finally, if !ranSession and previous is still active, mark it as stuck (or retain it as a pending teardown) and publish a non-terminal TeardownTimeout instead of Idle; keep Start blocked until it completes. Add the cancellation-before-budget test.
2. Fix B1-R1: on beginStart=false, if the instance is not foreground, post a status/error notification and stopSelf (without finishing an in-flight start). Add a service-level test if possible.
3. B1-R3: use AtomicLong or synchronize markTeardownStuck and the token check.
4. B1-R4: decide whether the stuck flag should gate Start during the terminal-Error window; expose it or make the state non-startable, and update design 08 completion semantics to match the preserved terminal Error.

After those, proceed to B-2. The state model, gates, epoch, static single emission, phase-driven UI/tile/notification, F2/F3/F4/F5/F6/F7/F8/F11 behavior and the G2-G7 fixes are otherwise confirmed.

---

# Re-audit (H1/R1/R3/R4)

**Date**: 2026-10-30 (after the H1/R1/R3/R4 fix round)
**Scope**: H1 primary and the supersede path, R1 service refusal, R3 token, R4 terminal precedence, docs, tests, and any new HIGH/MEDIUM.
**Method**: read-only review of the changed files/tests and fresh JUnit XML/lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- Fix-round edits are at 02:57-02:59: ServiceStartGuard.kt and its test, MockLocationService.kt, MockSessionRunnerTest.kt, MockSessionRunner.kt. No other source areas changed.
- Tests: core:location 76 (5 coordinator, 13 composite, 9 fused, 11 LM, 27 runner, 2 store, 9 lease), app 43 (6 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 168, 0 failures/errors. Core XML is 02:59:56 after the runner edit (02:59:25); app XML is 03:00:00 after the service/guard edits.
- Lint at 03:00:44 reports 0 errors and 15 warnings. Debug APK is 02:59:59. Release APK/mapping are still 23:30/23:43 and were not rebuilt.

## R1. Verdicts

| Item | Verdict |
|---|---|
| H1 primary (cancelled waiter stops) | **CONFIRMED fixed**; the new test genuinely keeps C from starting the engine until A completes |
| H1 second orphan path (supersede) | **HIGH/PLAUSIBLE B1-H2**: superseding a start that waits for a stuck previous teardown still orphans it; production gates make it a fallback-only path, but the runner is the documented fallback authority |
| R1 beginStart=false obligation | **CONFIRMED** in code; service call-site/Robolectric test remains deferred |
| R3 AtomicLong token | **CONFIRMED fixed**; one LOW mark/onStuck serialization residual remains |
| R4 terminal Error timeout window | **CONFIRMED fixed**; test is meaningful; a stale runner comment/docs note remains LOW |
| Tests | **CONFIRMED** 76/43/168 and meaningful new tests; H2, service integration and token concurrency remain gaps |
| Earlier G2-G7 fixes | **CONFIRMED** no regression; the full suite passes |

Recommendation: **BLOCK on B1-H2** if the runner fallback is in scope (the design says it is); if the coordinator explicitly scopes the runner fallback out, record that and proceed to B-2.

## R2. Findings

### B1-H2 - HIGH (fallback path) / PLAUSIBLE (production): superseding a start that waits for a stuck previous teardown still orphans it

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:140-163, :198-241, :315-323, :332-338; test: MockSessionRunnerTest.kt:508-556.
The H1 fix marks the previous teardown only inside the start-wrapper finally branch if (!ranSession && isCurrent(id)) (:201). That covers B being stopped, because stop() does not change sessionId. It does not cover B being superseded: start(C) increments sessionId first, so when B is cancelled its finally sees isCurrent(B) false and skips the entire pending-teardown block. B completes, and C's awaitPrevious(previous = B) joins B, sees it completed, and returns Joined. C then runs while A (the job B was waiting for) is still in NonCancellable teardown. job is B then C; A is unreferenced; teardownStuck is false. This is the same orphan as H1, reached through a direct runner.start supersede instead of stop.

Reachability: the service and UI reject a start while B is Starting (canStartNewSession false; the service top refuseStart; the tile active path sends stop), so this is the runner fallback or direct-caller path, not a normal service path. But the design says the runner still uses sessionId/join as the fallback, and start() is public; a future caller, a test host, or a service race would hit it. If the fallback is in scope, this is a HIGH safety gap because A can remove or release C providers. If the coordinator explicitly scopes the fallback out, record that as a known limitation.

**Fix**: track a pendingTeardown job independently of the current session, so a new start always joins the oldest unresolved teardown (A) before starting; or have B finalization mark A even when B is superseded (without publishing state that would clobber C) and have awaitPrevious re-check teardownStuck after joining B. Add a test: A hangs, B starts, C starts (direct supersede) before A completes, assert C does not start the engine until A completes, then starts. The current H1 test only stops B.

### B1-R1 - CONFIRMED in code: beginStart=false now ends the startForeground obligation when appropriate

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:147-178; ServiceStartGuard.kt:62-70; ServiceStartGuardTest.kt.
The beginStart=false branch no longer uses a non-local return. It records refusedWithoutForeground = !foregroundStarted && startGuard.isStopping under the decision lock, then, outside the lock, posts a non-ongoing notification, refreshes the tile and calls stopSelf when true. An in-flight first start (stopping false) is left alone to call startForeground itself. The in-flight case is safe because the main thread runs the first handleStart synchronously through startForeground before a duplicate intent is processed. The stopping-and-not-foreground case ends the five-second obligation. The pure test covers isStopping; the service call site (notification text and stopSelf) still has no Robolectric test, which remains deferred.

Potential minor concern: the beginStart=false notification uses the generic notification_stopping text even when the refusal is a duplicate on a stopping instance; it is a non-ongoing autoCancel notification on NOTIFY_ERROR_ID, so it updates rather than duplicates. stopSelf idempotent. No new issue found.

### B1-R3 - CONFIRMED: AtomicLong token; one LOW serialization residual

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:88-100, :332-338, :375-388.
stuckTeardownToken is now AtomicLong; mark uses incrementAndGet, AlreadyStuck uses get, and onStuck uses get, so no two marks share a token and no stale handler compares equal to a newer generation. stuckTeardownSessionId is @Volatile. This closes the R3 finding.

Residual (LOW): markTeardownStuck writes stuckTeardownSessionId and teardownStuck and registers the handler outside stateLock, while onStuckTeardownFinished holds stateLock and checks the token only at entry. A new mark can increment after that check while the old handler is inside the lock, and the handler can then set teardownStuck=false and clear stuckTeardownSessionId for the new generation before it emits. The production gates and the short critical section make this narrow, but synchronizing markTeardownStuck on stateLock (or re-checking the token immediately before clearing) would make the generation transition fully atomic. Add a concurrency test if the seam can be exercised.

### B1-R4 - CONFIRMED fixed

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:280-292, :348-365; test: MockSessionRunnerTest.kt:468-505.
stopAndJoin now always marks the current job and calls publishStuckTeardown, so a previously published terminal Error is replaced by the non-terminal TeardownTimeout window. The test asserts the terminal TooManyConsecutiveFailures is replaced by TeardownTimeout, terminal=false, canStartNewSession=false, isSessionActive=false; after the teardown completes the state is Idle and a new session starts (engine startCount 2). The Start-enabled-but-refused loop is gone.

Minor LOW docs/comment note: the runner comment at :370-373 still says a terminal Error left by a failed session is preserved, while stopAndJoin now supersedes it. The comment is stale or describes only an unreachable edge. Design 08 line 695 says the completion emits Idle, which now matches R4; consider adding one sentence that the timeout window supersedes a terminal failure, and update the runner comment.

### Earlier G2-G7 fixes

No regression found. failEarly still uses canStartNewSession and refuseStart (G2); the startDecisionLock/finishing/isStartInFlight ownership path is unchanged (G3); report/failurePublished still suppress post-stop failures and the failing-push test passes (G4); the token/stateLock behavior is the R3 topic above (G5); the tile refresh returns the rendered phase (G6); design 08 still matches the code enum names and the stale in-progress note is gone (G7). The full 168-test suite is green.


## R3. Tests

- Counts are correct and fresh: core:location 76 (5 coordinator, 13 composite, 9 fused, 11 LM, 27 runner, 2 store, 9 lease), app 43 (6 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 168, 0 failures/errors. Core XML is 02:59:56 after the runner edit; app XML is 03:00:00 after the service edit. Lint at 03:00:44 is 0 errors and 15 warnings. Debug APK is 02:59:59; release APK/mapping are still 23:30/23:43 and were not rebuilt.
- The H1 cancellation test is meaningful for the stop path: A fails five pushes and hangs its teardown; B starts and is stopped before the budget; the state is non-terminal TeardownTimeout with sessionId 1 (A), canStartNewSession false; a later C is refused and engine.startCount stays 1; after A releases, the state is Idle and C runs with startCount 2 (MockSessionRunnerTest.kt:508-556). The engine startCount is the provider-registration proxy, so the test asserts more than the state.
- The R4 test is meaningful: the terminal TooManyConsecutiveFailures is replaced by the non-terminal window, canStart and isSessionActive are false, the completion is Idle, and a new session starts (468-505).
- The R1 guard test covers isStopping; the service call site remains code-reviewed only.
- The R3 token has no concurrency test; the suite is deterministic virtual time.
- The earlier B-1 and 1A suites still pass (168, 0 failures), so no regression was found in the state, epoch, gates, F2/F3/F4/F5/F6/F7/F8/F11 or G2-G7 behavior.
- Missing tests: B1-H2 supersede-while-waiting; service/Robolectric startForeground refusal; token mark/onStuck concurrency; release rebuild.

## R4. Residual risks and needs-device

- B1-H2 is deterministic and can be virtual-time tested; the production service/UI gates make it a fallback path, but the runner fallback is part of the design contract.
- B1-R3 is a narrow concurrency window between markTeardownStuck and the resolver; it is code-level, not device-only.
- B1-R1 depends on the Android startForegroundService deadline and OEM scheduling; needs-device/OEM.
- A binder call in the old teardown can still block indefinitely; the stuck window and resolver are the intended recovery, but every engine stop returning is needs-device.
- Release APK/mapping and the two-OEM dumpsys matrix are still owed.

## R5. Recommendation

**BLOCK on B1-H2** unless the coordinator explicitly scopes the runner fallback out. The H1 primary fix (stop of the waiting start), R1, R3, R4, and all earlier G2-G7 fixes are confirmed, but a direct supersede of a start that is waiting for a stuck previous teardown still leaves the previous NonCancellable cleanup orphaned, and the next session can run its engine against providers the old cleanup may still remove or release. This is the same Phase 1A ownership invariant as H1, reached through the sessionId/join fallback path.

Required before B-2 if the fallback is in scope:

1. Track a pendingTeardown job independently of the current session so a new start always joins the oldest unresolved teardown; or mark the previous job in B finalization even when B is superseded (without publishing state that would clobber C) and have awaitPrevious re-check teardownStuck after joining B.
2. Add the supersede-while-waiting test: A hangs, B starts, C supersedes B before A completes, assert C does not start the engine until A completes, then startCount becomes 2.
3. Optionally serialize markTeardownStuck with stateLock (R3 residual) and add a service/Robolectric test for the R1 refusal path.

If the coordinator records that the runner fallback is not a supported production path and accepts the limitation, proceed to B-2 with that explicit note. Otherwise, fix H2 first; the rest of B-1 is otherwise ready.

---

# Re-audit (H2)

**Date**: 2026-10-30 (after the H2 supersede-orphan fix)
**Scope**: pendingTeardownJob/start targets/awaitPrevious/finally/clear, stateLock usage, R3 token serialization, R4 comments, the new H2 test, and any new HIGH/MEDIUM.
**Method**: read-only review of the changed runner and tests plus fresh JUnit XML/lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- H2 edits are at 03:13: MockSessionRunner.kt and MockSessionRunnerTest.kt. No other source or docs areas changed in this round.
- Tests: core:location 77 (5 coordinator, 13 composite, 9 fused, 11 LM, 28 runner, 2 store, 9 lease), app 43 (6 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 169, 0 failures/errors. Core XML is 03:14:44 after the runner edit (03:13:40); app XML is 03:14:48.
- Lint at 03:15:32 reports 0 errors and 15 warnings. Debug APK is 03:14:47. Release APK/mapping are still 23:30/23:43 and were not rebuilt.

## R1. Verdicts

| Item | Verdict |
|---|---|
| H2 supersede orphan | **CONFIRMED fixed**; pending global tracks the oldest unresolved teardown and C cannot start its engine while A runs |
| H1 primary stop orphan | **CONFIRMED** unchanged and still covered |
| R3 token/stateLock | **CONFIRMED fixed**; AtomicLong plus mark/onStuck under stateLock; one LOW lock-scope note below |
| R4 terminal precedence | **CONFIRMED fixed**; comments and design line 695 now match the non-terminal window and Idle completion |
| R1 service refusal | **CONFIRMED** unchanged (code); service/Robolectric test remains deferred |
| StateLock deadlock/lost wakeup | **CONFIRMED** no suspend, no deadlock, reentrant synchronous completion is correct; **LOW** indirect collector/binder scope and one read outside the lock |
| Tests | **CONFIRMED** 77/43/169 and the H2 test is meaningful; C-cancelled, A-never-completes, service/Robolectric and release remain gaps |
| New HIGH/MEDIUM | **REJECTED** none found |

Recommendation: **PROCEED to B-2**. The LOW notes can be handled in B-2 or a cleanup pass; no blocker remains.

## R2. Details

### H2 - CONFIRMED fixed

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:102-122, :160-197, :235-287, :367-393, :412-424, :464-481; test: MockSessionRunnerTest.kt:559-600.

- start() captures the global pending teardown and the immediate previous session under stateLock, filters completed jobs, joins them oldest-first (pending before immediate), de-duplicates by job, and records the immediate previous as the global pending before launching the job (:169-197). A later direct start therefore sees the same A even when every intervening wrapper was superseded.
- awaitPrevious joins all unresolved targets under one three-second budget, re-checks teardownStuck after the join, and on timeout marks the oldest still-unresolved target and returns TimedOut with that target id (:367-393). The H2 test asserts the window names A by sessionId 1, not B or C.
- The start-wrapper finally handles the global pending first, then the immediate previous, then Idle only when neither is unresolved (:235-287). The stale completed reference is cleared at start, after joins, and in onStuck; a completed pending cannot block a later start forever.
- markTeardownStuck and onStuckTeardownFinished write the token, pending job/id, stuck id and flag under stateLock; the token is AtomicLong. R3 is closed. The completion resolver re-enters stateLock when the job is already complete; the JVM monitor is reentrant and the token check keeps the sequence correct. The publish path also checks stuck.isCompleted after reporting, so the completion race cannot leave the Error without a resolver.
- The H2 test is meaningful and would fail on the old path: on the old code C would join B (completed), return Joined, and start the engine, making startCount 2 while A was still hung. The test asserts startCount 1 before A completes, window sessionId 1, non-terminal, canStartNewSession false, then Idle and startCount 2 after A releases. The H1 stop test (508-556) still covers the stop-waiter path.

No orphan was found for stop (H1 test), supersede (H2 test), or a supersede chain: each start captures the oldest global pending job and joins it before running. Note that runner.start is not a concurrent multi-thread API; the service serializes calls on the main thread. Concurrent direct starts from different threads are outside the supported contract and still race on sessionId/job assignment; this is not a B-1/B-2 issue.

### StateLock, deadlock and lost wakeup

- stateLock is used in start (:170), clearPendingTeardownIfCompleted (:397), markTeardownStuck (:413) and onStuckTeardownFinished (:465). No suspend call is made while it is held, and no other lock is taken inside it. The service startDecisionLock/ServiceStartGuard are not held while calling the runner, so there is no inversion with the runner stateLock.
- A synchronous invokeOnCompletion under the monitor re-enters onStuckTeardownFinished on the same thread; Java monitors are reentrant and the token check is satisfied. No deadlock was found.
- No lost wakeup was found: the start records pending before launch, awaitPrevious filters and joins, the timeout path marks and the publish path checks isCompleted, and the handler clears. The H1/H2 tests exercise the completion-after-timeout paths.

Residual LOW notes:

1. start() and onStuckTeardownFinished emit on the StateFlow while holding stateLock. When called on the main thread, a Main.immediate collector can resume inline and perform notification or tile binder work while the monitor is held. No deadlock was observed and the collector paths do not take stateLock, but this is an indirect binder-under-lock latency nuance. Emitting outside the lock with a token re-check would remove it.
2. The start-wrapper finally reads pendingTeardownJob and pendingTeardownSessionId outside stateLock (:241-244). The fields are volatile and the fallback to the immediate previous keeps safety conservative, but the pair can be transiently inconsistent (job from one generation, id from another) and a concurrent mark/clear can change the selection. Moving the pending select and mark under stateLock would make the diagnostic id and the choice fully race-free. No orphan was found in the normal paths.

### R3 and R4

- R3 CONFIRMED: AtomicLong increment/get; mark and onStuck both under stateLock; the token check is inside the lock; stale handlers return. The previous mark/onStuck serialization residual is closed.
- R4 CONFIRMED: stopAndJoin always marks and calls publishStuckTeardown (:326-338), so a terminal Error is replaced by the non-terminal TeardownTimeout window; the test at 468-505 asserts TeardownTimeout, terminal=false, canStartNewSession=false, isSessionActive=false, then Idle and a new session (startCount 2). The runner comments at :430-432 and :456-462 and design 08 line 695 now describe the same precedence. The Start-enabled-but-refused loop is gone.

### R1 and H1 primary

- R1 code is unchanged from the previous round and still posts a notification plus stopSelf for a stopping, non-foreground instance; the in-flight first start is left alone. The service call site remains Robolectric/OEM-deferred.
- H1 primary remains covered by the stop-waiter test (508-556).
- Earlier G2-G7 fixes are unchanged and the full suite is green.


## R3. Tests and residual risks

- Counts are correct and fresh: core:location 77 (5 coordinator, 13 composite, 9 fused, 11 LM, 28 runner, 2 store, 9 lease), app 43 (6 ServiceStartGuard, 3 SessionFinishOwnership, 10 Format, 21 MainUiState, 3 SessionStateGates), data 2, geo 47; total 169, 0 failures/errors. Core XML is 03:14:44 after the runner/test edit; app XML is 03:14:48; lint is 03:15:32 with 0 errors and 15 warnings. Debug APK is 03:14:47; release APK/mapping are still 23:30/23:43 and were not rebuilt.
- The H2 test is meaningful and would fail on the old path: it asserts engine.startCount is still 1 after the three-second budget, the window names A by sessionId, canStartNewSession is false, and only after A releases does the next start reach startCount 2. The engine startCount is the provider-registration proxy, so the test asserts more than a state string.
- The H1 stop-waiter test remains, and the full earlier suite is green, so no regression was found in the state model, epoch, gates, F2/F3/F4/F5/F6/F7/F8/F11, G2-G7 or R1/R3/R4.
- Gaps: no test for C being cancelled as well while A hangs, no test for A never completing (the stuck window should stay non-startable; the code does), no service/tile/Robolectric coverage (still B-2 deferred), and release is not rebuilt.
- LOW residual: stateLock held across StateFlow emission can synchronously run Main.immediate collectors with notification/tile binder work on the main thread; no deadlock, but it is an indirect binder-under-lock latency nuance. Emitting outside the lock with a token re-check would remove it.
- LOW residual: the start-wrapper finally reads pendingTeardownJob and pendingTeardownSessionId outside stateLock; the fallback to the immediate previous keeps safety conservative, but the pair can be transiently inconsistent and the selection can race a concurrent mark/clear. Moving the select and mark under stateLock would make it fully race-free.
- The runner start is not a concurrent multi-thread API; the service serializes start/stop on the main thread. Concurrent direct starts from multiple threads remain outside the supported contract.

## R4. Recommendation

**PROCEED to B-2.** No HIGH or MEDIUM issue remains. The H2 supersede-orphan is fixed with a genuine test; the pending global tracks the oldest unresolved teardown, the start records it before launch, awaitPrevious joins both targets oldest-first and re-checks the stuck flag, the wrapper resolves the pending first, and stale completed references clear on start, join completion and onStuck. R3 and R4 are also confirmed; the earlier G2-G7 and 1A/B-1 behavior remains green.

Handle the two LOW notes in B-2 or a cleanup pass: emit StateFlow outside stateLock (or keep the lock scope to plain fields) and move the wrapper final selection under stateLock. Add the deferred service/tile/Robolectric tests and rebuild the release before the final Phase 1 gate. No further blocking work was found.

---

# B-2 audit

**Date**: 2026-10-30 (B-2: SavedStateHandle, idle spec sync, favorites robustness, notification checklist)
**Scope**: EditorState/SavedStateHandle, SessionSpec/pending-spec sync, favorites retry/DAO order/save callback, notification checklist, Hilt wiring, tests and deferred items.
**Method**: read-only review of the B-2 files/tests plus fresh JUnit XML, lint and compiled androidTest outputs. No Gradle command was run by the auditor.

## B2-0. Fresh evidence

- B-2 edits are at 03:28-03:34: EditorState.kt, SessionSpec.kt, FavoritesRetry.kt, MainViewModel.kt, MainUiState.kt, FavoriteDao.kt, MainScreen.kt, SetupChecklist.kt, strings zh/en, previews, MainActivity.kt; tests EditorStateTest, SessionSpecTest, FavoritesRetryTest, MainUiStateTest; SpoofingDatabaseTest androidTest.
- Tests: app 66 (6 ServiceStartGuard, 3 SessionFinishOwnership, 10 EditorState, 3 FavoritesRetry, 10 Format, 22 MainUiState, 9 SessionSpec, 3 SessionStateGates), core:location 77, data 2, geo 47; total 192, 0 failures/errors. App XML is 03:35:02 after the last main edit (MainActivity 03:34:29); core:data XML is 03:31:45 after the DAO edit; core:location XML is the earlier fresh run (77).
- Lint at 03:35:47 reports 0 errors and 15 warnings. Debug APK is 03:35:01. Release APK/mapping are still 23:30/23:43 and were not rebuilt.
- The room instrumented DAO test was compiled at 03:36 (core/data debugAndroidTest classes) but was not run; there is no connected-device result in this round.

## B2-1. Verdicts

| Item | Verdict |
|---|---|
| EditorState/SavedStateHandle | **CONFIRMED** for encoding, round trip, forgiving decode, live/saved route cap, restore ordering and no session/favorites persistence; **LOW** wrong-type handle is not defended |
| Idle spec sync / SessionSpec | **CONFIRMED** for the pure decision rules; **MEDIUM M1** the ViewModel sync uses the lagging UI state, never re-syncs on a natural session end, and can overwrite a running session during restore |
| Favorites retry/order/save | **CONFIRMED** for DAO order, save-success callback and input retention; **MEDIUM M2** the retry counter resets on every emission, so an emit-then-fail flow can retry forever; **LOW** 2s backoff is unused and the instrumented test is not run |
| Notification checklist | **CONFIRMED** source, row/action, settings fallback, zh/en strings, previews, no start-gate regression and no duplicate dialog; **LOW** app-level notification state does not reflect a disabled channel |
| Hilt/process-death restore | **CONFIRMED** SavedStateHandle wiring and restore-before-collectors; M1 covers the active-session restore edge |
| Tests | **CONFIRMED** 192 and meaningful new tests; gaps: no ViewModel/static loop test, Degraded omitted from the SessionSpec active list, androidTest not run, release old |
| Deferred items | Robolectric service/tile/ViewModel tests and release/device matrix remain deferred; not claimed as done |

No HIGH issue found. Recommendation: **PROCEED to Phase 1C**, with M1 and M2 fixed before the final Phase 1 gate; 1C is independent of both.

## B2-2. Findings

### B2-M1 - MEDIUM: the pending-spec sync uses the lagging UI session, never re-syncs on a natural session end, and can overwrite a running session during restore

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:53-60, :207-212, :257-274, :281-287; app/src/main/java/com/snake/mobilespoofing/ui/SessionSpec.kt:45-50; test: SessionSpecTest.kt:106-118.

The syncPendingSpec(state) helper calls pendingSpecDecision(state), which reads state.session.isSessionActive. The state is _uiState.value, a UI copy that can lag the authoritative statusStore.state.value. Three consequences:

1. **Restore while a session is active.** restoreEditorState() runs in init before the collectors (:53-60) and calls updateEditorState, which calls syncPendingSpec with _uiState.session still the default Idle. If the Activity is finished and reopened in the same process while the service is still Running (the service is a separate foreground component), the new ViewModel restores the editor and either Updates or Clears SessionConfigStore even though a session is active. The design says an active session keeps its captured spec; the running runner source is unaffected, but the pending store that the tile and service use for the next start is overwritten.
2. **No re-sync on a natural end.** observeSession() only copies the session (:281-287). Edits during a Running or Degraded session are Keep, correctly leaving the pending store at the spec captured at start. When the session ends on its own (route complete, five failures, source or AppOp error), the collector updates the UI to Idle or terminal Error but nothing calls syncPendingSpec with the current editor. The pending store therefore keeps the old start spec, and a Quick Settings tile tap after the session starts the abandoned spec rather than the editor the user is looking at. The finished-session test in SessionSpecTest tests only the pure decision, not this ViewModel transition.
3. **Starting-window lag.** An edit made immediately after start, before the session collector has delivered Starting, sees Idle in the UI copy and Updates the pending spec while the session is already active. The runner source is captured, so the session is safe, but the Keep rule is violated and the next start can differ from the one the user believes is running.

**Fix**: base the sync decision on statusStore.state.value (the authoritative phase) and call syncPendingSpec(_uiState.value) from observeSession on the transition to a startable state (Idle or terminal Error), while skipping active and non-terminal teardown phases. Decide how this interacts with the explicit stopMock clear (:207-212): syncing on the Idle transition after an explicit stop would undo the clear, so either keep a stop flag and skip that transition, or make the idle editor the source of truth always and drop the clear. Add a ViewModel-level test, or a pure helper that takes the store phase and the editor snapshot, for the restore-active and natural-end transitions.

### B2-M2 - MEDIUM: the favorites retry counter resets on every emission, so an emit-then-fail flow can retry forever

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:296-328; app/src/main/java/com/snake/mobilespoofing/ui/FavoritesRetry.kt:11-19; test: FavoritesRetryTest.kt:10-26.

observeFavorites sets failedAttempts = 0 inside the collect block on every emitted list (:301-303). If a subscription emits at least one list and then throws (a corrupt or partially readable database, or a query that fails on a later invalidation), the catch increments to 1, waits, re-subscribes, receives the same initial emission (resetting to 0), and fails again. The loop never reaches MAX_ATTEMPTS, never clears the list, and can repeat the failure message indefinitely. The design promises a bounded three-attempt retry and a final clear and message. The unit test only exercises the backoff schedule; it does not run the ViewModel loop.

**Fix**: count consecutive failed subscriptions without resetting on each emission (reset only after a sustained successful subscription, or after a successful query has been stable for a chosen interval), or cap the total retries per ViewModel lifetime. Add a fake flow that emits then throws and assert the retry stops, the list clears and the final message is shown.

**Minor**: MAX_ATTEMPTS = 3 with the current loop uses delays 500ms and 1s; backoffMs(3) (2s) is never used. Either use four attempts for the 500/1s/2s schedule or adjust the report and test wording.


### B2-M3 - LOW: stopMock clears the pending spec before the stop is confirmed

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:207-212; app/src/main/java/com/snake/mobilespoofing/domain/StopMockUseCase.kt:14-27.

stopMock() clears SessionConfigStore and then calls the use case. StopMockUseCase only logs if startService throws; it cannot report success. If the stop is not delivered (background-start restriction or an OEM failure), the running session continues with its captured source, but the pending spec is gone. When that session later ends, a tile tap sees pending null and opens the app instead of restarting. The clear is intentional for the explicit-stop case, but it should happen only after the stop is known to be delivered, or the idle sync should restore the editor spec on the subsequent Idle transition (see M1). This is a low-frequency path because the UI is foreground, but it is a pending/session mismatch.

### LOW notes

1. **SavedStateHandle type safety**: readEditorSnapshot uses typed handle reads. The forgiving decode handles unknown names, NaN, out-of-range and malformed arrays, but a value stored under a key with a different type (for example an older or future app version) can throw ClassCastException during restore. Read as Any? and type-check if cross-version saved state is a concern.
2. **Notification state is app-level**: NotificationManagerCompat.areNotificationsEnabled() does not tell whether the mock-location channel itself is disabled. The checklist can show allowed while the foreground notification is channel-blocked. Consider checking the channel importance or block state for the service channel; needs-device/OEM confirmation.
3. **SessionSpecTest active list omits Degraded**: the test named every active phase lists Starting, Running and Stopping but not Degraded; the code uses isSessionActive, so Degraded is Keep, but the test should include it.
4. **EditorStateTest** does not cover a NaN or Infinity selected point or a wrong-type handle; the code isValidCoordinate handles NaN and Infinity, but the test only covers out-of-range.
5. **Instrumented DAO test not run**: the tie-break test was compiled at 03:36 but not executed; the SQL order is code-reviewed, not runtime-verified.
6. **Release not rebuilt**: release APK/mapping remain from 23:30/23:43.

## B2-3. Confirmations

- **EditorState/SavedStateHandle**: CONFIRMED. Keys are editor.mode, editor.travelSpeed, editor.endBehavior (enum names), editor.selectedLatitude/Longitude (Double) and editor.route (flat DoubleArray). The round trip preserves all five editor fields; an empty handle returns defaults; a removed point removes both coordinate keys; unknown enum names fall back; an out-of-range point is discarded; the route is flat WGS84 pairs, capped at 500 on encode and decode, and an odd trailing value or invalid pair is ignored. The live editor enforces the same cap through appendedWaypoint (MainViewModel.kt:334-339). Restore runs before the session, favorites and capability collectors; capability updates use copy and do not touch the editor; session and favorites are not in the snapshot and the snapshot test asserts that.
- **SessionSpec**: CONFIRMED for the pure rules. pendingSpecDecision keeps a live session (Starting, Running, Stopping and, through isSessionActive, Degraded) and Updates or Clears when idle; terminal and non-terminal errors sync the editor choice; closedLoop equals EndBehavior.LOOP. The test covers static, route, too-short, live, terminal, non-terminal, closed and non-closed plus buildMovementSpec. The ViewModel integration gap is M1.
- **Favorites**: CONFIRMED for DAO order, save callback and input retention. FavoriteDao orders createdAt DESC, id DESC; the entity createdAt defaults to the current wall clock; the instrumented tie-break test exists. MainActions.onSaveFavorite now takes a success callback, MainScreen clears favoriteName only in that callback, and MainViewModel invokes it only after repository.save succeeds; failure keeps the input and sets a message. The retry loop gap is M2.
- **Notification**: CONFIRMED. notificationsGranted comes from NotificationManagerCompat.areNotificationsEnabled and is displayed as a fourth SetupChecklist row with an onOpenNotificationSettings action. MainActivity resolves ACTION_APP_NOTIFICATION_SETTINGS with EXTRA_APP_PACKAGE, falls back to application details, and catches ActivityNotFoundException. The zh/en strings exist, previews pass the new parameters, the existing RequestMultiplePermissions path still includes POST_NOTIFICATIONS, the notification row opens settings instead of launching a second dialog, and a missing notification permission does not enter canStartSession (MainUiStateTest asserts this).
- **Hilt and restore**: CONFIRMED. MainViewModel takes SavedStateHandle and SessionConfigStore by injection; the app builds with Hilt; the editor restore happens before the collectors; the SavedStateHandle survives process death through the Activity saved state. M1 covers the active-session restore edge.
- **Tests**: CONFIRMED 192 from XML: app 66, core:location 77, core:data 2, core:geo 47, 0 failures. The new EditorState, SessionSpec, FavoritesRetry and notification-gate tests are meaningful. The instrumented DAO test compiled at 03:36 but was not run.
- **Deferred**: Robolectric service, tile and ViewModel tests, release rebuild and the two-OEM device matrix remain deferred; the B-2 report does not claim them as done.

## B2-4. Residual risks and recommendation

Residual risks: M1 and M2 are code-level MEDIUM issues that virtual-time or fake-flow tests can reproduce; M3 is a low-frequency stop-delivery edge. The notification channel state and the settings intent fallback are needs-device/OEM. The Room DAO order has a compiled but unrun instrumented test. Release is stale.

**Recommendation: PROCEED to Phase 1C (coordinate fallback).** No HIGH issue was found, and 1C is independent of the editor, favorites and notification work. Fix B2-M1 and B2-M2 before the final Phase 1 gate (M1 ideally before 1C touches the ViewModel, because 1C adds another editor entry point), fix or record the LOW notes, run the Room instrumented test, rebuild release, and run the device matrix. If the coordinator wants a clean B-2 before starting 1C, fix M1 and M2 first; they are small and localized.

---

# Re-audit (B2-M1/M2)

**Date**: 2026-10-30 (after the B-2 M1/M2 fix round)
**Scope**: authoritative pending-spec sync, end-of-session re-sync, explicit-stop semantics, favorites bounded retry, forgiving SavedStateHandle reads, tests, and any new HIGH/MEDIUM.
**Method**: read-only review of the changed files/tests plus fresh JUnit XML and lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- M1/M2 edits are at 03:50-03:55: SessionSpec.kt, FavoritesRetry.kt, MainViewModel.kt, EditorState.kt; tests SessionSpecTest, EditorStateTest, FavoritesRetryTest. No other source areas changed.
- Tests: app 75 (6 ServiceStartGuard, 3 SessionFinishOwnership, 12 EditorState, 6 FavoritesRetry, 10 Format, 22 MainUiState, 13 SessionSpec, 3 SessionStateGates), core:location 77, core:data 2, core:geo 47; total 201, 0 failures/errors. App XML is 03:55:36 after the last test edit (03:55:18) and the last main edit (03:51:53); core:location XML is the earlier fresh 77 run; core:data XML is 03:31:45.
- Lint at 03:57:59 reports 0 errors and 15 warnings. Debug APK is 03:53:40, after the last main edit. Release APK/mapping are still 23:30/23:43 and were not rebuilt.
- The Room instrumented DAO test was compiled at 03:36 and remains unrun; it was not changed in this round.

## R1. Verdicts

| Item | Verdict |
|---|---|
| B2-M1 pending-spec sync | **CONFIRMED fixed**: the decision takes the authoritative store phase, restore does not overwrite an active session, and the end-of-session transition re-syncs the editor; explicit stop no longer pre-clears and the Stopping-to-Idle transition syncs. LOW residuals: StateFlow conflation can skip the transition observation, and startMock still gates on the UI copy |
| B2-M2 favorites retry | **CONFIRMED fixed**: the helper counts failed subscriptions without resetting on emission, attempts are bounded at 4 (initial plus three retries), the 500/1s/2s backoff is used, exhaustion clears and reports, and the emit-then-throw test is genuine. LOW residuals: the budget is per ViewModel lifetime and there is no cancellation test |
| SavedStateHandle wrong-type LOW | **CONFIRMED fixed**: readForgiving guards the typed reads; wrong-type and non-finite tests are present |
| Tests | **CONFIRMED** 201, 0 failures; new tests are meaningful; no regressions in the earlier suites; service/tile/ViewModel Robolectric and release remain deferred |
| New HIGH/MEDIUM | **REJECTED** none found |

Recommendation: **PROCEED to Phase 1C**. The LOW notes can be handled in 1C or a cleanup pass.

## R2. Details

### B2-M1 - CONFIRMED fixed

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/SessionSpec.kt:41-68; app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:52-59, :206-212, :257-298; tests: SessionSpecTest.kt:116-217.
- pendingSpecDecision(state, session) now takes the authoritative SessionState and uses session.isSessionActive. The ViewModel passes statusStore.state.value in syncPendingSpec (:268-276), so a lagging UI copy can no longer rewrite or clear a spec while the store knows a session is active. The edit-before-Starting test covers the lagging-Ui case (SessionSpecTest.kt:132-138).
- restoreEditorState still runs before the collectors, but syncPendingSpec sees the store phase. If the store is Starting/Running/Degraded/Stopping, the decision is Keep; the restore no longer overwrites an active session's pending spec (the M1 restore scenario is closed).
- observeSession records the previous collected session and calls syncPendingSpec when shouldSyncPendingSpecOnSessionTransition(previous, next) is true (:287-298). The helper is previous?.isSessionActive == true and !next.isSessionActive (SessionSpec.kt:65-68). That covers Running/Starting/Stopping to Idle, terminal Error and the non-terminal TeardownTimeout; running-to-stopping is not a sync; the tests cover the matrix (SessionSpecTest.kt:159-174).
- stopMock no longer pre-clears SessionConfigStore (:206-212). The explicit stop remains Keep while Stopping, and the Stopping-to-Idle transition syncs the visible editor. The explicit-stop test asserts the edited selection (shanghai) is what gets synced, not the abandoned start spec (beijing) (SessionSpecTest.kt:177-194). This resolves the old clear-before-confirmation M3 and the clear-vs-idle-sync conflict in a coherent way: the pending spec always follows the visible editor once the session is over.
- The non-terminal teardown window is inactive, so its active-to-inactive transition syncs the editor; the pending spec is current when the window later resolves to Idle. The test covers this (SessionSpecTest.kt:151-156, :172).

LOW residuals:
1. **StateFlow conflation**: the observer is a StateFlow collector on Main. If an Idle emission and a subsequent Starting emission occur before the collector resumes, it can see previous active and next Starting active, missing the inactive transition. The tile could then start the last pending spec. The window is narrow because the new start needs user or service work on the same main thread, but syncing whenever the collected session is inactive (not only on the transition) would remove it. This is a LOW robustness note, not a repeat of M1 in the normal path.
2. **startMock still gates on the UI copy**: startMock checks _uiState.value.session.canStartNewSession (:184). If the collector lags Starting, the button can call StartMockUseCase, which checks the store and silently returns with no message. The service and use case remain correct (no supersede), but the user can get a no-op tap. Using statusStore.state.value for the start gate/message would remove the lag. LOW UX note.

### B2-M2 - CONFIRMED fixed

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/FavoritesRetry.kt:15-63; app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:308-330; test: FavoritesRetryTest.kt:15-128.
- collectFavoritesWithRetry no longer resets the failure count on an emission. It increments failedAttempts in the catch, calls onFailure for every attempt, calls onExhausted and returns when failedAttempts reaches maxAttempts, and otherwise invokes the backoff. CancellationException is rethrown; a flow that completes normally returns without retrying.
- MAX_ATTEMPTS is now 4: one initial subscription plus three retries. backoffMs uses 500, 1000 and 2000ms, and all three steps are reached before exhaustion. The emit-then-throw test creates four subscriptions, records failure attempts 1..4 and backoffs 500/1/2, then asserts the stale visible list is cleared and the final message is set (FavoritesRetryTest.kt:35-81). This test genuinely fails the old reset-on-emission loop.
- The failed-then-succeeds and healthy-flow tests assert two and one subscriptions respectively and no exhaustion; the bound/backoff tests assert the configured schedule (FavoritesRetryTest.kt:15-32, :84-128).
- MainViewModel uses the helper: the first failure surfaces a message, and onExhausted clears favorites and sets the final message. The old inline resetting loop is gone.

LOW residuals:
1. **Per-lifetime budget**: because the counter never resets, a transient failure after three earlier failures over the ViewModel lifetime exhausts immediately even if the flow had been healthy for a long time. This is conservative (stale data is cleared, not shown) and matches the report's never-reset choice; a per-failure-streak reset after a stable success would be friendlier but is not required.
2. **No explicit cancellation test**: the helper rethrows CancellationException, but the new tests do not cancel the collection. The code path is simple; add a cancellation case if the helper grows.

### SavedStateHandle LOW - CONFIRMED fixed

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/EditorState.kt:68-84; test: EditorStateTest.kt:80-118.
readEditorSnapshot now uses readForgiving, which reads as Any?, casts with as? and wraps the read in runCatching, so a wrong-type value degrades to the editor default instead of throwing ClassCastException. The tests include a non-finite saved point and wrongly typed saved values. The route and enum forgiving rules are unchanged. CONFIRMED.

### No regression

The earlier B-2 editor/favorites/notification behavior, the 1A/B-1 runner and service behavior, and the G2-G7/R1-R4/H1/H2 fixes are unchanged in this round; the full 201-test suite is green. The notification checklist, Hilt SavedStateHandle wiring, favorites DAO order and save-success callback remain as previously confirmed.


## R3. Tests and residual risks

- Counts are correct and fresh: app 75 (6 ServiceStartGuard, 3 SessionFinishOwnership, 12 EditorState, 6 FavoritesRetry, 10 Format, 22 MainUiState, 13 SessionSpec, 3 SessionStateGates), core:location 77, core:data 2, core:geo 47; total 201, 0 failures/errors. App XML is 03:55:36 after the last test and main edits; core:location XML is the earlier fresh 77 run; core:data XML is 03:31:45. Lint at 03:57:59 is 0 errors and 15 warnings. Debug APK is 03:53:40; release APK/mapping are still 23:30/23:43 and were not rebuilt.
- The M1 tests are meaningful pure tests: the authoritative phase beats a lagging UI copy, all four active phases (including Degraded) Keep, the finished session and non-terminal teardown sync, only active-to-inactive transitions sync, the explicit stop syncs the edited selection, and the natural end syncs the route/speed/end behavior. They do not instantiate MainViewModel; the observeSession wiring is code-reviewed.
- The M2 tests are meaningful: the 4-attempt budget, the 500/1s/2s backoff, the emit-then-throw exactly-four-subscriptions case with a stale list and final message, a failed-then-succeeds case, and a healthy single-subscription case. The cancellation path has no test but is a single rethrow.
- The EditorState wrong-type and non-finite tests are meaningful; the SavedStateHandle LOW is closed.
- No regressions were found in the earlier B-2, B-1, 1A, G2-G7, R1-R4, H1 or H2 suites; all 201 tests pass.
- Residual LOW risks: StateFlow conflation can skip the end-of-session transition observation; startMock still gates on the UI copy and can produce a silent no-op tap; the favorites retry budget is per ViewModel lifetime rather than per failure streak; no cancellation test; the Room instrumented DAO test remains compiled but unrun; release is stale.
- Deferred and not silently claimed: Robolectric service/tile/ViewModel tests, release rebuild and the two-OEM device matrix.

## R4. Recommendation

**PROCEED to Phase 1C (coordinate fallback).** No HIGH or MEDIUM issue remains. The M1 sync now uses the authoritative store phase, does not overwrite an active session during restore, re-syncs the visible editor at the end of a session, and gives explicit stop coherent semantics. The M2 retry is genuinely bounded, uses all three backoff steps, survives emit-then-throw, and clears/reports on exhaustion. The SavedStateHandle wrong-type LOW is fixed.

The remaining LOW notes (StateFlow conflation, start-gate UI lag, per-lifetime retry budget, missing cancellation test) do not block 1C. Fix them in 1C or a cleanup pass, run the Room instrumented test, rebuild release, and complete the device matrix before the final Phase 1 gate.
