# Drasov Legacy and Corridor A Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for tightly coupled controller work; use superpowers:subagent-driven-development only for explicitly delegated isolated tasks. Track steps below. Do not turn an investigation hypothesis into an assumed production fix.

**Goal:** Produce usable Legacy and Corridor A geometry on Drasov without unsupported kinks, retaining successful strong traces, sparse strands and supported switchbacks.

**Architecture:** Reconstruct the first faulty physical-geometry decision, repair Legacy narrowly, then remeasure and repair Corridor A independently. Assess exact final previews and preserve versioned diagnostics, existing transaction authority and A's one exact second-order DP. Diagnosis determines the repair mechanism before its numerical patch is specified.

**Tech Stack:** Java17/JUnit5, JOSM19555, Gradle, existing offline replay and public GitHub CI; private local imagery.

**Spec:** `docs/superpowers/specs/2026-11-02-drasov-legacy-corridor-quality-design.md` (approved2026-11-02).

## Global Constraints

- Preserve explicitly configured ±25m discovery, factual source resolution and measured ground chainage. No cap increases, hidden narrowing or source acquisitions.
- No blanket angle/forward-progress veto, post-hoc moving average, geometry consensus, hidden tracing pass or destruction of supported switchbacks.
- Preserve exact fixed/shared/tagged anchors, junction opt-in, stale checks and atomic Apply/Undo/Redo; contextual ways/relations remain unchanged.
- Keep current engine choices/defaults. B search/performance redesign and new Hybrid behavior are out of scope; Hybrid regression is required.
- A retains its isolated exact second-order optimizer and bounded state space; no global beam/history expansion without a separately approved design amendment.
- User-approved good blue-family result is not yet bound to an exact ID. Investigation proceeds; private visual acceptance cannot be asserted until reference annotation is recorded.
- Private captures never enter public commits/CI. Ordinary code changes must have deterministic RED→GREEN and retained tests.
- Source baseline6f98691 (RC14); documentation baseline18a594d. Use existing release worktree, preserving dirty primary117baaf and all unrelated/untracked files. Reconcile actual HEAD before execution.
- One local Gradle process; default0children, normally at most1useful bounded worker. Use Luna for test/helper work, Sol for coupled geometry decisions and final independent audit. No Astra without user approval. Weekly telemetry unknown; do not invent it.
- No implementation has been performed by writing this plan. No RC is automatically authorized by its approval.

## Review Focus

1. A genuine supported switchback reverses against source direction: retain the turn, not a straight shortcut (Tasks2,3,5).
2. Two close thin strands at a bend: do not connect them through a dark gap or erase a supported apex (Tasks2,3,5).
3. Equivalent collinear node insertion changes source tangent sampling: preserve physical shape within the fixture's source-pitch tolerance (Tasks2,3,5).
4. Tagged/shared/fixed anchor insertion after tracking: assess the final preview and keep protected coordinates exact (Tasks4,6).
5. Old diagnostics show neutral zero metrics: unavailable measurements remain unavailable; old replay/identities stay readable (Task4).

## File map and verification conventions

Existing production responsibilities:
- `service/RenderedHeatmapSampler.java`: anchor/tangent/normal construction and scalar sampling.
- `service/RidgeTracker.java`: Legacy peaks/path/anchors/offset smoothing and reconstruction.
- `service/CorridorCenterlineOptimizer.java`, `CorridorAwareTracker.java`, `CorridorTracker.java`: isolated A state optimization/association.
- `service/AlignmentService.java`: engine routing, final-preview reconstruction, assessment and existing diagnostics export.
- `service/quality/FinalGeometryEvaluator.java`: modern exact-final physical/topology checks; do not fabricate its required image evidence for Legacy.
- `model/CenterlineCandidate.java`, `CandidateEvidence.java`, `CorridorQuality.java`: immutable candidate evidence and historical neutral metrics.
- `diagnostics/LastSlideDebugBundle.java`, `scripts/analyze-debug-bundles.py`: checksummed export and consumer compatibility.
- `util/ReplaceWaySegmentCommand.java`: unchanged transaction boundary, regression only unless a separate reproduced defect requires a scoped correction.

Prospective test helper: `src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/PhysicalGeometryFixture.java`. Keep diagnostic construction test-only; no public feature/menu.
Prospective regression class: same package `PhysicalPathContinuityTest.java`.

Every `GTEST` below means this exact shell command from the isolated worktree:
`JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk sh ./gradlew test --offline --no-daemon --max-workers=1 --console=plain` plus the listed `--tests` arguments. Check exit0 and XML zero failures/errors/skips. Expected RED means the intended behavioral assertion fails, not compilation, missing files or fixture setup. Record RED/GREEN revision and XML counts. Stage only each task's exact files, run `git diff --check`, and commit the coherent task.

### Task1: Bind original evidence and separate output stages

**Files:** Create ignored `build/private-drasov-quality/<run-id>/{inventory.json,measurements.json,comparison.html,receipt.json}` and a bounded helper under `.superpowers/sdd/drasov-quality/`; modify this plan's ledger only. No production edits.
**Interfaces:** Consumes Drasov.zip SHA256d8580ef870e8e0a91ec447519c487ac176469cce3833b6939b11b9559b78b3cb and RC14 frozen/original geometry. Produces locally bound raw/final/applied candidate records and user annotations, never inferred authority.

- [ ] Verify archive SHA/checksums; inventory six captures by engine/mode/mapping/source/settings, selection and output kind. Record recorded runtime separately from newly measured runtime.
- [ ] Recompute turn angles/intersections independently on raw, reconstructed preview and actual applied coordinates. Assert applied bluered maximum164.3896degrees and applied aggregate124.5794degrees within0.01degrees; do not use these angles as universal rejection thresholds.
- [ ] Generate a local browser comparison on actual z15 imagery with candidate IDs, stage labels and per-candidate feedback saved locally. Label missing final geometry unavailable; B's no-route run has no drawable inferred path.
- [ ] Bind user's accepted blue result/marked defect intervals if available. Keep reference status PENDING if not; continue causal analysis rather than inventing a good ID.
- [ ] Save immutable inputs/output hashes and commands. Commit only nonprivate test/reporting instructions and ledger changes; no private artifacts.

### Task2: Isolate the first defect and freeze the patch contract

**Files:** Create `PhysicalGeometryFixture.java`, `PhysicalPathContinuityTest.java`; inspect existing sampler, RidgeTracker and A optimizer without speculative production changes. Store bounded private diagnostic rows locally.
**Interfaces:** Fixture helper exposes `static List<RenderedHeatmapSampler.CrossSectionProfile> rotatingFrameProfiles()` and `parallelFrameProfiles()`, using existing public constructors and synthetic scalar data. Production interfaces remain unchanged at this stage.

- [ ] Reconstruct profiles119–124 around the main Legacy bend from factual source sampling. Export anchor, normal, offset, physical point, chosen peak/evidence, source chainage and anchor ownership. Attribute where raw/final geometry first departs; do not substitute screen units for metres.
- [ ] Construct a tiny synthetic equivalent of the demonstrated defect. Add `rotatingFramesDoNotCreateUnsupportedFoldback()` using a known non-self-crossing synthetic ridge with direct two-sided support; assert the path remains within one source pitch of that ridge and does not introduce a self-crossing. Pin exact coordinates/pitch/expected physical envelope in the fixture, independently of output from the tested tracker.
- [ ] Add negative/positive controls: `supportedSwitchbackRetainsApex()`, `parallelStrandsDoNotGainDarkConnector()`, `collinearInsertionPreservesSupportedShape()`. Derive expected apex and corridor tubes from raster-generation geometry; use one native source pitch as the positional tolerance, not a tuned Drasov tolerance. Check weak thin strands and invalid support separately.
- [ ] Run GTEST `--tests '*PhysicalPathContinuityTest' --tests '*RidgeTrackerTest'`. Retain an intended RED on the new defect and passing independent controls; fixture errors are not RED.
- [ ] Write a causal receipt selecting exactly one first repair: sampler geometry, Legacy transition/anchor decision, or final anchor reconstruction. Include measured counterfactuals that hold offsets fixed versus hold frames fixed. Explain rejected mechanisms.
- [ ] Before Task3 production edits, append an exact patch packet here: chosen files/method signatures, physical formula and normalization, constants derived from the fixture, numerical-policy identity changes and expected RED/GREEN assertions. This is a required evidence gate, not a placeholder to tune until green. If it changes sampling guide/evidence domain or violates the approved architecture, obtain a specific design amendment. Routine in-contract mechanism selection does not require another general approval.
- [ ] Commit the reproducible fixture and completed causal/patch packet. Mark Tasks3–5 not started until this gate is satisfied.

### Task3: Correct Legacy at the proven source

**Files:** Modify only Task2 packet's bounded subset of `RidgeTracker.java`, `RenderedHeatmapSampler.java`, `AlignmentService.java`; retain `RidgeTrackerTest.java`, `PhysicalPathContinuityTest.java`. Add sampler regression to existing sampling tests if that consumer changes.
**Interfaces:** Preserve `RidgeTracker.track(List<CrossSectionProfile>, double, CancellationProbe)` and immutable candidate output. Source-pixel argument remains raster pitch, not ground metres. New internal functions must have unit-explicit signatures recorded in Task2 packet.

- [ ] Re-run the pinned RED at the committed baseline and record failure at the intended assertion.
- [ ] Apply the single Task2 correction. Preserve existing palettes/seeds/fallback/ranking/smoothing outside the proved defect. A physical-continuity correction must retain supported turns, not forbid source-relative reversal. Do not combine a sampler change and new tracker weights in one unexplained patch.
- [ ] Run GTEST `--tests '*PhysicalPathContinuityTest' --tests '*RidgeTrackerTest' --tests '*SamplingScaleTest' --tests '*HeatmapFixtureArchiveTest'`. Source-independent controls must pass without updating expected values to new output.
- [ ] Run original-input private Legacy comparison on all captured Drasov mappings; export exact final/applicable geometry and changes from RC14. Reject any claim based solely on lower point count/angle or additional blocking.
- [ ] Record usable fitting geometry at annotated defect locations, good-reference preservation, intense/sparse/switchback controls, unchanged requested25m width and new method-policy identity where required. Commit the scoped correction and tests.

### Task4: Assess exact previews and expose measured availability

**Files:** `AlignmentService.java`, relevant immutable evidence/diagnostics models, `LastSlideDebugBundle.java`, analyzer; tests `AlignmentServiceTest.java`, `CorridorQualityMetricsTest.java`, analyzer tests. Reuse existing geometry helpers before creating a new one.
**Interfaces:** Proposed shared measurement entry point, if reuse does not already provide it: `service/quality/PhysicalPathMeasurements.java` with `static Result measure(List<MetricPoint> finalPoints)`. `Result` is immutable `List<Double> vertexTurnsDegrees`, `double lengthMeters`, `int zeroLengthSegmentCount`; unit and finite-input validation are explicit. Caller carries availability and stage, rather than interpreting neutral CorridorQuality as a measurement. Topology/support decisions remain with evidence-owning existing checks.

- [ ] Add `legacyFinalGeometryMetricsDescribeAppliedPreview()`: fixture with an independently known physical reversal must export its measured nonzero turn, not the neutral legacy zero. Add `unmeasuredLegacyQualityIsNotMeasuredZero()` and old-bundle unavailable-field/analyzer controls.
- [ ] Add `fixedAnchorReconstructionIsCheckedBeforeApply()`: raw safe path plus a protected anchor yields an unsafe final preview; assert final geometry is inspected and protected coordinates unchanged. Add supported-sharp-turn control so angle alone never causes rejection.
- [ ] Run these tests RED; implement reusable physical measurements only on trustworthy metric coordinates. No certification or frozen image evidence may be invented. When conversion cannot be proved, mark unavailable.
- [ ] Integrate final geometry safety/availability with existing assessment and modeless preview; retain actual inherited contact/disconnection rules. New self-intersection and independently established unsupported foldback stay blocked. A merely ambiguous fit remains reviewable under its existing contract.
- [ ] Add checksummed `geometry-quality.csv` stage-tagged rows to existing diagnostics, with explicit availability and final candidate identity. Preserve old artifacts and readers; update manifest and any affected identity/schema explicitly. Test export→analyzer roundtrip and old archive readability. Do not overwrite raw fields with final-stage values.
- [ ] Run GTEST `--tests '*AlignmentServiceTest' --tests '*CorridorQualityMetricsTest' --tests '*V022FinalGeometryTest' --tests '*V022Format15FoundationTest'`; run `python3 -m pytest -q scripts/tests/test_debug_analyzers.py`. Commit code, tests and documentation together.

### Task5: Repair Corridor A's independently remaining defect

**Files:** Bounded subset of `CorridorCenterlineOptimizer.java`, `CorridorAwareTracker.java`, `CorridorTracker.java`, `RenderedHeatmapSampler.java`; tests `CorridorCenterlineOptimizerTest.java`, `CorridorRasterIntegrationTest.java`, `CorridorScaleInvarianceTest.java`, `CorridorTrackerTest.java`, `PhysicalPathContinuityTest.java`.
**Interfaces:** Preserve optimizer API, `OptimizationResult`, complete bounded state space and one exact pair-state DP. Use factual physical state coordinates and existing track-owned tube/scale evidence. No RidgeTracker dependency from A.

- [ ] Replay A after Task3 shared changes; compare original capture's two new self-crossings and record whether each remains. Attribute sampler versus A-only deltas separately.
- [ ] For any remaining crossing, trace band/track identities, allowed states, existing geometricCurvatureCost and active weights, and post-anchor geometry. Add `corridorBendDoesNotIntroduceSelfIntersection()` from a reduced synthetic raster with an independently specified noncrossing ridge; establish intended RED. If Task3 already solves this, retain the regression and do not invent a new A algorithm change.
- [ ] Freeze A's exact patch packet (formula, units, term activation, coefficient and identity) in the ledger before editing. Correct the earliest demonstrated failure. Missing global topology history is an architecture-amendment gate, not permission for beam search, a repair pass or unlimited topology states.
- [ ] Apply minimal A correction and establish GREEN, retaining `supportedSwitchbackRetainsApex`, thin parallel-strand and collinear-insertion controls. Do not alter protected anchors, incomplete/censored evidence ownership or coarse-parent/child identity.
- [ ] Run GTEST `--tests '*CorridorCenterlineOptimizerTest' --tests '*CorridorRasterIntegrationTest' --tests '*CorridorScaleInvarianceTest' --tests '*CorridorTrackerTest' --tests '*PhysicalPathContinuityTest' --tests '*MultiScaleCorridorTrackingTest'`.
- [ ] Replay Drasov A plus previously good Omice A/bluered-corridor at original settings. Require usable applicable/reviewable non-self-intersecting geometry, no dark connector and preserved supported bends. Existing hard blocking alone is not task completion. Commit scoped correction/tests.

### Task6: Integration, independent audit and delivery evidence

**Files:** Tests `AlignmentServiceTest.java`, `util/ReplaceWaySegmentCommandTest.java`, `V022LiveBPreviewServiceTest.java`, relevant action/cancellation tests, README and this ledger. Production transaction changes require a separately reproduced defect.
**Interfaces:** Exact final candidate identity→confirmation→command remains immutable; both modes use their existing authorized node-edit contract. Native output/work identities stay native; numerical comparisons are explicitly named.

- [ ] Test repaired Legacy/A in Precise and Move modes, managed and rendered sampling when affected. Assert Move retains original node IDs/count; Precise preserves protected assignments while using authorized replacement geometry.
- [ ] Add/retain mixed Hybrid intervals: repaired Legacy parent, one usable B refinement and one B refusal; assert interval-local fallback without blocking usable intervals or broadening authority. Test fixed/tagged/relation junction vicinity unchanged.
- [ ] Run focused transaction, stale-confirmation/Apply, cancellation/no-late-publication and old-codec suites. Then run required broader public validation through `python3 scripts/run-validation.py --profile public --output <new-output-directory>` on CI, with exact code identity and both existing real-JOSM GUI engines. No private upload; no skipped gate counted as pass.
- [ ] Benchmark old/new Legacy/A on identical frozen inputs, host and settings: one warm-up and three measured repetitions per engine/case, record median/range, phase/work/memory data where available. No fixed speedup target; materially slower or larger allocation requires review before inclusion. B's known resource refusal remains a separately reported limitation.
- [ ] Run local private visual comparison against Task1 annotations, including strong and sparse successful controls. If the accepted blue reference remains unidentified, report private visual gate pending; do not declare full completion. Do not repeatedly rerun B's128M failure merely to benchmark unrelated engines.
- [ ] Freeze all writers; independent Sol audit of the entire task delta against design, fixtures, final-preview/Apply, diagnostic compatibility and private evidence. Fix in-scope findings, rerun invalidated checks and review changed areas.
- [ ] Update README, completion matrix and this ledger; inspect final full diff and `git diff --check`. Report actual verified outcomes, residual blocks and exact revisions. Commit scoped documentation. Publish an RC only under explicit release authorization and after CI/manifest/public-download checks; do not automatically create one for each experiment.

## Scope-to-task coverage and completion ledger

| Design requirement | Owning tasks | Current status |
| --- | --- | --- |
| Capture attribution, actual raw/final/applied distinction and accepted blue reference |1 | NOT STARTED |
| Causal proof and deterministic non-Drasov-only controls |2 | NOT STARTED |
| Legacy usable geometry, wide search and successful behavior retained |3 | NOT STARTED |
| Exact final safety, availability, old diagnostic/identity compatibility |4 | NOT STARTED |
| A actual existing objective traced, usable geometry, exact optimizer retained |5 | NOT STARTED |
| Both geometry modes, consumers, Hybrid, protected topology and transactions |6 | NOT STARTED |
| Independent audit, CI, private visual acceptance, bounded performance |6 | NOT STARTED |
| B performance redesign/new hybrid/stable release scope |None | OUT OF SCOPE |

Planning self-review: all approved design workstreams map above. The exact repair
formula is deliberately not claimed known: Task2's executable causal/patch gate
must close before Task3 production work; Task5 repeats it only if A still needs a
distinct correction. This is a staged executable plan, not a promise that the
unproven normal-rotation hypothesis already supplies the final algorithm.
