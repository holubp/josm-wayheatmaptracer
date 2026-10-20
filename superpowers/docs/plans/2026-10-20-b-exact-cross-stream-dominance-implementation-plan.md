# Exact Cross-Stream Dominance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use
checkbox (`- [ ]`) syntax for tracking. Preserve the controller's existing SDD execution method and reused roles; this header does not authorize fresh per-task children
or Astra.

**Goal:** Add an explicit raw-1 exact execution capability which can avoid losing K-best heading/triple/score work without changing the inferred problem or production
defaults.
**Architecture:** Preserve the existing solver recurrence and old result/work layouts. A separate funded context supplies a pre-triple finite certificate and bound
metrics; a new opt-in entry returns the unchanged inference result plus an immutable sidecar. A distinct five-case CI protocol evaluates resource usefulness once after
review and source freezing.
**Tech Stack:** Java 17, existing Gradle/JUnit 5, Python/pytest/PyYAML for bounded protocol/workflow guards; no new dependency.
**Spec:** [Approved design](2026-10-20-b-exact-cross-stream-dominance-design.md), SHA-256 `ca5137c07a4aeb989542c7eec8676970cc139a113889b2b9c6cc4b8c65a818fb`.
**Base:** `.worktrees/b-observation-ci-20261018` HEAD `5a89a856d27f6002ddba7748c59325c5e3510860`; inference SHA-256
`4e3b10c0c1b589d8457fd6d506d735353b6dd4e62db6258f7557937aa7f8f390`.

## Global Constraints

- PLAN ONLY now: no implementation, experiments, numerical executions, CI launch, commit or push while preparing this document.
- Preserve OBS-01–20, all admitted states/edges, objective and grouping, negative unaries, temperature, cell measure, full FB, exact endpoints, lexical ties, all
  alternatives and failure atomicity.
- Eligibility is K=`min(32, maximumRawAlternatives)` equal to 1 AND turnWeight>0. Native 32 raw/8 distinct remains V2; no changed admission/coarsening or hidden K
  reduction.
- Preserve `ProbabilisticExecutionPolicy.CURRENT_VERSION`, EAGER_V1, EXACT_DOMINANCE_V2 and `ProbabilisticTraceEngine` dispatch. No default/live/replay numerical-policy
  promotion.
- Preserve `InferenceWorkAccounting`, `ProbabilisticInferenceResult` constructors/layouts and old frozen authorities/resources/fixtures. Current changed source remains
  ineligible under an old freeze; never repin it for green tests.
- Existing 96-state, 8M-pair, 128M cumulative-score guards and experimental 64MiB ledger remain. Native ordinary 256MiB identity is a separate baseline, not spare
  capacity.
- Local verification is limited to pure metadata/certificate checks and tiny graphs: <=6 profiles, <=3 states/profile, <=729 complete routes/graph. Exception: one
  3-profile 2x1x4 graph (8 complete routes) exercises nextStates>=4 cache allocation AND a second before-stream with a potentially full raw1 heap. Large arithmetic and
  all five full cases are CI-only.
- No coefficients, observation model, G strength, new cohort, private input, FIT/SELECT/HOLDOUT, historical retry, posthoc smoothing, triple tensor or unbounded cache.
- Approval of this execution plan may authorize ONE new-policy five-case CI run after its verification gates. It does not authorize an old five-case rerun or any repeat;
  retain previous failures unchanged.
- Freeze sources during independent review. Controller owns integration, publication, CI observation, ledger and closure; component GREEN never closes model/release
  gates.

## Review Focus

1. Tiny scale/overflow/subnormals must disable unsafe bounds without concealing original failures — Task 1 `certificateEnclosesSourceTripleOrFallsBack`.
2. Negative costs, rounded ties and signed zeros must preserve ordered winners exactly — Task 3 `rawOneMatchesIndependentEnumerationIncludingTies`.
3. Lazy point/potential validation and cache allocation attempt must precede a skip; optional refusal continues uncached, cancellation/nonoptional failure remains
terminal — Task 4 `lazyValidationAndCacheAdmissionCannotBeSkipped`.
4. Old APIs must reject an unfunded V3 selection; ordinary native32/V2 stays unchanged — Task 2 `oldOverloadsRejectV3BeforeAnyAcquisition` and Task 4 legacy neighbors.
5. Less scored work must mean genuinely avoided terms, with added work, cancellation and ownership visible — Tasks 3–5 metric and lifecycle tests; Task 6 CI report.

## File and actual-seam map

Use `P=src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic`,
`M=src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model`, and
`T=src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic` below. Every path expands against the source worktree.

| File | Responsibility / existing seam |
| --- | --- |
| `P/ProbabilisticInference.java` | Existing 8-arg `solve(profiles,parameters,budgets,region,probe,owner,mode,potential)`; exact branch 650–773; `WorkCounter.score` 1865–1874; `allocated` 2009. Modify shared recurrence only. |
| `P/ProbabilisticExecutionPolicy.java` | Add named V3 constant/enum; retain VERSION/CURRENT_VERSION literal values. |
| New `P/CrossStreamDominance.java` | Bounded mutable state, outward stage certificate, strict partial test; no scoring/graph recurrence. |
| New `M/CrossStreamWorkAccounting.java` | Immutable 20-long metric sidecar; original `M/InferenceWorkAccounting.java` untouched. |
| New `P/CrossStreamInferenceResult.java` | Two-reference record `(ProbabilisticInferenceResult result, CrossStreamWorkAccounting boundWork)`; old result class untouched. |
| New `T/V022CrossStreamBoundTest.java`, `T/V022CrossStreamInferenceTest.java` | Pure enclosure/primitive census and independent tiny solver oracles/failure lifecycle. |
| Existing `T/V022ExactKBestExecutionTest.java`, `FrozenPairLocalEnergyReference.java` | Existing independent retained-prefix and arithmetic reference patterns; do not weaken their oracles or old receipt expectations. |
| New `T/BExactCrossStreamComparison.java`, `T/V022ExactCrossStreamComparisonTest.java` | Separate fixed-five factual assembly, one V3 call per case, bounded export; no G construction or old schedule modification. |
| Existing `T/V022BGapMatchedControlsTest.java` | Read-only reuse `nativeComparisonSource(String)` and `captureNativeComparisonSource(String,Map<String,Object>,CancellationProbe)`; no copy of its inference recurrence or changes to old 27/5 schedules. |
| Existing `T/BNativeProductionComparison.java`, `BThinSwitchbackAcceptance.java` | Read-only fixed source ID allowlist and direct `evaluate(source,path,1,probe,owner)` ordered full-edge truth oracle; no fabricated native roundtrip reference. |
| New `.github/workflows/b-exact-cross-stream-checks.yml`, `scripts/verify-b-exact-cross-stream.py`, `scripts/tests/test_b_exact_cross_stream_workflow.py`, `scripts/gradle/exact-cross-stream-test.gradle` | Fixed comparison/preflight guards and exact Test-task forcing; preserve every old workflow and no-skip gate. |
| New `src/test/resources/b-exact-cross-stream/protocol.json`; `DEVELOPMENT.md` | Pending comparison protocol, then reviewed source freeze/one-run instructions. No old authority edits. |

### Frozen interfaces and ownership decisions

- Add `Mode.EXACT_CROSS_STREAM_RAW1_V3`, version `terminal-path-count-cross-stream-raw1-v3`; leave CURRENT_VERSION at scored-work-v2.
- Add public `CrossStreamInferenceResult solveCrossStreamRaw1(List<InferenceProfile> profiles, EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion
  region, CancellationProbe probe, AttemptMemoryLedger.Owner resultOwner, AdjacentStatePotential potential)`.
- Every old public `solve` overload rejects an explicitly supplied V3 before checkpoints, profile access or owner allocation. The new entry alone calls a private
  same-name overload with the extra last argument `CrossStreamDominance.State boundState`; old entries pass null. Preserve existing public/private reflection signatures.
- Shared `enumerateKBest(..., AdjacentStatePotential potential)` remains as a null-state wrapper; its new same-name overload adds `State`. Do not create a second solver.
  V3 selects the existing exact descriptor path and computed-score transition semantics, while EAGER/V2 comparisons remain unchanged.
- New helper signatures: `State.beginStage(int index,int beforeStates,int priorStates,int nextStates)`, `double upperTriple(double leftChainage,double
  rightChainage,double weight,double scale,State state)`, `boolean strictlyDominatedRaw1(double prefix,double unaryPair,double upper,double worst,State
  state,CancellationProbe probe)`.
- Use NaN ONLY as internal UNSAFE certificate sentinel, never as a returned source cost. Failed certificate resumes original arithmetic/scoring; it must not synthesize
  NUMERIC_FAILURE.
- Original/fallback paths and new helpers must not allocate per query; no capturing callback in the hot loop. All new diagnostic strings are static literals; no
  arrays/maps inside the helper.
- Fund the new entry transaction before new allocation. State fields are exactly twenty longs + two long ceilings + two cached doubles + two ints =200 field bytes:
  `objectBytes(200)=216`.
- Immutable sidecar fields are exactly twenty longs: `objectBytes(160)=176`; wrapper is two references: `objectBytes(16)=32`. Reserve all 424 payload bytes before
  inference; adopt State then retain the 208 publication bytes as active reservations until immutable construction.
- State is temporary; after snapshot it closes before the wrapper is transferred. Sidecar+wrapper retain 208 bytes until caller result-owner closure, in addition to the
  unchanged original result. No retained duplicate State/array, optional wrapper or string allocation is hidden in that sum.
- Use a fresh child result transaction; 424 is additional caller-declared payload, NOT a JVM/RSS or whole-attempt peak promise. `AttemptMemoryLedger` lines13–18
  explicitly exclude recursive ledger bookkeeping charges; preserve that contract, existing index/lease/owner implementation and guards rather than claiming bookkeeping
  is zero or included in 424. Close the transaction on exception; preserve original caller/profile owners. Reserve-before-allocation includes refused wrapper publication.
- `CrossStreamWorkAccounting` accessors, in field order:
  `stageCertificates,certificateFallbacks,boundQueries,boundFallbacks,finiteTests,magnitudeTests,arithmeticOps,outwardSteps,partialAdditions,strictComparisons,boundCheckpoints,skippedStreams,skippedLogicalPrefixes,firstHeadingEvaluations,secondHeadingEvaluations,wrapEvaluations,huberEvaluations,tripleEvaluations,ceilingChecks,boundUnits`
  (all long).
- Receipt version comes from a static method/constant, not an extra per-record String field. Constructor validates nonnegative counts, `skippedStreams<=boundQueries`,
  raw1 `skippedLogicalPrefixes==skippedStreams`, and all checked sums.

### Primitive, finite-check and cancellation contract

- Eligibility guards stay outside original term evaluation only when they access existing scalar K/mode. No new point or cost access for K>1 or turnWeight==0.
- In eligible streams retain original admission, nextCounts/logical counts, unaryPair, ALL three `point()` calls and mandatory optional-cache allocation attempt. Only
  then invoke bound entry checkpoint/certificate; defer heading cache lookup/fill ONLY on a proven skip.
- Essential sequence: lazily cache `A=nextUp(PI)` / Q initialization once; `x=up(A/scale)`; source-Huber upper arm; EXACT source m; `B=up(weight*H)`; `upper=up(B/m)`;
  finite nonnegative intermediate checks; Q guards; L=`prefix+unaryPair`; strict L>worst. No completed prefix+increment evaluation before score admission.
- `beginStage` resets cached m/upper to NaN and certificateState=UNCOMPUTED for exactly the new profile index; adds dimension ceilings without reading costs/points.
  `upperTriple` is called only after the original point/cache allocation boundary AND a full heap, then caches SAFE or UNSAFE once for that stage; no unsafe
  recomputation. Other stages never borrow that certificate.
- Exact stage finiteTests order: `leftChainage,rightChainage,weight,scale,m,x,halfUp (quadratic arm only),H,B,upper`. Each means one actually reached `Double.isFinite`
  call, incremented before invocation: ten on a successful quadratic certificate, nine on a successful linear certificate, fewer on short-circuit refusal.
- Exact stage magnitudeTests order: `left>=0,right>left,weight>0,scale>0,m>0,x>=0,x<=1,halfUp>=0 (quadratic only),H>=0,B>=0,upper>=0`: eleven quadratic/ten linear. The
  x<=1 branch comparison counts here once, never also in strictComparisons.
- Stage arithmeticOps: source chainage subtraction, *.5, max; A/scale; quadratic .5*x and halfUp*x OR linear x-.5; weight*H; B/m: eight quadratic/seven linear. Each
  actual nextUp after those bound intermediates counts outwardSteps: five/four. Initialization counts scalb(Q) once in arithmeticOps and nextUp(PI) once in outwardSteps.
  Underflow up(0) stays a valid positive enclosure.
- Query guard order: new query entry poll; full-heap and SAFE-certificate dispatch; finiteTests prefix/U/worst; two abs calls in arithmeticOps; magnitudeTests
  |p|<=Q,|U|<=Q,upper<=Q; L=p+U counts one arithmeticOp AND its descriptive partialAdditions subset; L>worst counts strictComparisons only. Poll again before accepting
  true. Equality/signed zeros fall back; negative unary is never removed. No epsilon.
- Primitive counters count only these explicitly named bound operations. Integer index/control dispatch, field loads/stores and checked metric increments are not floating
  primitives; their bounded work is included in admitted blocks. `ceilingChecks` counts stage ceiling derivation/admission and each query block admission, not recursively
  its own counter update. Original heading/wrap/Huber/triple counters count actual calculation invocations, not their internal FLOPs or cache hits.
- Unit admission is conservative and separate from score units: 8 initialization units, 128 per eligible stage, 64 per eligible nonempty before-stream query, including a
  non-full heap query. Stage blocks include 34 named primitive events on the longest successful certificate path, <=48 checked metric-field updates, and <=10 remaining
  scalar/checkpoint/admission events; query blocks include <=12 named primitive/checkpoint events, <=20 checked metric-field updates and <=16 remaining dispatch/admission
  events. These bounds fit 128/64 without treating metric increments as free. Count per-operation fields immediately with addExact, not an unspecified aggregation. Blocks
  are labelled Java-level work envelopes, NOT CPU instructions or transition scores. Snapshot/constructor checks run once under the pre-reserved metadata payload.
- At original stage entry derive q=`multiplyExact(multiplyExact(a,b),c)`. Accumulate query ceiling Σq and unit ceiling `8+128*stageCount+64*Σq` with checked arithmetic,
  no graph scan/array. This bounds all certificate/query attempts, including fallbacks; actual `boundUnits` records admitted blocks, while primitive counters record
  executed operations/subsets, not summable independent timings.
- A ceiling/counter overflow is ArithmeticException through existing numeric-failure cleanup; an internal charge beyond its derived ceiling also fails closed before
  further bound work. Never reset/increase 128M, subtract from score counts or charge a complete score as a bound.
- New checkpoints: before stage block admission, after original point/cache admission at query entry, and immediately before accepting a skip. Certificate initialization
  is one bounded <=128-unit block; no asynchronous work. Checkpoint-triggered cancellation is terminal, never certificate fallback.
- Add original-term calculation metrics ONLY for V3 eligible execution at actual firstHeading/secondHeading/wrap/Huber/triple calculation sites. Cache hits do not count
  as calculations; no fictional saved count or changed old global diagnostic receipt.
- Old EAGER/V2 routes have null State: no new allocation, callback, bound checkpoint or metric update. K>1 via the new capability returns the original V2 arithmetic with
  zero bound/term metrics and explicit ineligible-K disposition in its outer receipt; it never changes native production dispatch.

### Task 1: Implement the finite certificate and bounded sidecar

**Files:** Create the helper, accounting record and `V022CrossStreamBoundTest` listed above; no solver/policy switch yet.
**Consumes:** actual source triple/Huber/wrap arithmetic and `Owner.reserve/adopt`; **Produces:** the helper signatures and 20-field snapshot contract above.
- [ ] Write `certificateEnclosesSourceTripleOrFallsBack`: independent arithmetic reference for both Huber arms, pi boundary, scale MIN_VALUE/MAX_VALUE, w=0/MAX_VALUE,
  subnormal m and overflowing coordinate differences. Ordinary weight=scale=1 on the right-angle three-point source MUST give a finite positive certificate, so an
  always-UNSAFE skeleton is a genuine RED; successful finite upper encloses actual T.
- [ ] Add `strictBoundKeepsEqualitySignedZeroAndNegativeUnary`: p=-16,U=-8, positive upper; strict gap vs equal threshold, +/-0, NaN/Infinity/Q edges.
  `counterCeilingsAnd424BytePayloadAreEnforced` independently checks formulas, checked overflow and 216/176/32 charges/refusal cleanup.
- [ ] Run behavioral RED using a bounded incorrect-certificate skeleton, not merely a missing-class compile failure; retain XML/log and exact source snapshot before
  GREEN.
- [ ] Implement only source-backed outward arithmetic and counted state; an unrecognized unsafe case must fall back. Do not implement the full K-best recurrence in
  tests/helper.
- [ ] GREEN: `JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk sh ./gradlew test --tests '*V022CrossStreamBoundTest' --no-daemon --console=plain`; expect
  zero failures/errors/skips and no captures/solves.
- [ ] Self-audit finite proof and payload census; commit only this task's paths after its required review checkpoint: `feat: add bounded cross-stream certificate
  accounting`.

### Task 2: Add the funded opt-in API without changing old layouts

**Files:** Modify Inference/ExecutionPolicy; create result wrapper and `V022CrossStreamInferenceTest`; original result/work/engine classes remain unchanged.
**Consumes:** Task 1 state/record; **Produces:** funded `solveCrossStreamRaw1(...)` and private shared-state overloads.
- [ ] RED `oldOverloadsRejectV3BeforeAnyAcquisition`: an empty/throwing profile list, failing probe and near-zero ledger prove rejection before all three.
  `newEntryReservesSnapshotAndWrapperBeforeSolver` refuses 424-payload setup without a partial inference result.
- [ ] Implement the transaction, reservations and V3 dispatch guard; `CURRENT_VERSION`, parameter default versions and production engine calls stay unchanged. Initially
  share original V2 scoring without pruning so funding/metadata tests can GREEN independently.
- [ ] `newEntryOwnsBothSuccessfulAndTypedFailureSidecars` verifies actual work status, 208 retained additional payload, cancellation/exception closes new scope, original
  profile owner survives, final closed ledger zero. Include exact publication-reservation refusal and original TraceMemoryLimitException semantics.
- [ ] Run only the two new classes plus `*V022ExecutionWorkReceiptTest` and `*V022ProbabilisticMemoryTest` focused lifecycle methods; preserve distinct RED/GREEN XML
  before Gradle overwrites results.
- [ ] Commit scoped API/layout delta after checks: `feat: expose funded raw-one execution comparison`.

### Task 3: Integrate strict pre-triple skipping in the actual recurrence

**Files:** Inference/helper/new inference test only. **Consumes:** funded State; **Produces:** exact successful outputs and factual new work metrics.
- [ ] RED `rawOneMatchesIndependentEnumerationIncludingTies`: independently retain prefixes at EACH target pair/stage with original cap, rounded grouping and lexical
  ties, following the existing `V022ExactKBestExecutionTest.reference` contract without production helper calls. Compare ordered raw path/energy bits to V3. A global
  exhaustive final-path sort is NOT the finite-precision retained-prefix oracle: later rounding can collapse energy differences and reverse lexical preference.
- [ ] Separately enumerate <=729 complete paths for partition/posterior checks using the existing independent 1e-12 tolerance, not a new exact-summation claim.
  V2-versus-V3 partition, every posterior row, credible set and all successful raw-result fields must match bits exactly. Include nonzero orientation/turn/guide, nonunit
  T, negative unaries, unequal widths and exact anchors.
- [ ] RED `strictWitnessAvoidsActualTripleWorkNotOnlyScoreUnits`: freeze a tiny unequal-geometry witness before evaluating V3; require skips>0, fewer actual
  triple/wrap/heading calculations than its independently counted V2 calculation sites, unchanged FB/logical counts and identical successful geometry. No hardcoded
  full-case saving forecast.
- [ ] Insert only the specified pre-triple branch. On skip retain nextCounts/logical-extension accounting and untouched heap; on fallback execute original
  heading/cache/triple/increment/prefix score in original grouping/order.
- [ ] `rawCapsTwoAndThirtyTwoRemainUnpruned` and `singleAndTwoProfileGraphsDoNotEnterBounds` compare raw/distinct order, terminal saturation, completion/truncation and
  all output bits; K32 never enters raw1 bounds.
- [ ] `tiesPlateausAndRoundedCollapseNeverSkip` covers equal L/W, zero turns, I rounding to U, signed zeros, Q edges and mixed-sign extreme finite values; validate field
  bits with `doubleToRawLongBits`, not tolerance.
- [ ] GREEN new tests and existing `*V022ExactKBestExecutionTest`, `*V022SingleRoutePosteriorRoundoffTest`, `*V022AdjacentStatePotentialTest`; keep local graph limits
  above. Commit: `perf: skip strictly losing raw-one streams before turn evaluation`.

### Task 4: Prove failures, cancellations and old execution compatibility

**Files:** new inference test; extend existing neighbor assertions only when a new meaningful contract is absent. **Consumes:** Task 3 actual mode; **Produces:** stable
numerical/resource/failure evidence.
- [ ] RED `lazyValidationAndCacheAdmissionCannotBeSkipped`: throwing point/potential inputs and cache allocation attempt occur at original lazy use, before bound
  acceptance. Use the explicit 2x1x4 three-profile exception (eight routes): nextStates>=4 triggers allocation, while the second before-stream can be dominated with a
  full heap. Assert the retained attempt order and owned bytes; `ScalarReuseTable.create` catches ResourceLimitException and returns null, so optional refusal MUST allow
  uncached continuation and may still permit a valid skip. Cancellation/nonoptional failure remains terminal. Huge finite points/infinite differences certify safely;
  nonfinite point construction still fails unchanged.
- [ ] `cancelAtBoundEntryAndSkipAdmissionPublishesNothing`: deterministic probe (no sleeps), cancellation before certificate, after point/cache work and before skip;
  assert no returned path/sidecar, caller data intact and new ownership closed after scope exit.
- [ ] `unsafeCertificatePreservesOriginalNumericalFailure` and `budgetRefusalKeepsActualScoresWithoutPartialPublication`: typed failures keep actual counters, no
  fabricated completed score/normal return; cap exhaustion still admits/refuses original score request before calculation.
- [ ] `legacyModesKeepFrozenReceiptCheckpointsAndBytes` reuses unchanged `V022PairLocalEnergyReuseTest.executionCheckpointsRefusalsAndBytesMatchFrozenPrechangeReceipts`,
  `V022ProfileTermReuseTest`, `V022ProbabilisticFixedEndpointTest` and memory/refusal neighbors. No receipt regeneration for old branches.
- [ ] Run the two new classes plus those named neighbors in ONE owned Gradle session; retain source snapshot, full XML counts, actual counters, peak/current/closed bytes
  and diffcheck. Broaden only if an invalidated interface demands it.
- [ ] Independently audit Tasks 1–4 against the approved proof, all unit boundaries and whole delta before any full-case source capture; commit affected regression fixes
  separately. Failure to close a proof/layout blocker stops progression, not tests.

### Task 5: Build the separate five-case source-bound comparison harness

**Files:** new `BExactCrossStreamComparison`, `V022ExactCrossStreamComparisonTest`; no old fixture/schedule/oracle edits.
**Consumes:** existing fixed source/capture seams, factory/admission/adapter APIs and funded V3; **Produces:** one public case receipt and terminal summary per attempted
fixed case.
- [ ] Literal IDs in this order: `switchback-thin-strong`, `switchback-thin-faint`, `same-trail-three-strong`, `same-trail-five-strong`,
  `same-trail-three-separated-strong`. Distinct ID/order hashes enter the new protocol.
- [ ] Reuse source-only seams; create one 64MiB ledger and keep existing 32MiB capture +1MiB metadata reservations. Use actual
  `factory.create(source,factory.profileChainage(source,1.5),25,true,evidence,evidence.fields().get("scalar"),PARAMETERS,probe,true,owner)`.
- [ ] Reuse `FactsExtractor.extract`, continuation proposals, `ProbabilisticOptionalOverflowAdmission.select`, its `buildProfile`, and
  `BObservationAdapter.evaluate(...,true,B0,probe,owner)` exactly as the existing run. No native geographic roundtrip is inserted into identified B0. Require original
  complete +/-25m domain and native-preserving decline identity; no coarsening/admission change.
- [ ] Record raw source/frame/snapshot/facts/lattice/profile point/cell-width bits and hashes before inference; assert actual source/cell binding. New source identity may
  differ from historical producer metadata: do not mislabel old authority as current. An unexpected physical graph difference is a blocker, not an automatic hash update.
- [ ] New method `Map<String,Object> execute(String id,Path output,LongSupplier clock,CancellationProbe cancellation)` invokes ONLY `solveCrossStreamRaw1` once with `new
  TraceBudgets(96,8_000_000,128_000_000,1,1)`, unchanged PARAMETERS and null potential. No G field/table/views, old V2 rerun or hidden second solve.
- [ ] TDD fast `fixedInventoryAndPolicyCannotDrift`, `sourceBoundaryCannotReceiveTruth`, `missingRefusalCutoffAndExportOverflowStayUnavailable`,
  `allAlternativesAndRawBitsAreRetained`; use controlled nonnumerical outcomes. Add a tiny actual 4-profile call proving shared solver integration, never acquire a full
  five-case source locally.
- [ ] Only after a factual completed path, reproduce the existing five IDs' `assessSwitchbackReturnedPath` contract:
  `BThinSwitchbackAcceptance.evaluate(BThinSwitchbackFixtures.source(),path,1,probe,owner).receipt()`, referenceIdentity
  `ORIGINAL_FROZEN_METRIC_SOURCE_PRESERVATION_REFERENCE`. Source lines1326–1333 classify BOTH switchbacks, BOTH same-trail cases and separated case as curved; the earlier
  seven THIN_CASES retain their separate BThinStrandAcceptance/expected-route-kind oracles untouched. Assert this dispatch explicitly, and verify the frozen source equals
  this reference for these five before using it. Never generalize source=truth to other controls or claim a native geographic roundtrip. False/unavailable stays data,
  never inference input.
- [ ] Output `build/exact-cross-stream/<id>.json`, `summary.json`, `phase.json`: source commit/policy/graph/parameter identities; actual calls vs returns; original work;
  20 bound metrics; actual result/completion/path/raw-bit fields; geometry disposition; current/peak/post-close owners. Missing values stay null, no engine entry inferred
  from intent.
- [ ] Bound each leaf<=65536B, aggregate<=2GiB, safe cause tokens only; terminal overflow preserves failure evidence without route truncation. Whole-attempt cleanup and
  cancellation/300s deadline use the same probe; no retries or resource fallback.
- [ ] Run only fast fixture/receipt methods locally, not `executesOneFrozenCase`. Tag that actual method `dedicated-experiment`, require
  `V022_RUN_EXACT_CROSS_STREAM=true` AND literal `V022_EXACT_CROSS_STREAM_CASE` before acquisition. Default discovery has zero skips; explicit missing env fails.
- [ ] Commit scoped harness: `test: add fixed raw-one execution comparison harness`; freeze actual source/member hashes, named artifact bounds and pending protocol. This
  is not model qualification.

### Task 6: Freeze and authorize the ONE new-policy GitHub run

**Files:** new workflow/verifier/workflow-test/protocol; DEVELOPMENT only. **Consumes:** committed reviewed source and immutable five recipes/old receipts; **Produces:**
source-authenticated single-run protocol, never new authority for old models.
- [ ] Protocol fields: named comparison/version, execution/source commit and exact source closure, helper/oracle/fixture hashes, five IDs, raw1/distinct1,
  96/8M/128M/64MiB, 300s cooperative/360s external, null potential/eta0-only, known previous receipt pins, approval/engineEligible false until controller freeze after
  review.
- [ ] Source/member digests are generated from the final implementation commit; do not invent future hashes. Protocol excludes its own/workflow hash; workflow literal
  authenticates its reviewed digest. Pending always refuses execution. Controller verifies only approved promotion/hash delta; no old authority repin.
- [ ] Push-only branch `codex/b-exact-cross-stream-20261020`; contents:read, no dispatch/PR/release/tag. Five literal matrix IDs, fail-fast:false, max-parallel:1,
  per-case job15m; compile/current tiny preflight outside each single attempt timer, no recursive Gradle/engine retry.
- [ ] Compile and verify `sh ./gradlew testClasses --no-daemon --console=plain` OUTSIDE the timer, then authenticate source/protocol and the explicit compiled
  core/helper/test/resource closure before entering it. New reviewed Groovy init script configures only
  `rootProject.tasks.named('test',org.gradle.api.tasks.testing.Test)` after projects evaluation with `outputs.upToDateWhen { false }`; it changes no classpath, filter or
  inputs. [Gradle API](https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.api.tasks/-task-outputs/up-to-date-when.html) supports this predicate since0.9;
  wrapper is9.4.1.
- [ ] Actual CI command: `timeout 360s env V022_RUN_EXACT_CROSS_STREAM=true V022_EXACT_CROSS_STREAM_CASE="$CASE_ID" sh ./gradlew -I
  scripts/gradle/exact-cross-stream-test.gradle test -PdedicatedExperiments=true -x compileJava -x compileTestJava -x processResources -x processTestResources --tests
  '*V022ExactCrossStreamComparisonTest.executesOneFrozenCase' --no-daemon --no-build-cache --console=plain`. Exclusions are valid only after authenticated testClasses
  outputs exist; init forces the actual Test task. NO --rerun-tasks inside the timeout, because it would recompile dependencies.
- [ ] Cheap CI orchestration regression proves precompile outputs/integrity, Test-task execution twice when requested (not UP-TO-DATE), unmatched selector/missing opt-in
  failure, and that excluded compile/resource tasks never execute inside the timed invocation. Use a metadata-only test selector, never an actual five-case run, to test
  orchestration.
- [ ] Before it, verify exact branch/publication/source/protocol/fixture closure and no pending approval. After terminal wait retain exit/cutoff/phase facts; shell intent
  cannot prove entry/return/root closure. Check source execution independently from geometry; resource/numeric/cancel is failed feasibility, not a quality PASS.
- [ ] Explicit artifact allowlist only summary/phase/current-ID JSON, exact class XML and bounded log/exit leaves under `build/exact-cross-stream`; no workspace/private
  wildcard. Atomic publication, empty named console log allowed, authoritative JSON nonempty. No hidden oversized source/native export.
- [ ] TDD Python authentic pending refusal/approved controlled admission, wrong branch/source/policy/hash/ID/caps, actual command one-attempt order,
  nonzero/cutoff/missing/partial reports, bool-vs-integer counts, overflow and all five ID coverage. Do not stub away source or digest guards.
- [ ] Run `python3 -m pytest scripts/tests/test_b_exact_cross_stream_workflow.py -q`, then `python3 -O -m pytest scripts/tests/test_b_exact_cross_stream_workflow.py -q`;
  py_compile new verifier/test; diffcheck. Reuse existing strict no-skip and dedicated-discovery tests; do not relax validation scripts.
- [ ] Controller self-review then ONE independent Sol review of original spec+whole source/protocol/workflow delta. Freeze writers, source inventory, RED/GREEN evidence,
  old guard refusals and ownership; stage scoped paths only, preserve unrelated blobs. Commit protocol/workflow separately after review.
- [ ] Once the user approves this execution plan and the above source/protocol/review gates pass, controller may publish and observe the single new run without a routine
  extra milestone question. Never automatically repeat failed/missing cases, old baselines or historical32.
- [ ] Report each of five results, actual added/avoided expensive terms, FB/K-best/logical/bound units, memory/time and failure uncertainty. Compare unchanged full-FB
  counts and graph pins to archived prior refusals; numerical winner equivalence comes from proof/tiny exhaustive tests, NOT a fabricated old successful five-case output.
- [ ] Preserve actual current native32/8 ordinary/historical policies as separate baseline outputs, including their refusals and distinct memory envelopes; no new
  native32 solve in this experiment. Original functional model, representative/no-hop/strong-tip/noise gates and release Tasks4–6 remain OPEN.

## Spec coverage and self-review

| Approved design section | Owning tasks / evidence |
| --- | --- |
| 1–2 unchanged problem, frozen provenance, five-case failures | Global constraints; Tasks 4–6 source and baseline checks |
| 3 insertion after validation/cache admission | Tasks 3–4 lazy-site and failure probes |
| 4 finite outward proof, negative unary, strict ties | Task 1 source-derived enclosure; Task 3 exhaustive/rounding tests |
| 5 ordered exactness, K>1 fallback, counts | Tasks 2–4 old-entry rejection, completion and raw32 tests |
| 6 genuine avoided work vs added work | Tasks 3/5 metrics; Task 6 honest once-run resource comparison |
| 7 accounting, deterministic cancel, ownership | Frozen primitive/payload contract; Tasks 1/2/4 lifecycle tests |
| 8 source identities, review and separate release gates | Tasks 4/6 source freeze; no model/release closure claim |

- [x] Plan self-review signatures: every new interface is defined above; existing seams were mapped to actual source; no throw-only API or sidecarless V3 entry.
- [x] Plan self-review scope: the boundary preserves score admission/sum-product arithmetic; only raw1 can skip; no old fixture/authority/record/default migration.
- [x] Plan self-review accounting: 424 new declared payload, 208 retained; original ledger bookkeeping exclusion/owner contracts remain, no spare-reservation assumption.
  Primitive blocks/counters distinguish new work from actual completed scores.
- [x] Plan self-review tests: all five Review Focus items have named tests/tasks; retain failed RED sources and distinct XMLs, no compile-only proof or model-qualified unit
  GREEN.
- [x] Plan self-review handoff: written-plan review pending; no execution has occurred under this plan. Preserve selected SDD/reused-worker route, no fresh role/pool changes.
  A real contradiction stops its task with exact evidence rather than silently changing the approved design.
