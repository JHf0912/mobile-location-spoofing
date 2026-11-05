# 09 - Phase 1A Audit (teardown ownership)

**Date**: 2026-10-30
**Scope**: Phase 1A only: provider lease, cold-start cleanup, engine contract and timeouts, runner failure and AppOp, service and use-case teardown behavior.
**Contract**: docs/optimization/2026-10-29/08-phase1-design.md sections 4, 5, 6, 8, 12 (authoritative) and the approved plan Phase 1 design revision.
**Method**: read-only static review of the listed Phase 1A files, git status and git diff HEAD, file mtimes, and the existing JUnit XML results. No Gradle command was run and no source/build/CI/doc file was modified. The only file written is this audit.
**Evidence labels**: verified = read from code/tests/XML; inferred = consequence of the code path; needs-device = cannot be established without a device or OEM run.

---

## 1. Baseline evidence

### 1.1 File-set and forbidden-change check

The Phase 1A implementation cluster has mtimes 2026-10-29 23:54:00 through 2026-10-30 00:05:43 (new files and the listed edits). The other modified files in the working tree are older (11:17-23:27), i.e. pre-existing Phase 0 and UI-session work:

- core/model/.../Models.kt 12:30, core/location/build.gradle.kts 11:37, MockStatusStoreTest.kt 12:29, app UI files 13:17-13:39, strings.xml 23:27, CI 23:05, SpoofingDatabase.kt 23:05.

Verdict: **CONFIRMED** - no Phase 1A edit landed in core:model, core:data, UI state/Compose, strings.xml, CI, or Gradle. The working tree still contains unrelated pre-existing changes (not attributed to 1A and not reviewed here). git status shows docs/ is untracked; no commit was made.

### 1.2 Test results (from XML, not re-run)

| Module | Tests | Failures/errors | XML timestamp |
|---|---|---|---|
| core:location | **53** | 0 / 0 | 2026-10-30 00:06:11 |
| :app | 28 | 0 / 0 | 2026-10-30 00:06:22 |
| core:data | 2 | 0 / 0 | 2026-10-29 23:06:44 |
| core:geo | 47 | 0 / 0 | 2026-10-29 13:28:04 |
| **Total** | **130** | 0 / 0 | mixed runs |

- The 53 core:location tests and the 28 :app tests were produced at 00:06, after the last 1A source edit (MockSessionRunner.kt 00:05:43), and the :app compile output is newer than MockLocationService.kt; so the final 1A sources compiled and the unit suites are green (verified for compilation and unit tests; assembleDebug, lint and release were not run in this audit).
- The 130 total is a sum across XML files from different runs; core:geo results predate 1A. They are valid baseline evidence for unchanged modules, not a single fresh 130-test invocation.

---

## 2. Verdict table

| # | Audit item | Verdict |
|---|---|---|
| 1 | Deadlock/race: lease lock nesting, cleanup vs start, startForeground before gate | **CONFIRMED** (no nesting; gate ordering correct; startForeground-before-gate is safe). One separate stop/start race in the service, see F3 |
| 2 | Lease: write-before-add, merge, release-only-removed, SecurityException, package mismatch, durability, test quality | **CONFIRMED** for ordering/semantics; **F1 HIGH** for ignored commit result; **F6** package-mismatch deviation; durability **needs-device** |
| 3 | Engine contract: timeouts as values, rethrow caller cancellation, LM-first/reverse stop, per-push timeout, fused generation guard, stop results | **CONFIRMED** except **F2 HIGH/PLAUSIBLE** (fused late completion can disable a newer session) and **F4** (fused stop failure is not observable) |
| 4 | Runner: 12s start, 3s join and refusal, five failures, AppOp, onFinished, stopAndJoin, isRunning, no UI state changes | **CONFIRMED**; **F5** is an edge path where onFinished can be skipped |
| 5 | Service/use cases: error notification, bounded joins, Running preservation, cleanup | **CONFIRMED** for the requested behaviors; **F3** stop/start race; **F4** fused stop not covered by lease |
| 6 | Tests: 53/130 counts, coverage of the listed behaviors, tautologies | **CONFIRMED** counts; **F10 MEDIUM** test gaps and weak assertions |
| 7 | Forbidden changes | **CONFIRMED** none from 1A |
| 8 | Device-only limitations | **NEEDS-DEVICE** for all items listed in section 4.8 |

Overall: the architecture is sound (lease-before-add, two-level gate, per-engine timeouts, LM-first/reverse stop, bounded joins and failure termination), but **F1 and F2 should be fixed before Phase 1B**. The design gate says unresolved high-risk items must not enter the next workstream.

---

## 3. Findings

### F1 - HIGH: commit failure is ignored, so a provider can be registered without a durable lease

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/ProviderLeaseStore.kt:90-96, :99-113
**Contract**: design section 4.2 step 1 (wait for commit success), invariant I1 (no lease write, no provider registration).

The acquire method calls prefs.edit()...commit() and discards the Boolean result. If the disk write fails, the method returns normally; LocationManagerMockEngine.startMockMode then proceeds to addProvider (LocationManagerMockEngine.kt:53-59). The in-process preference map may still hold the value, so the session looks correct, but the value was never persisted; after a process death the provider is live in system_server with no lease, exactly the failure 1A exists to prevent. The release method has the same ignored result, but there the consequence is only a stale lease (conservative, retried at cold start).

The test double always returns true (ProviderLeaseStoreTest.kt:184-189), so no test can catch this.

**Fix**: check the result (for example check(editor.commit()) or throw IOException); the existing catch (e: Exception) in LocationManagerMockEngine then counts that provider as a start failure and never adds it. Add a fake that returns false and assert no add occurred. Same for release (log or throw; keep the lease when the clear fails).

### F2 - HIGH / PLAUSIBLE trigger: fused late-completion compensation can switch mock mode off for a newer session

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/FusedMockLocationEngine.kt:83-116 (callback guard :86-94; cancellation :96-104; superseded branch :106-114; stop :129-139)
**Contract**: design section 5.4 (generation guard and late Task).

The code path is confirmed: a setMockMode(true) Task that times out or is cancelled is not cancelled at the GMS level. When it completes late and the engine has moved to a newer generation, the callback sends a compensating setMockMode(false) unconditionally (:90-92). generation is incremented by both a stop and a newer start (:84, :101, :130), and the callback cannot distinguish the session that issued me has stopped (safe to disable) from a newer session has already enabled mock mode (disabling it is wrong).

Sequence:

1. Session A starts; fused setMockMode(true) is issued, generation 1; the composite 5s timeout cancels the wait.
2. Session A tears down; the runner joins; generation becomes 2 (stop).
3. Session B starts; generation 3; setMockMode(true) for B completes and B is Ok.
4. The original Task of A completes successfully late; the callback sees myGeneration 1 is not 3, issues setMockMode(false) after the true call of B, and the fused mock mode of B is off.

B still reports Running (LM is first and usually Ok), so fused consumers silently stop receiving mocked positions until the next session. Whether GMS processes the compensating false after the true of B is an ordering question (needs-device), but the absence of any check for a newer active start is a code-level gap.

The current test only covers cancel, stop, late completion in one session (FusedMockLocationEngineTest.kt:131-155); it never starts a second session between the cancel and the late completion.

**Fix**: make the compensation state-aware rather than generation-inequality-based. For example, keep a desiredMockMode or active-start token guarded by a mutex, and only send false if the engine is still supposed to be off when the call executes; or serialize all gateway calls through one serialized worker and re-check the token at execution time. Add a test: start A, cancel or timeout, stop, start B and complete the true of B, then complete the true of A late and assert no false is issued (or that B remains on).

### F3 - MEDIUM: a failed duplicate start can still let the in-flight gate start a session after stopSelf

**Files**: app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt:97-170 (preflight before duplicate check; duplicate check at :146), :117-129 (startForeground catch), :134-142 (bad spec), :148-169 (gate coroutine), :302-306 (failEarly), :207-213 (finishSession)

failEarly, the startForeground catch, and the bad-spec path all emit Error or call finishSession without setting stopping to true and without cancelling startGateJob. The duplicate ACTION_START check (:146) runs only after those preflight checks. Race:

1. Start 1 passes preflight and starts the gate coroutine.
2. Start 2 arrives while start 1 is gated and a cheap check fails (for example the mock AppOp or location permission was revoked) -> failEarly -> Error + stopSelf; stopping stays false and startGateJob is not cancelled.
3. The gate of start 1 finishes; stopping is false (:161), so runner.start is called and can register providers after the service already decided to stop.

The service is destroyed shortly afterwards; onDestroy only joins for 1s (:215-222), while the composite worst-case stop budget is 2s + 2s. The lease is the recovery path, so this is not a permanent provider leak, but it does mean a start can happen after a stop decision and the Error status can later be overwritten by Running.

**Fix**: move the duplicate ACTION_START check to the top of handleStart (before preflight), and in the pre-gate failure paths set stopping to true and cancel startGateJob. Add a service-level (Robolectric) test for stop or failure during the startup gate.

---

### F4 - MEDIUM: fused stop failure is invisible to EngineHealthReporter; no health reporter is bound

**Files**: FusedMockLocationEngine.kt:129-145 (both disableOnce results ignored; always returns emptyList()), MockLocationEngine.kt:88-94 (onStop signature), CompositeMockLocationEngine.kt:91-93, di/LocationModule.kt:66-71, MockLocationEngine.kt:96-102 (NoOpEngineHealthReporter)

Design section 5.4/12.4 says fused returns no provider results and its stop success or failure is recorded through health. The implementation returns an empty list even when both setMockMode(false) attempts fail, so the composite onStop cannot distinguish success from failure. In addition, LocationModule builds the composite with the default NoOpEngineHealthReporter and no real implementation is bound, so no per-engine health is exposed at all. This is a deliverable gap (plan A3, expose per-engine health) and an observability gap, not a teardown bug by itself.

**Fix**: either return a synthetic result or reason for a failed fused stop (see F8), or extend the reporter with a stop outcome; bind at least a minimal logging reporter. If intentionally deferred, record it explicitly in docs 08/09.

### F5 - MEDIUM/LOW: CancellationException from stopMockMode in the NonCancellable teardown skips status and onFinished

**File**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunner.kt:251-266

Inside withContext(NonCancellable), the code catches CancellationException and rethrows it (:253-255). If an engine stopMockMode throws one, the exception leaves the finally and the subsequent if (isCurrent(id)) block with onFinished (:261-266) never runs. The service then has a completed runner job but no finishSession call; it can stay foreground with a stale notification. Under NonCancellable there is no caller cancellation to preserve, so the rethrow protects nothing. A well-behaved engine should not throw here, but the runner is the last line of defense and the comment says the status reset matters more than a failed teardown.

**Fix**: run the status and onFinished block in an outer finally, or catch CancellationException alongside Exception inside this NonCancellable block (teardown is already non-cancellable). Add a test with an engine whose stopMockMode throws CancellationException and assert onFinished still runs.

### F6 - LOW: package mismatch drops the lease, but the design says to leave it untouched

**Files**: core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/AppStartupCoordinator.kt:84-94; test AppStartupCoordinatorTest.kt:88-106
**Contract**: design section 4.3 (if the lease is empty, the package does not match, or there are no providers, end directly).

The implementation calls leaseStore.release(lease.providers) on a package mismatch (:90-92) and the test codifies that behavior. This forgets providers that may still be registered (renamed build or corrupt data) instead of retrying later. Practical impact is low (a package mismatch is only possible via rename or corrupt data, and test providers do not survive a reboot), but it is a contract deviation and conflicts with the approved design. Either align the implementation with do nothing, or amend the design and the test with the rationale.

### F7 - LOW: lease entries for acquired but not added providers are never released by the session

**File**: LocationManagerMockEngine.kt:53-61 (acquire, then add, then touched +=), :99-116 (stop iterates touched only)

If acquire succeeds but addProvider or setProviderEnabled throws, the provider is persisted in the lease but not in touched; stopMockMode never returns it and never releases it. It is cleaned on the next process start (clearStaleProviders treats IllegalArgumentException as removed and releases), so this is lease hygiene, not a permanent leak. The retention is also conservative because the provider name may belong to another app. Document it as an accepted trade-off, or track acquired-but-not-touched providers and attempt one confirmed removal at stop.

### F8 - LOW: composite fabricates ProviderStopResult entries whose provider is an engine class name

**File**: CompositeMockLocationEngine.kt:117-136 (provider = nameOf(engine) at :122, :129); test CompositeMockLocationEngineTest.kt:196-213

When an engine stopMockMode throws or times out, the composite returns a ProviderStopResult whose provider is for example FusedMockLocationEngine, not a system provider name. ProviderStopResult.provider is documented as the provider that was removed (MockLocationEngine.kt:34-45), and the lease logic treats that field as a provider name. Today only health reads these entries, so nothing breaks, but a future caller that feeds the list into leaseStore.release would pass an engine name. Use a separate engine-stop outcome type (or a null provider or flag), and have onStop carry the engine failure separately.

### F9 - CONFIRMED (no issue): no deadlock; startForeground before the gate is safe

- withLeaseLock is never nested with itself: the engine wraps only acquire (LocationManagerMockEngine.kt:53-55) and release (:110-114); acquire and release do not take the mutex; the coordinator does not use withLeaseLock; the service never holds it (MockLocationService.kt:179-183).
- startGate is held only by the cleanup job around its decision and clear section (AppStartupCoordinator.kt:66-82) and briefly by markServiceStarting (:52-56); finishCleanup joins outside the gate (:63-64). The service marks and then joins (MockLocationService.kt:179-183), so the gate is not held while joining. No lock-order inversion exists.
- Cleanup cannot remove the providers of a new session on the normal path: markServiceStarting waits for an in-progress cleanup, and cleanup re-checks serviceStarting inside the gate; the service does not start the runner until finishCleanup returns. The tests cover both directions (AppStartupCoordinatorTest.kt:30-86). F3 is a separate stop/start race, not a cleanup race.
- startForeground before the cleanup join (MockLocationService.kt:117-119 before :148-169) is a safe deviation from the design ordering: the 5s startForegroundService deadline is satisfied synchronously before any suspension, and the later join is bounded by 6s. It reduces the RemoteServiceException risk. CONFIRMED acceptable.

---

### F10 - MEDIUM: test gaps and misleading or tautological tests

Covered as requested (verified): timeout-not-cancellation at the runner (MockSessionRunnerTest.kt:261-283), LM starts while fused hangs (CompositeMockLocationEngineTest.kt:126-139), per-push timeout (:167-180), partial stop (:195-213, LocationManagerMockEngineTest.kt:82-104), lease write-before-add (LocationManagerMockEngineTest.kt:32-45), cleanup vs start (AppStartupCoordinatorTest.kt:30-86), AppOp recheck (MockSessionRunnerTest.kt:374-399, LocationManagerMockEngineTest.kt:118-149), five consecutive failures (MockSessionRunnerTest.kt:331-355).

Gaps and weak tests:

1. **No commit-failure test** (F1): the fake always returns true; a regression to apply would also pass because the fake apply calls commit (ProviderLeaseStoreTest.kt:184-193). A fake that records commit vs apply and can return false is needed.
2. **No assertion that acquire and release run under withLeaseLock**: the fakes implement withLeaseLock as a pass-through (LocationManagerMockEngineTest.kt:203, AppStartupCoordinatorTest.kt:136). Removing the withLeaseLock wrapper in the engine would not fail any test.
3. **withLeaseLock runs the block is tautological** (ProviderLeaseStoreTest.kt:102-109): it only asserts a lambda returns 42; it does not exercise mutual exclusion.
4. **peek returns a copy does not prove the store copies** (ProviderLeaseStoreTest.kt:90-100): the fake getStringSet already returns a fresh mutable copy (:118-121), so mutating lease.providers never touches the stored value even without the toSet in the store.
5. **a single failed push reports an error does not assert the error** (MockSessionRunnerTest.kt:136-148): it only checks the final status is Running and the push count; the intermediate Error emission is never observed. Use Turbine or a recording store.
6. **No recovery test after a stuck teardown** (MockSessionRunnerTest.kt:285-329): the refusal is tested, but not that a later start is allowed once the stuck job completes and invokeOnCompletion clears teardownStuck (MockSessionRunner.kt:143-145).
7. **No finishCleanup false or timeout test** (AppStartupCoordinator.kt:63-64): the service refusal branch (MockLocationService.kt:152-158) is untested.
8. **No composite push or stop caller-cancellation test**: only cancellation during start is covered (CompositeMockLocationEngineTest.kt:215-228); the design matrix asks for true cancellation rethrow across the composite.
9. **No cross-session fused test** (F2).
10. **No service or use-case tests**: app/src/test contains only FormatTest and MainUiStateTest (28 tests); the gate, onTaskRemoved, onDestroy, notification channel and posting, and Start/Stop Running preservation are untested. The design service and tile Robolectric row is not satisfied for 1A.
11. **Partial-start lease assertion missing**: LocationManagerMockEngineTest.kt:70-80 does not assert that the failed provider lease is retained (F7).

---

## 4. Item-by-item answers

### 4.1 Deadlock and race

- ProviderLeaseStore.withLeaseLock is never nested with itself and never held across addTestProvider, provider enable or disable, or runner start: **CONFIRMED** (LocationManagerMockEngine.kt:53-61, :99-116; AppStartupCoordinator.kt:66-103).
- AppStartupCoordinator and startGate serialize cleanup vs service start without holding the gate while joining: **CONFIRMED** (AppStartupCoordinator.kt:52-64; MockLocationService.kt:179-183).
- Cleanup can remove providers of a new session: **REJECTED** for the normal path (gate ordering and re-check). REJECTED does not cover F3, which is a service stop/start race unrelated to cleanup.
- startForeground before the gate join: **CONFIRMED safe** for the 5s deadline; the deviation is an improvement, not a risk.

### 4.2 Lease

- Write-before-add: **CONFIRMED** (LocationManagerMockEngine.kt:53-59; test LocationManagerMockEngineTest.kt:32-45).
- Merge semantics and session-id update: **CONFIRMED** (ProviderLeaseStore.kt:81-97; tests :44-66).
- Release only removed=true: **CONFIRMED** in the engine (LocationManagerMockEngine.kt:103-114) and cleanup (AppStartupCoordinator.kt:96-102); tests LocationManagerMockEngineTest.kt:82-104, AppStartupCoordinatorTest.kt:108-126.
- SecurityException stays leased: **CONFIRMED** (LocationManagerMockEngine.kt:200-206; test :107-116).
- Package mismatch: **PLAUSIBLE deviation** (F6).
- Force-stop: documented, not fixed (SpoofingApp.kt:13-24); **NEEDS-DEVICE**.
- Durability: commit is used on Dispatchers.IO (**CONFIRMED** ProviderLeaseStore.kt:79-96), but the result is ignored (**F1 HIGH**). Actual fsync, disk behavior and process-death recovery are **NEEDS-DEVICE**.
- Test quality: meaningful for ordering, merge and partial stop, but gaps 1-4 and 11 above.

---

### 4.3 Engine contract

- Timeouts are values and never leak as cancellation: **CONFIRMED** (CompositeMockLocationEngine.kt:165-176; withTimeoutOrNull and rethrow CancellationException).
- True caller cancellation is rethrown: **CONFIRMED** in code (CompositeMockLocationEngine.kt:53-59, :169-172; FusedMockLocationEngine.kt:98-104; LocationManagerMockEngine.kt:62-64, :143-145, :177-179); test coverage is start-only (gap 8).
- LM-first start and reverse stop: **CONFIRMED** (LocationModule.kt:66-71; CompositeMockLocationEngine.kt:41-52, :90-94; tests CompositeMockLocationEngineTest.kt:114-124, :182-193).
- One timeout does not abort the others: **CONFIRMED** for start and stop (CompositeMockLocationEngine.kt:44-52, :90-94; tests :126-151, :195-213).
- Per-push timeout: **CONFIRMED** (CompositeMockLocationEngine.kt:112-115, :186-190; test :167-180).
- Fused generation guard and late Task: **PLAUSIBLE HIGH** for the cross-session case (F2); the single-session case is tested.
- Stop results: LM returns per-provider results; fused returns emptyList (**CONFIRMED**, design 12.4), but a fused stop failure has no observable channel (F4) and the composite synthesizes engine-named results (F8).

### 4.4 Runner

- 12s start timeout: **CONFIRMED** (MockSessionRunner.kt:291, used at :160-173); tested with an injected 1s timeout (MockSessionRunnerTest.kt:261-283).
- 3s previous.join and refusal on timeout: **CONFIRMED** (MockSessionRunner.kt:136-146, :285; test MockSessionRunnerTest.kt:285-329). Recovery after the stuck job completes is untested (gap 6).
- Five consecutive push failures terminate: **CONFIRMED** (MockSessionRunner.kt:216-227, :294; test MockSessionRunnerTest.kt:331-355).
- AppOp recheck every 10 ticks: **CONFIRMED** (MockSessionRunner.kt:183-187, :293; test MockSessionRunnerTest.kt:374-399).
- onFinished on every exit path: **CONFIRMED** for normal, failure, cancellation and supersede paths (MockSessionRunner.kt:96-103, :245-266); F5 is the exception edge.
- stopAndJoin: **CONFIRMED** (MockSessionRunner.kt:122-126; test MockSessionRunnerTest.kt:401-414); timeout branch not asserted.
- isRunning: **CONFIRMED** formula (MockSessionRunner.kt:68); untested directly.
- No UI or session-state changes: **CONFIRMED** (the runner still emits MockStatus; no core:model, store or UI edits from 1A; see 1.1).

### 4.5 Service and use cases

- Error notification before startForeground: **CONFIRMED** (MockLocationService.kt:302-306, :315-328; channel created in :66-71). OEM crash avoidance is **NEEDS-DEVICE**.
- onTaskRemoved and onDestroy bounded joins: **CONFIRMED** (:195-205, :215-225); onDestroy uses runBlocking on the main thread for at most 1s (bounded but a StrictMode or ANR margin; inferred low risk).
- Start and Stop Running preservation: **CONFIRMED** for an actual MockStatus.Running (StartMockUseCase.kt:34-38; StopMockUseCase.kt:28-32). **PLAUSIBLE** partial gap under the old model: a session can be injecting while the latest status is Error (non-terminal push failure, MockSessionRunner.kt:216-227), so the guard can emit an Error for a still-running session. This is the 1A/1B boundary, not a new 1A regression.
- Stop without cleanup: every stop path is bounded and the lease is the documented backstop; however, the 1s onDestroy join plus the 3s handleStop budget is below the composite worst-case 4s stop budget (FusedMockLocationEngine 1s retry plus LM 2s, CompositeMockLocationEngine.kt:186-190). A slow but successful teardown can be abandoned; LM is recovered by the lease on the next process start, fused mock mode is not covered by the lease (needs-device). F3 is the only path that starts after a stop decision.

### 4.6 Tests

Counts are correct: 53 core:location (0 failures) and 130 JVM total from the XML set; see 1.2. The requested behaviors are covered except the gaps in F10. Tautologies and weak assertions: the withLeaseLock block test, the peek copy test, the single-failed-push test missing the Error assertion, and the pass-through withLeaseLock fakes. The fake apply delegating to commit masks an apply regression.

### 4.7 Forbidden changes

**CONFIRMED** none from Phase 1A. Evidence: mtime cluster of 1A files (23:54-00:05) vs pre-existing files (11:17-23:27); git status and git diff HEAD show no 1A edits under core:model, core:data, app UI, app/src/main/res, .github, or Gradle files. core/location/build.gradle.kts and Models.kt are modified in the working tree but predate 1A. No commit was made.

### 4.8 Device-only limitations (needs-device)

- force-stop, LMK, process death: lease and next cold-start cleanup (SpoofingApp.kt:13-24; ProviderLeaseStore.kt:14-29).
- Real GMS late Task ordering and the F2 cross-session compensation.
- OEM foreground-service behavior, notification denial, and startForegroundService without startForeground on the early-failure paths.
- SharedPreferences commit durability under disk pressure or power loss.
- onDestroy 1s join actually completing teardown on each OEM.
- Lease heuristic: global gps and network names can belong to another mock app; cold-start cleanup may remove a provider of another app (design 4.6).
- Whether fused mock mode survives process death and how long a timed-out setMockMode(true) can remain pending.

---

## 5. Residual risks and recommendation

Residual risks after the code review: the two HIGH items (F1, F2) are the only ones that directly threaten the 1A goal or fused correctness. F3 is a narrow stop/start race with the lease as backstop. F4 and F8 leave teardown failures unobservable; F5 is a defensive gap. All device claims remain needs-device; the code review does not establish that provider cleanup is fixed on any device.

**Recommendation: fix F1 and F2 before Phase 1B; fix F3 and F5 in the same patch if possible; F4, F6 and F8 may be fixed or explicitly deferred with a note in docs 08/09. Add the missing tests in F10.** The design gate (unresolved high-risk items must not enter the next workstream) makes F1 and F2 blocking. Once those are closed and the core tests are re-run, with app and service tests added, Phase 1A is structurally sound and Phase 1B can start.

**Suggested fix order**:

1. ProviderLeaseStore.acquire and release: check commit and fail the start on a failed lease write (F1), plus tests.
2. FusedMockLocationEngine: state-aware late-completion compensation, not generation inequality alone (F2), plus a cross-session test.
3. MockLocationService: move the duplicate-start check before preflight; set stopping and cancel the gate job on pre-gate failure (F3).
4. MockSessionRunner: guarantee onFinished even if teardown throws (F5).
5. Health and stop-result cleanup (F4, F8) and package-mismatch alignment (F6), or record them as accepted deviations.

---

## 6. Verification commands for the fix round

- ./gradlew :core:location:testDebugUnitTest
- ./gradlew :app:testDebugUnitTest
- ./gradlew :app:assembleDebug :app:lint
- Device matrix: am force-stop / am kill, dumpsys location, appops revoke, second mock app.

Do not promote this audit to provider cleanup fixed without dumpsys location evidence on at least two OEMs (design 4.6 and 8).

---

# Re-audit (fix round)

**Date**: 2026-10-30 (after the 1A fix round)
**Scope**: F1, F2, F3, F5, F6, F4/F8, the new tests, and regressions. Read-only; only this report was updated.
**Method**: re-read the changed 1A files and tests, git status/mtimes, and the fresh JUnit XML plus lint artifacts. No Gradle command was run by the auditor; the evidence below is from the artifacts produced by the implementation/fix round.
**Evidence labels**: verified = read from code/tests/XML; inferred = consequence of the code path; needs-device = device/OEM required.

## R0. Fresh evidence

- Changed files after the first audit are confined to the 1A cluster: ProviderLeaseStore, LocationManagerMockEngine, FusedMockLocationEngine, CompositeMockLocationEngine, MockLocationEngine, LocationModule, MockSessionRunner, AppStartupCoordinator, MockLocationService, the new ServiceStartGuard, and the corresponding tests. No UI, core:model, core:data, strings, CI, or Gradle file was modified after 00:30 (verified by mtime and git status).
- Core:location tests: 62 (5 AppStartup + 13 Composite + 7 Fused + 10 LM + 17 Runner + 1 StatusStore + 9 Lease), 0 failures/errors, XML 2026-10-30 00:46:46.
- App tests: 32 (4 ServiceStartGuard + 10 Format + 18 MainUiState), 0 failures/errors, XML 2026-10-30 00:49:20.
- core:data 2 and core:geo 47 from older runs (unchanged modules): total 143 JVM tests, 0 failures/errors.
- Fresh build artifacts: app debug classes and app-debug.apk at 00:49; lint report at 00:50 with 0 errors and 15 warnings. Release APK/mapping still date from 23:30/23:43, i.e. before the fixes; release was not rebuilt in this round.

## R1. Verdicts

| Item | Verdict | Evidence |
|---|---|---|
| F1 durable lease and rollback | **CONFIRMED fixed** | ProviderLeaseStore.kt:89-123; LocationManagerMockEngine.kt:53-96, 131-150; tests ProviderLeaseStoreTest.kt:32-73, LocationManagerMockEngineTest.kt:32-79 |
| F2 fused cross-session late completion | **CONFIRMED fixed** for the reported race | FusedMockLocationEngine.kt:91-129, 142-189; test FusedMockLocationEngineTest.kt:158-190 |
| F3 service start/stop race | **CONFIRMED fixed** for preflight and stop paths; one LOW completeness gap (null/unknown intents) | ServiceStartGuard.kt:24-50; MockLocationService.kt:100-184, 199-243, 319-327; ServiceStartGuardTest.kt:11-57 |
| F5 onFinished after a throwing teardown | **CONFIRMED fixed** | MockSessionRunner.kt:245-270; test MockSessionRunnerTest.kt:452-474 |
| F6 package mismatch retains the lease | **CONFIRMED fixed** | AppStartupCoordinator.kt:84-98; test AppStartupCoordinatorTest.kt:88-108 |
| F4 health reporter bound and failure separate | **CONFIRMED** for the reporter channel and binding; **PLAUSIBLE/MEDIUM remaining** for a failed fused disable Task | MockLocationEngine.kt:85-140; CompositeMockLocationEngine.kt:85-99, 117-134; LocationModule.kt:72-77; FusedMockLocationEngine.kt:157-163 |
| F8 no synthetic provider result reaches the lease | **CONFIRMED fixed** | CompositeMockLocationEngine.kt:117-134; MockLocationEngine.kt:73-82; test CompositeMockLocationEngineTest.kt:221-245 |
| New tests meaningful | **CONFIRMED** with listed gaps | see R4 |
| Regressions | **REJECTED** for new deadlock/lost cancellation/over-broad catch; **PLAUSIBLE/LOW** for the monitor issue call and a latent stop/start atomic window | see R3 |

No HIGH item remains. The recommendation is at the end.

## R2. Detailed findings

### F1 - CONFIRMED fixed

- ProviderLeaseStore.acquire and release now return the commit Boolean (ProviderLeaseStore.kt:89-106, 108-123). The store uses commit, never apply; the tests assert applyCalls == 0 (ProviderLeaseStoreTest.kt:45-48).
- LocationManagerMockEngine checks the lease result before addProvider and breaks on an exception or false (LocationManagerMockEngine.kt:54-69). No provider is added when the lease write fails (test :51-61).
- The rollback path removes providers already added by this start and releases their lease (LocationManagerMockEngine.kt:91-96, 131-150). The test gps Ok then network lease failure asserts remove:gps after add:gps, controller.added empty, and release {gps} (LocationManagerMockEngineTest.kt:63-79). This is the correct ordering and fails if the rollback is removed.
- Release failure keeps the providers in touched so a later stop retries the release (LocationManagerMockEngine.kt:141-149). The semantics are safe: the lease stays on disk, so the cold-start cleanup can retry; no provider is forgotten.
- The acquire and release under-lock assertions are no longer pass-through (LocationManagerMockEngineTest.kt:45-48, 124; fake :228-265). The peek-copy test now uses a fake that returns the stored mutable set, so the test is meaningful (ProviderLeaseStoreTest.kt:122-133, 174-176). The withLeaseLock test asserts real serialization (:135-162).
- Remaining test gap: the fake has a failRelease flag (LocationManagerMockEngineTest.kt:233, 260) but no test sets it. The engine release-failure retry path is code-reviewed but not exercised. A rollback whose lease release fails is also untested. Add one test: stop with failRelease true, assert removed=true results and touched retained, then clear the flag and assert the second stop releases. This is a test gap, not a code defect.

### F2 - CONFIRMED fixed; two low residuals

- generation and desiredMockMode are guarded by one monitor (FusedMockLocationEngine.kt:91-93, 97-108, 143-146, 166-189). The late callback only compensates when desiredMockMode is still false (:101-105, 166-172). A stop and a newer start are distinguishable because the newer start sets desiredMockMode true while the monitor is held.
- The monitor does not span completion.await or withTimeoutOrNull: the await is outside the synchronized block (:110-117; disableOnce :157-163). The monitor is reentrant and the real gateway only calls onComplete synchronously on the same thread in its catch path (GmsFusedLocationGateway :37-45), so the reentrant compensation is safe.
- The cross-session test starts A, cancels it, stops, starts B, completes B first, then completes A late, and asserts no extra false is issued (FusedMockLocationEngineTest.kt:158-190). It exercises the exact ordering and fails on the old generation-only code.
- Residual 1 (PLAUSIBLE/LOW): the monitor is held across the non-suspend gateway.setMockMode call (:101-107, :160). That call initiates a GMS Task and can perform a short binder enqueue. A callback on the main thread that wants the monitor can wait for that call. There is no suspend or await inside the monitor and no deadlock was found, but strict no-main-blocking needs device timing (needs-device). If this is a concern, use a single serialized worker for gateway calls instead of a monitor.
- Residual 2 (PLAUSIBLE/LOW): stopMockMode updates desired=false under the monitor (:143-146) and then issues the false in a separate monitor acquisition inside disableOnce. A start that interleaves between those two steps would set desired=true and could then be disabled by the stop false. The current runner serializes start and stop (previous.join before start), so this window is not reachable today; it becomes a real race only if concurrent start and stop is ever allowed. Add a comment or a token re-check inside disableOnce if that changes.


### F3 - CONFIRMED fixed for the reported paths; one LOW gap

- beginStart is the first statement of handleStart (MockLocationService.kt:104), so a duplicate start never reaches preflight or startForeground. The guard also refuses any start after a stop request (ServiceStartGuard.kt:24-31, 48-50).
- All preflight failures go through failEarly, which calls requestStop and cancels the gate job (MockLocationService.kt:319-327; call sites :110, :115, :119). The startForeground catch and bad-spec paths do the same (:132-138, :149-153). The gate failure requests stop (:164); the gated coroutine checks canStartRunner immediately before runner.start (:171-179). handleStop, onTaskRemoved and onDestroy all requestStop, cancel the gate job, and perform a bounded stopAndJoin (:199-208, :210-221, :231-243).
- ServiceStartGuardTest covers duplicate refusal, stop during the gate, start after stop, and finishStart re-opening the window (ServiceStartGuardTest.kt:11-57). It is meaningful at the guard level.
- Gap 1 (LOW): onStartCommand null-intent branch (:79-85) and unknown-action branch (:90) call stopSelf without requestStop or startGateJob.cancel. If such an intent arrives while a start is gated, the gate can still launch the runner. The null path is system-only and START_NOT_STICKY, and the unknown-action path has no current caller, so this is not reachable from the app today; route both through the same guard and cancel helper for completeness.
- Gap 2 (test, not code): there is no MockLocationService or Robolectric integration test; the guard unit test does not prove that every service path calls it. The code review confirms the call sites, but a service test for failEarly during the gate and stop during the gate would make the fix regression-proof.
- Residual (1B, not a 1A regression): a start while a session is already Running still passes beginStart because startInFlight is false after the gate, and it can supersede the session or, if preflight fails, stopSelf it. The UI and tile phase gate is the 1B fix; runner sessionId and join remains the safety net.

### F5 - CONFIRMED fixed

- The teardown now catches Exception, which includes CancellationException, inside withContext(NonCancellable), and the status reset plus onFinished run in an outer finally (MockSessionRunner.kt:250-269). A non-cancellation exception or a CancellationException from engine.stopMockMode cannot skip onFinished.
- True caller cancellation is still rethrown by the loop catch (MockSessionRunner.kt:240-241) and by the start path; the new catch is scoped to the already-NonCancellable teardown, so it does not swallow the caller cancellation.
- MockSessionRunnerTest.kt:452-474 uses an engine whose stopMockMode throws CancellationException and asserts stopCount 1, Idle status, and onFinished 1. This test fails on the old code, which rethrew and skipped the status reset.
- A teardown Error, not an Exception, still reaches the outer finally, so onFinished is preserved for that case too.


### F6 - CONFIRMED fixed

- AppStartupCoordinator returns immediately on a package mismatch and does not release the lease (AppStartupCoordinator.kt:84-89). The test now asserts no provider removal and no release (AppStartupCoordinatorTest.kt:88-108). This matches design 4.3.
- The cleanup still releases only confirmed removals and ignores a release commit failure by leaving the lease on disk (AppStartupCoordinator.kt:91-97), which is the conservative behavior; the next cold start retries.

### F4 - CONFIRMED for the reporter; one MEDIUM remaining

- EngineHealthReporter.onStop now carries a separate failure string (MockLocationEngine.kt:95-101); the composite uses StopOutcome and never fabricates a ProviderStopResult (CompositeMockLocationEngine.kt:117-134); a real LogEngineHealthReporter is bound in production (LocationModule.kt:72-77; LogEngineHealthReporter :117-140). The composite test asserts the hanging engine is reported as a stop failure and the results contain only gps (CompositeMockLocationEngineTest.kt:221-245).
- Remaining MEDIUM: FusedMockLocationEngine.disableOnce completes the deferred on both success and failure because the callback ignores its Throwable (FusedMockLocationEngine.kt:157-163). A failed setMockMode(false) Task therefore returns true, is treated as a successful stop, skips the one retry, and produces no failure in health. The comment at :147-151 says a failed stop gets one more chance, but only a timeout is detected. Consequence: fused mock mode may remain on after a failed disable, and the log says nothing; LM provider cleanup is unaffected because fused has no system provider. This is not a HIGH for provider ownership, but it is a real fused-teardown and observability gap. Fix: make disableOnce complete with the Throwable, return success only when it is null, retry once on error as well as timeout, and report the failure to health by throwing a dedicated exception so the composite records StopOutcome.failure, or by passing a health sink into the engine. Add a fake-gateway test that completes the disable with an error and asserts the retry and the failure.
- LOW: LogEngineHealthReporter.onPush logs every non-Ok engine outcome (MockLocationEngine.kt:126-130). A permanently failing engine that coexists with a working engine logs once per tick at 1 Hz for the whole session. Consider logging only on transition or rate-limiting; this is log hygiene, not correctness.

### F8 - CONFIRMED fixed

- ProviderStopResult is produced only by LocationManagerMockEngine and is documented as real provider names only (MockLocationEngine.kt:35-46, 73-82; LocationManagerMockEngine.kt:226-241). The composite returns only the engine results and reports engine-level failures separately (CompositeMockLocationEngine.kt:117-134).
- The only lease release inputs are the LM results and the coordinator LM clearStale results (LocationManagerMockEngine.kt:141-143; AppStartupCoordinator.kt:91-97). Health is not wired to lease release. No synthetic engine name can reach release.
- The composite test stop-timeout case asserts results are exactly the gps result and the failure is in health (CompositeMockLocationEngineTest.kt:221-245), so a regression to synthetic results fails.


## R3. Regression check

- **No new deadlock found.** The lock inventory is: ProviderLeaseStore mutex, AppStartupCoordinator startGate, FusedMockLocationEngine stateLock, and the new ServiceStartGuard monitor. They are not nested in a conflicting order: the lease mutex is only around acquire and release; startGate is never held while joining or while taking the lease lock; stateLock is a leaf monitor with no other lock inside; ServiceStartGuard is a leaf monitor on the main thread. The F2 monitor re-enters itself from a synchronous gateway callback, which is safe because it is the same thread and Java monitors are reentrant.
- **No monitor held across a suspend await.** The F2 monitor wraps the non-suspend gateway.setMockMode issuance, not the completion await or the timeout await. The AppStartupCoordinator startGate is a coroutine Mutex intentionally held across the cleanup suspend calls (peek, clear, release); it suspends the service start, not a thread. The residual timing note is in F2.
- **No lost cancellation.** The new catch in the runner teardown is scoped to NonCancellable; the loop and start paths still rethrow caller CancellationException. The LocationManager lease path rethrows CancellationException; the fused cancel path rethrows after cancelStart.
- **No over-broad catch regression.** The runner teardown catch is the only new broad catch and is required by F5; it is inside NonCancellable and followed by an outer finally. The provider rollback catches are per-provider and were already documented; the lease failure catch rethrows cancellation.
- **No scope creep.** Fix-round mtimes are confined to the 1A cluster plus the new app ServiceStartGuard and its test. No UI, core:model, core:data, strings, CI, or Gradle file changed after 00:30.
- **Build evidence.** Core:location 62 tests at 00:46:46 and app 32 tests at 00:49:20, both after the last source edit (core 00:46:05, service 00:48:37), 0 failures and 0 errors. Lint at 00:50 reports 0 errors and 15 warnings. Debug APK and classes at 00:49. The release APK and mapping still date from 23:30/23:43, before the fixes, so release was not rebuilt in this round.

## R4. Test quality and remaining gaps

Meaningful new tests verified:

- ProviderLeaseStoreTest: commit versus apply (applyCalls 0), commit false on acquire and release, withLeaseLock serialization, and the peek copy with a fake that returns the stored set (ProviderLeaseStoreTest.kt:32-73, 122-162, 174-176).
- LocationManagerMockEngineTest: acquire and release under lock, rollback after a late lease failure, and the retained lease for a failed provider (LocationManagerMockEngineTest.kt:32-79, 99-106, 124).
- FusedMockLocationEngineTest: the cross-session late completion ordering (158-190), the single-session late completion (133-156), and the stop timeout retry (114-130).
- CompositeMockLocationEngineTest: engine failure reported through health with no synthetic provider result (221-245), push cancellation rethrown (247-261), stop cancellation rethrown (263-276).
- MockSessionRunnerTest: the teardown-throws-cancellation case (452-474), recovery after a stuck teardown completes (476-520), and the single failed push now observing the intermediate Error (176-199).
- AppStartupCoordinatorTest: package mismatch left untouched (88-108) and finishCleanup timeout false (110-131).
- ServiceStartGuardTest: the four guard transitions (11-57).

Remaining gaps:

1. No engine-level test for a failed lease release, even though the fake has failRelease (LocationManagerMockEngineTest.kt:233, 260). Add stop with failRelease true then retry.
2. No MockLocationService or Robolectric integration test for F3 wiring (guard unit test only).
3. No failed fused disable Task test (the F4 remaining MEDIUM); the current stop tests only cover timeout and success.
4. No coordinator test for leaseStore.release returning false during cold-start cleanup.
5. No concurrent start and stop test for the F2 stop window; the runner protocol currently makes it unreachable.
6. Release build and device matrix are still owed; the debug build and lint are not a substitute.

## R5. Residual risks (needs-device)

- A failed fused setMockMode(false) Task is treated as success today (F4 remaining MEDIUM); the real GMS error/ordering behavior and whether the mode stays on are needs-device.
- The F2 monitor is held across the GMS Task issuance call, not the await; main-thread callback latency under a slow binder is needs-device.
- F3 edge: a startService arriving after a stop request on a still-alive service instance is refused by beginStart and does not call startForeground; the 5s foreground-service behavior on OEM builds is needs-device. The null and unknown intent branches also bypass the guard and are a LOW gap.
- Fused late Task ordering, force-stop and LMK cleanup, SharedPreferences commit durability, and onDestroy join completion remain needs-device. The lease heuristic and global provider names remain accepted risks.
- The release APK and mapping predate the fixes; the final release verification and the two-OEM dumpsys matrix are still required before claiming provider cleanup fixed.

## R6. Recommendation

**No HIGH item remains. PROCEED to Phase 1B.**

The F1, F2, F3, F5, F6 and F8 fixes are confirmed by code and the new tests; the cross-session fused test is exactly the ordering the first audit asked for. The only MEDIUM left is the fused disable Task error in F4: a failed setMockMode(false) is currently treated as success, skips the retry, and is invisible to health. It does not leave a system test provider and does not threaten LM lease cleanup, so it is not a BLOCK for Phase 1B, but it should be fixed before the final Phase 1 gate, ideally at the start of 1B: make disableOnce observe the Throwable, retry on error, and surface the failure through EngineHealthReporter. Also close the LOW items: route the null and unknown intent branches through ServiceStartGuard, add the missing engine release-failure and service integration tests, and consider transition-based logging for onPush.

The Phase 1B work should then take over the remaining session-state issues already documented in the first audit (start while Running, stale Error during the startup window, phase-driven UI and tile). After 1B, rerun the debug build, lint, release build, and the device matrix; do not claim provider cleanup fixed without dumpsys location evidence on at least two OEMs.

