# Drasov geometry repair for Legacy and Corridor A

Status: USER APPROVED2026-11-02 ("Go ahead" in response to execution-plan preparation).
Design only; no implementation or release authority
is added by this document. Baseline: RC14 source6f98691, release worktree50e1e35.
Independent Sol6.1/medium builder check2026-11-02: no material planning gaps;
causal fixtures, reference annotation and architecture-amendment gates retained.
User design approval received; execution planning authorized, implementation not started.

## Intent and scope

Produce practically usable Legacy and Corridor A results on Drasov while preserving
their successful traces elsewhere. The user selected the sequence: common diagnosis,
Legacy repair, then Corridor A repair. Successful output must fit the visible trace
without unsupported kinks or newly introduced self-intersections. More rejected
candidates, successful command execution, or improved numerical scores alone do not
meet this objective.

Keep engine choices/defaults, source acquisition, palette semantics, configured
search width, fixed-node/junction protections and command transactions unchanged
unless a separately reviewed, specific defect requires an explicitly described
change. Engine B inference/search/performance redesign is outside this design.
Hybrid compatibility with the repaired Legacy output is a required regression.

## Evidence and current implementation

Private input Drasov.zip SHA256
d8580ef870e8e0a91ec447519c487ac176469cce3833b6939b11b9559b78b3cb.
Keep imagery, coordinates and bundles local; public CI uses synthetic fixtures.
Private report and overlays are under primary build/private-drasov-analysis-20261102.

- Applied Legacy bluered has two turns above90degrees, maximum164.3896degrees;
  applied aggregate has one, maximum124.5794degrees. These are observations, not
  a universal angle-based failure criterion.
-17/21 raw Legacy ridges have a turn above90degrees. Their exported corridor
  turn counters are neutral zeros and warnings empty, so these fields are not
  evidence of measured safety.
- Corridor A final contact evidence identifies two new self-intersections.
- User reports one blue-family Legacy variant works. The exact candidate has
  not been identified reliably from the saved applied selections. Do not claim
  that blue/ridge-1 or blue-corridor/ridge-1 is the accepted reference by inference.
- Standalone B has no route; Hybrid's native B refinement reaches128M scores and
  its Legacy fallback is blocked. Their speed problem is independent of this work.

RenderedHeatmapSampler builds cross-sections using neighboring source-anchor
tangents. RidgeTracker optimizes/smooths scalar offsets and reconstructs points as
anchor+normal*offset. At the main defect, a0.16m offset change accompanies a3.71m
physical step, followed by a0.26m step. Changing normals is a supported hypothesis,
not yet a complete causal proof. Palette-dependent peak switches and fixed-anchor
reconstruction must be separated from it.

CorridorCenterlineOptimizer already has geometricCurvatureCost, corridor-tube
terms and a second-order optimizer. Its defect must be traced through those actual
terms and track ownership; do not propose adding an already existing mechanism.
Legacy assessment currently consumes populated safety warnings, signal/support and
measured coverage. CorridorQuality.empty supplies neutral legacy-compatible values;
the new diagnostics must distinguish availability instead of reinterpreting zeros.

## Approaches considered

1. **Selected: repair physical geometry through existing engines.** Diagnose the
   shared failure, repair Legacy narrowly, measure Corridor A again, then repair
   its independently demonstrated failures. This preserves successful behavior
   and avoids changing several interacting mechanisms at once.
2. **Corridor A first / replacement of Legacy.** Offers a single stronger optimizer
   but loses the requested usable Legacy path and assumes A's self-intersections
   are solved. Rejected as the delivery sequence, retained as the second workstream.
3. **B as a general corrective refinement.** B is accurate on strong traces but
   currently cannot finish this wide search. Making geometry repair depend on it
   couples two unresolved projects. Deferred, not removed from product choices.

## Invariants

- No final moving average, geometry consensus or hidden second tracing pass.
- Preserve ±25m discovery where explicitly configured. Do not substitute a narrow
  search, change its UI meaning, or inflate computational/resource limits.
- Physical continuity uses ground metres, measured chainage and factual source
  resolution; raster/projection units are not interchangeable with metres.
- A supported switchback may reverse relative to original OSM direction. No global
  monotonic-forward rule, blanket turn cutoff or source-direction prior may erase it.
- Weak evidence may warrant a smoother path or review, but must not fabricate
  image support or force a jump between unrelated strands.
- Invalid/censored/missing raster support never authorizes a new center or turn.
- Protected anchors remain exact; new geometry/quality results are candidate-owned,
  immutable and revalidated at confirmation/Apply. Diagnostics do not mutate OSM.
- Legacy changes remain in its own path; Corridor A retains its isolated exact
  second-order DP, bounded states, tube/association rules and tie determinism.

## Workstream 1: causal reconstruction and acceptance baseline

Create an offline diagnostic reproduction from original slide-time inputs, not the
already applied kinked way. Bind capture SHA, build, engine/mode, palette mapping,
selection occurrences and all effective settings. Keep raw tracker, post-anchor
preview and actual applied geometry distinct.

For each selected defect export a bounded row containing anchor/chainage, normal,
observed peaks and selected peak identity, chosen offset, physical candidate point,
neighbor distances/headings, fixed-anchor ownership and evidence availability.
Trace where the first incorrect geometry appears. For A, include track identity,
allowed states and already-existing local objective terms. Reconstruct only the
relevant intervals initially; no repeated full B inference is needed.

Use controlled comparisons that alter one factor: captured offsets on original
frames; controlled smooth/fixed offsets on those frames; exact sampled-curve data
with changed source vertex density; and raw versus anchor-reconstructed preview.
Label altered inputs as diagnostic experiments, never historical replay results.

Exit criterion: a reproducible causal account and minimal deterministic failing
fixture for each proposed mechanism change. A report that merely flags sharp
angles is insufficient. If shared sampling cannot be changed without affecting
the evidence domain or supported shape, obtain a specific design amendment before
production edits rather than silently introducing a new sampling guide.

Reference acceptance: prepare a small local browser comparison with candidate IDs
and defect intervals for user annotation. Existing captures permit investigation
now; absence of the exact good blue ID does not block it. User identification or
approval of a reference is required before claiming private visual acceptance.

## Workstream 2: Legacy repair

Keep peak extraction, detector ordering, seeding, anchors and unsupported-run
fallback unchanged except where the causal reproduction proves a specific defect.
Prefer an engine-local correction at the first faulty construction/decision step.
Do not smooth away the final symptom.

If scalar continuity admits physical foldbacks, constrain or score candidate
transitions using their actual reconstructed geometry and local image support.
Use bounded, source-resolution-aware influence that permits strongly supported
sharp bends. Offset smoothness alone is not a safety proof. If the first defect
instead arises from nonregular sampling frames or anchor insertion, repair that
step and remeasure before changing tracker weights. These are evidence-selected
mechanism branches, not authorization to implement all speculative alternatives.

Specify coefficients, units, evidence ownership and supported-turn exceptions in
the follow-up implementation plan from the causal fixture. No untested tuning
loop against private Drasov alone. Retain the released implementation as an offline
comparison baseline, without adding another public engine/menu option.

Exit: Drasov's annotated Legacy defects removed with usable supported geometry;
the accepted good candidate preserved within an explicitly reviewed physical
tolerance; intense corridors, sparse strands and supported switchbacks retained.
Newly blocked defective results are interim protection, not completion.

## Workstream 3: final-preview quality and truthful diagnostics

Measure exact immutable final preview after anchor reconstruction and simplification
for every affected Legacy/A candidate. Prefer reuse of existing final-quality
primitives when their evidence contracts are satisfied; never synthesize a frozen
image field or full authority merely to call FinalGeometryEvaluator.

Export measurement availability explicitly. For old bundles retain historical
interpretation; unavailable Legacy corridor fields do not become measured zeros.
Additive format/identity changes must be versioned and covered by codec tests.
Keep raw, preview and applied measurement sets separate and attributable.

Block objectively unsafe new self-crossings/overlaps and independently established
unsupported foldbacks under the existing topology policy. Preserve explicitly
reviewable inherited context contacts/disconnection rules; do not blanket-reject
every crossing or every sharp turn. Inconclusive image support remains unavailable
or review-required under the applicable contract, never invented confidence.

Check source freshness, candidate switching, confirmation, Apply and Undo/Redo on
the exact inspected geometry. Export failures through the existing modeless/current-
attempt UI. No new modal dialogs or analysis-only launchers.

## Workstream 4: Corridor A

First replay A after any proven shared change and attribute the delta. If the
crossings persist, trace sampled band completeness, strand association, tube,
allowed states, objective costs and final anchor insertion at each crossing.

Repair only the earliest proven incorrect decision. Physical terms must charge
actual candidate geometry and respect local branch-owned evidence. Existing
geometricCurvatureCost may already do this; investigate evidence attribution,
term activation and relative weights before adding a new term. Distinct strands
must not be joined through an unsupported connector. Strongly supported real
turns must not be flattened to avoid a crossing.

Keep a single exact pair-state DP. If preventing the demonstrated self-crossing
requires nonlocal history beyond this architecture, stop this mechanism branch
and present a bounded design amendment; no beam search, hidden repair pass or
unreviewed topology-state explosion. Existing final rejection stays in place.

Exit: A produces usable non-self-intersecting geometry on annotated Drasov cases,
retains complete alternative corridors and preserves its previously good Omice
bluered-corridor result and independent real/synthetic controls. Merely retaining
its current hard block does not finish A repair.

## Verification and delivery gates

Use deterministic RED→GREEN for each confirmed defect. Synthetic fixtures cover
rotating normals with smooth offsets; close competing strands; genuine sharp
switchbacks; weak separated thin traces; fixed anchors; invalid/censored support;
and equivalent insertion of collinear source nodes. Assertions concern physical
shape/evidence and authorized geometry, not only incidental exact coordinates.
Numerical changes get named reviewed comparison policies; strict recorded replay
remains strict by default. New identities preserve old formats and native work.

Run both engines in Precise Shape and Move Existing Nodes, through managed and
visible sampling consumers where affected. Do not require their outputs to be
identical across modes. Precise may add/remove authorized nodes; Move retains node
identities/count and may have less shape freedom. Test Hybrid interval fallback,
untouched junction neighborhoods and unrelated ways/relations, source staleness,
confirmation, exact atomic command behavior and Undo/Redo.

Benchmark released versus changed Legacy/A phase times and bounded work using
the same frozen cases/settings on one host. Report repeat count and variability;
no required speedup factor and no unobserved timing guarantee. Do not fix quality
by introducing a many-minute computation into Legacy/A. A material runtime or
memory regression needs explicit review before inclusion.

Public CI runs synthetic correctness, codec/diagnostic, full regression and actual
GUI tests unattended. Private local runs consume the actual captures; no private
artifact upload. Independent Sol review covers the complete stable task delta and
original criteria; use cheaper bounded workers where useful, one writer/worktree.

Before an RC: all in-scope gates green, private reference comparison recorded,
no blocking review finding, applicable usable output demonstrated, correct JAR
manifest and downloaded bytes verified if publication is separately authorized.
Do not publish intermediate experimental results as RCs automatically.

## Decisions left to implementation planning

The builder may select fixture/helper layout, shared measurement reuse and bounded
diagnostic representation. The cause-dependent repair location, numerical policy
and tolerances must be justified by the causal fixtures and recorded in the plan;
they are not silently accepted architecture changes. User visual acceptance of
the good Drasov reference remains a later validation checkpoint. No estimate of
weekly allowance is asserted without telemetry. B search/performance work, new
Hybrid behavior, engine removal, broad junction changes and stable0.22 release
scope remain outside this design.
