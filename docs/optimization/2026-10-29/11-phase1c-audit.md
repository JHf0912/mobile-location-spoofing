# 11 - Phase 1C Audit (coordinate fallback and LOW fixes)

**Date**: 2026-10-30
**Scope**: Phase 1C coordinate input/parse/datum/UI, the LOW1/LOW2/LOW4 fixes, LOW3 documentation, tests, and the final Phase 1 gate readiness.
**Contract**: docs/optimization/2026-10-29/08-phase1-design.md sections 7, 9.3 and 12, plus the approved plan section C and the LOW notes in 10-phase1b-audit.md.
**Method**: read-only review of the changed files/tests plus fresh JUnit XML and lint artifacts. No Gradle command was run by the auditor. Only this report was written.

## 1. Baseline evidence

- Phase 1C edits are at 04:08-04:14: ui/input/CoordinateInput.kt, ui/components/CoordinateInputField.kt, MainViewModel.kt, SessionSpec.kt, FavoritesRetry.kt, MainScreen.kt, MainActivity.kt, strings zh/en, ComponentPreviews.kt; tests CoordinateInputTest, SessionSpecTest, FavoritesRetryTest. The design 08 was not changed in this round.
- Tests: app 94 (6 ServiceStartGuard, 3 SessionFinishOwnership, 12 EditorState, 7 FavoritesRetry, 10 Format, 22 MainUiState, 13 SessionSpec, 3 SessionStateGates, 18 CoordinateInput), core:location 77, core:data 2, core:geo 47; total 220, 0 failures/errors. App XML is 04:14:58 after the last test and main edits; core:location XML is the earlier fresh 77 run; core:data XML is 03:31:45.
- Lint at 04:13:38 reports 0 errors and 15 warnings, but it predates the last main edit (FavoritesRetry.kt 04:14:39); the final-tree lint is not proven by the artifact. Debug APK is 04:14:57. Release APK/mapping are still 23:30/23:43 and were not rebuilt. The Room instrumented DAO test was compiled at 03:36 and remains unrun.

## 2. Verdict table

| # | Audit item | Verdict |
|---|---|---|
| 1 | Parser formats, datum and validation | **CONFIRMED** for the advertised forms and tests; **MEDIUM/PLAUSIBLE C2** the q handling deviates from the design for a valid base coordinate; **LOW** percent-encoded/parameterised geo links |
| 2 | ViewModel coordinate input | **CONFIRMED** for STATIC/ROUTE, WGS84, updateEditorState, return semantics and messages; **MEDIUM C1** the 500-waypoint route cap returns true while the append is silently ignored and the field clears |
| 3 | Coordinate UI | **CONFIRMED** placement, WGS84 label/hint, clear-on-success, 48dp button, wiring, previews and zh/en strings; **LOW C5** no GCJ02-vs-WGS84 difference prompt as design 7.2 requests |
| 4 | LOW1 observed-inactive sync | **CONFIRMED** for every observed inactive phase; **LOW C6** an entirely conflated Idle followed by Starting can still be missed; the test is helper-only |
| 5 | LOW2 authoritative start gate | **CONFIRMED**: statusStore gate and the two messages |
| 6 | LOW4 cancellation test | **CONFIRMED**: cancellation rethrows, no failure/exhaustion |
| 7 | Tests | **CONFIRMED** 220 and meaningful new tests; gaps: no ViewModel/UI coordinate test, lint predates the last edit, androidTest unrun, release old |
| 8 | New HIGH/MEDIUM | **REJECTED** no HIGH; C1 and C2 are MEDIUM/PLAUSIBLE |

## 3. Findings

### C1 - MEDIUM: the 500-waypoint route cap returns success while silently dropping the coordinate

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:93-110, :389-394; app/src/main/java/com/snake/mobilespoofing/ui/components/CoordinateInputField.kt:42-46.
onCoordinateInput parses an Ok pair and calls updateEditorState; in ROUTE mode the update uses appendedWaypoint, which returns the same list when routeWaypoints.size is at EditorSnapshot.MAX_ROUTE_WAYPOINTS. updateEditorState still persists the unchanged editor and syncs the spec, and onCoordinateInput returns true. CoordinateInputField clears the text only when the callback returns true, so at the cap the user sees the field clear and no error, but no waypoint is added. The map and favorite paths also ignore extra points at the cap silently, but the coordinate field makes the loss look like success.

**Fix**: in onCoordinateInput, when mode is ROUTE and the route is at the cap, set a localized message (a new route-full string or a clear reuse) and return false so the field keeps the text; or make appendedWaypoint return a Result and propagate it. Add a ViewModel-level or pure test for the cap.

### C2 - MEDIUM/PLAUSIBLE: a q parameter overrides or rejects an otherwise valid geo base coordinate

**File**: app/src/main/java/com/snake/mobilespoofing/ui/input/CoordinateInput.kt:43-64; tests: CoordinateInputTest.kt:84-98, :140-149.
parseGeoUri always prefers a q parameter. If q parses as a pair, it wins even when the base coordinates are valid; if q is not a pair, the whole URI is Malformed even when the base pair is valid. Examples: geo:39.9042,116.4074?q=Office is rejected; geo:39.9,116.4?q=31.2,121.4 returns 31.2,121.4 rather than the base. Design section 7.1 says the query after geo:lat,lon is ignored and q addresses are not parsed; it only accepts geo:0,0?q=... as 0,0. The tests codify the deviation (geo:1,2?q=Beijing is expected Malformed). The q-pair extension is useful for map share links, but the q-first rule regresses the core geo:lat,lon format in the design.

**Fix**: if q parses as a pair, use it; otherwise fall back to a valid base pair, and only reject when neither is a valid pair. Alternatively parse q only when the base is 0,0 or absent. Update the tests and, if the extension is intended, amend design 7.1.

**Related LOW**: percent-encoded q labels (for example q=31.2,121.4%20(Office) or encoded parentheses) are not URL-decoded and fail. Plain literal labels work. A share link with an encoded label should at least ignore the label part; consider decoding the q value (without using android.net.Uri in a pure module).

### C3 - LOW/MEDIUM (contract): the parser is in app/ui/input instead of core:geo

**File**: app/src/main/java/com/snake/mobilespoofing/ui/input/CoordinateInput.kt.
Design 7.1 and the file list in 9.3 place parseCoordinateInput in core:geo as a pure JVM parser, with its test under core:geo/src/test. The implementation is pure Kotlin and does not use android.net.Uri, but it lives in the app UI package, so the core module boundary and the planned pure-JVM reuse and test location are not honored. Move it to core:geo, or explicitly amend the design; this is an architecture and contract issue, not a runtime bug.

### C4 - LOW (contract): error enum names and granularity differ from design 7.1

**File**: CoordinateInput.kt:7-13.
The design suggests CoordinateInputError with EMPTY, NOT_PARSEABLE, NOT_FINITE, LAT_OUT_OF_RANGE, LON_OUT_OF_RANGE and UNSUPPORTED_SCHEME. The implementation has Empty, Malformed, OutOfRange and NotFinite; latitude and longitude range failures collapse, and an unsupported scheme is reported as Malformed. The UI messages are coherent for the implementation, but the distinct reasons and the naming in the design are not matched. Record or align.


### C5 - LOW (UI gap): no GCJ02-versus-WGS84 difference prompt

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/components/CoordinateInputField.kt; app/src/main/java/com/snake/mobilespoofing/ui/components/StatusHero.kt; app/src/main/java/com/snake/mobilespoofing/ui/components/MapCard.kt.
Design 7.2 asks for a WGS84 label next to the input and a prompt explaining that the map is GCJ02 while the Hero and coordinate are WGS84. The field label and hint state WGS84, but there is no visible difference prompt on the map or Hero. The field itself is correct; the missing hint is a documentation and UI gap for users copying coordinates between AMap and the app.

### C6 - LOW: an entirely conflated Idle followed by Starting can still miss the end-of-session sync

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:335-342; app/src/main/java/com/snake/mobilespoofing/ui/SessionSpec.kt:65-75; test: SessionSpecTest.kt:159-199.
The LOW1 change is better than the old transition-only check: observeSession calls syncPendingSpec whenever the collected session is inactive (shouldSyncPendingSpecForObservedSession is !isSessionActive). That covers every observed Idle, terminal Error and non-terminal TeardownTimeout. It cannot cover an Idle that StateFlow conflates with a subsequent Starting: the collector sees Starting, which is active, and no sync runs. The new tests call the helper with a concrete inactive state; they do not simulate a StateFlow that skips Idle. The tile-start window therefore remains, though it is narrow because the next start needs main-thread work. A start-path sync or a non-conflated event would close it.

### C7 - LOW evidence gaps

- Lint was generated at 04:13:38, before the last main edit (FavoritesRetry.kt 04:14:39) and before the final app test run; the final-tree lint is not proven by the artifact.
- The Room instrumented DAO tie-break test remains compiled but unrun.
- There is no ViewModel or Compose UI test for onCoordinateInput, the field clear-on-success, or the route cap; the parser tests are pure and the ViewModel is Android-bound.
- Release APK and mapping are stale; the no-map/x86 or AMap-failure device path has no device evidence.

### C8 - CONFIRMED behavior

- **Parser**: plain lat,lon uses the caller default (WGS84 from the ViewModel); geo:lat,lon and geo:lat,lon?z are WGS84; geo:0,0?q=lat,lon(label) uses the q pair; the scheme is case-insensitive; whitespace, negatives and inclusive bounds are accepted; NaN, Infinity, 1e999, empty, malformed and extra tokens are rejected with the expected reasons; the ViewModel never calls onMapPointSelected, so the GCJ02 map path is not reused. The 18 parser tests are meaningful and would fail a naive comma split that ignored datum, bounds or the geo form.
- **ViewModel**: onCoordinateInput returns true only on Ok, replaces the STATIC point and appends a ROUTE waypoint, routes through updateEditorState (SavedStateHandle persist and authoritative pending-spec sync), and sets localized Empty, Malformed, OutOfRange and NotFinite messages on failure. The route-cap success is C1.
- **UI**: the field sits below the map card, is always visible, labels WGS84, hints the two accepted forms, clears only on success, keeps text on failure, has a 52dp apply button and a full-width field, uses rememberSaveable, and is wired through MainActions and MainRoute with a preview. zh and en strings exist for the label, hint, action and all four errors. C5 is the only UI content gap; no token or layout regression was found in code.
- **LOW1**: observed inactive phases sync; active phases Keep. The explicit stop and natural end tests pass for the observed cases; C6 is the conflation residual.
- **LOW2**: startMock gates on statusStore.state.value.canStartNewSession and shows msg_already_running for active phases or msg_teardown_in_progress for the non-terminal teardown window; the UI lag no-op is fixed. zh and en strings exist.
- **LOW4**: the cancellation test launches a never-completing flow, cancels it and asserts the job is cancelled with zero failures and no exhaustion; collectFavoritesWithRetry rethrows CancellationException. 7 FavoritesRetry tests pass.
- **LOW3**: the per-ViewModel-lifetime retry budget is documented in FavoritesRetry.kt and MainViewModel; no reset on emission; the trade-off is recorded.
- No regressions were found in the earlier B-2, B-1, 1A, G2-G7, R1-R4, H1/H2 or B-2 M1/M2 suites; all 220 tests pass.

## 4. Residual risks and needs-device

- C1 and C2 are code-level and can be fixed and tested on the JVM.
- C3, C4 and C5 are contract or content deviations, not runtime failures; they need a design-doc update or a small follow-up.
- C6 is a narrow StateFlow conflation window; it does not occur when the collector observes the inactive phase.
- The actual purpose of 1C is the no-map device path: an x86 emulator or an ARM device where AMap fails to load must still show the field, accept a pasted coordinate, persist it, and start a session. That is needs-device; a code review and JVM parser tests do not prove the AMap-failure UI or the native map placeholder.
- The final-tree lint, the Room instrumented test, the release rebuild and the two-OEM matrix remain owed.

## 5. Recommendation

**No HIGH issue found. Fix C1 and C2 before the final Phase 1 gate; record or fix C3, C4 and C5; C6 is a LOW residual.** The coordinate fallback parser, ViewModel entry point and UI are functionally confirmed for the advertised forms, the WGS84 datum is not mixed with the GCJ02 map path, and the LOW1/LOW2/LOW4 fixes are confirmed.

Concretely:

1. C1: make onCoordinateInput return false with a route-full message when the 500-waypoint cap prevents the append, and add a test.
2. C2: fall back to a valid base coordinate when a q parameter does not parse as a pair (and decide whether a q pair may override a valid base); add tests for geo:lat,lon?q=address and percent-encoded labels.
3. C3: move parseCoordinateInput to core:geo or amend design 7.1 and the 9.3 file list.
4. C4/C5: align the error enum and add the datum difference hint, or record them as accepted deviations.
5. C6: optionally sync on the start path or use a non-conflated event; not blocking.
6. Re-run lint after the last edit, run the Room instrumented test, rebuild release, and complete the no-map/x86 plus two-OEM device matrix for the final Phase 1 gate.

If the coordinator accepts C2 q-first as a deliberate design amendment and C1 as an accepted cap limitation with a documented message, the final gate can proceed with those records; otherwise fix C1 and C2 first. The 1C core path is otherwise ready for the final gate.

---

# Re-audit (C1/C2)

**Date**: 2026-10-30 (after the C1/C2 fix round)
**Scope**: the route-cap refusal, the q/base parser precedence and fallback, the updated tests, C3-C6 recording, and any new HIGH/MEDIUM.
**Method**: read-only review of the changed files/tests plus fresh JUnit XML and lint artifacts. No Gradle command was run by the auditor.

## R0. Fresh evidence

- C1/C2 edits are at 04:27-04:33: MainViewModel.kt, ui/input/CoordinateInput.kt, CoordinateInputTest.kt, EditorState.kt, EditorStateTest.kt, strings zh/en. No other source areas changed; design 08 was not edited.
- Tests: app 101 (6 ServiceStartGuard, 3 SessionFinishOwnership, 13 EditorState, 7 FavoritesRetry, 10 Format, 22 MainUiState, 13 SessionSpec, 3 SessionStateGates, 24 CoordinateInput), core:location 77, core:data 2, core:geo 47; total 227, 0 failures/errors. App XML is 04:33:42 after the last main edit (EditorState.kt 04:33:21); core:location XML is the earlier fresh 77 run; core:data XML is 03:31:45.
- Lint at 04:32:50 reports 0 errors and 15 warnings, but it predates the last main edit (EditorState.kt 04:33:21); the final-tree lint is not proven by the artifact. Debug APK is 04:33:41. Release APK/mapping are still 23:30/23:43 and were not rebuilt. The Room instrumented DAO test remains compiled but unrun.

## R1. Verdicts

| Item | Verdict |
|---|---|
| C1 route-cap refusal | **CONFIRMED fixed**: appendRouteWaypoint returns Full at the cap; onCoordinateInput reports msg_route_full and returns false; the field keeps the text; map/favorite paths unchanged; EditorStateTest covers 500/499. LOW: no ViewModel-level cap test; map/favorite cap remains a silent ignore |
| C2 q/base precedence | **CONFIRMED fixed** for the requested cases: a valid q pair wins; a label q falls back to a valid non-0,0 base; 0,0 placeholder with a label q is rejected; q out-of-range/non-finite propagates; no q uses the base; %2C is decoded; no regression in the plain/geo/z/no-q cases. LOW contract notes: the design still says the query is ignored and 0,0 is accepted; percent-encoded labels beyond %2C are not decoded |
| C3-C6 | Unchanged as instructed and recorded in this report; not silently dropped. The design doc has not been amended |
| Tests | **CONFIRMED** 227, 0 failures; new cap and q tests are meaningful; no regressions in earlier suites; lint predates the last edit, release stale, androidTest unrun |
| New HIGH/MEDIUM | **REJECTED** none found |

Recommendation: **PROCEED to the final Phase 1 gate**, after recording the C2 design amendment and rerunning lint against the final tree.

## R2. Details

### C1 - CONFIRMED fixed

**Files**: app/src/main/java/com/snake/mobilespoofing/ui/EditorState.kt:50-73; app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt:93-135, :389-413; app/src/main/java/com/snake/mobilespoofing/ui/components/CoordinateInputField.kt:42-46; test: EditorStateTest.kt:147-158.
- appendRouteWaypoint returns RouteAppendResult.Appended when the route has fewer than EditorSnapshot.MAX_ROUTE_WAYPOINTS and Full at the cap; the point is never silently discarded by the helper.
- onCoordinateInput first parses the input; on Ok it checks the current mode and append result. In ROUTE mode at the cap it sets msg_route_full with the 500 limit and returns false before updateEditorState. The field clears only on a true return, so the typed text stays on screen and the user sees the cap message.
- The updateEditorState transform also maps Full to unchanged state as a defensive branch. Because the pre-check and the update both run synchronously on the main thread, the transform branch is not reachable in normal use; if it were, the method would still return true while the point was dropped. This is a theoretical residual, not a current race.
- onMapPointSelected and pickFavorite still use appendedWaypoint, which maps Full to the unchanged list. The map and favorite paths remain silent at the cap, as the implementation report states. They have no text field to explain the cap; this is a pre-existing LOW UX issue, not part of the C1 fix.
- The cap test builds a 500-point route, asserts Full, drops one point, and asserts Appended; it is meaningful. No ViewModel-level test asserts the message and false return, so the integration is code-reviewed only.

### C2 - CONFIRMED fixed

**File**: app/src/main/java/com/snake/mobilespoofing/ui/input/CoordinateInput.kt:44-97; tests: CoordinateInputTest.kt:85-152.
- If no q parameter is present, the base pair is parsed as WGS84, so geo:lat,lon, geo:lat,lon?z=..., and geo:lat,lon?foo=... keep the old behavior.
- If q is present, the value is trimmed, %2C (upper or lower case) is decoded to a comma, and a trailing parenthesized label is stripped. A q that parses as a pair wins (WGS84). A q that is malformed as a pair falls back to the base pair only when the base is valid and not the 0,0 placeholder. A q that looks like a pair but is out of range or non-finite propagates that q reason instead of falling back. An empty q also falls back to the base. A 0,0 placeholder with a label q is rejected.
- The tests cover: q with a label, q without a label, q wins over a different base, a label q falling back to a valid base, an empty q falling back, 0,0 placeholder rejection, q OutOfRange and NotFinite, an out-of-range base with a label q, %2C in both cases, scheme case-insensitivity, and all the old plain, geo, z, NaN, Infinity, empty, malformed and extra-token cases. The 24 tests are meaningful and would fail the old q-first-only behavior for geo:1,2?q=Beijing.
- Contract notes: design 08 section 7.1 still says the query after geo:lat,lon is ignored and geo:0,0?q=... is accepted as 0,0. The implementation intentionally lets a valid q pair win, falls back to a valid base for a label q, and rejects the 0,0 placeholder. The task expected these outcomes; the design needs an amendment recording the q extension and the safer 0,0 rejection. Percent-encoded labels beyond %2C (spaces, encoded parentheses) are not decoded; a q-only link with such a label still falls back to a 0,0 base and is rejected. That remains a LOW share-link limitation.

### C3-C6 - recorded, not silently dropped

- C3 parser location remains app/ui/input rather than core:geo; C4 error enum names/granularity remain Empty, Malformed, OutOfRange, NotFinite; C5 has no GCJ02-vs-WGS84 difference hint; C6 retains the StateFlow conflation window. The C agent did not change them, as instructed, and this report records them. The design doc has not been amended; the coordinator should either amend 08 or add an accepted-deviations note alongside the C2 q semantics.

### Confirmations and regressions

- 227 JVM tests: app 101, core:location 77, core:data 2, core:geo 47; 0 failures/errors. App XML 04:33:42 after the last main edit; core:location XML is the earlier fresh 77 run; core:data XML 03:31:45.
- The new cap and q tests are meaningful; the earlier parser, ViewModel, UI, LOW1/LOW2/LOW4 and all prior suites show no regression.
- Lint 04:32:50 predates the last main edit (EditorState.kt 04:33:21); the final-tree lint must be rerun. Release is stale; the Room DAO androidTest is unrun; no ViewModel or Compose UI test covers onCoordinateInput or the cap message.

## R3. Recommendation

**PROCEED to the final Phase 1 gate.** No HIGH or MEDIUM issue remains. C1 is fixed with a real refusal and message; C2 fixes the valid-base fallback and the requested q precedence, and the updated tests are meaningful.

Before or as part of the final gate:

1. Record the C2 design amendment in 08: a valid q pair wins; a label q falls back to a valid non-0,0 base; the 0,0 placeholder is rejected; the design query-ignored/accept-0,0 text is superseded.
2. Record C3-C6 as accepted LOW deviations or fix them: move the parser to core:geo, align the error enum, add the datum difference hint, and optionally close the conflation window.
3. Rerun lint after the final edit, run the Room instrumented test, rebuild release, and complete the device matrix, including the no-map/x86 or AMap-failure path where the coordinate field is the only way to select a point.

The map and favorite paths still silently ignore points at the 500 cap; this is a pre-existing LOW UX issue outside the C1 coordinate-field fix. If the final gate wants a consistent cap message for every append path, record that as a follow-up.

If the coordinator requires C3-C6 or the map/favorite cap to be fixed before the gate, treat them as LOW follow-ups; none is a HIGH or MEDIUM blocker. The 1C coordinate fallback is otherwise ready for the final Phase 1 gate.
