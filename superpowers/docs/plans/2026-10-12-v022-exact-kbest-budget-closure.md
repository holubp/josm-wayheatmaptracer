# Exact K-best budget closure implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Preserve the existing implementer and independent reviewer; do not create recursive workers.

**Goal:** Admit corrected full-window B sampling under the existing production work limit by eliminating provably dominated prefix-score evaluations, without changing exact retained paths or numerical objectives.

**Architecture:** Keep the existing forward/backward pass and exact second-order pair-state graph. Replace eager prefix enumeration only for an explicitly selected, versioned execution policy with a bounded worst-first descriptor heap. Preserve eager execution as the compatibility/reference implementation; publish no route on cancellation or resource exhaustion.

**Tech Stack:** Java 17, JOSM 19555, Gradle/JUnit, Format-15 production replay, existing GitHub validation workflow.

**Spec:** Original `josm-wayheatmaptracer-v0.22.0-implementation-plan.md`; approved `2026-09-22-v022-b-reliability-performance-design.md` and safe-performance execution plan; current evidence in `2026-10-11-rc8-maganik-execution.md`.

**Approval state:** APPROVED by maintainer, 2026-10-12, following explicit explanation of transition counts and unchanged fitting behavior. Independent Sol plan review found no blocking ambiguity. Execute the work-accounting amendment; preserve exact outputs and existing caps. This subplan does not waive weak-trace quality, alternative completeness, or stable-release gates.

## Global constraints

- Keep production defaults: 96 states/profile, 8,000,000 pair visits, 128,000,000 cumulative scored transition work, 32 raw alternatives, 8 distinct alternatives.
- Preserve complete state space, arithmetic objective, offsets, lexical tie order, diagnostic objective rows, terminal cardinality and completion semantics. No beam/state pruning, smaller K, hidden second optimizer, smoothing, or primary-path salvage.
- Amend the old requirement to preserve transition count: retain the full eager Cartesian count as a separate logical witness; charge actual computed scores, including rejected cutoff peeks, to the unchanged cumulative work cap.
- Preserve private archives locally. Public CI receives source and public fixtures only. Preserve unrelated files and existing published RC8/RC9 assets.
- No production optimizer edits before maintainer approval of this amendment. No Astra, speed-tier changes or pool changes.

## Review focus

1. Post-addition rounding ties can invert prefix lexical order: scan equal scores fully (Task 2).
2. Long equal-energy streams may save no work: retain honest exhaustion with no published route (Tasks 2–3).
3. Cancellation or owner refusal during heap operations must release scratch and publish nothing (Task 2).
4. Old capture identity must not attest a new runtime algorithm; old archives remain readable but strict receipts mismatch (Task 3).
5. Corrected search boundaries can alter quality even when admission improves: evaluate actual production finalization and Apply separately (Task 4).

## Evidence and decision boundary

Main RC9 is published and verified at c951cec. The isolated full-window prototype has 144 neighboring/Apply tests and four additional refusal controls green; integration is held. Its seven-case stock run fails the long mixed Maganik case at transition attempt 128,000,001. Forward/backward completes at 9,369,647; K-best consumes the remaining 118,630,353 accepted descriptor evaluations before terminal materialization. There is no completed MAP path to salvage. Geometry work is 23,007,618/64M and feature work 1,230,452/8M, so neither new sampling cap causes this failure.

## Task 1: Freeze sampling correction and independent reference oracle

**Files:** Existing eight-file boundary prototype in isolated branch `codex/v022-weak-concentration-20261012`; `src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/V022ProbabilisticInferenceTest.java` and new `V022ExactKBestExecutionTest.java` in the same package.

**Interfaces:** Preserve all current `ProbabilisticInference.solve` overloads and eager behavior. The test oracle consumes the identical `InferenceProfile`, `EvidenceModelParameters`, `TraceBudgets` and decision region; independently enumerates retained prefix extensions, then applies existing energy/lexical ordering.

- [x] Record prototype commit, all eight paths/hashes, RED evidence, 144+4 GREEN evidence and failed stock admission in the ledger. Prototype `6e4934e6` committed with integration hold; do not cherry-pick alone.
- [x] Add independent small-graph exhaustive/eager oracle tests asserting all ordered raw state sequences, binary64 energies/log-measures, MAP, marginals, terminal counts, disposition and truncation. Cover K=1,2,32 and unequal cell/prefix counts.
- [x] Add a five-profile `1,32,16,1,1` finite-score fixture with exact zero orientation/turn/guide terms, profile-one unary j*0.001 and profile-two unary b*10. Reference counts3616/3151 establish fixed3300 discriminator; optimized production admission remains Task2.
- [x] Run inference tests with Java-17 command below.16 tests GREEN36s; historical56 regression unchanged.
- [x] Commit only oracle tests and evidence references. `c1faf9771df455dac390d9d280427e27d32e18d8`, independently reviewed without blockers.

## Task 2: Exact bounded descriptor selection and truthful counters

**Files:** Modify `ProbabilisticInference.java`, `ProbabilisticInferenceResult.java`, `ProbabilisticExecutionPolicy.java`; create `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model/InferenceWorkAccounting.java`; test `V022ExactKBestExecutionTest.java`.

**Interfaces:** Add `ProbabilisticExecutionPolicy.Mode { EAGER_V1, EXACT_DOMINANCE_V2 }`, preserve old `VERSION`, and add `CURRENT_VERSION = "terminal-path-count-scored-work-v2"`. Add `solve(List<InferenceProfile>, EvidenceModelParameters, TraceBudgets, MetricRegion, CancellationProbe, AttemptMemoryLedger.Owner, ProbabilisticExecutionPolicy.Mode)`. Existing overloads delegate to EAGER_V1. Add immutable `InferenceWorkAccounting(String executionPolicy, long forwardBackwardScores, long kBestScores, long logicalKBestExtensions, long refusedScoreAttempts)` and optional `workAccounting()` on results; old constructors remain compatible and unattested.

- [x] Add RED for optimized bounded admission with bitwise eager parity, exact-cap acceptance/refusal (including cutoff peek), and checked logical-counter overflow. Resource failure returns no MAP/raw/distinct paths or completion proof; refusal count distinguishes an attempted but uncomputed score. For V2 guard before computing: if `computedFB + computedK == maximumTransitions`, refuse the next score and increment `refusedScoreAttempts` to one; otherwise compute and increment the owning count. Successful cutoff peeks count as computed. V2 `evaluatedTransitions = computedFB + computedK`, attempts equal that sum plus refused attempts, and replay subtracts computed work only. Preserve V1's historical cap-plus-one refused-attempt counter. V2 failure retains accounting even with zero routes; failure before solver entry carries no accounting and cannot attest an executed solver policy.
- [x] Implement a private bounded descriptor heap inside the inference class, precharged to the existing scratch owner. A descriptor contains before/prefix indexes, exact rounded energy and prefix lexical rank. Maintain at most K descriptors; retain current stream order, grouped increment addition and `compareExtension` ordering.
- [x] For every admitted stream count all logical prefix extensions with checked addition, including tails not scored. Compute and charge each examined `prefix.energy()+increment`. Once K descriptors exist, skip the remainder of that energy-ordered stream only when a finite rounded score is strictly greater than the finite worst retained score. Scan equality fully; disable cutoff for nonfinite increments/scores. Sort retained descriptors with the original comparator and call `arena.extendRounded` in the original winner order. Preserve all terminal counts independent of pruning.
- [x] Add post-addition collapse with opposing lexical order, dense ties, signed zero, finite negative increments, extreme finite addition and nonfinite compatibility tests. Add deterministic randomized oracle comparisons for K=1,2,32; no timing assertions.
- [x] Add cancellation at cutoff, heap replacement and winner sorting, plus owner-refusal cleanup tests using controlled probes/ledgers. No sleeps or fallback output.
- [x] Run both inference suites, ownership/cancellation neighbors and unchanged eager 56-unit control. Require exact bitwise output parity; record actual versus logical work separately.
- [x] Commit algorithm/accounting/tests together; independent reviewer inspects source and oracle before production activation.

## Task 3: Explicit production activation, receipts and diagnostics

**Files:** `ProbabilisticTraceEngine.java`, `GuidedProbabilisticTraceEngine.java` only if it directly selects inference; `service/tracing/HybridTraceEngine.java`; `model/TraceHypothesisSet.java`; create `model/ExecutionWorkReceipt.java`; `LiveBPreviewService.java:parameterIdentity`; `EvidenceModelParameters.java:defaults/withoutShapeTerms`; `Format15NumericalPolicyReceipt.java`, `Format15ProductionBundleFactory.java`, `FinalOutputComponentsCodec.java`, `Format15ReplayRunner.java`, `ProductionReplayCommand.java` in the existing Format-15 package; `scripts/analyze-debug-bundles.py`; corresponding live identity, production replay, diagnostics and analyzer tests.

**Interfaces:** Production B and Hybrid call the new solve overload with EXACT_DOMINANCE_V2. Current capture parameter identity and runtime inference receipt both include CURRENT_VERSION. Preserve existing old archive fields; add optional coordinate-free accounting fields with explicit policy and units. `evaluatedTransitions` denotes actual computed forward/backward plus K-best scores for V2, not logical tails; old eager diagnostics retain their old interpretation. Missing new fields mean unavailable, never zero.

Create immutable `ExecutionWorkReceipt(List<InferenceWorkAccounting> bStages, long otherEngineTransitions)` with a copied bounded list in actual stage order and nonnegative checked totals. Add `Optional<ExecutionWorkReceipt> workAccounting` to immutable `TraceHypothesisSet`, keeping its seven-argument constructor as a compatibility overload delegating to empty. Standalone B wraps the solver accounting as one stage with zero other work. Hybrid retains unguided and, when executed, guided B stages separately; keeps A work in `otherEngineTransitions`; and charges all stages to the existing cumulative budget. A Hybrid aggregate total need not equal its B-only score sum. Missing/mixed executed B policies prohibit current-policy attestation. Inference-to-hypothesis conversion, Hybrid combination and engine result wrapping preserve the receipt through successful and zero-route failure results.

Extend receipt `current(...)` to consume this optional immutable receipt; B/Hybrid current-policy attestation requires present, matching actual solver stages, or exports explicit unattested/refused status. Never synthesize it from defaults or mutable counters. Pre-solver failures and cancellation exceptions have no returned solver result: permit unattested CANCELLED/no receipt, publish no geometry, and do not invent score accounting. Preserve the carrier through production finalization and replay/export; test successful, zero-route and guided-Hybrid chains.

- [x] Add RED proving actual production B/Hybrid select V2, default/live identity and receipt agree, old archives read, old strict receipt rejects, and A/Image/legacy identities remain unchanged.
- [x] Implement explicit activation and receipt/accounting export/read/analyzer compatibility; receipt must attest runtime selection, not merely a parameter label. Default parameter versions must identify CURRENT_VERSION for current production while legacy solve overloads still execute EAGER_V1 and report that actual policy. Preserve frozen old parameter strings on archive read. Add accounting to the additive diagnostics artifact; do not append bytes to historical unversioned output-component encodings. Version any changed canonical fingerprint encoding explicitly, retain old reader branches, and keep replay cumulative charging based on actual scored work.
- [x] Add production-path bypass mutation controls: forcing eager/omitting current policy must fail the activation/receipt gate for standalone B and Hybrid's guided B leg. Test Hybrid stage aggregation including A cumulative usage, missing/mixed B receipts and skipped guided stages. Add a resource-limited production result proving no partial route/Apply becomes available, and cancellation proving unattested/no-output behavior.
- [x] Run focused production replay, live identity, diagnostics compatibility and analyzer tests. Restore all mutants; confirm clean diff against intended changes.
- [x] Commit activation/receipts with tests; update completion matrix without closing quality gates.

## Task 4: Coherent integration and testable RC gate

**Files:** Boundary prototype and Tasks 1–3 delta; current completion matrix, handoff and ledger; eventual next-version release notes/build metadata only after source gates pass.

**Interfaces:** Actual unchanged production B/Hybrid finalizer and immutable Apply adapter consume the optimized inference result. Replay must use production engines, not the oracle.

**Discovered Task 4 correction (2026-10-12):** The complete 7.01 m window
changes lateral grid parity; symmetric windows must retain a center sample
with uniform spacing no greater than the requested pitch. Bind this sampling
change as `profile-window-centered-v2`. The original Hybrid relation-junction
Apply regression passes RC9 but fails the completed window: a generated point
just beyond a protected anchor creates a final backtrack. Do not remove raw
points in the common finalizer or weaken that block. Extend the existing safe
interval route to Hybrid's manual endpoint islands, preserving ordinary
automatic-eligible Hybrid junction behavior. The interval recapture must freeze
receiver geometry, retain proved read-only ports and verify frozen parity before
inference. A/Image routing remains unchanged. The old B-only endpoint assertion
is superseded specifically for Hybrid manual endpoint islands; every other
assertion remains required. Gate this correction with visible/managed capture,
protected relations/tagged nodes, strict interval replay, actual Apply/Undo/Redo,
whole-preview safety and cumulative resource/cancellation controls. Independent
review and fresh stock results are required; no approval or release is inferred
from a grid-only pass.

The actual Hybrid interval diagnostic found a distinct endpoint-localization
case: the unchanged ordinary selected endpoint's first changed edge has five
ambiguous midpoint samples (about 2.23 m) followed by three direct samples.
Keep the existing 2 m support bound on all fixed-island/interior seams. Only
at an unchanged `SELECTED_ENDPOINT` may a leading prefix of complete
`DIRECT_AMBIGUOUS` samples use one additional explicitly known native pixel;
direct two-sided support must resume on that same edge. Missing/censored/
no-signal/occluded samples, later unsupported runs, unknown native pitch and
an actual selected-route `UNSUPPORTED_TERMINAL_KINK` may not use the margin.
Check the chosen production route, not caller reason strings or all alternatives.
Preserve final backtrack/topology validation and exact anchor assignments.
Add physical-limit positive/negative controls, an actual terminal-kink marker
control and unchanged dark/ambiguous fixed-junction seam regressions.

- [x] Independently review the entire coherent delta, including boundary containment, narrow unavailable/resource failures, execution math, memory/cancellation and receipts. Resolve blocking findings; no self-approval. Component and final correction reviews found no source blockers; publication still requires the remaining CI/artifact gates.
- [x] Run all seven local stock captures serially with default 128M cap and version-pinned runtime. Require admission or explicitly justified original typed refusal; if the same long case still hits the cap, do not waive it or publish this repair as complete. Record counts/time without assuming speedup. Current-source revision23363c8: seven admitted, twenty CSVs identical to prior scored-v2; long case89,926,264 scores/62,234ms.
- [ ] Compare final geometry, physical roughness, direct support/gaps, dispositions and available immutable Apply plans. Retain faint/strong 12m bends, parallel identities and both Precise/Move Apply–Undo–Redo tests. Do not equate admission or identical optimizer outputs with resolved dispersed-signal zigzags.
- [ ] Integrate reviewed commits without overwriting main/private edits; run focused suites and five private fixture methods. Launch the existing full public GitHub validation plus JOSM-19555 graphical host gate. Require zero failed/error/skipped mandatory cases and retained prior-case multiset.
- [ ] Run `git diff --check`, inspect whole delta against original scope/matrix, and record exact revisions/evidence. On any quota/environment block checkpoint safely; do not release an unverified artifact.
- [ ] Build a new immutable test RC only after gates pass. Verify Java-17/JOSM manifest, public downloaded JAR hash/bytes and remote tag. Preserve RC9. Release notes explicitly state whether weak dispersed-fit remains open; stable 0.22 gates remain separate.

## Commands and closure

### Reviewed Task4 numerical correction after full CI failure

The original public7.01m Gaussian oracle is retained. Current eager and V2
return identical raw and distinct paths, excluding dominance as the cause of
the2.82m miss. The peak is interior, not a censored mode: scalar extraction
rejects it before censoring because coherent first differences are classified
as local noise (prominence0.36449 versus required0.47141). A fixture-only14m
diagnostic restores a measured mode and passes the unchanged1.8m oracle, but
is not a substitute for the original gate.

Existing independent Sol reviewed this bounded correction: estimate local
noise from the RMS of contiguous second differences, scaled by1/sqrt(3), retaining
the existing3x uncertainty multiplier. This normalizes iid noise variances6
versus2 while cancelling a local affine signal slope; retain prominence,
raw/B3/B5 center agreement, invalid support and exact inference unchanged.
Second differences must never bridge invalid samples. Keep physical broad/faint
Gaussian controls, flat/affine/nonlocalized controls, alternating/dispersed
noise, narrow bends, invalid triples and resampling coverage. Prove RED before
source edit, then original public7.01m oracle and neighboring A/B/Hybrid/Image
final-evidence/Apply and ownership checks, eager/V2 parity, stock runs and CI.

The extractor is shared with common final validation: use one versioned policy,
not a B-only switch. Bind scalar-mode-second-difference-rms-v2 into SHARED_COST and
detached B parameter versions. Historical decoding remains unchanged; obsolete
strict receipts reject. This specifically supersedes earlier A/Image identity
stability for common evidence classification only: their algorithms/caps are
unchanged but final evidence and plans may change and require regression tests.
No noise/shape gate, work cap, K, oracle, window, topology or Undo guard is waived.

2026-10-12 status: Tasks 1–3 are implemented, independently reviewed and
integrated as b78b925/f22613a/2c7eb48/68834db. RED/GREEN, cancellation/ownership
and bypass-mutation evidence is in the existing SDD progress ledger. Task 4
corrections are independently reviewed at 23363c8; source controls and current
seven-capture stock execution are green on the current RMS correction. Full
public CI/GUI run38262446411 FAILED on preceding source, GUI skipped. Current
focused gate138 and private fixtures5 pass; broader gate224 has two failures.
Their exact independently reviewed disposition awaits explicit approval in
2026-10-12-rc10-test-disposition-approval.md. New full CI/GUI remains required.
No RC10 tag or artifact has been published. Managed Hybrid is
still declared unsupported; its two refusal cases are not execution coverage.
Weak dispersed-fit and stable-release requirements are not closed here.

## Reviewed quality correction: missing-window reliability, 2026-10-12

For R02/R04 weak-strand evidence, an interior missing connection must contribute
zero over the available physical continuation window. Normalize the unchanged
direct-chain integral by triangular mass L-L²/40, with L=min(20 m, distance to
the actual selection boundary) independently on each side. Do not normalize a
stopped fragment as though it were the selection boundary. Preserve the 4 m
minimum direct span, association, triplet, scalar/raster checks, state space,
solver objective/weights, caps and cancellation. Version B as direct-longitudinal-4.

RED: four-metre island within20m yields.5277778 although maximal direct fraction
is.36. GREEN: full reliability suite, short/asymmetric complete selections,
one-sided missing support and faint concentrated12m apex:29 cases, zero failures,
errors or skips. Current actual default-budget Ricany1/2 production comparisons
complete. Raw turn falls8.3774->8.0578rad and19.2645->16.5063rad; direct support
does not fall, and case2 unsupported span17.90->12.45m. These are partial metric
improvements, NOT quality closure. Half-native staircases remain unresolved.
Wholesale quarter-native grid was rejected: both production controls exhausted
the default transition budget, providing no valid geometry result. The original
half-native grid is restored. Any bounded geometry-state correction requires
a separate reviewed amendment; no state-space change is silently covered by
the exact-Kbest performance contract.

Run Gradle from the actual isolated worktree, with `login:false`:

```sh
env JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk PATH=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin:/data/data/com.termux/files/usr/bin sh ./gradlew --no-daemon test --tests '*V022ProbabilisticInferenceTest' --tests '*V022ExactKBestExecutionTest'
```

Resolve neighboring exact class names from retained prototype evidence, rather than accepting an empty wildcard suite. Every gate records command, terminal status, counts, source revision and whether evidence is public/private. Tasks 1–4 close this admission subplan only. The overarching RC8 weak-fit goal and stable release scope stay open until their own quality criteria are evidenced.

Self-review: original numerical, topology, privacy and release constraints preserved; changed counter invariant explicit; five review-focus cases mapped; old/new signatures consistent; no permission to implement before approval.

Independent review corrected computed/refused count semantics and missing immutable receipt propagation through Hybrid's two B stages. Final review accepted exact heap math and the stated gates; this approves the design for maintainer consideration, not implementation or release. Prototype is committed at `6e4934e6c93c4fb2d0ad03475f5b7ebf0a451c4d` with integration held. Both main and prototype tracked trees and `git diff --check` were verified clean at this checkpoint.
