# Drasov Legacy and Corridor A Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for tightly coupled controller work; use superpowers:subagent-driven-development only for explicitly delegated isolated tasks. Track steps below. Do not turn an investigation hypothesis into an assumed production fix.

**Goal:** Produce usable Legacy and Corridor A geometry on Drasov without unsupported kinks, retaining successful strong traces, sparse strands and supported switchbacks. Execute the approved optional-emission experiments and, on positive results, related follow-on designs, implementation plans and verified implementation autonomously, using useful parallelism.

**Architecture:** Reconstruct the first faulty physical-geometry decision, repair Legacy narrowly, then remeasure and repair Corridor A independently. Assess exact final previews and preserve versioned diagnostics, existing transaction authority and A's one exact second-order DP. Diagnosis determines the repair mechanism before its numerical patch is specified.

**Tech Stack:** Java17/JUnit5, JOSM19555, Gradle, existing offline replay and public GitHub CI; private local imagery.

**Spec:** `docs/superpowers/specs/2026-11-02-drasov-legacy-corridor-quality-design.md` (approved2026-11-02).

## Resume checkpoint — 2026-11-05

### Active execution card — authoritative

**LATEST VERIFIED CLOSURE — 2026-11-06:** The active Legacy Drasov zigzag goal
is complete on pushed production/test snapshot
`f80fde258d51a6d0d1f0677646d7926535801153`, branch
`codex/v022-legacy-b-release`. GitHub run42148673153 completed SUCCESS:
2454 public Java tests (225 XML suites),0failures/errors/skips; complete public
validation including Python, sampling scale, diff and artifact integrity;
both Probabilistic and Legacy-then-B Xvfb GUI smoke jobs; and the matched
Legacy speed job. The three-fork/eight-group/twenty-pair speed gate has zero
breaches (maximum median overhead0.614427ms, maximum p95 overhead6.954555ms).
The separately configured20 private regression tests and local Python222tests
plus9subtests also pass. Independent production and scoped repair reviews have
no outstanding blocker. Actual unchanged-Drasov image comparison removes the
marked backward fold (worst164.3896/154.5890degrees becomes maximum42.5368),
with increasing reference chainage and exact fixed endpoints; the clipped v2
figure was visually rechecked. No filters or tracker objective were changed.

Validated CI JAR SHA256:
`162fd0d8b23600ce0354d62fe3333783d81dd78d33972f094fabe00c36ff6a77`.
Local test copy: root `build/wayheatmaptracer-legacy-zigzag-f80fde25.jar`.
Its manifest remains0.22.0-rc.14/JOSM19555; this is an explicitly identified CI
test build, not a newly published RC or stable0.22 release. Artifacts and225
JUnit XML suites are under root
`build/private-drasov-resume-20261105/legacy-wide-secant/integration/ci-final-42148673153`.
Primary dirty checkout and its unrelated/private files remain untouched.
Filter optimization, pre-existing managed-hairpin baseline apex limitations,
Corridor-A work and remaining broader0.22 release scope are not claimed solved.
The older checkpoint paragraphs below are historical and superseded by this closure.

**CI execution milestone — 2026-11-06:** Frozen source `cef5556c820361ebf6d259d70e7aadacc06ef08b`
pushed to `codex/v022-legacy-b-release`. GitHub Build run42143312418 is in
progress: https://github.com/holubp/josm-wayheatmaptracer/actions/runs/42143312418.
Independent scoped re-review closed BGT-1/2/3 and the pinned JOSM host setup
after repairs9a46ba4f/cef5556. All18 focused Python gate/workflow tests pass;
the corrected16-row public quality preflight passes. Combined production source
passed20 private regression tests,0 failures/errors/skips (2fixture,17palette,
1sparse replay), with XML retained under root `legacy-wide-secant/integration/private-final-xml`.
The three-fork matched performance verdict and full public CI are PENDING;
do not declare speed acceptance or goal completion before inspecting artifacts.
No private captures were uploaded, no release published, no filter changes made.

**LATEST CI RERUN:** https://github.com/holubp/josm-wayheatmaptracer/actions/runs/42148673153
on pushed `f80fde258d51a6d0d1f0677646d7926535801153` is now in progress.
The earlier42143312418 run completed its three-fork speed gate PASS (zero
breaches; maximum median overhead0.431ms), but public Java validation had one
failure in2454 tests: an old ordinal-cancellation memory-boundary test. Test-only
repair64bbd596 reproduced RED and passed GREEN, and independent review passed
its paid-allocation-state assertions; production code is unchanged. Full local
Python suite222tests plus9subtests passes. Latest whole-CI/GUI outcome is pending;
inspect the new run before closing the active Legacy goal. Root evidence is
`legacy-wide-secant/integration/ci-performance-42143312418` and
`integration/ci-public-42143312418/test-fix`. No new filter work or release.

**Integration milestone — 2026-11-06:** Controller production snapshot950230d5
contains both standalone and Hybrid/frozen-source integrations. `classes
testClasses` passed (Gradle9.4.1/JDK17, offline, 2m17s). Controller worktree has
only the three preserved unrelated untracked handoff/plan files before this
ledger update. Independent whole-delta audit found two open P2 items; fix round
1/5 is active with original authors: LSEC-1 adds actual per-attempt sampling
policy/fallback to ordinary diagnostics JSON; LSEC-2 retains the incremental
Hybrid input metadata under the attempt owner after temporary planner scratch
closes. Neither changes the direction algorithm. Covering tests and scoped
independent re-review are required before closing either finding. Public
visible/native-managed benchmark preparation is ongoing; no matched speed
result, final public CI, or release is claimed. Filter optimization remains
deferred by the user's latest instruction.

**CURRENT NEXT ACTION — 2026-11-06:** The ONE actual Drasov image pair is
POSITIVE for the marked fold. Integrate only the reviewed sampling-direction
core into genuine Legacy callers, including its Hybrid stage with truthful
historical replay policy; then complete public/real-fixture regressions, matched
speed measurement and final independent review. Filter changes remain deferred.
Read `zigzag-live-integration-packet.md` under the inspection evidence directory
(SHA2569d44a7151c9db2797a01498af5792c28b95ec697eb70db486bee5ea44a29565c).
Named core22bbe4f3 (38focusedPASS) was cherry-picked as884211a8 onto controller;
retired smoother commits were NOT imported. Live integration now runs in two
isolated worktrees: L1 Luna owns standalone Legacy callers/Tile/Rendered/shared
policy in `/data/data/com.termux/files/usr/tmp/josm-legacy-secants-live`
(`codex/drasov-legacy-secants-live`); L2 Sol owns frozen Hybrid source/runner/
codec/receipt/replay in `.worktrees/drasov-integrated-verification`
(`codex/drasov-legacy-secants-hybrid`, reused clean; old branch preserved).
L1 shared API79ca1a26: enum `LegacyProfileNormalPolicy`, strict ids and immutable
`LegacyProfileNormalFences.Plan(eligible,reason,profileBoundaries)`;6testsPASS,
behavioral empty-fence stub RED retained. L1 Tile/Rendered API43c3ed3b: explicit
policy+original-fixed-index overloads for native/aggregate,17targetedtestsPASS,
old signatures remain immediate. L2 cherry-picked shared API as1a164754 and is
integrating43c3 after current RED run; no shared source writers. Source-backed
integration packet is approved under standing autonomous goal execution.

L2 global fallback ruling: LIVE unprovable plan resolves to effective IMMEDIATE
before freezing and retains bounded reason/original fixed-index authority.
Replay of an explicitly protected payload with invalid/mismatched authority
rejects; it never silently changes recorded policy. Per-row endpoint/protected/
degenerate-chord fallback is intrinsic to protected3. VERSION1 immediate empty
defaults remain byte-identical; VERSION2 carries explicit policy/fallback facts.
Receipt producer must derive effective policy from frozen source; minimal
Format15ProductionBundleFactory call site is in L2 ownership. Pure A/B untouched.

Predeclared speed screen (before matched measurements): actual production Legacy
discovery on public frozen visible/managed captures, starting before policy/fence
preparation and ending after final previews. Acquisition/rendering/export are
excluded and must be stated. Warm12 then20 alternating old/new pairs per scene,
3fresh JVM forks, same JDK/caches/detectors/source and no B inference. Include
straight, displaced/noisy-frame bend, strong hairpin and parallel strands, with
geometry/support/fixed-point/call-count assertions. Report absolute ms AND ratios.
Median overhead above max(5ms,10% baseline), or p95 above max(10ms,20% baseline),
in >=2of3 forks for any scene blocks adoption. A noisy isolated fork is
inconclusive, never silently passed. Include source-count fence planning and
native decoder/geometry work; no percentage-only microbenchmark verdict.

Actual `legacy-wide-secant/private-pair-one` process EXITED2.746852s under512MiB/
120s guard, original24 input hashes and all1410 runtime fences unchanged. Ordinary
185profiles/301peaks archive parity passes;1native candidate each,2samplings/
2ordinary tracker calls/2identical finishes. Stored final worst turns fall from
164.3896/154.5890degrees to maximum42.5368degrees; marked118–123 nearest-reference
progress is now increasing. Final sampled zero-intensity probes13→10, mean
0.2570974→0.2573290; both self-contact reports empty and fixed endpoints exact.
These are sampled/qualitative numerical observations, NOT continuous image
support or live Apply authority. No performance acceptance from ordered one-pair
timings. `final-preview-comparison-v2.png` uses actual stored FINAL geometry;
v1figure had un-clipped overlay spill and is preserved as superseded.

Source reviewer closed the two test gaps against an independently compiled exact
7-test copy (7PASS/0skip,1.839s), then author applied the same patch and38 focused
Gradle tests pass. First unqualified whole-project run was deliberately stopped
by controller when live integration superseded its snapshot: exit130, process
reaped, Gradle lock released. It had4 missing-private-archive failures before
stop; do NOT label it passing or treat those as proven new code regressions.
All3 original private archives exist at repository root. Final public CI uses
existing public validation profile; local private gates use RC profile with
absolute fixtureRegressionArchive/heatmapFixtureArchive/sparseCorridorDebugArchive
properties for `wayheatmaptracer-testing.zip`, `extracted-tiles.zip`, and
`last-slide-debug-1785794101840.zip`. No fixture uploads or new search required.
Whole-project/fixture/speed/adoption gates remain required, not waived.

**Active goal:** Finish the Legacy Drasov repair without degrading other cases
or sacrificing Legacy's fast performance. User explicitly defers filter changes
until the marked zigzag is resolved. Prior turn updated evidence/ledger but made
no production repair; completion remains unproven.

Geometry next-step report shows a common reachable marked arc of23.2661919 native
pixels within unchanged118–123 anchors' existing radius. Changing directions can
therefore remove the annotation reversal in principle; a particular rule remains
unverified. ONE public static discriminator is now assigned to existing Luna:
immediate secant versus fixed radius3 secant, noisy Gaussian-bump source against
straight independent ridge, and displaced genuine-turn/150degree/hairpin controls.
No filter, image, tracker, production or private-variant execution in this batch.
Outcome must gate the next experiment; do not tune windows against Drasov.

Controller independently reproduced the public analytic RED: immediate secants
hit y8 at x−3.5392494 then−4.3413959, and x4.3413959 then3.5392494,
reversing order despite all hits being inside radius16. Fixed radius3 secants
remove both reversals (maximum hit distance8.0990878). This is geometric
evidence only; displaced genuine-turn controls, actual-image quality and runtime
remain outstanding. No production defaults or tracker/filter code changed.

Public discriminator v1 executed once: Gaussian reversal removed; displaced
straight,90degree corner and180degree hairpin pass, hairpin extra intersections
unchanged32→32. Overall NEGATIVE_OR_INCONCLUSIVE: exact finite150degree arc has
29/31 hits and135degree sampled turn under BOTH policies because identical
one-sided endpoint rays miss the displaced target's endpoints. Original report
and JSON retained unchanged. Sol is auditing whether this fixture conflicts with
fixed endpoint semantics and identifying the smallest honest control correction;
do not label the whole gate passing or tune radius3. Artifact filenames:
`public-normal-discriminator.py`, `.json`, `-report.md` in the inspection folder.

### Bounded zigzag sampling experiment — execution packet

Scope follows approved autonomous cheap-Legacy experiments; filter changes and
new solvers remain excluded. V2 public control supplements (never overwrites) v1:
boundary-compatible150degree core plus connectors has all rows ordered/reachable,
fixed endpoint errors0, analytic150degree span and independent145degree polygon
turn. Hairpin per-row competitors do not worsen. Controller's ONE frozen radius3
static Drasov check reduces3 reversals to1 of0.0118003 native pixels along the
approximate annotation. This is not image quality or practical repair evidence.
Sol independently reviewed v2 source/oracles: PASS for scoped public geometry
only. Circle37/37 and hairpin35/35 have one owned reachable hit each; circle
fixed endpoints exact and hairpin competitors nonworse per row. Circle's stored
`sourceChainage=i*5` is an angular station label, NOT factual metric arclength;
never import it into production sampling. The original v1 negative is retained.
Actual-image helper and named source implementation still require review.

Named sampler author reports actual old-sampler behavioral RED, followed by six
new tests GREEN. Source/test frozen for independent Sol review at SHA256
`f8a793437f14148cb78759d85f80ed2f8202dd05d00dc3e0654d99c4128d83b0` /
`1c01a6462a0466e23ad8d9890329cf509e0cdbcee4c6112054cd5499efd97dfe`.
Focused neighboring suites and full project tests remain in progress; no commit,
live adoption, actual-image result or timing qualification yet. New helper draft
is undergoing correction before public smoke: exact shape-derived width/step,
nonempty explicit dense boundaries, actual archive parity, public-only route and
source fences are required. Never run the draft with hardcoded16/1 parameters.

Helper is now compiled against the actual isolated target (not dirty root APIs).
Controller corrected public source scaling/normalization before smoke, added
atomic phase/counter core, nearest-native-pixel tracker/final support probes and
limited production contact diagnostics. Public smoke-three through final bounded
launcher EXITED2.735s:2samplings/2ordinarytracks/2finishes, all1410 reviewed-runtime
inventory entries unchanged. Original expected24 input hashes are bound to the
previous quality receipt. Runtime manifest SHA256
`51ed77d7023b0640ba27ed5df764f7fed52c3188ff270928753b2c5c2850a180`.
Deterministic launcher controls pass interruption, timeout, receipt-write failure
and interruption before wait: original failure identity retained, synthetic
child stopped/reaped, last atomic record retained, ZERO actual Java/private reads.
Fresh private claim NOT consumed; actual Drasov comparison NOT run. Final scoped
Sol helper re-review is in progress. Source test-only review fixes still pending
the live full-suite completion; no production adoption or performance claim.

- [ ] TDD named sampler only in existing clean isolated fast-smoother worktree
  atdf842141. Author owns `RenderedHeatmapSampler.java` and one new test class;
  preserve retired heuristic and ordinary Legacy/A/B defaults. Add a package-private
  named wide-secant sampling entry feeding the existing plain sampling loop.
  Radius3, immediate fallback in first/last3 rows, finite nonzero chord required.
  Require explicit dense-profile protected boundary indices including0,last;
  leave old directions when radius3 crosses any boundary. Unknown authority is
  ineligible, never guessed from nearest dense row. No live caller yet.
- [ ] RED must expose original geometric reversal through actual sampler normals;
  GREEN uses the named entry. Cover straight parity, fixed boundaries, degenerate
  chord fallback, genuine-turn controls, cancellation and unchanged ordinary path.
  Run focused sampler suites and required project tests, retain exact evidence.
- [ ] Independent Sol review of actual diff/tests before actual-image experiment.
  Any private helper must sample the immutable original mosaic anew, keep anchors,
  frame,width and count fixed, use ordinary tracker/finalization and explicitly
  recorded numerical-only endpoint authority. Archived1D profiles cannot be reused
  as rotated evidence. One baseline/candidate pair, no window sweep.
- [ ] Assess marked-region reversal/final support plus other strong/sparse/turn
  fixtures and matched whole-workflow runtime. Only a positive result authorizes
  live Legacy-only caller integration with factual protected-occurrence mapping;
  never apply the named helper globally or activate rejected smoother. Keep goal
  active until actual Drasov repair, regressions and speed are proven.

**Prior 2026-11-05 checkpoint (superseded next actions):** User explicitly prioritizes the marked
zigzag geometry repair; defer convolution/filter optimization. Both browser
feedbacks are saved: first 14-point line is the approximate intended ridge;
second 2-point line marks the worst location ONLY. `feedback-roles.json` binds
these roles. Independent static intersection analysis confirms nonmonotone
ray/ridge correspondence around rows118–123 for this annotation (not universal
impossibility across the finite-width corridor). All26 ray labels exist; ray124
crosses120/121 near the marker. Evidence: `legacy-normal-chart-inspection/`
`marked-ridge-intersections.json` and `static-ridge-diagnosis.md` under
`build/private-drasov-resume-20261105/`. Public v2 geometry discriminator and
independent Sol oracle audit pass their scoped checks. Existing Luna author is
verifying the named sampler TDD packet below atdf842141:37 focused tests pass,
full Gradle test worker is live in agent session52312. Independent source review
requires two test-only default-parity assertions; production source is conforming
and frozen. To avoid blocking the hypothesis on the slow existing Hybrid full
run, author will compile/run the exact updated test copy in a separate artifact
class directory/JVM against the same frozen main bytecode now. Apply that same
patch to the worktree only after its live Gradle run ends. Whole-project evidence
remains required for completion, not for this numerical observation. Helper/
launcher final review PASS; controller must close the test gap/re-review, then
admit ONE actual-image comparison. No new solver, filters,
private inference yet, or further Astra. Preserve supported
hairpins, sharp bends, configured width, protection authority and Legacy speed.
Ordinary Legacy remains unchanged; real Drasov repair and original A work remain
open. The detailed entries below preserve the chronological execution evidence.

- USER REDIRECTION2026-11-05 supersedes the experimental-solver continuation
  below for Legacy: ordinary Legacy must not become dramatically slower.
  Focus cheap heuristics within the existing fast tracker, not a new expensive
  solver. Suspend new grouped/native solver development, private solver attempts
  and consumer-detachment integration; preserve all committed/dirty work and
  evidence. No production adoption, deletion or rollback implied. Existing A
  work remains partial; a new shared consumer project is not the shortest Legacy
  repair. First reconcile prior failed heuristics and propose source-backed cheap
  alternatives, preserving supported bends/strong traces and measured runtime.
  Prior broad autonomous approval remains subject to this new constraint.
- Consumer writer stopped safely atd1ba in drasov-consumer-detachment:2modified
  producerfiles/3untracked snapshot-adapter-testfiles preserved, no commit. Intended
  absent-API RED recorded; GREEN attempt interrupted before a result, NOT passing.
  Durable consumer-detachment/report.md and five-file checkpoint manifest865d810a
  preserve unfinished work. Do not integrate or classify it as completed.
- Final native-guide128 result audit: U1056.4837149472492 is bit-identical to
  earlier witness (409081ef52f89c16), so it gives NO stronger pruning bound.
  Narrow first-seed feasibility PASS, stronger-U hypothesis NEGATIVE. This is
  additional evidence against extending that route as a practical Legacy fix.
- Fast-heuristic options reviewed independently78ba77b0: select ONE named
  existing-smoother coherence/backoff experiment (Option2), not new spur/chord
  evidence infrastructure. Existing interpolation helper sorts/copies percall;
  the experiment must use once-validated ordered samples and bounded direct
  brackets, fixed window/backoff and no pertrial allocation. Current ordinary
  default remains unchanged pending supported-bend/isolated-stray discrimination,
  strong/sparse/switchback/parallel/protection regressions and matched tracker+
  whole-workflow runtime. This addresses darkening only; raw fold remains open.
  Exact compact packet2026-11-05-legacy-fast-smoother-experiment.md SHA0222c8fa
  independently reviewed1f21c138 PASS after3clarifications (fixround1/5,3addressed,
  0open). Luna worker owns `.worktrees/drasov-legacy-fast-smoother` frome8d69ba2,
  only RidgeTracker+oneexperimenttest. Named experiment only; no ordinary default
  change, newsolver import, private replay/benchmark or adoption at this stage.
- Fast-smoother initial public behavioral RED→GREEN reported on nine-row analytic
  selected straight ridge: old update darkens, named experiment retains support.
  Remaining preservation/invalid/ambiguity/parity suites still running; no private
  quality or performance verdict. Ruling: changedUpdates counts decisions whose
  final experimental value differs from the old proposal y, including retaining
  current after rejecting y; counting only motion from current would hide active
  protection. Worker to retain exact diagnostic regression for this clarification.
- Fast-smoother code frozen419c011c12abc19c026f9ec9965feeec79d8a77f frome8d,
  clean isolatedbranch, twofilesonly;41tests/5classes0f/e/s. Named actual bend,
  isolated stray,150degree arc and parallel controls verified; retained-sample
  hairpin still OPEN (existing switchback tests fallback only). Source review
  active. Ordinary bodies unchanged. Public sampled-raster benchmark helper is
  prepared; controller authorizes its ONE run as normal verification before code
  review completes, not adoption/private access. No special private-run admission
  machinery applies to public synthetic tests. Full workflow timing and private
  Drasov quality remain OPEN, not supplied by tracker-only timing.
- First public benchmark419c completedONE20.93s run, deterministic geometry/input
  and source/class fences unchanged. REJECTED runtime medians:straight1.3812,
  bend1.2849,stray1.1649,parallel1.5061; JIT/GC noise prevents exact steady-state
  attribution, NOT PASS. No fixture geometry changed, changedUpdates0, yet688–724
  trials/call. No adoption/private replay. Reviewer assessing ONE bounded exact
  no-change/lazy-evidence cost revision, not thresholds/model tuning; if another
  cost revision cannot meet the gate, abandon this heuristic implementation.
- Source reviewbbd12b0 BLOCKED exactlyP1/P2. Fixround1/5 dispatched to original
  author from419c: reject search-window-clipped core via conservative interior+
  both-endchannel bracket, and correct analytic fixed[-25,25] B3/B5 convolution/
  relative support oracle. Need intendedbehavioralRED/GREEN and affected review.
  No performance changes in this correctness fix until reviewed bounded packet.
- Cost follow-on independently approvedf4b4e616: after correctedEAGER R1 freeze,
  ONE separate semantics-preserving lazy/no-op revision (not threshold/model
  tuning). Snapshot mutable geometry/reference inputs, prove correctedEAGER/
  LAZY exact output parity including signedzero; counters report actual work.
  Rebenchmark once with all4originalfailed cases retained plus ONE declared
  activeguard case. If failure or inconclusive, STOP this heuristic implementation.
  No private replay/adoption or cap changes. Fresh public-benchmark-r1 artifacts
  preserve prior benchmark and source evidence; ordinary default stays untouched.
- CorrectedEAGER R1 frozena10aa25a9986f0916044b1c99d856911a6f3bd46,
  42tests/5classes0f/e/s. P1clippingRED expected0 got10 recorded byauthor;
  historical rawREDlog/XML notpreserved, so classify as reported observation,
  not independently archived. CorrectedGREEN/source/class artifacts verified.
  ScopedP1/P2source re-review PASS/aeb020f7, bothADDRESSED, no blocking newbreakage.
- Cost TDD oracle correction: author's initial new test expected certified
  stationary−0 to remain−0, but correctedEAGER alpha1 arithmetic yields+0.
  That reported RED is INVALID for this cost-only task, not a new geometrybug.
  Controller required exact eager/lazy output parity: certified case+0 retained,
  uncertifiedfallback sign retained. Valid costRED must demonstrate wasted work
  reduction with geometry unchanged. No silent signedzero model change permitted.
- Lazy test adaptation: automaticapprovalreview rejected simply removing positive
  bend/arc certificate assertions as weaker coverage. Safe replacement must retain
  eager positive-evidence assertions plus strict eager/lazy raw-bit geometry parity,
  AND active lazy guard support/certificate assertions. No shell bypass or dropped
  quality gate. Inactive lazy counters may report zero only as actual skipped work.
  Cost revision and benchmark final disposition are recorded below; no adoption.
- Lazy costrevision frozendf842141d72244578d03b29a49cba788ab2e13fe aftera10,
  clean two-filedelta,43tests/5classes0f/e/s. Corrected eager stationary case
  255samplevisits/1certificate versus lazy0/0, exact output bits; active analytic
  lazy10certificates/84trials/28changedupdates. Cost-source review active on exact
  a10→df842 package; author stopped. Controller authorizes ONE publicR1 run with
  unchanged4originalcases+declaredactivefifth, same rejectiongates/fresh output.
  No private inputs/adoption; prior419negative benchmark immutable. Source/class
  hashes and honest invalid-initial-oracle note retained in report/artifacts.
- FINAL HEURISTIC-V1 DISPOSITION: STOP/REJECT, not adopted. Cost-source review
  PASS1a33030f with43tests/5classes verified; current eager-cache test oracle is
  not literal a10 binary execution (frozen arithmetic/source proof supplements
  tests). ONE R1 publicrun23.506s/5000calls preserved all4oldcases+activefifth:
  medianstraight1.2508 andactive1.1476 REJECT; bend1.0366,stray0.9381,parallel
  1.0700 INCONCLUSIVE dueJIT/GC. Activecase changedUpdates1436 shows real action;
  source/classes/inputhashes unchanged. No retry/private replay/adoption/release.
  Ordinary Legacy remains unchanged. All V1 implementation/optimization stopped.
- After two failed cost attempts, focused Sol/high READONLY diagnosis is allowed
  to identify a genuinely smaller heuristic using pre-existing tracker evidence,
  not a third V1 retry/new evidence-processing layer. Must distinguish raw forced
  fold from smoothing darkening and identify actual cheap source seams; no new
  implementation or larger design without that evidence. No Astra authorized.
- Reviewed diagnosis c53784fd identifies a planning-metric defect: absolute
  tracker median differences were +0.222336ms/+0.264084ms; noisy kernel % screens
  do NOT establish dramatic end-to-end slowdown. Old REJECT and STOPcosttuning
  remain unchanged. Controller wrote separate2026-11-05-legacy-quality-only-
  diagnostic.md to permit ONE independently reviewed quality-only comparison on
  unchangeddf842, not a timing retry/adoption/gate waiver. Protocolreview pending.
  Read-only metadata prep confirms exact saved mosaic/frame/pixelphase and15node
  source/fixed[0,14], Precise/simplifyOff/junctionOff; ordinary numericalOnly
  sampler/projectCandidate +LegacyPreciseGeometry.finish exist. No pixeldecode,
  inference/private workload yet. Original liveOSM authority remains unavailable.
- Quality-only protocolreview389cd74f PASSforpreparation. Minimalhelper compiled,
  synthetic smoke/gate/CLI checks passed, frozenmanifest4e2ebdcb; no privateclaim
  or decode. Helperreviewcd3b368d BLOCKED only2failurepaths: primary-safe atomic
  CORE-beforebulk export and guaranteed bounded JVMgroup kill on timeout.
  Fixround1/5 dispatched, no source/frame/math redesign. Controllerclarifications
  now explicit in protocol: all nativecandidates≤3/route,≤6finalizations actualIDs;
  strictcontroller-created manifest/review/ONEgo admission record accepted instead
  of newCLI flags. Raw/anchoredcurrenttrace remains unavailable without extraDP.
  No private execution until scopedfixreviewPASS/refreeze/newcontrollerONEgo.
- QualityhelperR1 finalPASS aedc9786 bindsmanifest1f861dc1. Controller wrote the
  reviewed strict admission record and ran EXACTONE --execute-one; claim consumed.
  Execution3.66s/exit0/reaped, source fenceunchanged and all24before/after hashes
  match. Sample1/ordinary1/named1/finish2;185profiles/301peaks, archiveDelta0.
  Independentresultreview483a609c: evidencePASS, practical target repairNEGATIVE.
  Onlytrackerrow/finalvertex36 differs; target119–126 androw124exactlyunchanged.
  Bothcomplete sampled intensity sequences unchanged (921tracker/920final probes,
  13zeroeach). Native B3max atrow126=.23009318017281663<.25 rigorously excludes
  the five-row certificate for124; this is ONE exclusion, not sole causality or
  permission to lower a private threshold. V1familyretired; no tuning/adoption.
- User explicitly approved ONE focused Astra ANALYSIS-ONLY consultation ("Yes",
  2026-11-05). Requested route gpt-6-astra/medium, agentastra_fast_legacy_consult;
  actual runtime/weekly telemetry unavailable. Only report writing is authorized
  for that consultation: no source edits, private variants, timing retries,
  decoding, new solver or delegation. Brief focused-design-brief.md is approved;
  output astra-focused-design-review.md completed (683words). Recommendation:
  no grounded cheap repair yet; first inspect one static local image overlay to
  determine intended ridge order against normal rows119–126. Do not infer global
  Drasov impossibility from public folded-chart fixture or tune intensity floors.
  Agent's Git ownership check failed; controller independently verified clean
  df842 before consultation, with no source writer active. No Astra follow-up
  authorized; all implementation/adoption constraints and negatives unchanged.
- Standing original data-analysis authority permits ONE static inspection artifact
  from existing saved imagery/CSVs, not another sampling/tracker/model variant.
  Luna worker drasov_reference_picker creates legacy-normal-chart-inspection/
  with unchanged image, source anchors/normals/extracted peaks/current tracker
  overlays and local feedback. No intended ridge is fabricated. Raster coordinates
  divide by exact24.001238633946606 scale; no extra0.5 phase adjustment. No new
  acquisition/production code/Gradle/private inference. Parent will inspect and
  request user ridge annotation only if genuinely ambiguous.
- Static artifact completed and independently checked: exact scale comes from
  tile-manifest (diagnostics rounds it); chosen-offset→ordinary positions and
  ordinary/named difference are0nativepixels over110–135. FullsourcePNG remains
  unchanged/RGBA; sameROI source-only and layered views supplied, no inferredridge.
  Browser labels/strokes corrected for native-unit magnification. Loopback server
  accepts bounded append-only feedback (default unresolved), no fabricated data.
  Server GETmetadata matcheslocal file; TermuxopenURLexit0. Artifacts live in
  primarybuild/private-drasov-resume-20261105/legacy-normal-chart-inspection/.
- Current points1–4: evaluator/measurement fixes verified; slow new-Legacy solver
  mechanism suspended by user (not a delivered repair); usable realDrasov Legacy
  fix OPEN; CorridorA original remaining scope still PARTIAL, preserved. Ordinary
  Legacy/default unchanged. No newRC/JAR/release or complete-goal claim.
- User requires continuous completion of remaining points1–4, preserving ordinary
  Legacy speed. No new RC/default adoption or Astra authorized. Primary dirty
  `codex/v0.22.0-release`117baaf0 and private/untracked files remain untouched.
- Point1 evaluator findings CLOSED. Point2 core/grouped managed implementation
  reviewed, but practical execution/consumers incomplete. Point3 real Drasov
  improvement OPEN. Point4 endpoint-only A source corrected/reviewed, but actual
  quality/performance, protected-interior and consumers OPEN. Nothing here waives
  original plan scope or permits declaring all four complete.
- Integrated dev worktree `.worktrees/drasov-integrated-verification` now exact
  d1ba6e5d11f4f00c2bcd212d1c23c28438cccab8, pushed to matching `codex/` branch.
  It contains guide d95 ownership fix, A12e+54bf R1, and immutable proof264722eb+
  test-only R1e386. All source batches independently accepted. Full CI42003042605
  now PASS at e386:3253executions/310XML/3125distinct/0f/e/s, both real-JOSM GUI
  summaries PASS. Public artifacts imported durable ci-42003042605, noJAR.
  Prior CI41995586137 exactfdd851c6 PASS3250 remains earlier evidence.
  Local combined337/43 passed atfdd; immutable proof accepted89/6 composed from
  retained five suites+final40 affinity XML. Do not call these one newer full run.
  Added reviewedguide-only164+zeroR1664d14b2 (73/5GREEN), plus19line developer
  contractdoc atd1ba. FullCI42030731061 PASS at exactd1ba:3263executions,
  310XML/3135distinct/0failures/errors/skips; both real-JOSM GUI summaries PASS.
  Five public evidence artifacts retained in durable ci-42030731061; noJAR/release. Helper
  remains pinned exactSOURCE664d14b2 classes, not docdescendant/code rebuild.
- Ordinary Legacy old bodies/behavior unchanged; prior public kernel benchmark
  ratios .9851/1.0013/1.0258 versus RC14 remain bounded evidence, not full workflow
  performance qualification. New experimental code must not enter old fast paths.
- Durable evidence is primary `build/private-drasov-resume-20261105/`; recent
  `/tmp` loss was recovered through surviving commits, not invented evidence.
  CI evidence, source union, A-R1, helper and host proof packets are retained.
- Private witness experiment9a2268be used32M before first BASE seed witness
  completed (row129, noU/geometry). Six cached tiles, zero network, all24 hashes
  unchanged, peak99465920→0. Java0 exported failure receipts, not model success.
  Earlier4fe4 claim failed preparation on incorrect256 tile recipe; factual512
  correction and both consumed claims remain preserved. No automatic retries.
- Immutable301-credit saving is implemented/reviewed but alone does NOT justify
  another private run; old-prefix saving upper bound2.56M, completion unproved.
- Native-guide-only claim009a/legacy-native-guide-only-first-original-20261105-one
  also numerically FAILED32M at first original BASEseed0 row147→148, inside
  actual profile convolution. No completed witness/U/route; other two original
  seeds NOT_REQUESTED. Java0 means sealed failure export succeeded. All24 input
  hashes unchanged, six cached tiles/zero network, peak106294136B→0, total5.612s.
  Independent result audit PASS for evidence only; numerical result remainsFAIL.
  Ruling: the binding affinity amendment retains128M/8M/256MiB ceilings and
  stricter caller limits;32M was a named stricter screen. ONE separate same-guide
  diagnostic at the existing128M default is eligible under standing experiment
  authority, not an increase of the production hard ceiling. New design/helper
  review, fresh fingerprint/claim/controller admission remain required. The A-only
  historical128M ruling is precedent, not blanket permission. No automatic retry.
  Read-only source assessment of immutable measurement reuse and consumer gaps
  proceeds separately. Neither hypothesis is an implemented or accepted repair.
- CLOSED host experiment: Sol/high `a_endpoint_r1_recovery`, durable isolated
  `.worktrees/drasov-exact-host-admission`, basee386. New explicitly experiment-only
  exact-host single-use validation protocol; approved proof/plan ec6298daf... and
  accounting cert855cf46ab... plus `host-admission-proof/implementation-review.md`
  CODE-READY PASS. JOSM19555/Java17 pinned raw-stream theorem, setup652576B→1MiB,
  paid2344745SHA units+reads, six fact bounds and narrow seventh Binding overload.
  Conservative code now frozen01c6861ec7e89e6f01352bf2635117b0b3cae190,
  107affected +separate source/tile neighbors PASS. Independent full core audit
  active onthatcommit (notmutabletree). Author applying only reviewed record-unit
  normalization +public2/15-node measurements before final neighbor/freeze;
  pre-normalization source/receipts preserved. No new private admission.
  Public controlled-fork RED/GREEN, source review and helper review remain BEFORE
  any newly admitted private attempt. Portable production adoption remains OPEN.
  LATEST: hostR1 now4f304d1308f13bc0f8b6d97ce7c05d7d47137427,152tests/11suites
  GREEN and scopedindependentreview PASS/all4ADDRESSED. Correctness experiment
  CLOSED; measuredworkunfavorable, isolated/unadopted, NO hostprivate retry.
- Ruling: only new explicit entry may amend incidental bookkeeping callbacks;
  each numerical boundary retains cancellation and complete post-callback live
  checks, bounded work/memory/latency, no post-refusal query/publication. Old
  public charge/default paths/assertions/counts stay unchanged. No live-source
  cache, invented generation, hidden free work or raised32M/8M/256MiB/512MiB caps.
- Reviewer `drasov_independent_audit` available; immutable worker completed. No
  recursive delegation. Requested routes Sol6.1/high and Luna/high; actual runtime
  attribution/weekly remainder unknown, standard speed. No pool/speed switches.
- After feasibility: actual completed Drasov/Omice geometry+independent controls,
  owned M/N-aware candidate adapters, Precise/Move immutable final preview and
  assignments, post-anchor quality, exact Apply/Undo/Redo/staleness and Hybrid
  occurrence bridge remain required. Source reference assumptions do not become
  original OSM authority. User visual reference acceptance remains a later gate.
- Next: independently close the native-guide-only32M result evidence; distinguish
  its screening cap from the existing128M production hard ceiling before any
  separately reviewed follow-on diagnostic. Source-code measurement-reuse and
  consumer-gap assessments proceed without private reruns. Host R1 is CLOSED
  and unfavorable, so no host adoption/private retry. Actual geometry, protected
  interiors and consumer requirements remain open. Below is historical detail.
- Consumer Slice1 packet1d25bb82 and independent design reviewa3c73a97 PASS:
  issuer-gated paid immutable N/M/arc detachment, current-source authority and
  logical-close revocation before any candidate/UI adoption. Sol worker owns
  isolated `.worktrees/drasov-consumer-detachment` fromd1ba; real public producer
  TDD includes foreign/forgery/stale/cap/ownership failure paths. This advances
  required consumer integration without claiming practical Drasov geometry.
- ONE default128M native-guide diagnostic execution independently admitted via
  f7682d31 helperreview/fingerprintcf31640a, fresh consumedclaim
  legacy-native-guide-only-first-original-default128m-one. Actual first seed
  FEASIBLE, certified costs Q229.03865719413182/C365.89975270770043/
  T427.41865184043155/K34.12665320498529;35,418,004totalwork/1058pairs,
  peak119436200B→0, entry4183ms/total5069ms. All24 hashes unchanged, zero network.
  This is witness feasibility only: no full grouped winner/M/quality/Apply proof.
  Result/pruning-value audit active; compare U against earlier1056.4837149472492
  before admitting any full solve. No automatic further private run.
- Measurement-reuse source assessment found one existing session plus exact-key
  caches; no cross-session duplication demonstrated in the failed guide run.
  Ruling: do not implement an unproven cache optimization. Preserve source checks.

Parallel bounded route: native-guide-only diagnostic design39eda4cc independently
CODE-READY (359wordreview). Luna/high immutable_native_proof implementing in
`.worktrees/drasov-native-guide-only` fromstablee386, NOhostdependency/privateinput.
BASEfirstoriginalseed only, greedyNOT_REQUESTED, distinctscope/coverage, actual
KernelreplayQCTKU/ownedlease/sourcechecks unchanged. Fullmin/defaults unchanged.
This can test the prepared native route that greedy-first32M never reached;
no success/geometry/feasibility promised. Code→review→helper→newONEadmission
gates remain, no automatic private retry.
LATEST code frozen164ccfa82c163d91a616d8d26a09d7d3851c6e31 (5files366+/14-),
clean frome386;72tests/5suites0f/e/s. Independentactualdiff/evidenceaudit active.
Helperworker authorized javac/publiccontrols againstfrozenclasses concurrently,
noGradle/sourcebuild; SOURCE_ACCEPTANCE_PENDING gates stillhardblockprivate
input/claims/run/finalacceptedfingerprint. Ifcodefixclasseschange, updateonly
invalidatedhelpercontrols/hashbeforefreeze. No privateguide-onlyrun yet.
LATEST sourcezeroR1664d14b2 independentlyPASS,73tests/5suites0f/e/s: actual
positive-length3OBSERVEDcost0 retainedU0/progress+certificate; no-positiveleg
stillabsent. Helperactualentry5controls+512recipe+5Pythongates+IOzero GREEN
against664; sourceacceptancePASS relayed forfinalfingerprint/packet/command
freeze. IndependentHELPERreview then separateONEcontrolleradmission still
required; no privateinput/claim/attempt yet.
LATEST helper independentlyPASS fingerprint009a1bfe1d18ed53b07f7e3095417187192d6593231c43851614e76232376d5b,
1416hashes exact; rootcontroller admitted ONE claim
legacy-native-guide-only-first-original-20261105-one/rootgo
ONE_LEGACY_NATIVE_GUIDE_ONLY_FIRST_ORIGINAL_32M. Exactrecordedcommand launched;
receipt exported: numerical32M refusal atrow147, noU/geometry; see activecard.
No greedy comparison/allseed/primarysolve/geometryclaim.
Preserveconsumedclaim, no automaticretry orreinterpretationofoldcappedattempts.
FixednewAPI `RidgeTracker.trackOwnedNativeGuideOnlyFirstOriginalSeed` and
`Kernel.evaluateFirstOriginalOwnedNativeGuide`; nativeGuideOnly Optional sidecar
hasexactoriginal/requestedseed rawbits, FIRST_ORIGINAL_SEED_ONLY, greedy
NOT_REQUESTED, comparison=false, guidedQCTK/steps/realU. Luna focusedtests active.
Sol legacy_witness_recovery preparing DRAFTonly helper in native-guide-only-helper/
usingcorrected512oldlauncher; no privateinput/build/fingerprint/claim/run before
codefreeze+review. This overlaps artifact prep, not private execution/adoption.
Luna publiccontrols now establish first-original-seed guidedcost/steps/U exact
parity witholdcomparison's guidedcomponent onbothmatching(.95amplitude) and
genuinelydiffering(.45) syntheticrasterpaths, unchangedproducerseedorder.
DifferingQCTK greedy≈(11.013186,.64,.774624,.017689), guided≈(8.761317,.64,.96,0).
Thosearetestfixturefacts, NOTprivateimprovement/tunedproductionparameters.
Fullfocused/regressionfreeze/review stillpending. Drafthelperuncompiled/blocked.

Ownership clarification for new host guard: its paid index may retain exact
witness/request/frame identity once and replace only the immutable lifetime
proof that otherwise traverses historical co-owner lists. Every other request
match, selection/parameter/source fact stays checked. Require exact issued
retention lease, original-owner-close/live-retained-owner control, foreign/closed
index refusal and zero final owner; independent source review must verify it.
No old/general owner-list behavior changes are authorized.

Normalized host405dd313f8e38803bee09b333f10f72f29c4a612 frozen146tests/11suites
0f/e/s; independent accounting delta PASS, overall source BLOCKED on4findings:
budget/input monitor inversion, coordinator close/epoch race, projection class
identity, same-owner foreign-witness index retarget. Originalauthor doing R1
onlythose+same-lock callback variant; realREDs established, no privateadmission.
Hostpublicworkremainsworse despite normalization; no performanceclaim/adoption.

Independent alternate bounded diagnostic design/review active: explicit BASE
native-guide-only FIRST original seed, oldlivee386guard, greedyNOT_REQUESTED,
actualproductionreplay/caps/lease preserved, no primarywinner or changeddefault
fullmin(greedy,guide) contract. Existing32Mrun never tested nativeguide because
greedy consumed budget first. Design39eda4cc... durable; NO implementation/run
before review. It tests an untested original proposal, not a new numericalmodel.

Latest host implementation acceptance milestone (not frozen/reviewed):4JUnit
tests GREEN,21 owned-fork cases plus provider/loader/home negative forks and
hash short/truncated/growing/zero-progress/IO-redaction/owner-revocation controls.
Hidden-growth actual+4608 raw credits,16 mutable/cancel/closure/lock-write cases
refused with no NODE_READ, original failure identities/zeroowner preserved.
Baseline tiny-fixture active admission635credits, NOT a private feasibility
forecast. Public185row/301peak old/new+setup composition and affected neighbors
running before freeze. Snapshot carries additive actual validation-policy
identity; old/default value retained. No new private attempt admitted.
Additional RED within approved fence: replaced fake provider named SUN was
accepted after warmup. Use-time bootstrap provider/service/delegate verification
being fixed; name or earlier warmup alone is insufficient. Preserve typed safe
refusal and original failure/zeroowner. Pure public verifier control, no forged
capability or private query. Same implementation worker/branch, no new scope.
Public185/301 composition actual: olde386 admission404/new3564; setup2352018,
activation2355582; oldquery20475/new39435;2336 scalarreads andfullquery/mode
outputs bit-identical, totalpublicattempt4684930. Unfavorable newaccounting:
NO privateattempt/feasibilityclaim. Author finishing actual nested-source-only
mixed-callback-path RED viahost-only dispatch/nonblockingactive ticket, then
necessaryneighbors/freeze. Two fork-launcher apiguardian compile-only classpath
failures were fixture errors (105/107 otherneighbors passed), not model RED.
Independent bounded work-unit-normalization review active: compare old1logical
profile-record iteration versus new8fixed-check credits, allvariableloops paid;
no pricechange/capraise without reviewedoperation definition/newpolicyidentity.
Normalization review now frozen/accepted narrow correction: new explicit
EXACT_HOST_RECORD_ADMISSION_V1 may charge1PROFILE_RECORD_CHECK per actual fixed
profile iteration (all≤128straightline checks/≤2fixedtemps), matchinge386 loop
granularity. Only8→1profileterms; predicted3564→974 forL1N185, NOT404. Keepall
raw64+r/string/member/SHA/setup/index charges andscratch, oldentries/finaltickets
unchanged. Author authorized to preservepre-normalizationpatch/receipts then
apply/test onlythatcorrection; newexportedpolicy required. Also compare onepublic
15collinear-way-node/185profile/301peak case with2fixedends (originaldefault2node
fixtureunchanged), old/new within samecase; no privatecoordinates or forecasts.
Actualresults+fullcodeindependentaudit still precede any privateadmission.

Private reference picker ready in durable `reference-picker/index.html`,21exact
candidateIDs/six existing raw overlays, finalpreviews unavailable. No automatic
selection, local JSON feedback only. Async exactgood-blue-ID question pending;
continue independent implementation, do not infer acceptedreference.

Implementation milestone: supported controlled-fork activation/setup/policy
receipts, cachedidentity, hidden/deleted refs and coordinate/tag/referrer/normal/
projection refusal controls passed before coordinator-close RED. Ruling: add
read-only `TileFetchCoordinator.isClosed()` (eighth narrow source file), consumed
only by new host-specific source check; old generation/epoch methods/default
bodies unchanged. Needed because close leaves generation/epoch equal. Preserve
RED and close/cachehit/sticky failure/zeroowner controls; no coordinator rewrite.

### Historical resume cards — superseded by active execution card

- Hostpacket now CODE-READY independentPASS for latest proof ec6298daf90cdba516c3e6dcbfc89971ea0f2929f7bdfff3e42a97cc5a28c333
  andcert855cf46ab8e0d143479493722e480d2b9b75f623b427e313c1354eb626a2aeb8.
  Explicitsetup652576B→reserve1MiB, paddingaware2344745SHAunits+reads/metadata;
  sixvariablefactAPI/work/storage rows and seventh identity-gatedBindingfile
  accepted. Sourceauthor dispatched IMPLEMENTATION in durable isolated
  .worktrees/drasov-exact-host-admission frome386a5c470a5ac6a01f738ec8f7af69325b4bf36,
  newexplicitexperiment-onlyentry, publiccontrolledforkTDD, no privateattempt.
  Oldpublic/default paths/charge/assertions/counts unchanged; no implicit
  fallback claimingnewpolicy, all source/cancel/failure/owner/finalpublication
  checks retained under reviewed schedule. Evidence exact-host-admission/.
  Currente386publicCI42003042605 running; fddfullCI41995586137 PASS3250 remains
  lastfull result. Realgeometry/consumers/portableadoption remainOPEN.

- Immutableproof source264722eb +test-onlyR1 e386a5c470a5ac6a01f738ec8f7af69325b4bf36
  independently CLOSED PASS. Originaltest/evidenceP2 corrected, final40affinity
  XML includesbothsnapshotstest,0f/e/s; combinedaccepted89tests/6suites from
  fiveunchanged+final40, not claimedonefreshsixsuiterun. Durable evidence within
  .worktrees/drasov-immutable-native-proof/build/private-drasov-resume-20261105/immutable-proof.
  Integratedverification devbranch advancedlocally toexacte386; remote/fullCI
  remainsfdd PASS3250. No privateattemptfrom301savingalone. Hostpacket review
  acceptedexactrawpipeline andSHApricing, still NOTCODE-READY onlyprecisefixed
  setupscratch+sixvariablefactcharge/copy/compare formulas pending; author
  amendingthattableonly, no generalproofrestart. Allprivatequality/consumersOPEN.

- Ruling (new explicit experiment-only protocol, not ordinary production):
  incidental bookkeeping-callback scheduling may be amended only if each
  numerical boundary retains cancellation and complete post-callback current
  validation, bounded latency/memory/work, no post-refusal query/publication,
  and every old path/assertion remains unchanged. No source/safety waiver.
  Independent exact-host addendum conditionally accepts pinned JOSM19555 /
  Java17 bytecode capability as a way to evaluate the numerical hypothesis
  before portable adoption. NOT implementation/run approval: exact bootstrap/
  module/agent fence, nonexpanding pipeline/raw-array estimate bound, same
  post-callback captured iterator, paid setup/traversal and all freshness checks
  must first be proved. Sol worker assembling durable host-admission-proof/
  packet/minimal plan; no source optimization/private run yet. Portable and
  consumer/quality gates remain OPEN. Full integrated CI PASS remains valid.

- Latest integration gate: fullpublicCI41995586137 SUCCESS exactfdd851c6;
  durable ci-41995586137 imported onlypublicevidence/GUIreceipts, noJAR.
  Parsed310XML/3250executions/3122distinct/0failures/errors/skips and bothGUI
  summaries PASS. This supersedes prior running status, not private quality.
  Liveadmission design review CONDITIONAL/notimplementationready: hiddenraw
  backlink/filtertraversal bound and protocolcallbackschedule proof unresolved.
  Exacthost-pinned experiment-only proof being assessed; portableproduction
  remains OPEN and no newprivateattempt admitted. Immutable301proof worker
  active on bounded reviewed TDD task. Core points3/4 quality/consumers OPEN.

- Execution-environment refresh during continuation: prior agents no longer
  exist and recent Termux tmp worktrees/reports/classes (guide2aff/d95, A12e/R1,
  freshCI downloads) are absent. Git objects/commits and this ledger survive.
  Do not silently count absent artifacts as fresh verified evidence. Guide
  d95 and A12e are recoverable commits; A's last R1 had no saved commit, so its
  lost uncommitted fixes/tests must be recreated. Earlier e4ac32M donor/helper
  survives. Recovery workers legacy_witness_recovery and a_endpoint_r1_recovery
  requested Sol6.1/high with no recursive delegation; use durable worktrees
  under repository .worktrees and build/private-drasov-resume-20261105 evidence
  to avoid repeating this loss. Standard speed; weekly/runtime attribution
  telemetry unknown. Free storage now4.5GiB; no additional cleanup needed.
  Primary dirty117baaf remains untouched. No cap/default/release change.
  Recovery milestones: exactd95 source compiles in durable owned-guide tree;
  four helper synthetic modes and inherited atomic/primary controls GREEN,
  metadata/single-use/fences completing; no private read/run. A durableR1 fresh
  RED reproduced actualABA/partialoverlap and finish/fail terminalheader writes;
  twofilefix focused19tests/4suites GREEN, broad ManagedA/NativeA/executor
  neighbors running. Incorrect bowtie positive fixture corrected/disclaimed.
  Source/coreintegration and privatequality remain OPEN. Fresh remoteCI status
  d19 SUCCESS rechecked; artifacts redownloaded durable ci-41940029560 and all
  296XML/3159exec/0f/e/s plus bothGUI PASS independently parsed again.
  Legacyhelper now independently PASS exactfingerprint4fe4e7b110f8750901580ea188368c5f68c3f9bf630ea59ee385c9435571e30d;
  durable preparation/report/manifest/execution-review in legacy-witness.
  Controller admitted ONE claim legacy-owned-base-witness-20261105-one with
  rootgo ONE_LEGACY_BASE_OWNED_WITNESS_32M. Actual run consumed claim and FAILED
  beforecapture/solver: seedCache recipe assumed256x256 but tile dimensions
  exceed it. javaexit1/no timeout; acceptedwork1,652,736/pairs0; all24inputhashes
  unchanged. No numerical cap/result/quality conclusion. Originalworker fixing
  factual six-tile preparation recipe only, new fingerprint/control/review
  required before any new controller admission; oldclaim remains consumed.
  Not full optimization, geometry quality, or completion. Do not retry
  this consumed fingerprint automatically; preserve actual failure/partial data.
  A recoveredR1 frozen54bf59922fa5455162f809c8bfbc864c661296f5 from12e,
  durable .worktrees/drasov-managed-a-r1-recovery clean. Fresh217tests/34suites
  0failures/errors/skips; durable a-r1 logs/XML/commands/fixdiff bd643215.
  Independent scoped review active for adjacentoverlap/terminalcensus fixes.
  Legacy prep correction factual six512x512 PNGs, not256; headers alone read,
  unchangedimages/caps. New recipe/fingerprint/control/review pending.
  A recoveredR1 independent PASS54bf5992 (durable a-r1/source-review.md).
  Integratedsource fdd851c6 in .worktrees/drasov-integrated-verification:
  d95 +12e(8d411046)+54bf(fdd851c6), sourceunion exactreviewedblobs; onlyshared
  file isidenticaltypedM2testrepair. Focused337tests/43suites0f/e/s GREEN.
  Pushedonlydevbranch codex/drasov-integrated-verification; publicCI41995586137
  running exactfdd851c6, no privateuploads or release.
  TileR1 helper independentlyPASS9a2268be90c869201ed2d0741a314c920bd6c2958ec96fc5a679886c35f475cc;
  controller admitted newONE legacy-owned-base-witness-20261105-shape-r1-one.
  Actualjava0 meansreceiptexported, numericalFAIL32M: BASEseed0row129, first
  witnessnotcompleted/Uunavailable. PREP21,354,789 (diag5563), failureactual
  ManagedSelectionWitness.snapshot via Session.fresh/freezeFamily and current
  ManagedOrdinaryAffinity.prepare, notwalltimeout. peak99,465,920→0; all24hashes
  unchanged/6cache/0network. Resultaudit +bounded readonlyfreshnesscost design
  active; no automaticprivate rerun/capraise. Realquality/consumers remainOPEN.
  Result independently audited (legacy-witness/result-review.md): capnext15,
  threeplannedseeds/zero completed, oneattempt/zero certified, no witnessCSV.
  Native immutableproof reuse design/review durable: safe301peakcredits removed
  permanagednativeadmission onlyif185rowcallbacks andbothlive snapshotsremain;
  oldprefixsaving upper2,564,520 beforecertificatecost, completionunproved.
  NO newprivateattempt onthatoptimizationalone. Luna/high requested for bounded
  immutableproofimplementation fromfdd in durable isolatedtree with TDD.
  Sol/high Aworker separately designs cheaper SINGLE-USE complete liveadmission
  withactualAPI/prepaidbounds; no generationinvention/freshnesscache/capraise.
  That strongercontract needsindependentreviewbeforecode. Continueauthorized
  work; no broadplanrewrite/RC/default adoption. Weeklytelemetry unknown.
  Ruling: immutablecertificate may add narrow managed-only package-owned one-time
  binding→actualInput association beforepublication; literalinputidentity is
  required, not waived. Prepaystorage/rejectforeignorrepeatedassociation without
  damagingfirstinput; no publicsetter/registry/sourcecallback, oldsyntheticpaths
  unchanged. Coverconstructorfailure ownership and foreign/repeateduse in tests;
  independentwholecertificate-boundaryreview required. Costifwrong: bounded
  sourcebinding rework, not adoption or weakened authority.

- 2026-11-05 continuation: user explicitly requires finishing all remaining1–4
  without milestone stops. Guide2aff3119 frozen96tests/11XML GREEN, ordinary
  compiled323/4fc fences unchanged; managed_source_audit reviewing actual whole
  delta. Endpoint-A12e96faf frozen512tests/39suites GREEN; kernel_memory_audit
  reviewing full25-file delta. Both are implementations awaiting independent
  acceptance, not real-quality completion. kernel_memory_closure preparing only
  a frozen BASE witness-only32M private helper; execution awaits source/helper
  review. FullCI41940029560 exactd19faedb now SUCCESS, public-validation and both
  PROBABILISTIC/LEGACY_THEN_B GUI jobs SUCCESS. Imported public artifacts only:
  3159executions/296XML/3044distinct/0failures/errors/skips; public summary PASS,
  bothGUI summaries PASS (ordinaryAction, geometryOracle, cancellation/no-late-
  preview, dataset/Undo unchanged and silentSettingsSave all true). NoJAR/private
  upload. Evidence /tmp/drasov-ci-41940029560. Guide review preliminary lifetime
  blocker: caller provider closure after finish may invalidate retained result
  payload. A review preliminary adjacent-overlap hole in final-shape gate.
  Consolidated reports pending; helper paused with no private reads/run/claim.
  Reviews now consolidated: guide oneP1 lifetime transfer, R1 originalworker
  in isolated /tmp/josm-drasov-legacy-owned-guide-r1 from2aff; A twofindings
  adjacent overlap and late terminal-census mutation, R1 originalworker.
  Reports /tmp/drasov-legacy-owned-guide-source-review.md and
  /tmp/drasov-managed-a-endpoint-source-review.md. No unreviewed adoption.
  Read-only consumer map /tmp/drasov-quality-consumer-next-dependencies.md:
  ordinary AlignmentService/Hybrid still use oldtrack APIs; after viability,
  owned M/N-aware candidate adaptation, Precise/Move finalpreview/assignment,
  post-anchor quality, exactApply/Undo and Hybrid occurrence bridge remain.
  This is binding remaining scope, not an optional follow-up or completion.
  Guide fix round1/5 implemented d95f42b6 (isolatedR1 tree, base2aff): explicit
  prepaid lease transfer, four real lifetime RED then109covering tests GREEN,
  old ordinary bytecode fences PASS. Scoped independent re-review CLOSED PASS
  d95f42b6; oneP1 addressed, no new blocker. Helper preparation resumed against
  this exact source/classes; private execution still awaits helper review.
  Reuse existing ledger/reviews; no new RC/default adoption. Real Drasov visual,
  consumer/Apply, protected-interior A and practical performance gates remain.

- User: finish remaining points1–4, preserve fast ordinary Legacy, clean obsolete
  temporaries; continue autonomously. No RC/default adoption or Astra authorized.
  Primary dirty117baaf0 untouched. Controller docs branch lastfbf8b1a9 plus this
  ledger; three original untracked handoff/plan files preserved.
- Point1 original evaluator findings CLOSED. Point2 full grouped kernel/forwarder
  and managed composition CLOSED4fc; guide/actual geometry/consumers remain. Point3 real
  Drasov improvement OPEN. Point4 A synthetic R4 certified; managed A producer/
  consumers OPEN. Consumer/Apply/private visual gates remain; no completion claim.
- Highest full publicCI b27310b0/run41912441658 PASS:3060executions/287XML,
  2950distinct,0f/e/s and bothJOSM19555 GUI PASS. Public artifacts retained only;
  no private uploads/JAR/release. Does not cover later323/M2/census changes.
- Ordinary fastpath323c2874 CLOSED independent PASS: exactRC14 four-arg smoother,
  other65compiledbodies unchanged,136/20tests GREEN. RC14 publicJAR af0fe6f4
  frozen. First minimal benchmark ratios1.47/.86/1.12 were unstable; second
  protocol2f64 ONE INCONCLUSIVE320warmcalls/no measured pairs, fullbits equal.
  NO local retry/no runtime PASS. PUBLIC-ONLY existingBuild benchmark CLOSED
  independentPASS350a1434 afteratomicexportP2;83original/41affected controls,
  unchangedJava/protocol/fixtures reused. Pushedcodex/ordinary-legacy-public-benchmark;
  rootONE manual run41931505171 (workflow_dispatch/head350) SUCCESS. Duplicate
  push-run41931491674 cancellationrequested; never count it asPASS. Fullvalidation
  andGUI run onmanualhead, then optionalbenchmark. Source-only descendant323, exactproduction
  blobguard/pinnedRC14,3literalpublicfixtures, fixed100x32warm/16x64pairs,
  eight-minute bound, readiness/INCONCLUSIVE/no retry. No accepted slowdown
  tolerance; no private parser/data/upload orRC. Reports ordinary-legacy-public-ci-*.
  Actual benchmarkCOMPLETE25344calls/6144measured, all6readiness/fullbits/fences
  pass; pairedmedians corrected/RC14 .9850912922/1.001275117/1.025785343.
  Publickernel no-large-slowdown evidence, NOTacquisition/export/Apply/fullworkflow
  gate. Outputaudit managed_context_closure CLOSED PASS: all696samples/710lines,
  digests/readiness/stats/fences independentlyrecomputed, review9bda9501
  ordinary-legacy-public-ci-result-review.md. FullCI3070/288XML,
  2960distinct0f/e/s andbothGUI PASS; drasov-ci-41931505171-report.md.
  Integratedcomposition4fc pushed; fullCI41934421000 FAILEDjava-build:3158/296XML,
  3043distinct/2fail/0error/0skip, GUI skipped. Both parameterizedM2tests fail
  exactdiagnosticscalarstring atline229; priorsemanticownershipassertions pass,
  actualstringnotlogged soportability is hypothesisnotprovedcause. Isolated
  test-onlyrepair ci_scalar_portability (requestedLuna/medium) preservesproduction
  rules/strictactualPrawcore andexistingassertions; independentreview+newCIrequired.
  Repaird19faedb onefile25+/4- CLOSED SolPASS: typedauthorizedactualPscalar,
  uniqueassociatednumericcore threshold/strictfinitebelow, equality/above negatives,
  alloldsemanticassertions retained.37/37 GREEN. Report/review
  drasov-scalar-portability-{report,review}.md. DetachedlocalHEAD pushed via
  explicitd19:refs/heads/codex/drasov-scalar-portability (initialHEAD refguess
  failedwithoutremotechange); fullCI41940029560 INPROGRESS exactd19.
  Report drasov-ci-41934421000-report.md, evidence retained. Watchsessions
  52584/1175 bothcompleted, new419400watch active (see toolsession/log), logs ordinary-legacy-ci-41931505171-watch.log
  anddrasov-ci-41934421000-watch.log; poll existing, never redispatch.
- M2 actual managed local classifier1802ac1e CLOSED independent PASS after2P1s:
  tri-state uniqueness + prepaid complete callback-free publication ticket.
  Final85/7 GREEN, covering133/12 reused. Native .30/.60 below-core UNKNOWN;
  no private improvement/composition claim. Reports drasov-managed-affinity-m2-*.
- A recoverydb332b ONE original-r4-host-export-recovery-01 completedCERTIFIED,
  M3/J.30259409391738523/y64error.002923m,95.58Mwork/180241744Bpeak→0;
  output auditPASS. Synthetic originalR4 ONLY. Old32MFAIL and interrupted128M
  UNVERIFIED retained. Managed-A next producer dependency map saved, no code yet.
  Managed-A producer contract42462fe6 (915words) reviewed90da6d7b:
  nativefloatmultiscale/FLOAT_L0_Q/ownership sound, PRACTICAL correctionrequired.
  Ruling: endpoint-only capturedauthority must befirst usefulslice, since ordinary
  selections fix endpoints; do not implement unusableunprotected-onlyslice and
  callTask4complete. Actualsampler endpoint roundoff +F metric versus captured
  exactMetric/aliasenclosure isnot equalityproof. affinity_contract_probe owns
  R1 designamendment binding bothmetricusers/legs/Q/emission/finalgeometry,
  preserving native rows/state/C/T/K or specifying narrowreviewedcorrection;
  no arbitraryepsilon/fakebefore-row0barrier. Offgridinterior protection remains
  explicitunsupported/safeintervalfuture; null-bindingA outerfinalticketrequired.
  R1contract293bfa5d1072words/b48e4e90diff independentlyPASS4ad6ed4d642words.
  affinity_contract_probe implementing NEW4fc
  endpoint-only A slice: Pnative/Eexact/R(E)evidence roles, freshactualphysical
  core/support, no GAPmode invention, physicalQ/K/zero/cache/winner corrections
  explicitversioned; nativeproducer/state oracle separatefromphysicaloracle.
  No completeTask4 claim. Useabsolute worktreepaths; root verified original4fc
  unchanged afternativeworktree-createfail/fallback (117baaf wascwd ambiguity).
- Legacy e4ac ONE grouped-q0-d00-32m-001: numericalFAIL31,999,843,next258,
  BASEseed0row12, noM/winner; U1056.4837149472492,188bounds/0deferrals,
  67eval13admit54reject,production20,582,036/tax11,417,807,peak54508560→0.
  Output auditPASS; matched63 production+4.28%. No cap retry/adoption.
- Static bound now independentlyPASS: drasov-legacy-q0-static-prefix-bound{,-review}.md.
  Frozenrow0..29 envelopes/actualseed+carry rules giveD<352,V<=1124;
  C<237/T<355/Q<310/K<151 and conservativegroupedLcap<1054<U1056.4837 for
  ALLearlyprefixfrom<=12/to<=29. Numericzero transitions update slope but included;
  protectedzero adds no extra numeric update. Scope onlythisU/BASEprefix, notlater
  rows/stages/newguide. SKIP newCensus32M/helper andone-edgeScalaradapter for this
  obstruction. Reviewed identity-preserving existingnativeordinary-guide contract
  is ready: fullyowned/current-admission replayed U,
  no fabricatedpeak/carry/state, no ordinaryfastpath hooks/extra source sampling.
- Diagnostic-only census a8ff4301 had2 boundary findings (later-row mutation and
  revokedobserver tax). CensusR1 afb0d8ac from88cdc,117/10 GREEN, fixededbcda9c
  independentlyPASS, reusing CLOSEDM2 ticket/exact observer authority;
  review drasov-legacy-ceiling-census-source-review.md. Staticproof retiresprivate screen; keep/source
  closure is independent from any experimental adoption.
- Composition contract c7f9f546 independentlyPASS. affinity_contract_probe owns
  josm-drasov-managed-grouped-composition: base323 +M2imports9fbd42a8/d8c4f287
  +census4aeec970 +4-line nocharge inventorywrapper88cdc5f13895b8e763493f7dd86e18259d172409.
  Seven guardfiles EXACT1802; compileJavaPASS. ExplicitGroupExecution+
  composedidentity, sameadmissionwitness/Q0U, allstages/edges/rawretention,
  outercopyprep→finalticket→chargefreecompatible/SUCCESSseal; revokeduntouched.
  COMPOSITION NOW FROZEN4fc79c87: correctedcensus importedcfc4c074 treeEXACTafb;
  behavior-onlycfc..4fc9files565+/18-, whole323..4fc28files3139+/42-.
  Report drasov-managed-grouped-composition-implementation-report.md921e2ea6,
  packet drasov-managed-grouped-evidence/frozen-4fc79c87/821c7e6d checksumlist.
  519distinct/47suites verified by combined518PASS+layoutNPE then13/2affected
  GREEN; test-onlynullarrayguard retainedbound, no prodchanges aftercombined.
  Whole83939ae3/behavior9195e681/source28/class1977 manifests saved.
  managed_source_audit finalsource review CLOSED PASS; report
  drasov-managed-grouped-composition-source-review.md. No private run/adoption.
- 88cdc prerequisite independently conditionalPASS exactunion/manualKernelmerge:
  20changedpaths/834unaffected blobs,7guardfiles EXACT1802, Ridge EXACT323,
  inventorywrapperpure null delegation. Report drasov-managed-grouped-prerequisite-integration-review.md;
  Compile-only original scope; latercomposition tests supplement it.
  kernel_memory_audit closedcensusR1; managed_source_audit closedM2/designs and
  reviewscomposition. managed_context_closure closedpublicCIsource/exportR1.
  No new children/delegation recursion. Requested Sol6.1/high; telemetry unknown.
- Storage cleanup journals drasov-temporary-cleanup-20261104{,-pass2,-pass3}.json:
  only generatedJavadoc/HTML/compiler/projectGradlecaches removed under sharedflock;
  source/classes/resources/JAR/XML/logs/privateinputs/proofs/helpers preserved.
  512MiB execution admission/256MiBbuild safety unchanged; caches can regrow.
  AllGradle offline/JDK17/max-workers1/sharedflock. No globaldependencies deleted.
- Next: composition4fc fullCI result; kernel_memory_closure implementing ownedguide
  in NEW4fc-based tree under reviewedcontract, no ordinaryfastpath changes.
  Guide draft compiledfence GREEN: all66existingRidge methods/20lambdagroups
  vs323, all70/23 vs4fc andState fields/descriptors/methods unchanged. Analytic
  feasibleU improvement/R4equality/managedoracle/source-revocation checks green;
  final K-worse fixture/neighbor matrix pending, no sourcefreeze/review/private
  run yet. Portabilityd19 importedbf939a96. No numericalpracticality claim.
  Source/test/review thenONEwitness-only32Mscreen; actualgeometry;
  managedA producer; consumer/Apply/integration/privatequality/fullreview gates.
  Update this card at coherent milestones. Do not
  rerun consumed claims, edit frozen trees/classes, or equate source/tests with
  real geometry/runtime acceptance. Exact commands/reports remain below/tmp.
  StaticproofPASS meansNOcensusscreen. Active guide contract
  drasov-legacy-owned-ordinary-guide-design{,-review}.md (772/644words).
  Preserveordinaryfastbytecode, actualpeakidentity/typedcarry/forcedtargets,
  sameadmission/grouped replay, WHOLEminfeasiblevector vsoldgreedy/equaltieold,
  guidecosts/prepay/owners/terminalfailure; witness-onlyBASEperseed32M screen
  before anyfulloptionalretry. No newsource sampler/A/B/primaryoptimizer.

### Prior execution notes — historical; current card above takes precedence

- M2 R1 FINAL independently CLOSED PASS1802ac1e, report
  drasov-managed-affinity-m2-source-review.md. BothP1s addressed; no new blocker.
  Next composition contract c7f9f546,680words, file
  drasov-managed-grouped-composition-contract.md: explicit chosen GroupedExecution
  plus composed identity, same-managed-admission witnesses/Q0U, fullseed/interval/
  stitch/smoothing and outer final-ticket publication. managed_source_audit
  assigned focused design review; no composition code yet.
- Ordinary fastpath public-only CI proposal6454f7c3 approved for implementation,
  a_native_integration new helper/workflow-only descendant323. Existing Build
  default-off workflow_dispatch option; exact323 production-blob guard; reuse
  validatedJAR/JOSM and publicRC14 SHA. ONLY3literalpublicgenerators, no private
  parser/input/path/upload. Fixed100x32 warmup, late20+20/readiness10%,16x64 paired
  cells, max25344calls/eight-minute timeout, retained allcells/counters/bitchecks,
  INCONCLUSIVE no retry. No accepted slowdown tolerance or default adoption.
  Independent review precedes root push/ONE dispatch; no local timing rerun/RC.

- Steady benchmark2f64 root ONE COMPLETED INCONCLUSIVE:320 warm calls, no measured
  samples, all full bits/fences equal. RC14 readiness medianRatio0.5313246895/
  drift−0.1093457979; corrected323 0.6936229212/−0.1123210416. First public parallel
  fixture stopped, no retry. Ordinary runtime gate remains OPEN, not failed
  geometry or PASS. After two unstable local protocols, route next public-only
  timing assessment to finite unattended GitHub CI; a_native_integration assigned
  small reuse-of-existing-workflow proposal and frozen-result report, no new local
  timing or private upload. Do not weaken readiness or invent slowdown tolerance.
- M2 R1 frozen1802ac1e, clean. Repair2c6ce07e, whole35a1cb62, reportd4693cf3,
  manifestf93a5615. Final85/7 GREEN plus covering133/12 reused; exact staged repair
  independently reconciled, source2P1s ADDRESSED, reviewer final report closure
  after producer append. No unresolved native-below-core cases reclassified.

- CURRENT active closure: ordinary steady benchmark root ONE launched session40504
  at fingerprint2f64ea20f36c9ac7a0320f3a4060f321af39510908633ecc3f0c3a10996bfbe3,
  directory drasov-ordinary-fastpath-steady-benchmark. DO NOT reexecute; poll/inspect
  onceclaims/output. Root inspected fixed10x16 warm/12x32 balanced paired protocol,
  synthetic controls and recomputed exact fingerprint/free559673344B. Readiness
  failure means INCONCLUSIVE/no auto-retry, never a timing PASS. Corrected323 source
  independently CLOSED PASS, report drasov-ordinary-fastpath-source-review.md.
- M2 R1: staged stable12files430+/13- at7d6 base, genuine matrix and final-unpin
  stale-publication REDs saved, covering133/12 GREEN. Final7 suites pending after
  invalid new cap-fixture experiment removed (JOSM deletes its assumed backlinks;
  not counted as RED, original tests preserved). Producer affinity_contract_probe;
  scoped reviewer managed_source_audit reading stable repair in parallel; final
  PASS must await commit/fingerprint/final GREEN. No default/weak-bound relaxation.
- Ceiling-census-only implementation active kernel_memory_closure fromd00 in
  josm-drasov-legacy-ceiling-census.9controls GREEN; actual managed source-check
  ALLOCATION diagnostic-tax control being corrected before freeze. One CURRENT
  seed/stage summary only, no8-stage cap/history truncation; whole-visited totals
  distinct. No scalar preflight/producer query/pruning, private run or helper yet.
- Cleanup2/3 removed regenerated project-local Gradle/compiler caches only,
  preserving global dependencies/classes/source/evidence. Journals
  drasov-temporary-cleanup-20261104-pass{2,3}.json. Sharedlock serializes builds,
  measurements and cleanup;512MiB execution admission remains unchanged.

- Legacy actual32M result audit PASS for evidence, numerical FAIL: Q0 saved no
  evaluated work; matched63 production+771467 (+4.28%). Exact U1056.4837149472492,
  188 bound preparations/0deferrals, witness component/margins unavailable.
  Focused Sol diagnosis/design review CLOSED:
  drasov-legacy-q0-next-obstacle-{diagnosis,design-review}.md. Ruling: implement
  packet stages1–2 FIRST as explicit charged ceiling-census only, before any
  complex scalar adapter. Actual Qcap=sourceSpan/nativePitch, grouped Lcap<=U
  proves only that one-edge scalar preflight cannot defer THAT candidate; never
  drop/native-reject candidate. Record actual stageU vector/L0/Lcap/eligibility;
  build scalar refinement only if evidence justifies. If no eligible candidates,
  next lever is one exact-owned/replayed native ordinary guide, not another cap.
  kernel_memory_closure assigned newd00-based isolated implementation, no private
  run/helper adaptation until source review. Original solver/consumer scope stays.
- Ordinary fastpath323c2874 fromb273 frozen clean,136affected/20XML GREEN,
  exactRC14core324instructions/other65compiledbodies unchanged. Independent
  source review assigned managed_context_closure, no new timing yet.

- Highest full CI now b27310b0, run41912441658 SUCCESS:3060 executions/287XML,
  2950distinct,0failure/error/skip. All7 public stages and bothJOSM19555 GUI
  engines PASS, officialhostd2e2310e...40338e. Downloaded public evidence and GUI
  summaries/receipts only; no JAR/privateupload. Report drasov-ci-41912441658-report.md.
  Regenerable461HTML/CSS/JS files2095703B removed after extraction; XML/JSON/logs
  retained. Does NOT cover M2 or new ordinary fast-path repair.

- Root ONE actual grouped32M run after R1 PASS: e4ac fingerprint,
  claimgrouped-q0-d00-32m-001. Host/Java0 means receipts exported, NOT numerical
  success. Typed kernel work refusal31,999,843, seed0BASErow12, no winner/M/QCTK,
  67 complete evaluations,1 certified witness,0 strict certificate deferrals,
  peak54508560B→0,6diskcache/0network/all24inputs unchanged. Result analysis and
  independent output audit assigned. No automatic retry/cap increase/adoption;
  next numerical decision must explain absent pruning from actual evidence.

- Ordinary fast-path correction assigned a_native_integration in NEW b273-based
  isolated tree: restore exact RC14 four-argument smoothing arithmetic, retain
  current six-argument metered implementation/callers and factual filtered-profile
  metadata. Bytecode diagnosis88db32a4 found2208 no-op checkpoints/null checks
  per185-row ordinary track;35 common bodies/15 lambdas otherwise identical.
  Timing warm drift prevents attributing all47% slowdown to those checks.
  Regression/compiled-body parity and independent review precede new timing;
  no arbitrary numeric slowdown tolerance waived or benchmark auto-retry.
- Grouped32M helper fixR1 e4ac1d6b frozen, exactfixb135e5bc/wholee4683760,
  actual synthetic success/prepayment/3observercap/cleanup controls GREEN,
  Python5 GREEN. Scoped re-review assigned; actual private run still held.

- M2 fix round1/5 at7d6: two P1s confirmed. Ruling: implement the smallest
  managed-only prepaid final-publication split across existing source/witness/
  Context/Input/Legacy receipt boundaries, because moving a charged freshness
  call merely moves the mutation opportunity. All charges/copies/unpins precede
  a complete charge-free freshness scan and publication; preserve old charged
  callers, conservative work prepayment, sticky failure/cleanup. Synthetic-only
  fa8 A publication helper is a precedent, not managed authority. Dependency
  expansion is necessary to close P1 and will receive scoped independent review.

- Ordinary Legacy comparison executed ONCE after cleanup under shared lock:
  RC14 JAR af0fe6f4 versus b273,192 calls, full output bits/inputs/fences equal.
  Three kernel-only median ratios current/RC14:1.470629 parallel,
  0.856908 weak-gap,1.117316 reconstructed archive. NOT runtime preservation PASS;
  real end-to-end still unmeasured. Report drasov-ordinary-legacy-runtime-report.md,
  receipt b383f41d. Read-only diagnosis found actual added default smoothing
  NONE-check/budget-null overhead and fuller suppressed-profile metadata; checking
  callers before a bounded opt-in-only overhead repair. No blind timing retry.
- M2 independent source audit identified unresolved alternative/uniqueness
  tri-state handling and charged operations after final source validation;
  exact findings being frozen. Do not adopt7d6 pending repair/re-review.

- User-authorized cleanup restored experiment capacity: removed only registered
  worktrees' build/tmp/compileJava and compileTestJava incremental caches/stashes,
  generated Javadoc and HTML/CSS/JS test presentations. Source/classes/resources/
  JARs/XML/logs/private captures/proofs/helpers preserved. Logical156042285B,
  actual free441597952→666435584B; journal
  drasov-temporary-cleanup-20261104.json. Legacy runtime hold lifted for ONE
  prepared shared-lock comparison;512MiB guards unchanged.
- M2 frozen7d6b81de from1b383d23, clean10-file delta45b513c8.366/30 tests
  plus41/6 final stable-source tests green,367 unique. Independent substantive
  source review assigned; .30/.60 native below-core UNKNOWN and missing grouped
  composition remain explicit, no real Drasov improvement claim.

- Recovery A db332b94 independently reviewed PASS; root ONE claim
  original-r4-host-export-recovery-01 completed exit0,13.239s host. Final report
  CERTIFIED/observation COMPLETE/gate true; per-track M3, J0.30259409391738523,
  length9.200766993288795, production7.487s/observer4.168s, peak180241744B,
  final owner0. Actual geometry stays near independent y64 ridge. This is the
  original public synthetic R4 case, NOT real Drasov or adoption qualification.
  Detailed result analysis and independent completed-output audit assigned.
  Bulk evidence124406868B retained; free storage now below512MiB admission gate,
  so further private runs/runtime benchmark held, no threshold weakening.
- Exact-union integration b27310b0 independently PASS; branch pushed to origin,
  public CI41912441658 in progress, no private inputs uploaded and no RC.

- Current closure batch: reviewed kernels integrated cleanly at b27310b0 with
  575 focused executions/53XML and Javadoc passing; independent exact-union
  integration audit assigned. Recovery A helper db332b94 and grouped Legacy
  helper98e92c1f frozen with synthetic controls passing; actual runs remain
  held for independent helper review. Legacy helper audit identified missing
  shared-budget charges/prepayment for newly added successful stage/geometry
  observation; repair required before execution. Historical sealed progress
  serialization is distinct. M2 source-owned moved-point and admission-oracle
  controls now green; named regressions/freeze pending. Ordinary Legacy matched
  runtime comparison assigned separately, no runtime parity claim. No RC.

- Legacy runtime preservation (user requirement, 2026-11-04): retain the fast
  ordinary Legacy path. New optional-emission/affinity experiments must not run
  implicitly in ordinary Legacy, introduce hidden A/B passes, or become default
  without measured evidence. Before adoption, compare the previous released
  Legacy and proposed Legacy on identical private inputs/settings/selection,
  detector set, JVM/heap and host, with warm-up and repeated interleaved runs.
  Report median and tail end-to-end latency, inference time, work/allocation and
  output identity or intentional geometry changes separately. Include Drasov and
  representative strong/sparse/long ways; distinguish cold acquisition from warm
  cached computation and diagnostic export. Exact output/work parity alone is
  not runtime evidence. A material repeatable slowdown blocks default adoption;
  investigate or retain the previous fast path instead of spending a larger
  budget, silently falling back, or claiming a quality/runtime tradeoff accepted.
  A numeric regression tolerance has not been approved: publish measured ratios
  rather than inventing one. No current claim of measured runtime parity.

- Controller ledger HEAD updated continuously in v022-legacy-b-release; primary
  dirty user tree stays117baaf0, untouched. No RC/default adoption authorized.
- Highest full-CI head3225cb3b: CI41898159020 PASS,2992executions/281XML,
  2886distinct,0failure/error/skip/malformed; bothJOSM19555GUI engines PASS.
  Public evidence only downloaded, no private upload/JAR. Report
  drasov-ci-41898159020-report.md. Earlier e774 CI41874502271 andee94 alsoPASS.
- New reviewed-followups integration ee94e707 is independently CLOSED after one
  cross-Input progress fix;353combined then182affected tests GREEN. Branch
  codex/drasov-reviewed-followups pushed; full CI41857618076 PASS. Old88
  source/classes and all measured worktrees/claims remain frozen.
- Two originalR4/A screens (88 then435refusalreuse) BOTH hit128M, no geometry.
  Realized reuse counters unavailable after throw; no claimed speedup/zero hits.
  Sol6.1/xhigh found missing pre-mode endpoint-specific scalar-bound stage.
  Packet drasov-a-workcap-strong-diagnosis.md independently approved by
  drasov-a-scalar-refinement-design-review.md with exact native-Q/Result identity,
  finite-bound, same Pending serial/lease and strict-closure guards. Bounded
  opt-in implementation frozenaf233c64 in josm-drasov-a-scalar-refinement
  from435.315broad/18XML then67current/5XML=316unique0f/e/s. Source review active;
  new ONE32M R4 helper preparation assigned, no invocation yet. Same native
  scalar Result/Qbits, Pending/Prefix/serial/lease and old paths retained by tests.
  Source fixround1/5 CLOSED at8d65c891:2addressed/0open foreign/reused census
  poisoning and terminal strong-Input retention,77affectedtests/6XML0f/e/s.
  Native-Q/sameResult/PendingPrefixseriallease proofs CLOSED. Whole-source review
  drasov-a-scalar-refinement-review.md PASS. No repeated broad315matrix.
  Helper final8d/source protocol review CLOSED; root ONE32M R4 invoked snapshot
  ee3e47575545b4d74c532079825de2bb95bdde1d2171d3ece6ef749d36cad528,
  claimscalar-r4-8d65-32m-001. TypedTOTAL_WORK31,999,989,next16;6.130549s
  production,87,312,920Bpeak/0after. Has certified incumbentU0.30259409391738523
  but pendingLB0.23148363422984822,166791queued/2737reached, no finalM/QCTK.
  278evals allADMITTED,4495positive scalar integrals/3395hits,7890reinserts.
  PREPARATION20,899,797 dominates versusMODE7,800,095. Prefix version explosion
  is now the measured blocker. Strong focused exact cost+tie dominance diagnosis
  assigned; no higher cap, blind retry or incumbent-as-preview claim.
  R4 helper ruling: no duplicate unbudgeted trackDetailed/nativeStateDomain
  reference solve. Charge fresh pixels/sourceS/scalaridentity/original17binding
  checks;239states/26098pairs/statehash/strand-3 are labelled HISTORICAL expected
  controls from unchanged native producer and closed binding tests. Fresh native
  inventory/hash UNAVAILABLE after a throw; compare actual returned state data
  only on success. This is explicit new32M experiment protocol, not old128M parity.
  Synthetic original17 profile INPUT generation remains unchanged and separately
  timed outside algorithm budget (no optional solver/reference optimizer).32M
  includes Input/context prep, retained production sampling, optional native
  preprocessing/refinement/closure/finalDP+census/export. Do not claim complete
  capture/runtime accounting from this public synthetic-input experiment.
- Legacy32M P0 completed as typed work refusal,seed0 BASErow11→16. All852local
  windows fell back for NOISE_FLOOR_NONZERO;zero repeats. Do NOT implement P1
  nested sample cache. Exact valid/zero-count shortcut67856654 in isolated
  josm-drasov-local-noise-precheck,base6d6,209focusedtests/13XML plus12rechecks.
  Independent auditor found no blockers (closed report being frozen). Synthetic
  41probes preserve5986samples and settled evidence, skip41doomedextractions;
  MODE153709→136325. Source/helper reviews CLOSED. Root ONE actual32M invocation
  initial-noise-67856654-32m-001 completed as typedTOTAL_WORK31999991,next32,
  seed0BASErow12 vs oldrow11,409pairs,4.382skernel,noM/QCTK.947skips,
  68completed evaluations vs63. Same first63 descriptors (all12words) prove
  production20,905,098→18,014,072(-13.83%); diagnostic10,339,711→10,333,071.
  Not practical geometry improvement. Result drasov-local-noise-precheck-result-analysis.md.
  Integration9832f7df on ee94 clean,317tests26XML PASS; independent merge audit
  CLOSED drasov-noise-integration-review.md. Focused strong Legacy execution
  diagnosis assigned; no cap increase or automatic retry.
- Curved-census source dc1bd5c6 and helper R1 independently CLOSED. Root's ONE
  fad79c35 replacement invocation completed184/184 pairs:7,870,402work,
  41,129,312Bpeak/0after,4.915s,0network,all24hashes unchanged. Fresh21folds
  reproduce saved21; producer-port support all available; positive-run envelope
  has one conservative route refusal. This is a diagnostic index-domain census,
  NOT full future-domain proof or fitting improvement. affinity_contract_probe
  result report drasov-curved-census-execution-result-report.md confirms184/184
  EXACT_POSITIVE_DYADIC auxiliary charts including all21 original-normal folds.
  Only pair5→6 has2/26 positive-run route refusals despite acquired support;
  16producer stations there pass. Actual rounded Mode placement/fullfuturedomain
  remain UNAVAILABLE. Next bounded managed-classifier ownership design assigned;
  no adapter or rerun yet. Ruling A: retain actual metric-line evaluator law with
  captured monotone inverse; never equate native-straight census lines to actual
  query curves. Design/review drasov-managed-affinity-m1-m2-design-{packet,review}.md
  approve M1-only actual numeric Mode/point/support ownership; consumer disabled.
  M1 source frozen775d5217 in josm-drasov-managed-affinity-m1 fromee94,
  300tests/20XML0f/e/s. Actual query-point receipts/Mode numeric and physical
  ownership only; consumer disabled. Original→retained peak-owner graph has
  genuine RED/GREEN regression. Independent implementation review reconciles
  numerical/query/owner contracts, initially NOT PASS: one terminal-cleanup finding
  across Family.project, NativePointBinding.validate and metricAnchoredQuery.
  Fixround1/5 CLOSED1b383d23,1addressed/0open: charged bodies abort immediately
  on late work/cancel failure;38affectedtests/6XML0f/e/s, RED12/9fail. Review
  drasov-managed-affinity-m1-implementation-review.md nowPASS. M1 integration
  ontoe774 e074939f:393tests/33XML0f/e/s, independent merge audit CLOSED PASS
  drasov-m1-integration-review.md. No production consumer or real run.
  M2 bounded observed-only managed classifier assigned new1b-based isolatedtree,
  no supplementary states/globalfuturefamily/threshold/optimizer changes. Typed
  UNKNOWN/nativefallback retained wherever actual support/order/cut proof absent.
  Concrete M2 geometry gap reviewedCLOSED for implementation in
  drasov-managed-affinity-m2-dependency-design-review.md: charged continuous-F
  inverse (not roundedmetricAt), actualnativeendpointbits, per-cell actual-piece
  bounds for rotated usefulness; fixedinner/outer proof-only domains. Tested
  connector must stay WHOLEinsideinner, with strict simple separating cut and
  endpointoverhang. No source/authority/decisionwidth expansion; uncertainmargins
  remainUNKNOWN. Couple minimaldependency+consumer, no unusedadapterframework.
  M2straightrotated diagnostic:289queryslots,only0/288 u±28 refused original
  metricroute; acquiredgeometrytrue/scalarUNQUERIED.2acceptedrawModes havecomplete
  bodies. Specific correction independently reviewed in
  drasov-managed-affinity-m2-tail-relevance-design-review.md: LOCAL fixed-connector
  positive-cut proof may replace blanket allqueryslot-valid gate; never claim
  tailsabsent/globalfamilycomplete. Freezeport-derivedinnerbeforeextraction,
  requirewholeconnectorinsideinner, M1rawcore portownership, simplelongitudinal
  overhangingcut and strictclosedcelldarkness belowMINfour actualownershoulder
  thresholds. Everyusedfootprint/link/corner/connector/cut authorized/supported;
  relevantunknown/equality/escape→UNKNOWN. Straightrotatedpositive mustretain
  refusedtails explicitly. No width/acquisition/authority expansion.
  M2 remains conditional on independently reviewed M1; no user approval needed
  for this authorized experiment follow-on. Roundtrip differences remain factual.
- New coherent A+noise integration e774e364 on9832 is frozen clean,
  406tests/31XML0f/e/s. Independent merge audit CLOSED; pushed development branch
  codex/drasov-a-noise-integration for full publicCI, noRC/release. Old8d source and
  classes remain frozen. Report drasov-a-noise-integration-report.md.
  CI41874502271 at e774e364 PASS; highest verified fullCI. Laterexperiments excluded.
  Strong A prefix-dominance packet drasov-a-prefix-dominance-diagnosis.md and
  independent drasov-a-prefix-dominance-design-review.md CLOSED for bounded
  implementation. Sufficient law requires BOTH cost and extension-stable tie
  preference dominance at identical FULL key, in closure and finalDP. Retired
  obligations also filtered from final proof scans; freshness/replacement leases
  remain mandatory. Original A implementer assigned new isolated e774-based
  tree. No new R4 screen of this law yet.
  Prefix source0cc→fa8f0a09 fixround1/5 CLOSED1addressed/0open finalpublication:
  finalguards after chargedresult/aggregate copy, bindingprepay beforecomplete
  retained+original scans,119affectedtests/8XML0f/e/s. Source/helperfence CLOSED.
  Root ONE strict32M prefix run claimprefix-r4-fa8f-32m-001 snapshot1f70949b:
  typedTOTAL_WORK31,999,991,next16,4.566043s,74,162,312Bpeak/0after,no finalM/QCTK.
  239evalallADMITTED,4372positiveintegrals,1956hits,dominated282/retired187,
  maximumactivekey8;physicalqueue121744/reached1770,logicalactivetotalsUNAVAILABLE.
  LasttrackedLB.6160578>U.3025941 is NOT a finalclosureproof. PREP21,729,300dominant.
  Ruling: strict32M remainsFAIL/immutable. Independentreview
  drasov-a-prefix-default128m-ruling.md supports ONE NEW unchangedfa8 experiment
  at ORIGINALapproved TraceBudgets.defaults128M, withsame8M/256MiB/512MiB/120s.
  No productioncap/source/modelchange, no automaticretry, no performance/quality
  waiver. Newhelperreview+freshfingerprint/claim/rootgo required beforeexecution.
  Default128M helper c8ed7443 independentlyCLOSED; rootONE claim
  prefix-default128m-fa8f-001 endedhostexit1/12.395s duringcontemporaneousENOSPC.
  NOreport.json/java.log0; track0has4312completecanonicalrecords/977645B and
  41629pendingrecords/19021824B withtornlastline. Entry reached result export,
  but finalCERTIFIED/winner/M/QCTK UNKNOWN; NOT a typednumericalcap or pass.
  Oldclaim/partialoutputsimmutable. Storagecleanup onlyrecoverableCIHTML/Javadoc
  plus75projectlocalGradleexecutionHistory caches(602899031B), no source/classes/
  dependencies/XML/logs/privateinputs/evidence deletion.688MBfree; M2boundedchecks
  resumed, actualbenchmarksheld. Newenvironment-recoveryhelper preparation only:
  samefa8/default128M/allothercaps, sourcecore-summaryBEFOREbulk, realemergency
  receiptreserve,>=512MiBpreflight and<=256MiBbulkexport; no truncatedsuccess.
  Requiresindependentprotocol/fence+newexplicitrootgo, no automaticrerun.
- Legacy competitive proof frozen8955baa5 in josm-drasov-legacy-competitive-proof,
  parent678.206affected/18XML then19current/7XML and3vector checks GREEN (overlap,
  not additive). Actual tiny raster2→1evals/92→33extracts,sameQCTK/winner for
  seeds0/.25,maxfullKeylabels2. OnlyKernel/Progress+5newtests. Hard gates one seed,
  2–3OBSERVEDrows,≤4paths,noevents/intervals,Qlower0. Whole source review active;
  no real run or full adoption. Report drasov-legacy-competitive-proof-report.md.
  Independent review CLOSED numerical core, NOT PASS solely inherited678
  budget-only progress guard at new solveObservedSeedProof entry. Ruling: resolve
  required ee94 dependency by integrating proof onto e774 (not copying guard back
  into measured678). Exact-Input prevalidation must precede try/seal; retain
  deterministic foreign same-budget proof-entry RED/GREEN. Fixround1/5 assigned.
  Integration23bf48fb ontoe774 clean, ownership regression underverification.
  Auto-review rejected temporary budget-only guard reenactment as integrity
  weakening; edit NOT applied/no RED command executed. No retry/bypass. Preserve
  current guard; use read-only8955 historical binary synthetic reproduction for
  RED if cheap/compatible, otherwise retained source finding remains evidence.
  Safe historical RED confirmed: expected IllegalArgumentException notthrown,
  frozen8955 1177-class aggregateSHA unchanged before/after. Current guard never
  modified. Integration b587f0a9 CLOSED PASS round1/5,1addressed/0open;
  334tests/31XML plus19focused/7XML0f/e/s,layout63152/65536. Full-stage prerequisite
  now satisfied, original implementer assigned newb587-based all-stage tree.
- Composite reviewed M1+Legacyproof batch8c37c900 frome074 combines DISJOINT
  components without conflict;19finalblob hashes equal closed e074/b587 inputs.
  345tests/30XML0f/e/s and independent combined merge audit CLOSED PASS.
  Pushed codex/drasov-m1-proof-integration for unattendedpublicCI; noRC/release,
  no M2/newdominance/defaultadoption. Report drasov-m1-proof-integration-report.md.
  CI41889814347 at8c37c900 in progress; highest fullCI remains e774e364.
  CI41889814347 FAILED Javadoc malformedHTML literal<=1 inManagedGroundTransform
  (2992executions/281XML/2886distinct/0failure/error/skip; GUI skipped). One-comment
  repair3225cb3b in newcodex/drasov-ci-doc-fix preservesdoclint/settings; targeted
  javadocPASS1m37s. Rootselfauditedexactcommentdiff andpushednewpublicCI branch.
  CI41898159020 at3225 nowfullyverifiedPASS(allstages+bothGUI), closesdocfailure.
  Reports drasov-ci-doc-fix-report.md anddrasov-ci-41898159020-report.md.
- Full-stage Legacy bb26a526 independentlyCLOSED PASS,349affected/33XML plus
  34current/9XML0f/e/s; oldb587 defaultwholeoutput/work/allocation/peakexact,
  1293historicalclassesunchanged. Q0practicalitystillunproved. Typedownedsidecar,
  oldEmissionResult unchanged. Report drasov-legacy-competitive-all-stage-review.md.
- Production groupedforwarder2e8→d00cfd08 fixround1/5 CLOSED1addressed/0open,
  65tests/10XML0f/e/s. Sameactualproductionprep/sourceS thensoleKernelbegin/seal;
  closed+refusingobserverdoesnotpublishlate. Oldtrackerwork/output/peakexact,
  1297frozenclassesunchanged. Review drasov-legacy-grouped-forwarder-review.md PASS.
  ActualDrasovgrouped32M helper PREPARATIONONLY assigned afterspace recovery.
- Active owners: a_native_integration sourcefa8 frozen; affinity_contract_probe
  completes conditional-approved M2; managed_context_closure prepares reviewed
  environment-recovery A128 helper; kernel_memory_closure prepares Legacy32M
  helper. No actualrerun untilnewgates. Full-stage design/review
  drasov-legacy-competitive-all-stage-design{,-review}.md CLOSED conditionally,
  core guard andallstage audits CLOSED; realquality/consumer gates remain. Ruling: exact grouped objective governs, do not
  claim old-bit parity for a prefix-merge exactness correction. Qlower0 allowed
  for minimal correctness slice only; not a Drasov feasibility result.
  Strong design/review drasov-legacy-competitive-strategy-{diagnosis,design-review}.md.
  No user approval needed for authorized follow-on experiments. Other actors idle
  unless explicitly followed up. No Astra; requested Sol6.1/high, focused xhigh
  for focused execution-obstacle consultations; actual routing/weekly usage unknown.

Older milestone details below preserve evidence; this map is the current state.

User instruction: finish remaining steps1–4 autonomously. Preserve root dirty/private
artifacts; no RC/default adoption authorized. No Astra used. Requested workers are
Sol6.1/high; actual runtime model/weekly usage telemetry unavailable. All local
Gradle uses the shared Termux tmp flock; full suites run in GitHub CI.

| Remaining step | Current disposition |
| --- | --- |
| 1: reviewed evaluator defects | CLOSED; source integration additionally reviewed/tested. |
| 2: Legacy solver/integration | Full grouped stages and explicit tracker forwarding independently CLOSED atd00, combined integrationb273 passed575 focused tests/Javadoc and merge audit. Real32M helper observation-accounting repair in progress; runtime comparison and consumers/Apply qualification incomplete. |
| 3: actual fitting improvement | M1 actual managed ownership CLOSED; M2 bounded managed consumer366 regression executions green, final simplicity guard/freeze pending. Drasov/Omice geometry improvement still open. |
| 4: native Corridor A | Exact scalar/prefix execution CLOSED atfa8 and integratedb273. Original synthetic R4 recovery CERTIFIED,95.58M work/M3/y64 error0.002923m, independently reviewed PASS. Real managed-A producer/consumers and quality remain open. |

### Verified integrated evidence

Experimental worktree `/data/data/com.termux/files/usr/tmp/josm-drasov-optional-emission-experiment`,
branch `codex/drasov-optional-emission-experiment`: stable HEAD88d4fa28 (reviewed
native-A original-row binding, affine affinity, source and explicit-policy work).
Remote88d4fa28 has full CI41833501937 SUCCESS. Priora8c098fc CI41826880110
SUCCESS excludes88 binding changes. Clean last
checked. Source snapshot91dee0d8 and kernel transportffeae28f are integrated.
Their independent reports close source, memory, coordinate, source-S, nonlinear
probe, freshness, ownership, endpoint-roundoff and transport findings. Focused
evidence169tests/5suites +159tests/9suites. Full source/kernela71708d3 CI41809033588
SUCCESS:245XML /2704executions /2610distincttests,0f/e/skip/malformed; both
pinnedJOSM19555 GUI engines PASS. Artifacts in `drasov-ci-41809033588/`.
Earlier49d34e37 CI41803224213 SUCCESS2674executions/239XML. No private inputs in CI.

Source reports: `drasov-managed-final-resource-review.md`,
`drasov-kernel-managed-transport-review.md`, and associated closure reports in
`/data/data/com.termux/files/usr/tmp`. Kernel pinfixc6595b79 is independently
closed. Source normalization getter29bc1323 passes11tests; policy forwarding
721a3717 passes165tests and independent `drasov-legacy-policy-review.md`.

### Actual Drasov practical failure; prior exact experiment

Actual per-address tiles are seeded into real ManagedTileCache; six truthful disk
cache receipts, zero network. Native185rows/301peaks exactly reproduced; old185-point
ridge matches captured coordinates to1.068e-8 projected units. Original archive
does not retain full tags/referrers/relations; missing-witness refusal is retained.
Independent `drasov-real-image-fixture-ruling.md` permits ONLY an explicitly
declared reconstructed selection fixture for numerical geometry/work testing,
not original OSM authority, strict original replay or Apply/release qualification.

`drasov-real-production-replay-report.md`: dense/eager and one reviewed local/stop
attempt BOTH reach128Mwork, no M/QCTK/geometry result. Dense:127999933used/9041pairs,
163715904bytespeak. Local:127999998used/1423pairs,70128288bytespeak,19.9s kernel,
zeroafterclose. Local operations:57.46Mmode extraction,35.55Mregion,18.76Mnode reads.
No more policy/cap/parameter retries. Actor `kernel_memory_closure` owns isolated
`josm-drasov-kernel-managed-transport`; the completed EXACT execution
shortcut after independent mathematical consultation: preflight unchanged actual
future slab integrals, reuse source-owned results; reject a zero slab only under
proved positive-shoulder guard. Bare global-minimum zero is NOT a sound shortcut.
Proof: `drasov-zero-scalar-early-refusal-review.md`. Report filename
`drasov-exact-slab-prefight-report.md`. See current continuation below for the
reviewed implementation and capped third-run outcome.

### Affinity correction and native A work — prior repair state

Binding amendment: `../specs/2026-11-04-drasov-experimental-affinity-amendment.md`.
Actor `affinity_contract_probe`, isolated `josm-drasov-affinity-test`: owner35cca6d2
repaired e59a3780 then c0cdc60c; all owner findings CLOSED by independent
`drasov-affinity-owner-review.md`. No raw transferTo reservation leak/global
ledger change. Classifier/revised solver still UNCOMMITTED: original .30/.60
geometry assertions pass, one strand in inference/final, Q31.6980385/19.7705871,
C/T/K0,38.33/38.34Mwork,42.64/42.77MBpeak. Additional affine/cell/direction/pitch
controls pass; exhaustive oracle, UNKNOWN/GAP/boundary/foreign-source controls and
coherent final review remain. Original v2 baseline retained. Affine-slab success
alone cannot qualify curved managed Drasov. Report `drasov-affinity-implementation-report.md`.

Actor `a_native_integration`, `josm-drasov-a-competitive`, frozen394e7e83:
64focused +173regressiontests pass. Generic executor9f557114 previously reviewed.
Actual A fallback-state test follows one supported strand; fewer extractor bands
do NOT prove missing native states. Independent native integration review BLOCKS:
(1) substitute-first-Legacy-peak endpoint owner must become actual opaque A-band/
allowed-state authority; (2) extraction/grouping accounting must cover actual
band/track pair/cubic work and in-phase cancellation. Repair packet
`drasov-a-native-round1-repair-packet.md` is active. Shared RowState remains5fields;
Legacy measured owner is a side table; use named A factory to avoid constructor
ambiguity. `drasov-a-r4-screen-packet.md` additionally requires original17 profiles
versus retained19 crop(18,17)/row+1 binding, not a different easier graph.
Original R4 potential26098 must reconcile before any graph run. Managed-A producer
is still missing: old A uses >=4m spacing plus full Gaussian levels; existing
managed retention is Legacy singleL0/185rows and CANNOT claim original-A parity.

### Reviewed repair rulings

Affinity integration389f31d8 has188tests/13classes plus13affected publication tests
GREEN, but independent review finds P1 final smoothing can cross branches through
raw-owner UNKNOWN, and P2 negative links/partial UNKNOWN evidence are discarded.
Fix round1/5 is authorized; closed owner findings remain closed.
Ruling: newly moved final vertices cannot inherit raw-state fallback authority.
Freshly validate final positions/edges; retain the valid raw consecutive run when
its optional smoothing cannot be proved safe. This is rollback of the permitted
smoothing stage, not another optimization pass or inference change. Preserve
unchanged/raw UNKNOWN fallback semantics and report the smoothing outcome. Retain
bounded typed negative-link and partial-proof provenance; do not fabricate missing
queries as empty measured evidence. Cost if wrong: raw runs may remain unsmoothed;
this is preferable to introducing the proved cross-branch kink after a valid solve.

### Resume discipline / next gates

Current continuation: affinity fix round1 committed a6690efe/c8bb6460,194 tests
across13classes pass; independent P1/P2 re-review CLOSED, no new blocker.
Actor affinity_contract_probe now owns integrated experimental worktree from836b
to merge the reviewed affine classifier with managed v3 and native-A owner APIs;
controller must not write there until handoff. Affine-only qualification remains.
Native-A4fcb7a9d
independently CLOSED (296tests/17XML); integrated185101bf passes180tests/6XML.
Original-A17/retained19 binding implementation now isolated in
`josm-drasov-a-r4-binding` at185101bf; no R4 graph run before binding review.

Exact scalar slab preflight b87d6ad4 independently CLOSED (184tests/8XML), but
the single authorized third real Drasov run still reaches127999992work (next14
refused),4314pairs,112893376Bpeak,18.69s kernel,zero memory after close and
zeroHTTP. No M/QCTK/geometry. Report `drasov-exact-slab-prefight-report.md`.
Do not compare absolute operation totals at different cap-limited progress as
proof of per-query efficiency. Sol6.1/xhigh focused exact-execution consultation
is active (`legacy_exact_execution_design`); justification: three capped failures
require a changed exact strategy, not more parameter/policy retries. No Astra.
No fourth private run or cap increase authorized by this checkpoint; first prove
and review the next execution contract and common-progress measurement.
Reviewed slab integrated836b07ee passes166focusedtests/5XML (native A and evaluator
neighbors included),0failure/error/skip. Log drasov-integrated-exact-slab-test.log.

Follow-on performance ruling: no global-Q bound pruning or same-Key shortcut.
Current grouped floating component accumulation and receipt-owned Keys prevent
the claimed first-certificate saving. Focused Sol6.1/xhigh packet
`drasov-legacy-exact-execution-design.md` selects bounded P0 progress/repeated
local-query census first. Independent design review APPROVES with fixed-capacity
overflow, exact sample-block boundaries, allocation-free per-operation diagnostic
tax, and pre-admitted terminal sealing clarifications. kernel_memory_closure now
implements P0 atb87d6ad4, no cache/model change; code review precedes ONE cache-only
32M local/STOP screen, not another128M retry. Conditional exact sample reuse stays
unimplemented until measured evidence supports it. Default observer-disabled path
must remain exact. Report `drasov-failure-progress-packet.md` defines bounded scope.

Actual curved-source gap: saved185profile metadata has21/184 bit-identical-normal
adjacent pairs,163rotated,21folded full-width ruled charts. Native configured search
is384 original virtual-raster units from legacy sampling shape; nominal25m setting
is not substituted for factual ground units. `drasov-affinity-managed-proof-gap.md`
records the source/hash/formula without claiming new fitting success. A convex-only
port misses the hard subset. kernel_memory_audit prepares one bounded native-grid
fixed-chart/interior-port design packet for independent review; no fabricated
affine managed transform, moved states, fake endpoint owner or new geometry pass.

Consumer readiness inventory `drasov-current-consumer-readiness.md` confirms N/M/K
adapters, actual managed-A configuration, rendered capture, movable junctions,
cleanup, Hybrid and export remain code gates. Original-context authority is a
separate factual evidence requirement; deterministic fixtures can test mechanics
but cannot grant historical authority. Substantial consumers stay behind positive
numerical evidence, as the existing plan requires.

Integration snapshot a8c098fc frozen: exact836b..a8 whole diff and focused
managed API adaptation in `drasov-affinity-managed-integration{,-adaptation}.diff`;
managed_context_closure independently CLOSED spec/quality/no blockers.25XML suites
contain306tests,0failure/error/skip; all25manifestfiles verified, same head clean.
Report `drasov-affinity-managed-integration-review.md` closes integration only.
Public experimental branch pushed a8c098fc; Build CI41826880110 is IN_PROGRESS at
that exact SHA (https://github.com/holubp/josm-wayheatmaptracer/actions/runs/41826880110).
No private artifact uploaded, no release. Earlier CI cannot qualify this new head.
Final verification supersedes that launch status: CI41826880110 SUCCESS at exacta8,
258JUnitXML/2801executions/2706distincttests,0failure/error/skip/malformed; both
pinnedJOSM19555 GUI engines PASS. Public status/summary and official host receipt
verified locally. Report `drasov-ci-41826880110-report.md`; no private inputs/JAR
download/release. Later88d4fa28 remains outside that full-CI evidence.
Native A binding first GREEN reproduces239states/26098potentialpairs and4frozen
hashes; broader validation continues. Ruling: permit a narrow retained-source-only
Input validator for A, preserving every sticky/open/source/witness/geometry check
and default Legacy validation; A owns its own complete native semantic checks.
This removes incidental Legacy-only authority and repeated uncharged scans, not
source protections. Input delta and tampering tests require independent review.
Curved-affinity design packet is drafted, not approved: its proposed new physical
line arclength/inverse certificate is absent. managed_source_audit now gives a
decisive feasibility review (including the alternative unit-typed native-grid
proof receipt), rather than treating conditional API assumptions as implemented.

Next bounded continuation: native-A alias is frozen e1d328b2 in
`josm-drasov-a-r4-binding`;202unique tests plus9historical-entry controls GREEN,
all4R4hashes/239states/26098potential pairs preserved. kernel_memory_audit reviews
whole185101bf..e1d328b2 including the Input validation seam; graph still held.
Legacy P0 counters are implemented but testing/uncommitted in kernel transport;
no private32M run before code review. Curved-proof design review now APPROVES
ONLY its section1 P0 source/chart/authority census. affinity_contract_probe owns
fresh `josm-drasov-curved-chart-census`, branch `codex/drasov-curved-chart-census`,
basea8c098fc. No physical-line adapter, new Modes/states/classifier/solver change.
It must distinguish actual ports, conservative possible centers and UNAVAILABLE
support/generated/final domains, preserve all184/21 accounting, and stop at one
eventual reviewed census. Report `drasov-curved-affinity-design-review.md` is the
task brief; source-grid/private census execution requires a later root packet.
Native-A binding integration88d4fa28 now independently CLOSED by
`drasov-a-r4-integration-review.md`, all15XML/239tests and6imported file identities
verified. kernel_memory_audit prepares a metadata-only public-synthetic R4 helper
and exact source/class/policy fingerprint in `drasov-a-r4-execution/` against88.
Requested ZERO_GUARDED_LOCAL_V1 + EXACT_SLAB_PREFLIGHT_V1; no substitution if class
enum missing. Original e1 classes lack EXACT_SLAB, integrated88 classes supply it.
One future128M/8M/256MiB/512MiB-host graph requires independent helper/run review
and root go. Typed terminal failure has no winner; partial metrics unavailable
unless exposed factually. No R4 graph has run by this checkpoint.
Reviewed88 binding batch pushed for unattended full CI41833501937 at exact88SHA:
https://github.com/holubp/josm-wayheatmaptracer/actions/runs/41833501937.
Current fulla8CI is verified SUCCESS;88run is not yet green. Public source only,
no private uploads/release. Harness host guard corrected before first execution
to120s (20s was an estimate, not the algorithm cap); unchanged128M/8M/256MiB and
512MiBhost remain binding. Fresh helper-host120 diff/snapshot are frozen; source
auditor reviews before the single invocation. Earlier metadata artifacts retained.
R4 harness fix round1/5: two reviewed helper findings OPEN. Charge summary-item
and output hash-chunk observer work in the same budget (memory reservation alone
is not work); stop production timer immediately on return, before serialization.
Original helper author repairs/refreezes metadata only; source/model/graph and
120s host guard unchanged. No execute claim exists; scoped re-review precedes go.
Superseding result: helper fix round1 independently CLOSED, snapshotcb1864e9;
root ran ONE original R4 graph. TypedTOTAL_WORK128000000,next1,253318pair visits,
18.075sproduction,peak128617880B/final0. No M/QCTK/quality result. Host guard did
not fire. ActualTRANSITION2767;128M is totalwork, not transition count. Original
execution-once claim is consumed and immutable. Report drasov-a-r4-execution-result.md.

Independent actual-source diagnosis identifies exact reusable positive optional
REJECTED/UNAVAILABLE receipts remeasured across different canonical prefixes in
A Engine.certify. ADMITTED already caches by arcID; zero physical arcs remain
direction-dependent and excluded. Binding packet drasov-exact-endpoint-profile-reuse-design.md
specifies a named opt-in, unchanged-default exact fix, no full Mode cache and no
claimed hit rate. a_native_integration now owns isolated josm-drasov-a-refusal-reuse,
branchcodex/drasov-a-refusal-reuse,base88d4fa28. TDD/review/new fingerprint precede
any distinct practical run; old claim never reset. Limits/objective/states unchanged.

Legacy progress P0 frozen6586c77a:203broadtests/11XML+final32affected/4XML GREEN.
Independent review holds3bounded metadata/export findings. Fixround1/5 active:
failure-field availability (no invented0/limit+1), honest fresh CSV numeric parity
within existing5e-7 (notEXACT; no comparator weakening), and export IO cannot replace
the primary typed numerical failure. kernel_memory_closure repairs source+ignored
harness; no32M private screen until scopedclosure. Original203broad commandargv is
notretained; exactaffectedcommand and allXML/log/manifests are available; do not
invent unavailable execution evidence. Current row-pair census remains synthetic
tested only; no actual184/21 private invocation has occurred.

Latest verified continuation: Legacy P0R1 independently CLOSED at6d6bd8ec,
manifest713cea59; root ran ONE32Mcache-only diagnostic screen. TypedTOTAL_WORK
31998286,next2304,seed0/3 BASErow11→16,firststagefalse;63completed queries
(13admitted/50rejected),64thunfinished. Tax10613495 included;production21384791.
All852LOCAL invocations used ONE local window then DENSEfallback for
NOISE_FLOOR_NONZERO;0expansions/repeats. Thus proposed P1overlapcache is REJECTED
for this measured prefix. Mode8989266/region5579401/node4709492;kernel4.556s,
peak53176224B/final0,network0,fresh185/301 numericdelta0 underexisting5e-7.
TaskBUILD SUCCESS is harness success only: no M/QCTK/fitting result. Original OSM
authority still unavailable. Receipthashes/analysis: drasov-legacy-p0-result-analysis.md.
Smallest proved next exact optimization counts actual valid/zero samples and
tests Z<=floor(0.2*(V-1)),V>0 to skip doomed LOCAL mode extraction, retaining
SAME denseFallback incl interval-rounding. No positive-global-floor assumption,
policy substitution, cache or new numerical model. kernel_memory_closure owns
new josm-drasov-local-noise-precheck,base6d6,explicitopt-in/TDD; no private rerun.

A refusal-reuse source435466dc independently CLOSED,239tests incl20focused,
no default change. kernel_memory_audit prepares NEW R4 helper/fingerprint/claim in
drasov-a-r4-refusal-reuse-execution/ bound435; old88measurement/claim immutable.
Curved-census sourceR1 dc1bd5c6 independently CLOSED (24affected tests); bounded
identity andinitial-stop fixes+index-only rounding clarification verified. Its
standalone32Mprivate-census helper is under preparation by affinity_contract_probe,
defaultmetadata-only/no capture; no actual184/21 run. Savedfoldreference JSON7883edd1
reproduces21 archived folds and lists distinct21bit-equal-normal pairs; no gridquery.

New integration tree josm-drasov-reviewed-followups,branchcodex/drasov-reviewed-followups,
preserves old88baseline. ReviewedA435→3832851c,census304/dc→9734d610/2d3a18f7;
managed_context_closure mergedP06586/6d→deddcdaf/4e447884 (3actualconflicts).
NamedforLegacyProgress avoidsnull5argambiguity; private7tuple preservesA/measured/
progress ande1freshness.30namedcombinedtest suites running; sourcefrozen4e447884
pendingresults/independentintegrationaudit. No private files imported/new release.
Full88CI41833501937 now VERIFIED SUCCESS:259XML/2810executions/2715distinct,
0failure/error/skip/malformed; bothpinned19555GUI engines PASS andhostreceipt
verified. Evidence/report drasov-ci-41833501937-report.md. Excludeslaterfollowups.
New integration7202ba1a has353tests/30suites GREEN and correct feature routes,
but independent audit holds ONE importedP0 owner issue: progress checks budget
identity rather than exactInput, permitting A's buffer on B with shared budget.
Fixround1 active in newintegration only: transientInput identity clearedonseal/
close; prevalidate foreignprogress beforeTracker/Kernel mayseal it. Oldcorrectly
bound6d32Mmeasurement remainsvalid. No unrelatedmodel reopening.

R4 refusal-reuse source435 and separatehelper BOTH independently CLOSED; root
ran ONE new screen against534262e7snapshot/newclaim. It stillreachedTOTAL_WORK
128000000,next1,253285pairs,19.400sproduction,peak128967912B/final0. NoM/QCTK
or returnedreusecounters (throwingAPI); neither speedup nor zero-hit claim follows.
Both consumedR4claims/artifacts/source88and435 baselines retained. Stop more
large runs/guessing. FocusedSol6.1/xhigh strongerdiagnosis active aftertwofailures:
actual executorenqueueOutgoing has near-zero Q lowerbounds, no discriminating
pre-mode scalar stage/witness; exactnative scalar is computedonlyaftercertify
selection. Minimal on-popscalarrefinement/reinsert +smallfailurecounters packet
is beingproved againstallprefixversions/strictclosure. No new source/model/cap.

Privatecurved-census standalonehelper frozenfingerprintc43ba649,33Java+3Python
controls with0capture/producer/census. sourceauditor reviewsactualcode/scope;
no184/21privateinvocation. NewR4outcomeandSourceFullCI neitherqualifyrealquality.

Use existing actors/reports; one writer per isolated worktree. No recursive agents.
`managed_source_audit` performs source/A/proof reviews; `kernel_memory_audit` closed
pin/transport/owner/policy reviews. Pause writers for review snapshots. Exact
closes remain scoped: consumer N/M/K adapters, final preview/Apply/Undo/Redo/Hybrid,
actual strong/sparse/switchback quality, required real evidence and final independent
whole-task audit remain binding. No cap increase, hidden state pruning, fictitious
source classes, altered geometric expectations or additional refusal counts as
success. Historical receipts below remain evidence, not current status.

## Prior execution evidence — 2026-11-03

Execution resumed by explicit user instruction to finish remaining steps1–4.
Step1 owners: connector_review implements the two independently confirmed native
contract corrections in isolated `josm-drasov-native-contract` at c1327e1;
query_policy adds independent fresh-port regression tests in
`josm-drasov-fresh-port-tests` at the same base. Only connector_review owns local
Gradle until explicit handoff. Both record RED/GREEN, preserve source classes,
caps and numeric predicates; controller integrates and commissions actual-delta
review. Remaining steps are Legacy integration/real Drasov, Corridor A and review;
they are not completed by these bounded evaluator repairs.
CI41604808996 at397bde4 is now verified SUCCESS: public validation plus both
Probabilistic B and Legacy→B GUI jobs. No new RC/default change.
Artifact audit:2616executions/231XMLsuites,2522distinct test IDs,
0failures/errors/skips/malformed; bothGUI summaries PASS. Report
`/data/data/com.termux/files/usr/tmp/drasov-ci-41604808996-report.md`.
Fresh-port tests-only commit f38ee8b is ready; RED/GREEN belongs to the evaluator
worker. Luna now implements the bounded immutable Legacy input-binding slice in
`josm-drasov-legacy-binding` at c1327e1: binding/input/factory/newtest only.
Binding is final at publication, not mutable attachment or aliased close ownership.
Actual sampler adapters, solver and final consumers remain separate unfinished gates.
Meaningful fresh-port RED now established: four positive GAP/censored combinations
fail ENDPOINT_OWNERSHIP with complete scalar/mode evidence; three negatives pass.
Initial missing imports were setup failures, not RED. Ordinary-reference missing
API compilation is also setup only; a compilable refusing seam must reproduce the
behavior before exact transport implementation. No temporary seam may remain.
Auto-review rejected a stale faint-fixture expectation edit as potential guard
weakening. Independent actual fixture review finds coherent native positive ridge
above threshold; approved fresh-port contract permits it without reclassifying GAP.
Resubmit only evidence-grounded structured patch with stronger numerical assertions
and exact-zero negative; no shell/edit bypass. Review receipt
`/data/data/com.termux/files/usr/tmp/drasov-faint-fresh-port-review.md`.
Typed ordinary RowState references are the first API slice; boundary-occurrence
ordinary legs still require exact typed ownership, never a dummy numeric offset.
Production adapter preparation found actual Legacy managed sampling currently maps
nearest ARGB directly, not an already-retained L0 scalar field. Its shared producer
must preserve exact native profiles and scalar semantics; no invented retention,
second acquisition or float-quantized profile substitution is approved.
Independent source review approves a bounded exact-double shared-grid producer
correction, conditional on bitwise profile/peak/tracker parity. Sol managed_adapter
owns new managed-grid worktree at397bde4, only new grid/retained-profile types and
opt-in TileHeatmapSampler/RenderedHeatmapSampler overloads/tests. No overlap with
binding/factory/evaluator/kernel writers. It is not production authority: managed
selection/tile/event attestation and nonlinear whole-connector ground authority
remain integration gates. Packet/review: `drasov-managed-adapter-packet.md` and
`drasov-managed-adapter-review.md` under the task tmp evidence directory.
Local Gradle uses one shared OS lock (manual queue superseded after session57043
completed): every worker wraps Gradle with `flock -x
/data/data/com.termux/files/usr/tmp/josm-drasov-gradle.lock env
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk sh ./gradlew ...`
in its own worktree. Actual builds serialize automatically; no unlocked runs or
duplicate handoff polling. Workers notified and acknowledged.
Binding88e0634 has22focused tests green; independent actual-delta review found
two required follow-ups: terminal refusal must prevent same-permit retry, and
equal-chainage protected events with conflicting exact positions must refuse.
Luna is correcting both without weakening existing guards. Sol legacy_kernel now
implements domain/result/exact solver+RidgeTracker opt-in entry in its own worktree
from c1327e1+88e0634; it must consume binding updates, not recreate that interface.
Evaluator writer additionally owns original-ordinal/context-index adapter and
typed boundary physical-reference follow-on after its first stable batch, with
backward-compatible defaults. Missing protected-boundary transport is not a full
kernel DONE. All whole-goal source-bound Drasov/A/consumer gates stay open.
Managed exact-grid prototype has meaningful numerical RED: compilable float
baseline fails bitwise native scalar parity and per-node work/cancellation checks
(15tests,2failures). This is new-producer prototype RED, not a claim that the old
native Legacy path used floats; it does not.
Binding review now CLOSED at0762b9b:22focusedgreen, bothfindingsfixed, no new
blocker. Integrated source into experimental branch as293bded+f4bf4ff (not pushed
yet); descriptor production eligibility remains false. Managed exact-double source
has initial15testGREEN; expanded suite/private Drasov profile parity pending.
New kernel worker has actual domain/result/recurrence source in
`josm-drasov-legacy-kernel`, old publictracker unchanged. Explicit scalar memory
and native physical endpoint identity now replace geometry-only merging; charged
backpointer comparisons replace copied whole-path tie strings. Compile, oracle,
evaluator ordinal/boundary follow-on and wholekernelreview remain pending.
Luna `query_policy` prepares independent tiny exhaustive kernel oracle in separate
`josm-drasov-legacy-oracle`; tests must call production entry, not a test-only solver.
Firstevaluator987efca review now CLOSED:245tests/15suites0f/e/s. Integrated and
pushed experimental6ba8dc9; CI41670103404 runs full public validation. Managed
be53718+714e555 source review CLOSED for exact numerical producer/work accounting;
integrated/pushed9ca7b53, CI41673834301 in progress. Neither is a public release.
Durable managed19focused XML/log now in `drasov-managed-grid-evidence/final19/`,
testedHEAD2fbe4cd plus uncommitted common-interface files; do not silently label
that source pure714e555. Exact integrated9ca7b53 full CI is the closure evidence.
Kernel independent oracle commits7925354+cdfe483:2/2focusedGREEN, analyticQ,
nonzero firstseedprior coefficient.28 (not an offset requirement), direct/skip tie
and GAP BaseCarry native-origin lex tie. Not fullkernel/Drasov/A qualification.
R4preflight found old17nativeprofiles/peaks differ in identity from19retained
measurementprofiles. Sol ruling `drasov-legacy-r4-binding-gap-review.md` requires
declared-synthetic-only native/measurement crosswalk, exact source/crop/normal/
chainage checks and originalnative17census. Luna owns Binding/Factory/Input until
its scoped correction commits; managed adapter must not concurrently edit them.
Managed adapter independently implements common grid/source-request receipt/ground
proof classes under `drasov-managed-capture-integration-review.md` rulings; no new
production attestation until original request authority, locked selection witness,
wholecurve transform and bounded pitch proofs exist. Actual Legacy/A fitting,
preview/Apply consumers and whole-goal quality remain unfinished.
Evaluatorfollowon5dd5414+9b6ad97 independently CLOSED: original ordinals,
dynamic registration, typed boundary/zero cursors, adjacent-bracket equalchainage
conflict;254tests/15XML0f/e/s. Integratedexperimental f79e1c3d+d0afb5b7.
CI41670103404@6ba8dc9 passed2630executions/233XML,bothGUI. CI41673834301@9ca7b53
also SUCCESS; detailedartifactauditpending. Sourcevirtualscalea68f61a reviewed
CLOSED20focusedXML, integrated6fc0c06 (notyetpushed). Managedprefetchd9708e0 fixed
by9c445dd source review CLOSED6capsule/lease/precharge tests; ground8c53be9 notyet
signedoffforproduction. Managedwriter ownsFactory/Context/managedBinding after
Luna6d8ddaf handoff; kernelwriter now exclusively ownsInput lifetime changes.
R4syntheticbinding6d8ddaf independently CLOSED136focusedtests; report
`drasov-r4-binding-review.md`. Kernel21a3010 has196focused/12XMLgreen,
R4N17retained19M3consumed14,J2.813508865870495 vsordinaryseedbest22.477839065525945,
work86087417/128M,pairs8721/8M,peak65201852bytes=62.2MiB (not65.2MiB).
Its fulltablet suite INTERRUPTED bycontroller; fullchecks goCIexactreviewedsnapshot.
Kernel independent actualwhole-delta review `drasov-legacy-kernel-review.md`
finds6materialgaps. Kernel fix round1 ACTIVE against21a3010: complete caller-bound
familyceilings; finalgeometrycoverage; finalpositiverouteguard; dominated/transient
reservationrelease; immutable native/stage/seed/anchor provenance; result/input
receiptlifetimes. No kernel DONE or private Drasov improvement claim yet.
A all-admitted global graph remains empirically over128M. Docs-only proposed
executionamendment701b7387 (`2026-11-03-drasov-certified-competitive-execution-proposal.md`)
gets bounded independent theorem approval in `drasov-a-certified-execution-review.md`
subject to ordinary-prefix cost certification and Qloss-one lowerbound clarification.
Subsequent independent theorem/clarification review has closed those two normative
conditions. The execution-only addendum is now authorized for bounded implementation
and tiny exhaustive oracles under standing positive-follow-on authority; Legacy's
kernel repair review must close before any R4 A attempt. No practical A closure,
adoption or release is claimed. This is not statepruning,
budgetraise, fabricated fullE/X or permission to return a best-so-far on cap.

Active: bounded original Legacy kernel/stage implementation packet at
`/data/data/com.termux/files/usr/tmp/drasov-legacy-kernel-packet.md` (Sol), existing
regression/fixture inventory (Luna), and verified source integration/CI (controller).
Original kernels/engine fitting, consumers/private Drasov quality and whole-goal
gates remain unfinished. No source-writing kernel assignment until packet closure.

Retained-payload correction c1327e1 is independently reviewed;235tests/14suites pass.
Corrected Legacy-only global dense screen is independently resource-qualified:
133/133proofs, all3witnesses,31022034/128Mwork,35230736bytes peak/final0, no cap stop.
Actual native R4 inventory is17observed states,3seeds,133geometry keys/399potential
seed uses, no carry/targets/zero variants. Seed solves were not executed. Old
4.46MBledger receipt is invalid; corrected source/classes/JOSM fingerprint is
d97ce6f59d8d1664485e5e786831ab72cda32de0206c18984f1e8a3180380d40.
Detailed evidence and correction packet: `2026-11-03-drasov-legacy-first-global-screen.md`.
Continuous local research is deferred; no1200run or full local census occurred.
Public experimental9655509 passesCI41582739824 (2610executions/bothGUI); controller
is integrating the new correction for its own CI. No RC/default change.

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
passes at9655509:2610test executions/231XML suites,0failure/error/skip and both
real-JOSM GUI smoke jobs green. Private fixtures were not executed; receipt
`/data/data/com.termux/files/usr/tmp/drasov-ci-41582739824-report.md` binds the exact
head and official host. The completed follow-on is the read-only100-key replay under
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

First cheap-certificate proposal is frozen at
`/data/data/com.termux/files/usr/tmp/drasov-cheap-certificate-design.md` and is
**REVIEW BLOCKED**: independent audit rejects full-census implementation because
the fixed1/8contrast and2p-core bounds analytically refuse required positive
broad/low-contrast controls. Same-corridor thin recordings versus separate parallel
paths also need an explicit ownership contract. Review:
`/data/data/com.termux/files/usr/tmp/drasov-cheap-certificate-design-review.md`.
Do not build a helper merely to rediscover these known failures. The focused
corrective Sol design pass is saved separately as
`/data/data/com.termux/files/usr/tmp/drasov-cheap-certificate-design-v2.md`, including
any actual objective/ownership conflict rather than silently narrowing scope.

Scope reconciliation is now closed against the actual approved November2 specs:
new grouped dark-gap representatives are outside this optional-emission experiment;
making them mandatory positives would broaden it. Existing native-parent successes
remain whole-goal preservation gates. REVIEW cannot replace unique connector proof.
Frozen corrected V2 SHA256cce2cd673347a397f8336608203bb5c4c087333940259888cc3de109a028dc4d
removes arbitrary amplitude/core-width refusals while retaining raw support/Q and
explicit numerical-policy change. Independent scope ruling is appended to the
design-review report. No new user approval or B/grouped-objective redesign needed.

**Active implementation:** bounded ignored Sol helper under
`/data/data/com.termux/files/usr/tmp/drasov-continuous-certificate-screen/`, governed
by `2026-11-03-drasov-continuous-certificate-feasibility.md`: API audit and analytic
RED/GREEN, then charged representative cost prefix only. Full census is gated on
independent actual-helper review and plausible80M admission/48Msolver headroom.
No production source/Gradle/default/release changes in this helper assignment.

Current refinement: continuous primitives pass external analytic/grid controls;
all-mode/unresolved evidence is required, blanket second-peak refusal was rejected.
Local3p evidence cannot claim unchanged full25m uniqueness. Independent review
allows a pure feature/Q **component-cost** prefix with no ADMITTED arcs while the
specific section4 proof-domain amendment remains open. Luna's separate prefix
runner selected1200Akeys across133groups plus3separate Legacy witnesses; no offset
substitution across colliding IDs. One-key API smoke then root gate precedes that
screen. All arc statuses remain provisional; no global proof/engine improvement
is claimed. Details and ownership in the active feasibility plan.

**Priority update: Legacy first.** Independent actual-spec audit establishes that
A's failed admission screen need not block the approved Legacy-before-A sequence.
Active work is `2026-11-03-drasov-legacy-first-global-screen.md`: Luna inventories
actual native Legacy suppression/support/seeds/families/zero variants before one
complete original DENSE certificate screen.17fixture rows/133row-pair groups are
known;17Legacy states/133arc keys are not assumed. No original family pruning or
new numerical rule is allowed. The synthetic screen cannot certify Drasov or the
future production census adapter (retained source includes padding).

Continuous research is deferred at seven-source plateau-guard snapshot
f54c6eac10702083ee759e9bb30d40d8c19bea6da534e3bc96ccb410762ee4b4.
Standalone controls pass; two-key smoke has unresolved R4 arithmetic and no
connector proof, and earlier cost figures had identified accounting gaps. Source
snapshots68c8/822d/f54c are archived/verified. No1200run/fullcensus/admission/product
change occurred. Do not resume local research ahead of the bounded Legacy inventory
without a deliberate recorded priority decision. Both original engines remain open.

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

Current production milestone: production source `cef5556c` (documentation HEAD
`2d70a549` before this update); LSEC1/LSEC2 reviews are
closed after fix-review SHA
`b0461f16223a96f50661e5da3085718296aca1186931755f48dbd5a36616c294`. The
corrected LSEC1 test count is 78 tests, not 79; LSEC2 retains its
additional allocation. Final combined private regression is 20 passed, 0 failed,
0 skipped in 4m32s; XML is archived under root integration `private-final-xml`.
The reusable public Legacy normal-policy harness/gate is recorded in benchmark
commit `1fa751` under R1 gate checks, result repeatability, geometry controls,
and partial-failure evidence. Current CI and matched-speed status is recorded
below; filters are deferred and no public release is authorized. No further benchmark-task design is
authorized by this checkpoint.

R1 performance-evidence correction is committed as `9a46ba4f` (result hashes
must be valid and stable per scene/source/policy across all 20 pairs and three
forks; geometry/support controls and failed-fork receipts are enforced). One
public quality preflight completed with 16 discovery rows and no performance
decision. Evidence:
`build/reports/legacy-normal-quality-preflight-20261106/summary.json`
SHA-256 `8e00d4ca872d3610d0851a1e9e2e232f6aaf520469660d70cb5e01740104974a`;
`observations.csv` SHA-256
`75fbcf66d29bd6203d49b3e55e12d8460d2522ce89ec9e6ba50be35efe2b39d5`;
harness class SHA-256
`f22792670c659562e8a4491dfa9f6d046f052fd1cbe627bd87919892e8939132`.
Full public CI `42143312418` completed 2,454 Java tests with one failure:
`OrdinaryModernAttemptAssemblyTest.managedSequentialHybridUsesAcquiredNativePixelsAndKeepsReplaySource`.
Its reservation-boundary helper still cancels by the second checkpoint and
omits explicit normal-policy working bytes, so it cancels during authority
validation rather than testing the native sampling reservation. The scoped
test-only correction must use the paid native reservation as its phase boundary;
production changes and test weakening are outside this repair. GUI validation
was skipped because Java validation failed. Evidence is under root integration
`ci-public-42143312418`.

Matched public speed is PASS: three forks, eight scene/source groups, 20 pairs
per fork/group, zero breaches. Maximum median overhead is 0.431 ms and maximum
p95 overhead is 7.783 ms. The authoritative locally rechecked gate is root
integration `ci-performance-42143312418/local-rechecked-gate.json` (PASS). The
existing private regression remains 20 passed; it was not rerun for this repair.

The full controller Python scripts suite passed 222 tests plus nine subtests in
195.28 s, exit 0; no Python source changed during the run. Evidence is root
integration `python-full.log`.

The scoped test-only reservation-boundary correction reproduced that exact
failure locally, then passed the exact failing method: one test, zero failures,
errors or skips. It independently includes explicit policy scratch, cancels only
when the native reservation is paid, checks both phase flags/peak charges, and
requires zero leaked ownership before cleanup. Production source is unchanged.
Next: rerun CI on the frozen correction, including the previously skipped GUI gates.
Filters remain deferred. No public release has occurred or is authorized here.
