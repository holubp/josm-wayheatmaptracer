# Drasov Legacy and Corridor A Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for tightly coupled controller work; use superpowers:subagent-driven-development only for explicitly delegated isolated tasks. Track steps below. Do not turn an investigation hypothesis into an assumed production fix.

**Goal:** Produce usable Legacy and Corridor A geometry on Drasov without unsupported kinks, retaining successful strong traces, sparse strands and supported switchbacks. Execute the approved optional-emission experiments and, on positive results, related follow-on designs, implementation plans and verified implementation autonomously, using useful parallelism.

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
| Causal proof and deterministic non-Drasov-only controls |2 | PARTIAL: R4 RED/objective witness and reviewed connector prototype verified; all full-window policies hit128M; reviewed local-certificate screen active; exact kernel remains pending |
| Legacy usable geometry, wide search and successful behavior retained |3 | PARTIAL: mapping, integral, factual input and resource foundations tested/reviewed; Legacy inference repair and real-data quality remain |
| Exact final safety, availability, old diagnostic/identity compatibility |4 | PARTIAL: frozen previews, contact guard and measured export integrated; final gates and image-supported foldback work remain |
| A actual existing objective traced, usable geometry, exact optimizer retained |5 | PARTIAL: approved correspondence experiment; fixed v2 objective witness screen passes; solver optimum and preservation unverified |
| Both geometry modes, consumers, Hybrid, protected topology and transactions |6 | NOT STARTED |
| Independent audit, CI, private visual acceptance, bounded performance |6 | PARTIAL: foundations independently reviewed and full public CI41560731847 passes; admission feasibility negative; repaired real-data quality and final whole-goal audit remain unverified |
| B performance redesign/new hybrid/stable release scope |None | OUT OF SCOPE |

Planning self-review: all approved design workstreams map above. The exact repair
formula is deliberately not claimed known: Task2's executable causal/patch gate
must close before Task3 production work; Task5 repeats it only if A still needs a
distinct correction. This is a staged executable plan, not a promise that the
unproven normal-rotation hypothesis already supplies the final algorithm.

## Latest checkpoint — 2026-11-03 exact rejection execution

The final-mode-failure experiment is implemented/tested/independently reviewed
at53b2ef8;229tests/14suites pass. The single frozen-build R4 screen completes
1937/26098pairs under the unchanged128Mcap versus579eager. All579shared completed
outcomes/reasons and scalar values match;201shared admitted Q values are preserved
by identical scalar mean/source mass/pitch. Additional1358completed pairs have
unknown eager outcomes. Full admission feasibility remains NEGATIVE, with24161
unmeasured keys; no engine/default/kernel activation or release is justified.
The detailed experiment plan records work/query/ownership evidence and next action.

Integrated source67ce845/docs9655509 on the public experimental branch; CI41582739824
is pending at9655509. Active follow-on is read-only100-key replay under
`2026-11-03-drasov-impossible-local-proof-screen.md`: prove a narrow impossible-local-
raw-peak condition, then measure earlier unchanged dense fallback opportunity.
Source53b2ef8/classes remain frozen. No production shortcut or additional graph
is authorized by the diagnostic helper alone; controller owns the evidence gate.

The100-key replay is now complete:100/100same baseline receipts and dense profiles,
35/50farther-offset and0/50near-ridge keys satisfy the proposed necessary condition.
Avoidable local suffix3854889/13033253units (29.6% selected work) is insufficient
to justify another exact-shortcut cycle given the observed~13.4x full-graph cost
gap. Independent cost review closes that branch without production implementation.
Active next work: one Sol numerical-certificate redesign in
`/data/data/com.termux/files/usr/tmp/drasov-cheap-certificate-design.md`, with explicit
changed numerical contract, full existing state/arc inventories and caps, amortized
cost targets, quality/adversarial controls, and a feasibility-first discriminator.
This preserves scope; it does not mark any original engine task complete.

Public CI41580122880 succeeds at507269e:2601test executions/231XML suites,
0failure/error/skip, public validation plus both GUI smoke engines. Private inputs
were not executed. This CI covers the prior zero-local batch, not53b2ef8 or actual
repaired Drasov geometry. Original Tasks3–6 and whole-goal review remain open.

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

Integrated Task4 code snapshot e868079:
- f90def7 freezes existing Legacy final geometry in both modes.
- 2874d28 adds cached typed final-contact warnings and source/exact-preview
  review/Apply rechecks, preserving Legacy empty assignments and A's own policy.
  Worker evidence: 136 broader focused tests, then 66 final affected tests pass.
- 87c5dbe/e868079 add optional checksummed geometry-quality schema1, frozen EPSG
  conversion plus strict local metric certification, raw/final/applied stages,
  explicit unavailable blanks, selected-preview mismatch handling and reader
  schema/header/duplicate/numeric validation. Right-angle and high-latitude
  controls independently reject projected units passed off as metres. Bundle
  artifact and selected-mismatch behavioral REDs are retained. Root analyzer
  suite: 21 tests and 9 subtests pass.
Code writers are frozen. Root full Java and full Python suites are running;
independent fresh Sol/high audit covers the whole implemented delta411a3c9..e868079,
not just the latest commit. Review must not approve unimplemented numerical
Tasks2/3/5 or claim full Task4 closure while image-supported foldback behavior
and final evidence/CI gates remain open. README now documents the additive
artifact and conservative inheritance limit. No RC publication or remote push.

Independent audit found one P1 in the safety slice: adjacent edges were skipped
even when they retraced over positive length. Root added a helper regression and
an actual Move reconstruction/assessment regression; both failed as intended
(3 selected tests, 2 assertion failures). A third acceptance test passed already:
a safe straight raw ridge becomes overlapping when fixed endpoints are restored,
and assessment inspects that stored final geometry without moving dataset nodes.
The scoped correction examines adjacent pairs, exempting only their common
endpoint, and updates original-contact certification consistently. Sharp
nonoverlapping turns remain permitted. Focused GREEN and affected re-review are
in progress; no new numerical fitting correction is implied.

Full Python verification at e868079 passes 206 tests plus 9 subtests. The local
full Java baseline was deliberately cancelled after12m12s with SIGTERM/exit143
to unblock the reviewed correction and move full computation to the approved
GitHub CI workflow. It is NOT a passing or failing-code full-suite result.
Log: Termux `drasov-integrated-full-test.log`; new true RED/GREEN logs:
`drasov-adjacent-review-{red,green}.log`, saved RED XML under ignored
`20261102-adjacent-review-red/`. CI has not yet been dispatched.

P1 correction GREEN: 144 tests (45AlignmentService,20LegacyFinalPreviewSafety,
67AlignWayAction,12ReplaceWaySegmentCommand), zero failures/errors/skips; XML
preserved in `20261102-adjacent-review-green/`. Independent affected re-review
finds no blocking issue: adjacent overlap is checked in both preview and source
classification, ordinary contacts and genuine sharp nonoverlapping bends remain
allowed, inheritance ownership stays strict. The new protected-endpoint test also
closes that particular acceptance gap. Exact distinct numeric export stages and
actual Java-export→Python-reader acceptance tests are being completed in the
existing diagnostics worktree, with no further production changes authorized
unless a test proves a defect. Remaining full Java/public host checks will run
through GitHub CI after that coherent tests-only integration.

Tests-only acceptance integration ef01444 now checks independently expected raw,
final and deliberately different actual-applied numeric geometry (including a
180degree reversal), immutable original export, and a real Java-generated ZIP
read through the Python analyzer CLI. Projection mutation is restored in finally.
Worker Java17 focused run: 51 tests, zero failures/errors/skips; Python analyzer
suite: 21 tests plus 9 subtests pass. These complement, rather than replace,
root's144safety/action/command tests and206-test full Python run. README declares
the Python test dependency explicitly. Independent final affected audit finds
no unresolved blocking issue in the implemented foundation/Task4 batch and
approves proceeding to CI only. This is NOT completion of the numerical fitting
goal, private visual acceptance, full Task4 scope, or release qualification.

Next external computation: push only the reviewed release branch (no tag/release,
no private artifacts) to trigger existing public validation and both JOSM19555
GUI engines. Do not claim the cancelled tablet baseline as full Java evidence.
Keep the active numerical reference question and original Task2/3/5 work open.

Connection-safe checkpoint: reviewed code/docs pushed as
`12e4522789315e173f13ebcc4602ea21fc87dd5f` to the existing
`codex/v022-legacy-b-release` branch, fast-forward from50e1e35. No tag, release,
private imagery or prototype branches were pushed. Public CI run41466305261:
https://github.com/holubp/josm-wayheatmaptracer/actions/runs/41466305261
was IN_PROGRESS when last checked. Background gh watcher uses60s intervals and
logs to Termux tmp `drasov-ci-41466305261.log`; its success has NOT been assumed.
Use explicit `-R holubp/josm-wayheatmaptracer` for gh commands because ownership
protection prevents gh's automatic repository discovery in this worktree. Do not
change global git safe-directory configuration to work around that.

Next safe actions: inspect CI outcome/receipts for this exact commit; fix any
in-scope regression. Keep numerical prototypes isolated/default0. User reference
feedback is still pending at localhost8770/comparison.html (server session96562).
Do not restart coefficient trials, infer that the witness is the desired path,
mark Tasks2/3/5 complete, or publish an RC from this foundation batch. Primary
117baaf tree and the three pre-existing untracked handoff files are preserved.

Continuation progress: test-only231fed3 adds independently painted strong/weak
thin parallel-ring and curved-ridge collinear-insertion controls for both Legacy
and A. Fixed amplitudes.95/.35 and the one-native-pixel oracle were selected
before observing outputs; no earlier failed fixture or relaxed threshold. Explicit
Java17 focused run passes8PhysicalPathContinuity+22RidgeTracker tests. This is
preservation characterization, not a RED or a tracing repair. The test-only commit
is local and is not part of the already running CI12e4522 snapshot; production
code remains identical to that reviewed CI snapshot.

Reference feedback UI now supports clicking an approximate intended path and
saving bounded capture-bound JSON directly under ignored
`20261102-a-feasibility/feedback/`. Scripts are ignored diagnostic helpers
`.superpowers/sdd/drasov-quality/reference-{annotation.js,server.py}`. The server
is localhost8770 (session77468); invalid identity/coordinates/schema are rejected,
existing annotations are never overwritten, and no synthetic test payload was
saved as user feedback. PNG placement was corrected to start atSVG−0.5/−0.5 so
integer-centered frozen-grid overlays and drawn points align with PNG centers.
No pixel values/filtering changed. JS syntax, HTTP resources and negative POST
checks passed; browser interaction itself has not been field-confirmed.

Do not turn the still-pending private annotation into a blanket blocker on other
work. Sol/high is checking actual engine output on the independently painted
straight ridge with folded source normals, using the existing R4fixture and
explicit Java17. This is the missing Task2 engine-level representability
discriminator, not another coefficient trial or production guide change. It can
establish a concrete design-amendment gate without guessing private ground truth.

The engine-level discriminator now establishes that gate. Isolated test-only
RED commit eb151e340960d92d6ecdf229bc8eb14786a9cb73 on
`codex/drasov-chart-engine-red` has2intended failures and8positive preservation
passes, zero errors/skips. Both actual engines fit the independently painted
straight ridge within1nativepixel yet retrace it: Legacy7.472m/A6.600m backward
image travel. The continuous normal-row equations force at least approximately
5.4m of retracing regardless of coefficient or finite state density. Sol/high
independently checked the derivation, output CSVs and all receipt checksums.
Do not merge these RED tests or claim a repair. Evidence and reproducible command:
ignored `20261102-chart-engine-counterexample/` (13-entry SHA manifest).

This is a genuine constraint conflict, not missing private-blue annotation or
a reason to continue coefficient trials. Proposed specific amendment:
`docs/superpowers/specs/2026-11-02-drasov-optional-emission-amendment.md`.
Bounded optional sampling-row emission is a reviewed candidate, not an approved
or demonstrated fix. Its concrete source-chainage witness fits16profiles/20m;
production recurrence, connector support and cost/provenance must still be
specified under the amendment. User approval is required by Task2's explicit
architecture gate before production implementation. Keep the original full
goal active and all other unfinished requirements intact.

Independent review of the proposed amendment found no blocking inconsistency,
with two incorporated clarifications: 16profiles/20m are adopted numeric bounds
for a NEW transition type, not permission inherited from gap handling; full
observed/gap/censored evidence and all one-row/one-vertex consumers need explicit
versioned mapping updates. The proposal authorizes precise specification and a
bounded experiment only after user approval, not a promise that resource
feasibility or Drasov quality is solved. No production change is authorized yet.

CI qualification completed for12e4522789315e173f13ebcc4602ea21fc87dd5f:
run41466305261 PASS. Downloaded receipts under ignored
`build/private-drasov-quality/ci-41466305261/` show2416Java tests across223JUnit
XML suites, zero failures/errors/skips; Java build, Python tests, sampling-scale,
diff-check and artifact-integrity stages all passed with zero reused stages.
Both GUI summaries PASS for PROBABILISTIC and LEGACY_THEN_B on pinned official
JOSM19555 (SHA d2e2310e24fdf8f4b2d18094997446e443d5894e86f6eeac27a4241bad40338e).
They attest ordinary action, visible preview, geometry oracle, cancellation,
no late publication, unchanged dataset/Undo and silent settings save. Private
fixture scope is explicitly NOT_EXECUTED; this is foundation/public qualification,
not Drasov fitting or full-goal completion. Later local231fed3 changes tests only;
the optional-emission proposal/CI checkpoint changes docs only. They are not part
of that exact CI commit and have not been pushed to trigger redundant full runs.

Current mandatory implementation gate: user decision on the reviewed optional-
emission amendment. This is the first goal turn reaching that concrete approval
gate; do not mark the goal blocked yet. The earlier private-reference question
must not be substituted for the now independently proved architecture issue.
No production guide/correspondence change, intentionally RED-test merge, cap
increase or RC publication may proceed before this approval. The goal remains
active; no unfinished original-plan requirement is waived.

Blocked audit, occurrence2: automatic goal continuation revalidated HEAD2f15ed2,
clean tracked production state, proposed/unapproved amendment status and absence
of saved browser feedback. The preceding goal turn made concrete progress
(preservation controls, engine-level RED/proof, reviewed amendment and full CI
receipts); this turn establishes no new implementation progress. CI is terminal
PASS, not an ongoing wait. The automatic continuation is not user approval of
the changed correspondence contract. No remaining numerical production action
is permitted past this gate; avoid repeated model/parameter experiments or
redundant reviews while awaiting the decision. Goal remains active until the
three-consecutive-turn blocked audit threshold is met or approval arrives.

Blocked audit, occurrence3: the same mandatory amendment decision remains
unanswered. Revalidated the proposed status, unchanged production state and no
saved reference feedback. CI is already complete; no live computation or
remaining permitted numerical implementation can advance this gate. Mark the
goal BLOCKED, preserving its full objective. Resume on explicit approval of
`2026-11-02-drasov-optional-emission-amendment.md`; then finalize/review the exact
recurrence and provenance packet, implement against isolated RED eb151e3, and
complete every original requirement and verification gate. No release is ready
or authorized. Preserve all worktrees, private evidence and unrelated files.

User approved the optional-emission amendment after clarification that it is
not yet demonstrated to improve Drasov. This satisfies the architecture gate
and authorizes precise specification, independent review and the bounded
experiment; it does not qualify a release or waive any original requirement.
Begin a fresh blocked audit if another genuine blocker arises. Previous
approval-block occurrences do not carry forward. Goal runtime still reports
BLOCKED and exposes no resume operation; do not manufacture a new goal or
claim runtime ACTIVE. Continue the explicitly authorized work in this turn.
Next: freeze exact recurrence, admission/cost/provenance and factual raster
context interfaces before production changes, then review and execute without
further general milestone approvals. Keep intentionally RED eb151e3 isolated
until the approved repair makes its unchanged image oracle pass.

Goal amendment explicitly requested by user: include the approved optional-
emission experiments in this existing goal and proceed autonomously without
waiting for implementation-plan approvals. The original full objective and
all unfinished requirements remain. Complete the precise contract, independent
technical review, bounded implementation and original-data comparisons, then
adopt only if practical quality/resource and regression gates pass. Resolve
routine design/plan details internally; do not impose another general plan
approval milestone. Safety invariants, factual evidence, existing resource
ceilings, private visual acceptance and separate RC publication authorization
remain binding. Parallelize independent work in isolated ownership lanes; no
recursive delegation. Current useful lanes are Sol/high recurrence/context
design and Luna/medium observation-to-emission consumer mapping; controller
integrates and assigns bounded implementation after contract review.

User further clarified the goal amendment and explicitly requested resumption:
proceed autonomously with approved experiments and, when their results are
positive, with subsequent designs and implementation plans related to this
objective. Do not wait for those plan approvals. Carry each justified follow-on
through independent technical review, implementation and verification. Preserve
the original full objective, safety/data/resource invariants and separate release
authorization; positive experimental results do not themselves prove completion.
This is standing session authorization, not a recurring approval checkpoint.

Optional-emission contract progress: the initially proposed worst-finite-unary
debit was screened against actual R4 production states before kernel coding.
It is REJECTED for A: bad adjacent path J15.9896833263 versus usable witness
J38.2733302032, dominated by37.84878 unary debt. Legacy witness wins, but that
does not rescue the shared proposal. Receipt and all A state unaries are ignored
under `20261102-optional-emission-cost-screen/`. No coefficient search or
production numerical change followed. Sol is defining physical connector
quadrature over factual source-chainage mass instead; freeze/review it only
after an actual independent-image objective screen.

Independent mapping foundation packet is frozen now so useful bounded work can
proceed alongside numerical design. Add `model/EmissionMapping.java` with
immutable N ObservationOwner entries and M VertexOwner entries; ObservationOwner
is Emitted(vertexIndex) or Consumed(arcIndex), VertexOwner is
ObservationVertex(observationIndex) or BoundaryVertex(occurrenceId). Copy all
collections. Require reciprocal one-to-one emitted ownership, monotonic source
observation order, emitted first/last observations and all mandatory rows,
unique nonblank boundary IDs, valid ranges and consumed rows strictly between
their owning consecutive vertices. Initially reject consumption on arcs touching
boundary-only vertices; adjacent behavior around boundaries remains available.
Expose create, emittedVertexForObservation and consumingArcForObservation with
OptionalInt absence, never fabricated offsets. Native observed/gap/censored
classification remains in the separate full observation ledger. No geometry,
coverage or movement authority is certified by this mapping primitive.
TDD fixtures cover identity and mixed N/M mapping, boundary-only ownership,
mandatory rows, missing/duplicate/out-of-order/null/out-of-range ownership,
defensive copies and query bounds. This unused model adds no engine behavior.
Numerical production entry points remain gated on the complete reviewed contract.

Resumption checkpoint: standing user authorization includes experiment-related
follow-on designs/plans after positive results, without another plan-approval
stop. Runtime goal still reports BLOCKED and exposes no resume/amend operation;
this is a tool-state limitation, not an approval blocker. Authorized work resumes.
No weekly remainder is surfaced. No Astra, speed/pool change or release added.

Experimental integration branch `codex/drasov-optional-emission-experiment`,
worktree `/data/data/com.termux/files/usr/tmp/josm-drasov-optional-emission-experiment`,
now contains mapping commits8efd98a+d76609b (worker958637e+a450dd8).
Twelve mapping regressions passed; independent Sol review requested. Release
branch production remains unchanged. Original intentionally RED eb151e3 remains
isolated; its independent image oracle must not change to fit new output.

The replacement objective screen is `optional-emission-native-integral-v2`:
J=Q+C+T+K, Q=sum(delta original ground chainage/native pitch * mean(1-I))
over actual native bilinear connectors, applied uniformly to adjacent and skip
arcs. Original N observations/native unary tables remain diagnostic; consumed
rows have no invented chosen offsets or automatic direct coverage. Physical K
uses unit weight and squared turn divided by max(1, adjacent mean length/pitch).
Actual existing-state witness beats bad baseline: A0.424549 versus12.954603;
Legacy2.574143 versus22.078044. This is a witness cost screen, not an inferred
optimum or proof of Drasov improvement. Sol is consolidating anchors, GAP
classification, context and actual bounded recurrence before independent review.

Parallel bounded math packet is `/data/data/com.termux/files/usr/tmp/drasov-native-integral-brief.md`:
strict native bilinear segment integral/minimum, conservative touched-cell
support, cancellation/work charging and analytic regressions. Luna owns only
the isolated `codex/drasov-native-segment-integral` worktree. No admission,
branch/region policy or engine integration is delegated to that primitive.
Only this worker holds the local Gradle slot. No recursive delegation.

Mapping task review: independent Sol inspected408f98c..d76609b and returned
clean spec/correctness verdict. Both reciprocal ownership and overflow checks
are verified; this does not certify route geometry or image evidence.

Native integral worker4d9f135:18focused tests GREEN, actual scaffold assertion
RED retained. Full suite was started unnecessarily and canceled without a result;
it is NOT passing evidence. Independent Sol review found a concrete per-cell
quadrature defect: nearParameter coalesces distinct crossings within1e-14.
On a procedural1002-square checkerboard and segment(0,0)->(1000,1000.000000000009),
mean0.6666666666659217 differs from independent per-boundary high-precision
oracle0.66666666666666816354. Fix round1 returns to Luna for a genuine RED,
strict distinct crossing traversal and focused GREEN, then scoped Sol re-review.
Do not integrate this unreviewed numerical service into release or call it exact.

Full v2 contract artifact is frozen for review at ignored
`20261102-optional-emission-cost-screen/contract-v2.md`, SHA256
e3ba710fedadef4d3b71f7c82930d2214d80abc956a0203201c9f59dcc81851a.
Independent Sol found three prerequisite fixes: preserve incoming heading through
zero-length occurrences/anchor phases rather than inventing a pi-squared turn;
charge every DP predecessor/triple visit against existing8M pair AND128M total
limits; explicitly represent Legacy unsupported-row carried-offset states and
anchored-interval target states with non-direct provenance. Directional endpoint
port caches require ordered exact endpoint/tangent/context identity. Author Sol
is resolving these internally; no new user approval gate. The16-interior-row
convention (index gap<=17) is consistent with the current graph screen.

Measured potential graph bounds: R4 exact16-interior/20m gates26,098pairs and
1,878,294triples; Drasov16-interior gate alone1,533,820pairs/441,927,213triples
before image admission (20m factual sequence not yet bound, never fabricated).
Admission and exact caches must establish practical work below BOTH actual caps;
a typed resource refusal is safe but fails the quality-success criterion.

Read-only consumer map saved beside that contract identifies candidate geometry/
offset equal-size APIs, same-index modern adapter pairing, Legacy sequential
offset metrics, cleanup geometry/evidence coupling and Hybrid provenance. Both
engine contracts change; consumed rows have no selected offset. Original N-row
observation/unary tables remain; M geometry and later K final-preview vertices
retain distinct indexing. No interpolated/zero/NaN selected-row padding.

Native integral fix round1:5c2d2ea preserves strictly distinct crossing events;
checkerboard oracle reproduces RED at4d9f135 and GREEN with reverse/subdivision
controls.19focused tests pass; independent Sol re-review marks ADDRESSED, no new
blocker. Boxed cell/node caches remain O(crossed cells) and require caller memory
admission; scalar math alone grants no connector authority. Integrated only into
experimental branch as28d54fb+759343f, alongside reviewed mapping8efd98a+d76609b.
Controller is running their combined31focused regressions on that exact snapshot.

Next independent packet is frozen at
`/data/data/com.termux/files/usr/tmp/drasov-scalar-grid-view-brief.md`:
`service/ScalarFieldGridView` wraps actual immutable ScalarIntensityField or
ScalarEvidenceField with no array copies, retains complete-cell masks separately
from node validity and maps crop/reduction grid coordinates exactly. Add only
package-private ScalarIntensityField grid read accessors; preserve existing
sampling behavior. This readonly producer adapter grants no metre, source-pitch,
branch or region authority. Luna owns isolated `codex/drasov-scalar-grid-view`,
base759343f. Controller retains Gradle slot until combined run completes.

Combined foundation run on759343f completed:31tests in2XML suites, zero failures,
errors or skips (Java17/offline/no-daemon/max-workers1). git diff --check passed.
Scalar view then completed as c6b53cc, integrated experimental as23ee630:
8view tests RED on scaffold,30focused GREEN including3pyramid+19integral;
independent Sol review clean. Existing ScalarIntensityField already exposes
package-private read access, so NO existing-field production edit was needed.
Requested ScalarIntensityFieldTest did not exist; GaussianIntensityPyramidTest
is the factual neighboring suite, not a skipped check. Release production is
unchanged; these primitives remain experimental and unused by native engines.

Contract re-review round1: all3P1 and directional-cache clarification ADDRESSED.
Canonical zero-occurrence physical/reference/scalar memory, actual Legacy carry/
interval-target families and stricter work accounting now have concrete tests
required. Persisted reviewed packet:
`docs/superpowers/specs/2026-11-02-drasov-optional-emission-native-integral-v2.md`.
Reviewed ignored revision SHAe559bd39eed685c724d39d3d5ab72f2050d7bcb344cb22e677116486f921967e.
Original e3ba review snapshot was overwritten before it could be archived;
do not claim that original file survives. Revised source and scoped review notes
are retained. No contradiction remains requiring user approval.

Factual20m graph binding is now complete: frozen Drasov309chainages bit-identical
to all309CorridorTraceInput distances, bound by row IDs/hash to5334allowed states.
The original unbounded graph count reproduces exactly. BOTH16interior/20m gates
give3630row arcs,1,091,848state pairs,227,563,996triple-state extensions before
image admission/transport (28.45times8M). See ignored chainage-binding.json and
bounded-graph-receipt.json. These are potential bounds, not actual admitted work.

Active bounded phase3: Sol owns `codex/drasov-emission-context`, base23ee630,
brief `/data/data/com.termux/files/usr/tmp/drasov-emission-context-brief.md`.
Implement exact retained L0 plus immutable detached/explicit synthetic factual
context and tests. Visible/managed contexts and whole consumers remain required
later scope. Sol holds the single Gradle slot. No kernel/admission bypass.

Parallel required work-budget primitive: Luna/medium owns isolated
`codex/drasov-emission-budget`, base23ee630, exact packet
`/data/data/com.termux/files/usr/tmp/drasov-work-budget-brief.md`.
EmissionWorkBudget(TraceBudgets,CancellationProbe), charge(Operation,long), snapshot
shares one attempt's stricter8Mpair/128Mtotal counters, atomic admission, typed
terminal refusal and sticky cancellation. It does NOT replace AttemptMemoryLedger
or certify engine integration. Worker may prepare code while waiting for Gradle.
No recursive agents, Astra, source acquisitions, cap increase or release added.

Work-budget primitive completed a529775 (experimental integration bcb5c21):
10focused tests GREEN after9intended scaffold assertion failures; independent
Sol source/test review clean. Worker used default Java21 and incorrectly reported
Java17 unavailable; controller verified explicit Termux17.0.20 binary and the
worker corrected its report. Required17verification remains PENDING until the
context integration run includes these10tests; do not relabel the21receipt.
No source change was needed for that environment correction. Context Sol now
holds Gradle; unused budget worker will not run duplicate verification.

Phase3 exact API/test packet:
`/data/data/com.termux/files/usr/tmp/drasov-emission-context-packet.md`.
Opaque producer/source binding admits shared-owner memory BEFORE the existing
single L0 producer, returns a closeable input lifetime, and keeps immutable
context facts separate from the existing mutable normalScreen profile accessor.
Eventual engine entry must revalidate profile coordinates/normals against those
facts. Do not broaden this into a global legacy-profile refactor. Shared budget
is caller-owned; no fresh phase allowance. Production visible/managed factories,
protected-event integration, connector admission, solver and consumers remain
explicit remaining work. Full Drasov quality is not yet demonstrated.

Phase3 committed04c4735 in `codex/drasov-emission-context`, following shared-budget
dependencyd76310d. Final Java17.0.20 run:51tests (13context/12detached/8grid/
8continuity/10budget), zero failures/errors/skips. New REDs cover prepublication
normal mutation and exact source-read accounting; canceled after10reads reports10,
not the full1600node count. Backward-compatible ScalarIntensityField callback
overload is a specifically authorized necessary allowlist extension; old arithmetic
is unchanged. Phase3 Sol/high independent source/ownership audit is now running;
do not integrate as approved until findings close. Visible/managed contexts,
protected-event integration, admission, kernel and consumers remain pending.

Useful parallel phase4 metadata lane: Luna owns isolated
`codex/drasov-mode-threshold-receipts`, basebcb5c21, brief
`/data/data/com.termux/files/usr/tmp/drasov-mode-threshold-brief.md`.
Ruling: preserve old DetailedResult/base collector layouts and default native work/
allocation identities. The initial proposed sixth component was superseded BEFORE
behavioral RED or implementation. Corrected component packet SHA
d886b8793e65186f2add14b09436c3e40a9156ea32c0b4ce0df83da3b7e53a27
uses explicit extractDetailedWithAcceptedThresholds and separate immutable wrapper.
No threshold formula, extractor acceptance or default numerical change is allowed.
Worker holds Gradle for focused RED/GREEN and baseline parity checks.

Sol is preparing bounded NativeConnectorEvaluator packet and a cheap actual-R4
production-extractor screen while its phase3 source remains frozen for audit.
Scalar shoulder-slab minima and finite probes alone do not prove continuous branch
identity; that obligation remains explicit. No proxy acceptance, extra tracing,
source-forward/Jacobian gate or cap increase is authorized. Standing autonomous
experiment/follow-on authorization remains; no user plan-approval stop added.

Context fix round1 COMPLETE: independent Sol re-review marks both producer-permit
and preparation-accounting findings ADDRESSED, no new blocker. Workerbf6ec8a
tests59focused plus8MetricRegion neighbors onJava17, all67pass withzero errors/
skips. Exact active pre-admitted permit binds width/step/pitch and source identity;
Gaussian/profile/region variable work now charges the shared bound. Authorized
necessary callback overloads preserve old scalar/filter arithmetic and traversal.
Integrated experimental as2374934+6dc9683; release worktree production unchanged.

Threshold metadata fix round1 COMPLETE: workerdf65328+caf2f14 integrated only
experimentally as09849d0+b70bc35;38focused Java17 tests GREEN and independent Sol
re-review all findings ADDRESSED. Fixed explicit IdentityHashMap capacity sizing,
before-new collector reservation and separate receipt-copy work. One-mode map
oracle544versusold416 and nonpower3/6 cases retain true RED evidence. Corrected
fixture plateau is explicitly atindices64/65=1.0; frozen prechange/current class
receipts use the SAME fixture and bound cleanbcb5source/dependency identities/JDK17:
detailed work3148/peak18840/retained864, plain3137/14232/456, veto3150/18840/896.
All default receipts match exactly; no inferred parity from new output alone.
Durable component: `docs/superpowers/specs/2026-11-02-drasov-native-threshold-receipts.md`.

Next coherent batch gate: push only public source/tests/docs on experimental
branch for existing public CI and both real-JOSM GUI smoke engines. Workflow
triggers all branches and has NO release publication job. No private capture,
fixture or local screenshot will be added. Full CI remains pending, not passing.
Stage4 Sol remains read-only, preparing actual production-extractor R4 screen and
bounded connector/continuous-branch packet; kernel and usable-geometry gates are
still open. No RC or numerical default change is authorized by this checkpoint.

## Current resumption checkpoint — coherent experimental batch

Standing user authorization is recorded in the Goal above: execute approved
experiments and related positive-result follow-on designs/plans autonomously.
No further implementation-plan approval is needed. All original requirements
remain. No Astra use, cap increase or RC publication has been authorized here.

Experimental source branch `codex/drasov-optional-emission-experiment` is now
pushed at180701b92218440592231289ec43b77b575ef4c6. Its integrated source passed
136distinct focused tests across12XML suites onJava17, zero failures/errors/skips;
full task diff against12e4522 passes git diff --check and the worktree is clean.
Every consequential component/fix above has independent Sol review. This remains
input/evidence infrastructure, not a repaired Legacy/A inference result.

Public full CI is running unattended:
https://github.com/holubp/josm-wayheatmaptracer/actions/runs/41502206347
Exact head180701b verified by gh run list. Full public validation and both existing
real-JOSM GUI engines are required. Status last observed IN_PROGRESS; do not call
it green. Watch session86554 writes only ignored experimental-worktree
`build/private-drasov-quality/ci-41502206347/watch.log`. Next use gh run view,
then download final evidence into that ignored directory and verify actual head,
suite counts, both GUI summaries and public host receipt. No private data uploaded
and no public release was created.

The scalar discriminator is POSITIVE but PARTIAL: the exact committed R4 paint
loop was repainted, production fromRaster crop[18,17,110,102] reproduced and all
native fixture profiles match bit-exactly. Final float-L0 SHA
d4b9ead3e6b15afb1b9818b715d7ef197c3ba9e150e0759b47756c08629830a6.
Both engines' known witness probes have33/33 uniquely compatible complete native
modes and measured flanks/core receipts; minimum actual arc intensities are
A0.9997175/Legacy0.9614827. Preliminary full-image hash27c008... is superseded for
this crop-specific receipt. Work108123/108078 concerns only three witness arcs
per engine, not full graph feasibility. No original decision authority, endpoint
ownership or continuous branch admission is certified by this screen.

Historical checkpoint (superseded by the resumption below): next dependency was the continuous native branch certificate and practical exact
admission graph. The978word packet is ignored controller-release
`build/private-drasov-quality/20261102-optional-emission-cost-screen/native-connector-stage4-packet.md`
SHAfeec8a17882bd2f102eacfe5be2f0487e8c431fb99fd0b1b2e93f040af6b9797.
Current APIs do not mint the needed between-probe critical-component/plateau
certificate. Do not relabel finite probes/scalar minima as full branch proof,
enable an ADMITTED fallback, or code the full kernel against a made-up receipt.
Input-owned execution session/child-memory-owner access also needs its precise
bounded API. The actual67test context revision is bf6ec8a, not superseded04c4735.

One focused Astra consultation was requested asynchronously, because the user's
model policy requires explicit approval. NO approval received and NO Astra used.
The question is model-use permission for this difficult continuous-corridor/work-
bound decision, not approval of another implementation plan. The user may choose
Sol-only instead. Unreviewed consultation hypotheses are at
`/data/data/com.termux/files/usr/tmp/drasov-continuous-certificate-hypotheses.md`;
they are NOT an admission rule. In particular source-cross-section overlap as
extra-edge eligibility would change the v2 graph and requires technical review
and a declared policy, not a hidden performance-only pruning change.

All child source writers/reviewers are currently completed; source worktrees are
preserved clean, including both fixes and original intentionally RED eb151e3.
No default engine, palette, source width, transaction or published JAR changed.
The existing goal service still reports stale BLOCKED from the former approval
gate and exposes no resume/objective-update operation. Do not falsely complete
or recreate it to change the flag. This does not revoke the user's authorization.
Weekly remainder is unknown; no speed/pool or budget-limit change was made.

After the model consultation decision, freeze/review the exact branch certificate,
run actual admission/work feasibility before substantial consumers, implement the
one exact A kernel and existing Legacy stages against unchanged eb151e3 image
oracles, then preservation/real Drasov+Omice and every remaining original Task1–6
gate. User-accepted Drasov visual reference is still pending, never invented.

## Resumption — native admission implementation

User explicitly resumed the ongoing experiments. Repository reconciliation:
experimental HEAD180701b remains clean; the existing native-connector worktree
also remains clean at that base, with no unreported implementation to integrate.
Primary dirty worktree and unrelated private/untracked files are preserved.

Ruling: use the independent Sol scope correction in
`docs/superpowers/specs/2026-11-02-drasov-native-admission-scope-review.md`.
The approved contract requires uniquely measured continuation at declared native
resolution plus exact continuous scalar support and full segment authority; it
does not require a continuous critical-topology/CAD/disk theorem. The prior
checkpoint's stronger certificate requirement was implementation-added. Removing
it retains endpoint ownership, complete modes, competing-mode rejection, mutual
continuation, strict support and all resource/safety gates. Cost if wrong: a
sub-probe topological feature is not certified; receipts must explicitly avoid
claiming that theorem and final geometry safety remains mandatory. No Astra
consultation is necessary or used for this implementation step.

Public CI41502206347 now reports SUCCESS at exact180701b, including public
validation and both PROBABILISTIC/LEGACY_THEN_B real-JOSM GUI jobs. Artifact-level
test counts and host receipts are being independently extracted; private quality
and repaired inference remain unverified.

Execution: isolated Sol6.1/high worker implements NativeConnectorEvaluator from
`/data/data/com.termux/files/usr/tmp/drasov-native-connector-brief.md` in
`/data/data/com.termux/files/usr/tmp/josm-drasov-native-connector` (base180701b).
One writer/one local Gradle process. A parallel Luna/low lane extracts public CI
evidence only. No recursive agents, source default changes or release. Native
state/event domain declarations are prototype inputs, not automatic attestation
of real-engine authority; later adapters must construct them from actual native
enumerators and captured protected occurrences. Actual R4 assertion RED/GREEN
and independent review precede integration. No new user plan approval is needed.

CI artifacts independently verified:228JUnit XMLs,2494executions/2449distinct
class-name cases, zero failures/errors/skips. Both GUI summaries PASS, official
JOSM19555host SHA d2e2310e24fdf8f4b2d18094997446e443d5894e86f6eeac27a4241bad40338e.
Public validation explicitly records private_inputs=false and private fixtures
not executed. Downloaded artifacts/report live only in ignored experimental
`build/private-drasov-quality/ci-41502206347` and
`/data/data/com.termux/files/usr/tmp/drasov-ci-41502206347-report.md`.

Useful parallel preparation: the Luna lane now owns isolated
`/data/data/com.termux/files/usr/tmp/josm-drasov-a-native-domain` at180701b,
brief `/data/data/com.termux/files/usr/tmp/drasov-a-native-domain-brief.md`.
It exposes exact existing A allowedOffsets/base weights as immutable row facts,
without alternate enumeration, metric authority, default engine or solver change.
Source ownership is nonoverlapping with connector work. Native-connector worker
owns local Gradle first; domain tests queue for that slot. Both require retained
behavioral tests and independent review before experimental integration.

Connector TDD reached intended RED:1test/1assertion failure, A witness0->1
expected ADMITTED but scaffold returned UNAVAILABLE/NOT_IMPLEMENTED. XML preserved
at `/data/data/com.termux/files/usr/tmp/drasov-native-connector-evidence/red/`.
The Sol6.1/high worker then hit service model capacity after writing a first
implementation (not a weekly-quota measurement). No commit/green/review claimed.
Controller took over its isolated worktree inline; Luna retains the Gradle slot
for domain RED/GREEN. A briefly overlapping connector run was terminated by the
controller; its incomplete results are not evidence. No retry/model escalation
or Astra use. Current connector/source-input edits remain experimental/unreviewed.

### Native implementation checkpoint — 2026-11-03

A domain implementation ac67ba2 plus test fix f86f40e independently reviewed by
Sol6medium fallback (6.1capacity unavailable): exact native helper reuse/default
optimizer unchanged. Review found missing nonunit/full-reduction parity tests;
Luna closed both, scoped re-review ADDRESSED,25focused Java17 tests green. Entire
ordered canonical output is checked at sourcePixel0.5 and NaN->native1.0, with
more than21eligible source samples and actual post-dedup19states, not an invented
requirement to fill21. Integrated into experimental branch as a4497d6+fe2c175;
not yet pushed/CI-tested at this new head. No behavior/default adoption.

Connector inline implementation: first11tests exposed/fixed acceptance of two
unowned ports and duplicate native row/state IDs (actual assertion RED retained).
Real R4 known witness admission passes for A and Legacy through retained cropped
field, production extraction and factual endpoint ownership. Expanded suite now
110tests/10XMLs, zero failures/errors/skips, Java17. Includes slab darkness, invalid
cells, narrow authority notch, exact16/20limits, protected barriers, sourceGAP
fresh binding, foreign identities, sticky exhausted work, closed input, measured
fork, broad plateau, off-connector invalid sample and issued zero-edge transport.
Initial0.1faint control wrongly called the original synthetic fallback OBSERVED;
corrected to a real observed0.7owner tapering to unchanged0.1GAP endpoint, and
asserts original fallback/GAP provenance explicitly. No intensity/threshold tuning.

Full source/report/diff are in native-connector worktree and
`/data/data/com.termux/files/usr/tmp/drasov-native-connector-{report,review}.md`
(review report pending), initial/expanded logs and RED XMLs in Termux tmp. Source
uncommitted and not integrated. Independent Sol6high review is in progress under
the allowed fallback after6.1capacity failure. Its first concrete finding is
projection before validation and scalar-pitch rather than actual affine normal
displacement in state bounds; controller awaits complete report for one coherent
TDD fix wave. Do not count110green as complete review/admission correctness.

Parallel Luna prepares ignored actual complete R4 native-A graph runner, reusing
all original states, factual limits and ONE shared work/memory attempt. It must
report typed resource refusal as negative feasibility, never reset/catch-continue
or infer graph practicality from the witness. Run after review corrections. All
native inference kernels, actual Drasov/Omice quality, consumers and original
integration/private gates remain outstanding. No RC/release created.

Review-fix wave: independent connector review found projection-before-validation,
general affine scalar-pitch assumptions, and inherited tiny authority tolerances.
Discriminating executions matter: the retained producer ALREADY rejects materially
anisotropic/skewed frames, so that proposed adversary is an upstream guard control,
not a reproduced evaluator defect. A valid uniform2m transform with matching
resolution/ground chainages DOES reproduce overflow before validation. A single
tiny-notch polygon was rejected at construction (not RED); the accepted union of
two rectangles separated1e-9m DOES reproduce skipped authority. Corrected run:
3tests,2intended assertion failures; anisotropy guard passed. Root reordered native
state/span validation before projection and derives normal displacement/owner
uncertainty from retained affine axes defensively without widening accepted frames.

Sol6high owns isolated `josm-drasov-conservative-authority` (base180701b), brief
`/data/data/com.termux/files/usr/tmp/drasov-conservative-authority-brief.md`.
New opt-in conservative segment AND point membership preserve ordinary methods;
unresolved event intervals/near-parallel or boundary arithmetic fail closed.
Actual union-gap RED retained, then40MetricRegion tests GREEN; tightened independent
near-parallel fixture rerun6GREEN with unchanged source (34neighbors retain valid
evidence). Implementation review/commit/integration remain pending; worker cannot
approve its own consequential change. No new default authority convention adopted.

Additional attempt-lifetime regression: deterministic in-evaluation cancellation
passes, but after memory refusal releasing scratch allowed a NEW evaluator session
on the same attempt. Actual assertion RED retained in terminal-red XML. Root added
first-failure retention to SampledEmissionInput execution entry (debug source access
remains available); constructor/evaluation resource failures record it. This fix
awaits combined GREEN and independent review. Do not label it verified yet.

Ignored full-R4-A graph runner prepared by Luna, no execution yet. Its reference
native tracking/enumeration is explicitly outside measured admission accounting;
domain preparation charges are labelled proxies. It can show negative admission
feasibility or bounded admission execution, never full solver/engine feasibility.
All real original state pairs retained, ONEsharedcap, no reset/pruning. Source/class
fingerprint must match final Gradle output before running, Java heap512MiB matches
project setting. Prep report `/data/data/com.termux/files/usr/tmp/drasov-r4-full-graph-prep.md`.

### Reviewed prototype and measured admission limit

Authority2b534fd+84a093e independently reviewed clean, including literal invalid
notch constructor rejection plus valid wider one-polygon notch forward/reverse.
Native source checkpoint e1e9b72, helper imports f24b75a+4e6c9bb and conservative
call-site4704668 are clean in `josm-drasov-native-connector`. Final combined
Java17 offline run123tests/11XML,0failures/errors/skips. Scoped independent Sol6high
review closes the original findings and new terminal-input lifetime correction;
report `drasov-native-connector-fix-review.md`. Relevant prototype control coverage
is not equivalent to completed native engine/adoption gates.

Integrated locally into `codex/drasov-optional-emission-experiment`:
a4497d6/fe2c175 (A domain),751e308/562aba1/2f0ac63/80cd23f (connector/authority).
Head80cd23f, clean, not pushed yet. Public full CI still only establishes180701b.
Keep the native checkpoint's known-RED authority dependency history honest; its
subsequent helper/call-site commits and123test run close that regression.

ACTUAL complete-graph screen at4704668 is NEGATIVE feasibility:239states and26098
potential pairs,551attempted/550completed,184admitted/366rejected, remaining25548
pairs unmeasured (includes the interrupted pair). Stopped typed total-work limit
at127999998units requesting4more.8.423s graph,10.9MiBpeak tracked memory, final0.
Work:mode extraction72.08M,node reads22.24M,regions22.12M,preparation8.69M,
cell queries2.85M. No cap increase, catch-continue, missing-pair rejection claim,
or solver-quality success. Reference tracking/domain proxies remain explicitly
outside/bounded estimates in the receipt. Source/class fingerprint/launch receipt,
helper and report remain ignored in `20261103-r4-full-a-graph/`.

Cheap necessary-owner census (Luna) proves only6of239states/1173of26098pairs fail
that distance bound;24925pairs remain (95.505%). At equal work1.047x, not the
rough48x gap. Do not spend a first implementation batch on this tiny filter alone.

Reviewed follow-on subplan:
`docs/superpowers/plans/2026-11-03-native-admission-resolution-experiments.md`.
Dense p/8 transverse step was implementation-added, not an approved lateral rule.
v2half-native ALONG-connector probes remain unchanged. Proposed explicit
HALF_NATIVE_L0 and PIXEL_CAPPED_L0 numerical variants preserve dense baseline and
all native states/caps; no automatic adoption or equivalence claim. Requested d
differs from producer interleaved actual L0 max(d,r)/2 (R4.5m), so the1mvariant
is NOT labelled producer-equivalent. Stop-truncated dense pairs are UNMEASURED;
common-pair comparison/new-admission independent adjudication are mandatory.

Active writer: pinned Luna/medium `/root/query_policy`, isolated
`/data/data/com.termux/files/usr/tmp/josm-drasov-query-resolution` base4704668.
Brief `drasov-query-resolution-brief.md`. It owns the single local Gradle slot;
assertion RED and preliminary92test GREEN reported, then extending all required
policy safety/phase controls before final report. Do not call Task2complete yet.
Bench harness prepared externally at `/data/data/com.termux/files/usr/tmp/drasov-query-policy-benchmark`;
Python comparison self-test passed; NOnew Java compile/run yet. Root must verify
code/tests/review and source/class fingerprint before three independent capped
policy runs. CI/publication of this coherent batch awaits those outcomes.

Further gates unchanged: actual A and Legacy inference kernels, genuine A band
owners (not fabricated Legacy peak ownership), protected captured events and live
factories, exact observations/emissions mapping consumers, original preservation
controls/real Drasov+Omice quality, Move/Precise/Hybrid/Apply/Undo/replay and final
review. No release/default engine changed. Model routes explicitly requested per
AGENTS; config has Luna/medium child defaults and no role pin files. Exact runtime
allowance/model telemetry not surfaced; do not invent weekly usage. No Astra used.

### Resumption checkpoint: query-policy review controls

Task2 implementation is isolated at `josm-drasov-query-resolution`, a170478,
with test-only review fixes in progress. Initial Java17 focused/neighbor run:
119 tests, 0 failures/errors/skips (NativeConnectorEvaluatorTest has 56, not 57).
Independent review found no production defect but required full three-arc R4
admission assertions, a meaningful competing/censored-mode control and evaluator
corner-mask coverage before Task2 closure. Do not treat the initial green run as
completion. Single local Gradle slot remains with `/root/query_policy`.

The new masked-ridge control invalidates only native node(20,34), with a genuine
Gaussian ridge centered at y32 and genuine endpoint owners. Dense admission and
coarser NO_COMPLETE_MODE differ. The earlier reported selected shoulder .007 was
a diagnostic attribution error: at x19 the selected dense mode has centre0,
sigma.5, shoulder .9036, valid samples at offsets +/-.25 with values .9016327,
and valid below-core neighbours. The .007 threshold belongs to a separate censored
mode more than 3 native pixels away. Preserve the unchanged fixture and obtain a
causal independent ruling before asserting either agreement or a defect. Strict
cell support can fail before x20; an exact t=.5 expectation was not justified.
Adjacent-corner mask(20,33) already yields SCALAR_SUPPORT for every policy.

The original faint phase-shift Gaussian and its RED evidence remain preserved;
its old producer is GAP, while the new extractor still sees faint modes. The
separate measured between-row area-coverage stroke supplies a genuine positive
ownership control. Automatic review rejected widening/deleting the original test;
no shell edit or guard bypass was used.

Task3 harness remains unrun. Comparer fingerprint equality is being strengthened
before use, in addition to existing common-completed/unmeasured distinctions.
Next: close justified review assertions, run neighboring suites, then freeze the
reviewed build and run all three capped same-input graph measurements. No engine
integration, route-quality success or RC release is established by these controls.

Ruling: preserve the masked fixture but assert its justified policy difference —
independent Sol/high diagnosis confirms dense has genuine observed below-shoulder
and below-core samples before the invalid support, whereas coarser policies lack
a complete mode and conservatively reject. No production correction is indicated;
forcing agreement would conceal numerical scope. The separate censored mode lies
outside the declared competitor window. Cost if wrong: unsupported admission or
lost coarse coverage; explicit receipt assertions and later common-pair admission
adjudication remain required. Existing split/fork control covers this bounded
Task2 concern; semantic parallel identity is not claimed. Diagnostic artifact:
`drasov-censored-flank-diagnostic.txt`; review report updated independently.

Task2 is now COMPLETE at7e6c64f: focused125tests and controller-expanded185tests
/14XML (region/budget/grid neighbors),0failures/errors/skips, Java17 offline
Gradle48s. Independent Sol/high closes all four focused findings on actual diff.
Source integrated cleanly as87ad860+49e8323+131a9dc in the experimental worktree;
no push/default/engine change. Reviewed source/class/JOSM fingerprint:
`4e6ca424eb2478b90fa9795095daab3fd6fbf61706f746bd1d020464b32a8f06`.

Task3 first complete capped comparisons ran successfully as a measurement harness,
but every policy is NEGATIVE full-graph feasibility. Same239states/26098keys,
identical scalar/state/chainage/field hashes verified by the comparer:

| Policy | Completed | Admitted | Rejected | Unmeasured | Peak tracked bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dense p/8 | 550 | 184 | 366 | 25548 | 10782536 |
| Half-native | 1621 | 534 | 1087 | 24477 | 34067928 |
| Pixel-capped | 7749 | 2869 | 4880 | 18349 | 68297040 |

All stopped at128M total work (actual128000000/127999507/127999999),0unavailable
completed receipts. Each interrupted pair is UNMEASURED, not rejected. Current
memory in receipts is measured before closing retained input; harness separately
asserts final ledger0. Per-policy elapsed was omitted in this first helper and is
not invented; helper timing/final-memory output is being added before a preserved
second run. Reference native tracking.472s is outside admission accounting.

Common-completed550pair comparison: half changes56outcomes/reasons, including3
dense-rejected admissions; pixel changes105, including45dense-rejected admissions.
Half347/pixel2640admissions against dense-unmeasured remain UNKNOWN comparisons.
No unmatched keys. Coarse-only admissions need image/receipt adjudication before
adoption, even though inspected endpoints remain near the analytic ridge.
Artifacts: Termux `drasov-query-policy-results-7e6c64f/`, frozen fingerprint and
benchmark log. Next bounded exact-cost diagnosis delegated to Sol/high; Luna
prepares pair summaries and timing-only helper correction. No inference kernel
or original Drasov/Omice goal-completion claim follows from these measurements.

Timed repeat with timing-only helper correction yields byte-identical CSVs:
dense8.904s,half8.505s,pixel10.616s to their different work-cap prefixes; final
closed tracked memory0 each. Not whole-engine/full-graph speed measurements.
Public reviewed batch f7f11d4 pushed; CI41560731847 is running (public inputs only).

Independent numerical inspection of48policy-specific admissions (45distinct keys,
3half included in45pixel) finds identical scalar integrals and no hidden dark
interior: endpoint offsets.5361–.6055native pixels, analytic quantized-Gaussian
minima agree within1.6e-9. Coarser sampled peaks can lower their own shoulder
thresholds; no established formula defect, but summary rows omit per-probe/slab
proof. Targeted135evaluation replay/export is now delegated to Luna under the
same caps, not another full graph. Unknown dense-unmeasured admissions stay unknown.

Sol/high cost review finds no single local exact optimization plausibly bridges
the remaining3.4xPixel/~16xHalf work gap. Task4 in the resolution subplan measures
bitwise profile-query reuse before a cache; Luna executes ignored helper only,
with actual witness bit-parity checks before full enumeration. Do not implement
a speculative cache or raise caps. Exact forward reachability could omit only
proved zero-mass sources, but original ordinary native edges also propagate even
when scalar support is unavailable (loss1). Thus optional-only reachability is NOT
a valid pruning argument; reviewer is checking whether native ordinary adjacency
already reaches every R4 state. Full graph gate remains until a concrete reviewed
correction, with no approval waiver inferred from these negative runs.

Exact-key census COMPLETE/negative:9/9witness schedule bit checks, all26098pairs
and334958queries/policy;0duplicate exact conservative keys,0zero-length arcs.
No cache implemented. Broader content/factored reuse is not disproved. Newly
generated310MB tables losslessly compressed to36MB with decompressed hashes
verified against the report; manifest retained, unrelated files untouched.
Independent source review also closes reachability as unhelpful: all239native
states are reachable through ordinary adjacency, even without optional edges.
No lazy-kernel gate waiver or optional-only pruning. See resolution subplan Task4.

Next scope is a bounded NEW numerical-certificate design consultation (Sol/high,
no Astra): original full-window 20th-percentile noise/background and +/-6sample
uncertainty forbid claiming a small local window is an exact optimization. Any
candidate must explicitly version different local semantics, preserve all native
states/full configured search, prove measured core/flanks/competitor/halo support,
expand/fallback on unknown bounds, then face original strong/sparse/switchback and
capped graph gates. No code for this new policy is authorized by a false exactness
claim. Standing autonomous experiment authority covers design and testing; original
completion/engine/replay/release requirements are not narrowed.

CI41560731847 is now SUCCESS at exactf7f11d46225f2cd2d9b7321ba0d9ae172522583e:
231XML suites,2566executions/2482distinct(class,name)identities,0failures/errors/
skips. Public validation and both JOSM19555 GUI engines pass. Official host SHA
d2e2310e24fdf8f4b2d18094997446e443d5894e86f6eeac27a4241bad40338e;
private_inputs=false/private_fixture_scope=not-executed. Report and bounded ignored
artifact download preserved; no JAR/release publication. Local experiment50947e2
adds only the later evidence checkpoint beyond that passing source revision.

New bounded spec/plan:
`docs/superpowers/specs/2026-11-03-drasov-zero-guarded-local-certificate.md` and
`docs/superpowers/plans/2026-11-03-drasov-zero-guarded-local-screen.md`.
Separate Sol/high design and independent review permit a numerical screen after
3corrections, now incorporated: BOTH-direction variable-pitch competitor/tap
support; extractor-wrapped memory refusal terminal across input sessions; exact
absence/partial-prefix/backend evidence. Existing actual extractor is reused;
computed local noiseFloor must be0 rather than injecting/overriding observations.
No local numerical code implemented yet.

ACTIVE prerequisite Task2a: sole writer Luna/medium `/root/query_policy`, new
`/data/data/com.termux/files/usr/tmp/josm-drasov-zero-guarded-local`, branch
`codex/drasov-zero-guarded-local`, BASE7e6c64f. It owns local Gradle. Fix only the
review-proven extractor-memory-wrapper terminal hole with actual in-extraction
RED/same-input retry identity, then focused GREEN/scoped commit. Do not start
local-policy numerical implementation until this prerequisite is inspected and
the next bounded packet is issued. Allowed evaluator/test only, no source shell
edits, recursive agents, defaults/engine/API-pool/release changes or cap raises.

Task2a COMPLETE24bef0d (experimental integration a60d5fa): one-line catch plus
21line actual in-extraction regression. RED proves same-input retry wrongly
succeeded after scratch release; final14XML/186tests0fail/error/skip. Root inspected
actual diff/XML and independent finding owner CLOSED. Saved RED XML/log and
hashes are in the worker report; Fixture.close asserts final tracked memory0.

ACTIVE Task2b: same isolated writer/worktree, BASE24bef0d. Numerical policy packet
issued after a fourth pre-code review correction: DEFER early slab optimization.
Cross-probe expansion/fallback can change a middle mode/threshold and stale its
earlier left proof. Settle both-neighbor obligations with monotone expansion and
at-most-once dense fallback, then freeze evidence before mutual/slab decisions.
Three-probe regression must actually distinguish provisional/final middle values.
This removes an unsafe proposed mechanism, not the original performance gate;
no early speedup is claimed. Existing three policies and full search/state/caps
remain unchanged. New policy is internal only; full capped screen follows tests
and independent source review, not worker implementation alone.

Task2b worker batch e5fc530 is now implemented in the isolated zero-guarded
worktree, NOT yet integrated/adopted or declared complete. Reported197tests is
179+separate18context; exact saved evidence requested before closure. Independent
Sol/high actual-code audit is active (scope coverage, fixed-point proof stability,
zero/censor/halo/metadata, terminal resource lifetime and old numerical parity).
New-policy zero transport/cancel/memory/remote-branch controls must be reconciled
explicitly; old-policy-only tests are insufficient. Next: scoped fix batch if
needed, complete focused/neighbors, freeze reviewed source/classes, then ONLY
new-policy capped graph using prepared metadata runner. No full graph run yet.

Current milestone supersedes the preceding worker report: Task2b fixes7374eb2
are independently CLOSED; exact14-suite220tests/0fail/error/skip saved and parsed.
Source locally integrated d4370ce+f498826. Sole new-policy graph run is NEGATIVE:
579of26098completed,201admitted/378rejected,25519UNMEASURED,128Mwork stop,
13.599s,14.4MBpeak,final0. All original state/input identities unchanged. Source
fingerprint a600546d0653871cb17add6cb014bde30b0eb20a8c22eaa41b458fba8a021258.
No engine/inference/release adoption. Details are in the bounded screen plan.

Measured cause:219NO_COMPLETE_MODEpairs consume120.811Mwork (94.38%). Every
planned probe is acquired before that failure is checked. First10replays match
exactly and all first fail at probe0 on immutable DENSE_FALLBACK;77.78% of their
sample queries lie in the later suffix. This is count opportunity, not a speedup.

ACTIVE next bounded EXACT execution experiment (no new numerical predicate):
`docs/superpowers/plans/2026-11-03-drasov-final-mode-failure-execution.md`.
Sol/high mathematical review permits stopping only final dense failure/proved
local absence; positive local, neighbor and slab evidence still settle normally.
Default constructors remain eager; explicit opt-in order is versioned. All states,
source identities/caps/admitted Q remain binding; no unproved no-expansion shortcut.
Sole source writer `/root/query_policy`, new worktree
`/data/data/com.termux/files/usr/tmp/josm-drasov-local-final-failure`, branch
`codex/drasov-local-final-failure`, BASE7374eb2; it owns local Gradle. Old compiled
zero-local worktree remains immutable baseline. Luna evidence worker prepares only
external runner execution selection; no graph until tests/independent review.
