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

- [x] Verify archive SHA/checksums; inventory six captures by engine/mode/mapping/source/settings, selection and output kind. Record recorded runtime separately from newly measured runtime.
- [x] Recompute turn angles/intersections independently on raw, reconstructed preview and actual applied coordinates. Assert applied bluered maximum164.3896degrees and applied aggregate124.5794degrees within0.01degrees; do not use these angles as universal rejection thresholds.
- [x] Generate a local browser comparison on actual z15 imagery with candidate IDs, stage labels and per-candidate feedback saved locally. Label missing final geometry unavailable; B's no-route run has no drawable inferred path.
- [ ] Bind user's accepted blue result/marked defect intervals if available. Keep reference status PENDING if not; continue causal analysis rather than inventing a good ID.
- [x] Save immutable inputs/output hashes and commands. Commit only nonprivate test/reporting instructions and ledger changes; no private artifacts.

### Task2: Isolate the first defect and freeze the patch contract

**Files:** Create `PhysicalGeometryFixture.java`, `PhysicalPathContinuityTest.java`; inspect existing sampler, RidgeTracker and A optimizer without speculative production changes. Store bounded private diagnostic rows locally.
**Interfaces:** Fixture helper exposes `static List<RenderedHeatmapSampler.CrossSectionProfile> rotatingFrameProfiles()` and `parallelFrameProfiles()`, using existing public constructors and synthetic scalar data. Production interfaces remain unchanged at this stage.

- [x] Reconstruct profiles119–124 around the main Legacy bend from factual source sampling. Export anchor, normal, offset, physical point, chosen peak/evidence, source chainage and anchor ownership. Attribute where raw/final geometry first departs; do not substitute screen units for metres.
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
| Capture attribution, actual raw/final/applied distinction and accepted blue reference |1 | EVIDENCE VERIFIED; user blue reference PENDING |
| Causal proof and deterministic non-Drasov-only controls |2 | ACTIVE |
| Legacy usable geometry, wide search and successful behavior retained |3 | NOT STARTED |
| Exact final safety, availability, old diagnostic/identity compatibility |4 | PARTIAL: measurement primitive tested; caller/export integration pending |
| A actual existing objective traced, usable geometry, exact optimizer retained |5 | ACTIVE: frozen-state experiments; default unchanged |
| Both geometry modes, consumers, Hybrid, protected topology and transactions |6 | NOT STARTED |
| Independent audit, CI, private visual acceptance, bounded performance |6 | NOT STARTED |
| B performance redesign/new hybrid/stable release scope |None | OUT OF SCOPE |

Planning self-review: all approved design workstreams map above. The exact repair
formula is deliberately not claimed known: Task2's executable causal/patch gate
must close before Task3 production work; Task5 repeats it only if A still needs a
distinct correction. This is a staged executable plan, not a promise that the
unproven normal-rotation hypothesis already supplies the final algorithm.

## Execution ledger — 2026-11-02

Goal ACTIVE following user approval. Base411a3c9; existing isolated release
worktree reused, primary tree preserved. Ruling: user's explicit permission for
up to20Luna threads supersedes the usual task delegation ceiling; start only
three independent read/evidence lanes because geometry implementation remains
coupled. Evidence lane owns ignored Task1 artifacts, Legacy lane owns ignored
causal reconstruction, A lane owns ignored independent crossing diagnosis. None
may edit production, run Gradle or delegate recursively. Controller owns tests,
integration, task-status decisions and final Sol audit. No runtime weekly quota
telemetry available, no speed tier/pool changes or release authorization added.

Task1 artifact receipt independently inspected: six captures,76/76listed F15
checksums valid; applied bluered164.389608degrees/aggregate124.579485degrees
match required measurements. Forty-two raw candidate browser views use real z15
mosaics; only actual selected candidates carry preview/applied overlays. Corrected
an inventory bug that confused activity=all with visible palette; fresh inventory
now binds selected-visible bluered. Local outputs:
`build/private-drasov-quality/20261102-evidence/`; helper under ignored
`.superpowers/sdd/drasov-quality/build-evidence.py`. Blue reference still pending;
asynchronous user clarification requested, no independent work paused.

Read-only A diagnosis: the two final self-crossings already exist in raw selected
candidate (raw201/208and231/233); final anchor insertion is not their origin.
Existing geometricCurvatureCost is tube-relative; cleanup-only absolute short-wave
term is inactive at captured NONE setting. Next bounded A reconstruction traces
track/tube/allowed/chosen states using existing compiled released code, no B run.

Fourth Luna lane owns only PhysicalPathMeasurements and analytic unit tests in
isolated tmp worktree codex/drasov-physical-measurements; sole local Gradle slot
assigned to it. This independent measurement primitive does not preselect a
Legacy/A fix or change source/ranking. Runtime session metadata confirms the
three original evidence/cause lanes actually use Luna medium/high/high as routed.

Integrated independent measurement primitive as e5b9576/aaa8cd9 after source
inspection;5analytic tests GREEN in worker, including a genuine numerical RED
for1e-200/1e200scale invariance. It is not yet connected to preview/diagnostics;
Task4 remains partial. Public surface has only measure and immutable Result.

Legacy approximate reconstruction confirms frame rotation can create physical
reversals with constant offsets; it does not prove that a safe state sequence is
available. Rejected a proposed fixture whose ground-truth ridge was generated
from the folded frames themselves, because its oracle would be contradictory.
Focused Sol/high consultation supplied independent straight-ridge/parabolic-source
regular/folded controls. Exact original-input Legacy reconstruction and A local
state representability audit now run with existing compiled classes only.

Controller added test-only PhysicalGeometryFixture and PhysicalPathContinuityTest
for production sampler on independent horizontal Gaussian ridge. R=12regular
source chart should preserve order; R=4folded chart should force a>6m reversal
even while all peaks lie on the straight image ridge. These are characterization
controls, not RED or a claimed tracing fix. Sole root Gradle session88806/log
`/data/data/com.termux/files/usr/tmp/drasov-representability-controls.log` runs those
plus measurement tests. No Legacy/A production behavior changed yet.

Root characterization run88806 PASS exit0 in47s: PhysicalPathContinuity2 and
PhysicalPathMeasurements5, zero failures/errors/skips. The actual production
sampler's straight image ridge has a forced reversal under the folded chart and
preserves order under the regular chart. This validates the representability
discriminator, not a repair. Do not add a physical cost before exact Drasov state
audit establishes that safe image-supported alternatives actually exist.

Task2 exact native replay now matches all185archived Legacy projected points
(max1.07e-8EPSG3857units, within export rounding). DP/anchoring already cross;
smoothing removes that local crossing but moves profile124 to native intensity0
from a directly supported retained point. Thus no blanket smoothing removal:
some other profiles improve native fit during smoothing. Native reconstruction,
stage rows and12peak combinations stay private in legacy-cause output directory.

Bounded in-contract patch packet for the independently proven smoothing defect:
modify RidgeTracker.smoothOffsets only, adding a private sampled-native-support
lookup over immutable IntensitySample rows. Before accepting each smoothing step,
if current offset has observed valid positive native support, refuse a proposed
offset whose bracketing observed native samples are both zero or unavailable/
outside raster. Interpolate only within valid bracketing rows; do not cross
invalid support or extrapolate. Empty historical sample lists retain historical
behavior as unavailable, never imply zero. No new intensity threshold, smoothing
strength, detector ranking, normal/frame, state space or public API change. Add
RED fixture with a known image-supported thin bend driven into a dark gap by
smoothing, plus positive supported smoothing and no-sample compatibility controls.
Require actual private profile124 native-support improvement after GREEN; raw DP
crossing/representation remains separately unresolved. This patch is a scoped
contribution to Task3, not completion of Legacy repair or a substitute for the
safe-path/architecture gate. Final whole-task audit must inspect replay policy
identity consequences of changing Legacy output, including Hybrid parent output.

Smoothing subrepair assigned to reused Luna/medium in separate tmp worktree
`josm-drasov-legacy-smoothing`, base604be8c. Owns only RidgeTracker and new
RidgeTrackerSupportTest; sole Gradle slot transferred to it. Controller retains
integration and full-scope closure. Other causal lanes perform bounded directJava
reads only, never Gradle. No new general engine behavior is authorized by this
packet beyond the observed-support guard.

Focused Sol/high consultation's independent oracle is y=0 with source anchors
S_j=(j,-9.7+j²/(2R)), j=-8..8, plus padding for central secants. True intersection
Q_x=j(1-9.7/R)+j³/(2R²). R=12isregular; R=4forces Q_-4=3.7,Q_0=0,Q_4=-3.7.
Production sampling characterization confirms this. No local score can create
missing ordered states. A change to correspondence/emission ownership would
require the explicitly approved design-amendment gate; that choice has not yet
been made or implemented. Exact private state audit remains the discriminator.

Exact A local audit231..234: all four direct bands COMPLETE,9801core-state
combinations,4106proper-crossing and5695noncrossing. All81pairs231→232have a small
source-relative backstep0.155..0.869m (<nativepitch1.556m), which is NOT a valid
rejection oracle. Therefore no architecture amendment is inferred merely from
source ordering. The candidate's231..234points match captured raw bitwise; only
index206differs materially in whole direct replay (0.072344m), two other doubles
differ atroundoff. A bounded fixed-context physical-turn counterfactual plus
independent2Dsupport checks is running; a cost-family viability result is not yet
an approved coefficient or production objective.

Smoothing lane candidate38f4e91 passes28focused tests after intended2-failureRED,
but is NOT integrated. Controller identified a possible semantic regression:
local positive pixels do not alone prove a coherent bend; guarding every such
pixel could retain the isolated noise that smoothing should remove. Worker now
tests real full-profile thin-strand/noise controls before any production change
is integrated. No test-pass or unsafe-candidate rejection counts as Legacy repair
completion. Root tracing code remains RC14; only measurement primitive is added.

Ruling: REJECT38f4e91 native-positive-only guard. A true raster counterexample
retains an isolated bright stray point10px off a persistent straight trace; the
unmodified smoother bridges it. Counterexample committed in isolated8e98368;
candidate never integrated. Root retained independent raster controls for both
gap/stray suppression and supportedmulti-profilebend; both PASS on root baseline.

Found and TDD-repaired an independent metadata defect in RidgeTracker's peak
filter: old convenience constructor dropped typed anchor/chainage, native scalar
samples and projected lateral transform. New canonical constructor retains them.
Root intendedRED2/2 (lost owner, detachedcoordinateexception), then GREEN for
profile-integrity plus existing tracker/characterization/measurement suites;
additional native-raster controls2/2GREEN. Latest combined profile/raster4/4
XML saved privately under20261102-metadata-green. This preserves data required
for diagnosis/future evidence-aware decisions; it is not a kink repair.

A's fixed-context231..234experiment found2131noncrossingcombinations/9801when
including outerneighbors229/230and235/236. A physical-turn-minimal witness retains
63/63sampled2Dscalar support,mean.21127vsbaseline.21184. Exactdata-costbreakdown
shows tube-center prior supplies24.4019of24.5796cost on firstnoncrossing envelope
witness,versusbaseline.08128. No productioncoefficientchosen: next diagnostic
ablates longitudinalpriors withoutaddingbending, preserving states/directcosts.

Native replay after metadata fix db6ff8b preserves185/185offsets and projected
points exactly versus pre-fix runtime; all185filtered profiles retain factual
owner/chainage/native samples/transforms, including54replacementrecords. No
quality improvement is claimed for this data-preservation fix.

A prior-ablation experiment FAILED to remove the local crossing: tube-only,
tube+relative-curvature, and also equivalent-peak center-prior removal all select
crossing paths. Do not ship confidence attenuation on that evidence. Negative
chart Jacobian is not itself distrust evidence: an opposite150degree circular
arc is a valid image-supported counterexample (source radius8p,target opposite
radius4p,offset12p; p=1.556means offset18.67m inside25m).

Next Task5 experiment packet (isolated, NOT a production/default change): add an
explicit immutable package-private optimizer constructor parameter for diagnostic
physical-turn weight, default0. Add three-point K=theta²/max(1,(Lprev+Lnext)/(2p))
inside the existing pair-state recurrence, with actual candidate edge lengths,
cached headings and factual pitchp. Weight0 must preserve all old arithmetic,
state/tie/work behavior and existing exact tests. Nonzero experiment gets separate
CostRow decomposition; zero-length edge gets explicit finite worst-turn diagnostic
penaltypi², not a fabricated heading. Do not reuse cleanup-only absolute turn cost
or change its Off semantics. Test analytic angle/scale/zero-weight cases RED→GREEN
before integration. Isolated prototype only, no source UI/policy/default toggle.
Then use existing production tracker/optimizer on frozen A and independently
painted strong/opposite-arc controls to determine a common feasible weight range;
do not select a production coefficient from Drasov alone. If no common range,
reject this family. This bounded instrumented experiment is a plan-approved
diagnostic numerical test, not release qualification or a claim of finished A.

Prototype owner is reused Luna/medium in tmp `josm-drasov-a-physical-turn-experiment`,
branchcodex/drasov-a-physical-turn-experiment, basedb6ff8b. Owns only optimizer and
new CorridorPhysicalTurnExperimentTest. Sole Gradle slot belongs to that lane.
Controller has prepared opposite-arc fixtures/tests in root but has NOT run them
while prototype owns Gradle. Root uncommitted tests must be baselined before any
weight-preservation claim. Default0prototype is not an integration candidate yet.

Legacy component ablation skipping only peak suppression does NOT improve the
main119..124bend: same offsets/geometry/native support, despite changed scores
and different167..177offsets. All candidate native peaks in that capture have
syntheticCenter=false, including the secondary peaks. Do not call them inferred
midpoints merely from their numerical offsets. Suppression is not the inspected
bend's cause; no replacement predicate or selector tweak follows from this run.

Verification paths: root typed-profileRED log drasov-profile-integrity-red.log;
root GREEN log drasov-profile-integrity-green.log; image controls GREEN log
drasov-native-support-controls.log (all underTermux tmp). Active root HEADdb6ff8b.
Guard's rejected branch `codex/drasov-legacy-smoothing` ends8e98368; do not cherry-
pick either guard commit into root. New measurement primitive is integrated and
tested; its final-preview/export caller integration remains NOT STARTED. No full
CI, RC publication, new engine-default coefficient or stable-release claim.

Opposite-arc baseline now PASS: Legacy and A both retain independently painted
150degree opposite arc within native-pitch tolerance; root PhysicalContinuity4
plus RasterSupport2 pass with0failures/errors/skips. RootHEADdd57e41 includes
those preservation controls and current documentation. Prototype A remains in
its tmp branch, not integrated.

Prototype A correction: reject2be524c's globalProjectionRegistry/unavailable→zero
approach. Follow-upa5ea082 uses each DetachedProfileSamplingLocation's frozen
metric/raster transform, validates a shared frame, and removes all global map
projection access. Controller added trueRED for zero computed metric pitch that
could silently disable nonzero cost;22focused tests GREEN after explicit guards.
Prototype code must be frozen before private runs; no default or UI changes.

Legacy coordinate-gauge attempt6/12/24 is INVALID for unit-causality: the higher
level sampler fixes reference raster/half-width scaling, so editing only mosaic
virtualscale expands actual physical support despite unchanged declared25m.
Out-of-window peaks confirm the inconsistency. No sampler/unit patch follows.

Bounded Legacy physical-turn prototype packet (isolated, no default integration):
package-private RidgeTracker(double diagnosticTurnWeight, boolean suppressNoise),
public default delegates0,true. Existing DP/anchored/smoother order unchanged;
nonzero3-point turn cost uses actual reconstructed raster coordinates normalized
by factual native sourcePixelSizePx; do not label raster lengths as metres or
query globalProjectionRegistry. Keep current State and read previous two offsets
from existing path history with explicit current profile index at each append;
no added state-space/history/pruning. Apply identical term in existing anchored
interval solving; no new tracing pass. Default0 preserves arithmetic exactly.
The suppressNoise=false option is diagnostic component ablation only, not a
selected production policy. Scalar smoothing stays unchanged for this experiment.
TDD analytic turn/normalization/invalid weight and default compatibility first,
then native frozen Drasov at weights0,.1,.3,1,3,10 with suppression on/off; report
raw/anchored/smoothed geometry and independent2Dsupport, not just scores. No
coefficient chosen until preservation controls/common viable range are shown;
reject family if none. Owned tmp branchcodex/drasov-legacy-physical-turn-experiment
basedd57e41. This controlled experiment tests the same physical-continuity family
as A, not another speculative production repair.

A physical-turn family receipt is now available at ignored
`build/private-drasov-quality/20261102-a-physical-family/receipt.txt` using frozen
prototype42a92eb. All seven declared weights retain the same complete state space
and1615031transitions; cost rows reconcile. Lambda0 exactly reproduces the prior
direct replay. No tested weight removes all proper crossings: lambda30 removes
the second crossing but retains200/207. No production coefficient is justified.
Synthetic regular-straight and opposite150degree-arc screens preserve their
ground-truth envelopes, but were run onJava21; they are exploratory evidence,
not pinnedJava17 qualification. Do not launch more coefficient searches.

Gap diagnostic: row202 has one forced allowed offset; the selected201→203
connector never intersects its cross-section within the finite segment. However,
replacing all three truly unobserved internal rows51/202/304 by bounded factual-
chainage metric interpolation leaves full crossing sets unchanged for both
lambda0and30. Each replacement has positive valid2Dscalar support and certified
decision-region authority, but this is a counterfactual only. Forced gap vertices
alone do not explain the defect; do not ship post-hoc interpolation as its repair.
Receipt `20261102-a-physical-family/gap-interpolation-receipt.txt` records exact
geometry, ownership and support. Focused Sol/high architectural consultation
requested before another A repair family: establish supported noncrossing local
lattice feasibility, separating objective failure from correspondence failure.

Legacy prototype d918e6f remains isolated in tmp. Its cached edge heading/length
belongs to the existing selected State path; no added state-space dimensions.
34focused tests passed, but the reported missing-API compilation failure is NOT
behavioral RED. Default compatibility and the fixed native family now run with
explicitJava17binaries; no new weights or source changes authorized by that run.
Task4 read-only integration mapping proceeds independently; final geometry and
export remain unimplemented. No new RC or push has been performed.

Task4 first vertical slice: Legacy candidates now freeze the existing final
reconstruction before assessment/export, in both geometry modes, preserving raw
geometry and intentionally empty Legacy node-assignment maps. Intended RED:
`legacyCandidatesFreezeBothModesBeforeAssessmentWithoutInventingNodeAssignments`
expected the four-point precise preview but received an empty list. Minimal
Legacy-only branch then passes42AlignmentService tests and all command tests;
saved XML/logs `20261102-legacy-preview-{red,green}` and Termux
`drasov-legacy-freeze-{red,green}.log`. This freezes existing reconstruction, not
a kink repair or completed safety/metrics integration. Root owns this slice;
isolated Luna diagnostics lane owns optional checksummed geometry-quality.csv,
analyzer compatibility and frozen slide-time projection-code capture. Export
must never interpret projected coordinates through the later global projection.

A synthetic controls rerun is now pinnedJava17.0.20: all14fixed runs pass and
every non-runtime output field equals theJava21screen, including full geometry,
costs and work. `20261102-a-physical-controls/qualification-java17.md` records
identities; it does not resolve the full private route crossing. Sol narrowed the
next discriminator to one capped offline finite-state feasibility search around
198..212, preserving all exterior points, original allowed states/singleton gaps
and existing contact policy. A cutoff is UNKNOWN; no source-forward rule or
production topology-history change is authorized by this diagnostic.

Legacy native family onJava17 is complete and REJECTED as a repair: weights
0,.1,.3,1,3,10 with suppression on/off leave each setting's185-point geometry
exactly unchanged across weights, at all three recorded stages and all three
seeds. Weight0,true exactly matchesdb6ff8b offsets/projectedpoints. Raw/anchored
have4proper crossings and maximum166.071degrees; unchanged smoothing removes
crossings but retains maximum164.390degrees. Suppression-off changes other
geometry, not119..124. `20261102-legacy-physical-family/family-findings.md` and
checksummed receipt/CSVs retain the results. No coefficient or default change
follows; no more retries of this family. Prototype remains isolatedd918e6f.

Task4 freeze slice committedf90def7; GREEN54tests (42AlignmentService,
12ReplaceWaySegmentCommand; zero failures/errors/skips). Final source-wide audit,
generic Legacy safety integration, certified measurements/export and full suite
remain pending. Tests at this checkpoint establish reconstruction/transaction
compatibility only, not repaired Drasov fitting geometry.

A finite feasibility discriminator now finds CHECKED_SAT in198..212 after81619
extensions/76684contact prunes,275ms. It changes only205..209, leaves all309
offsets in actual productionstate sets and preserves gap202singleton plus exact
protected/exterior geometry. First crossing disappears; untouched231/233remains.
Native affected-segment means.22376baseline/.20664witness and minima.15079/.13703
are measurements, not accepted shape/ownership truth. Existing lattice can
represent a locally simple route under these boundaries; neither normal-chart
impossibility nor a production topology-history redesign follows. Receipt and
full allowed-state/witness CSVs: `20261102-a-feasibility/`. Parameter search stops.

Task4 safety packet reviewed by Sol/high: preserve inherited self-contacts only
with a conservative occurrence certificate—both directed final segments must
uniquely/exactly equal their original source segments, and contact types match.
Changed/ambiguous ownership is inheritance-unproven, never inferred from equal
counts or crossing coordinates. Cache final-preview findings once before ranking;
no repeated geometry scan in static assessCandidate. Legacy Apply rechecks
immutable source/final geometry after SelectionIntegrity and requires displayed
preview==stored final preview; it must NOT demand A's assignment map or adopt
A's connected-way policy. Ordinary common closure endpoint is allowed; an overlap
beyond that endpoint is not. Supported sharp noncrossing bends stay allowed.
This conservative certificate can block changed/subdivided contact segments whose
inheritance cannot be established; do not mislabel them new contacts. Existing
modern external-contact/disconnection policy remains unchanged. Luna owns only
the named safety/service/action hunks in tmpcodex/drasov-legacy-final-safety,
basef90def7, with behavioral RED→GREEN and explicit Gradle-slot handoff. Diagnostics
lane owns separate tmpcodex/drasov-final-geometry-diagnostics. Neither lane may
change tracker defaults, numerical coefficients or private reference acceptance.

Model routing correction: the Legacy safety lane's initial Luna implementation
was discarded because it preceded an observed behavioral RED; its replacement
test also targeted static assessment without the source context needed for the
inheritance certificate. Controller stopped that lane and reassigned the same
isolated worktree to Sol/high. Sol reproduced the actual existing freeze→cached
assessment failure before implementation, retained O(1) static assessment, and
has57focused tests green; action-boundary regressions are still in progress.
This is an implementation-process correction, not evidence of completed safety.
Diagnostics lane independently supplied a valid existing-bundle artifact RED;
its first packet bc622de is not integrated pending stronger numeric assertions,
selected-preview mismatch handling and schema/content validation regressions.

Numerical next-step ruling after Sol consultation: coefficient trials stop.
The finite local witness still visibly bends back at201..204, so being simple
does not establish usable image fitting. A source-free comparison now renders
the exact frozen scalar grid, without additional blur or interpolation, with
baseline/witness toggles and downloadable feedback. Native PNG identity
a66e9bea50eb1a72d72fe3f55ac21f2a0fb20b4dd9a51dd4b848c908ef98bca3;
`20261102-a-feasibility/comparison.html` is served only on127.0.0.1:8770.
It explicitly is not the original color image or a corrected optimizer output.
User annotation requested asynchronously; unrelated Task4 work continues.
Before a new A scoring mechanism is specified, compare actual image-owned axial
orientation/turn evidence on an accepted reference and offending transitions,
retaining the independent opposite arc/supported switchback controls. Reject that
mechanism if the image cannot discriminate. Nonlocal topology history requires
an explicit prefix-equivalence counterexample and architecture amendment, not
an inference from failed coefficient trials. Legacy's mechanism is still separate
and unresolved. No sampler guide, state expansion or hidden refinement approved.
