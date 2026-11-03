# Native admission resolution experiments

> **For agentic workers:** Use superpowers:subagent-driven-development for bounded isolated implementation and superpowers:executing-plans for controller integration. This is a subplan of the approved Drasov plan, not a replacement.

**Goal:** Find a measured, correct admission implementation that can evaluate the actual R4 graph within existing limits before implementing its inference consumers.

**Architecture:** Retain the dense transverse-query baseline. Measure an exact necessary endpoint gate and two explicitly versioned lateral-resolution alternatives. All use the existing extractor, continuous scalar proof, conservative region authority and full original state graph. Query-policy changes are numerical experiments, never labelled exact optimizations.

**Tech Stack:** Java17/JUnit5, existing retained scalar/context, ignored standalone benchmark and public CI.

**Spec:** Approved optional-emission amendment and native-integral-v2 under `docs/superpowers/specs/`; existing declared-resolution scope review remains binding. Standing user authorization covers related experiments/plans; adoption/release gates remain in the original Drasov plan.

## Design and measured reason

Reviewed native-connector4704668 passed123focused tests. Complete actual R4 graph
has239states/26098pairs; dense admission stopped at128Mwork after550receipts
(184admitted),8.423s,10.9MiBpeak tracked memory. Extraction used72.08Munits,
node reads22.24M, regions22.12M. This is negative admission feasibility, not a
solver result. Reference native tracking is outside that measurement's accounting.

Considered approaches: exact necessary-owner rejection is low-risk but cannot
reduce work of already-admitted arcs; exact whole-profile authority/cell invariants
can avoid repeated work but do not alone remove the dominant extraction cost.
The implementation-added p/8 transverse clamp is therefore also tested explicitly
at lower declared resolution. v2's p/2 ALONG-connector probe rule remains intact;
it does not prescribe the transverse step. No claim that lateral policies agree.
The captured d is the REQUESTED step; the existing producer's interleaved L0 step
is max(d,r)/2, which is0.5m on R4. Neither proposed formula claims general producer
equivalence. Controller chose these bounded experiments under standing authority;
they are not adopted tracing policies.

## Global Constraints

- Same128Mwork/8Mpair/256MiB limits per independent attempt, original239states and26098potential pairs; no beams, narrowing, cap raises or catch-and-continue.
- Keep source regions, masks, native resolution, original source classes, state owners, scalar minima/thresholds and mandatory events authoritative.
- Along-connector spacing remains at most half minimum native pitch plus native grid events.
- Current dense API/default and all live engine defaults remain intact. New policies are internal experimental constructor arguments with explicit receipt identity.
- No engine/kernel/UI/transaction/release change. No private captures uploaded.

## Review Focus

1. Phase-shifted thin ridges and split/parallel modes must not gain unsupported connectors through coarser lateral samples.
2. Original faint synthetic fallback remains GAP; a fresh port does not fabricate original ownership.
3. Invalid/corner cells, authority gaps and ambiguous flanks remain unavailable.
4. Terminal work/memory/cancellation cannot reset across sessions.
5. A partial graph or fewer refusals is not solver or route-quality success.

### Task1: Bound the benefit of the exact owner predicate

**Files:** ignored benchmark helper only; report in existing ledger.
**Interface:** actual native R4 state inventory and retained context; calculate the existing endpoint Euclidean necessary condition with actual row-normal metric pitch and source-pitch uncertainty.

- [x] Count states/pairs surviving the necessary condition across the entire unchanged graph, without mode extraction or inference. GAP/censored ports stay unknown, not falsely observed/blocked.
- [x] Record counts, input/state hashes and actual measured/proxy work boundaries. Do not claim the necessary predicate proves admission.
- [x] Use this census to decide whether an early-owner implementation alone is worth a full capped comparison; preserve typed early-refusal provenance if implemented.

Task1 evidence:239states/26098pairs asserted unchanged;233eligible/6ineligible,
0unknown states;1173pairs excluded,24925remaining (95.505%). At equal pair cost
only1.047x benefit; actual per-pair skew unmeasured. Controller does not implement
this small prefilter first because it cannot plausibly explain the48x work gap
by pair count. Java17 helper/source-only census, no field extraction or state
pruning; ignored artifacts `20261103-owner-census/` and Termux report
`drasov-owner-census-report.md`. Task2 now delegated to isolated Luna/medium.

### Task2: Explicit query-resolution policies and retained controls

**Files:** `service/emission/NativeConnectorEvaluator.java`, `service/NativeConnectorEvaluatorTest.java` only.
**Interface:** add enum `QueryPolicy` and constructor `(SampledEmissionInput, Domain, QueryPolicy)`; existing two-argument constructor selects `DENSE_EIGHTH_NATIVE_V1`.

Let d be captured requested lateral step, p the minimum factual native pitch and r the factual output-raster pitch. Policies:
`DENSE_EIGHTH_NATIVE_V1=min(d,p/8)` (exact existing numerical path);
`HALF_NATIVE_L0_V1=min(d,p/2,r)`;
`PIXEL_CAPPED_L0_V1=min(d,p,r)` (experimental requested/native/raster-pixel cap,
not the producer's effective L0 step).
Record policy plus actual samples in immutable receipts; version identity must distinguish them. Do not change longitudinal probes, mode parameters or thresholds.

- [x] Add assertion-level RED for explicit policy identity/step and unchanged dense sample geometry; implement the minimal constructor/step switch.
- [x] Parameterize genuine R4 A/Legacy witness, no-ridge, scalar dip, mask/corner, region gap, fresh GAP, broad/faint, fork and zero-transport controls over policies with independently fixed expectations. Add phase-shifted thin ridge controls; retain originals and record any changed mode/flank/censor outcomes.
- [x] Run Java17 offline focused NativeConnectorEvaluatorTest plus integral/context/extractor/region neighbors. No weakened assertions or brighter fixtures to force agreement.
- [x] Independently review numerical scope and implementation. A failing variant stays experimental/refused; do not automatically choose the fastest policy.

Task2 complete at7e6c64f (24be56d+a170478+7e6c64f), isolated query worktree.
Assertion-level policy-version RED preceded implementation. Final focused125tests
and controller-expanded185tests/14XML (including region, budget and scalar-grid
neighbors) pass with0failures/errors/skips; Java17 offline Gradle48s. Independent
Sol/high actual-source review and focused closure find no blocking defect. The
unchanged masked-ridge fixture explicitly admits dense measured support and
rejects coarser NO_COMPLETE_MODE: numerical policies are not equivalent. Original
faint Gaussian/ownership evidence is preserved, not brightened/deleted. No engine
default or adoption changed. Source/class/JOSM fingerprint
`4e6ca424eb2478b90fa9795095daab3fd6fbf61706f746bd1d020464b32a8f06`
is frozen after final build/review. Task3 three-policy capped runner launched;
results remain pending. Comparer now rejects missing/mixed/different input hashes
and retains unmatched/unmeasured distinctions; its self-test passes. Java17 runner
compile was verified before launch, with actual track and refusal-count scopes fixed.

### Task3: Same-input capped graph comparisons

**Files:** ignored versioned benchmark runners/receipts; durable ledger/spec updates only.

- [x] For each policy, run a fresh independent attempt with identical scalar/state/chainage identities, complete graph and same caps. Record each ordered row/state pair's outcome/reason and work deltas, admitted-set changes, elapsed/peak memory and terminal refusal. Keep all old receipts immutable.
- [x] Public synthetic CPU runs may move to CI; no private upload. Compare new policies against a freshly instrumented dense baseline rather than inventing missing per-pair results from the old aggregate receipt.
- [x] A stopped baseline's remaining pairs are UNMEASURED, never rejected. Compare admission outcomes only on completed common pairs; report unmatched pairs separately. Independently assess new/coarse-only admissions from frozen image evidence before adoption, including mode/flank/censor/split cases.
- [x] Only after a correct policy/exact optimization passes controls AND complete admission feasibility, freeze the next exact-kernel packet. Otherwise record the new limiting evidence and select the next bounded exact optimization. Original Drasov/Omice, engine consumers, topology, replay and release gates remain open.

Task3 COMPLETE with NEGATIVE feasibility/no adoption (counts and timed repeat
below). Independent follow-up examined all45distinct coarse-only admission keys,
48policy-specific admissions: targeted135evaluations,2643probe rows,2508charged
slabs, identical frozen input hashes and prior outcomes. All881Pixel admitted
probes and27Half admitted probes have unique compatible mode, valid measured
flank/core brackets and no nearby censored/suppressed competitor; exact slab
margins >=.10542Pixel/.01427Half. Dense159/836slabs fail the threshold; Pixel0.
All backgrounds0; Pixel origin peak median.78886/threshold.56798 versus Dense
.98272/.70756. Thus coarse phase lowers the numerical envelope, not a scalar
integral/ledger contradiction. This is materially different evidence/quality,
not dense equivalence. Unmeasured dense comparisons remain unknown; none adopted.
Target report/CSV hashes retained in Termux
`drasov-query-admission-adjudication/results-45-v2/`. Once-per-policy normalcaps,
no stops, final memory0; extra slab-export6672work/policy itemized separately.
No full-kernel packet is opened on these infeasible policies. Task4 tested the
next exact opportunity and found no useful geometry-key reuse; numerical redesign
must be separately reviewed, not disguised as a cache or cap relaxation.

Review: focused independent Sol6high plan review permits execution after the two
clarifications above (producer naming and unmeasured baseline evidence). No new
user approval gate. Review receipt remains in controller Termux tmp as
`drasov-query-resolution-plan-review.md`.

### Task4: Measure exact profile-query reuse before a cache

**Reason:** Task3 measured550/1621/7749completed pairs; every policy stopped at
128M. Pixel needs at least70% lower mean pair work under an equal-cost remaining
mix. Mode extraction53%, region18%, node reads18%; no single local category
elimination plausibly closes the gap. Sol/high recommends a read-only reuse
census before any cache. Full-graph feasibility gate is retained; a lazy kernel
could mathematically skip zero-forward-mass states, but no such proof/count exists.

**Files:** External ignored helper only under Termux
`drasov-profile-key-census/`; existing production/test sources read-only.
**Interfaces:** Same R4 actual native track,239states/26098pairs and retained
context as Task3. Use the evaluator's actual private `point`, `interpolate` and
`probeParameters` through isolated reflection rather than a different schedule.
For each nonzero connector, form its actual metric heading normal and actual
local source chainage/effective native pitch. Include policy step/width and fixed
frame/grid/region/parameter identities in immutable key provenance. Source chainage
is conservatively included even where equal effective pitch could permit reuse.

- [x] Before full execution, compile Java17 and compare the emitted witness
  schedule/centre/normal/pitch keys against actual retained evaluator Probe rows;
  bitwise mismatches fail rather than rounding or relaxing the comparison.
- [x] Enumerate all structurally eligible pair schedules without mode extraction,
  integrals, inference or admission. Count zero-length arcs separately as UNKNOWN
  because their normal requires actual incoming transport. No invented direction.
- [x] Stream exact keys to bounded disk-backed counting/sorting; use `-Xmx512m`
  and avoid an unbounded in-memory HashSet. Preserve source/helper/result hashes,
  input identities, pair/query counts, uniqueness and reuse fanout per policy.
- [x] Report count-based opportunity only. A whole Probe cannot be reused because
  its t and receipt ownership are pair-specific; any later cache would share only
  immutable scalar/extraction payload, charge residency and preserve cancellation,
  input lifetime, all states and exact arithmetic. Equal query counts do not imply
  equal work savings. Quantify memory assumptions rather than claiming feasibility.
- [x] Integrate the census ruling into this plan/main ledger. If reuse is too small,
  do not build a cache on speculation. Obtain a concrete exact reachability/lazy
  kernel proof before amending the current all-graph prerequisite; no beam or
  heuristic pruning. Standing user authorization covers this bounded experiment.

Task3 timed repeat preserves byte-identical per-pair CSVs for all policies. Dense
8.904s/half8.505s/pixel10.616s at their respective typed caps, final closed tracked
memory0 each; source/class fingerprint unchanged. These are capped admission
times, not full-graph or whole-engine speedups. Artifacts preserve both runs.
Full public CI41560731847 is running at pushed experimental f7f11d4; no RC/default
change. Task3 new-admission numerical adjudication remains pending.

Task4 COMPLETE (negative opportunity): actual reflected witness self-check9/9
bitwise identical; all26098pairs scheduled per policy,334958queries each,
334958unique conservative keys,0repeats, maximum fanout1, zero zero-length arcs.
No geometry-key cache is implemented. Helpers performed no extraction/integral/
admission; preparation3.516Munits and peak tracked1.78MB are NOT total census
process/disk memory. Pixel sample visits17.083M before extraction show why the
same full-window proof is costly. Preserving all query payloads is not feasible:
even the transient reservation scale would be1.675GB for Pixel, not a measured
heap requirement. The stricter key includes source chainage; this does not prove
absence of all possible content-based or mathematically factored reuse.

Report `drasov-profile-key-census/results-7e6c64f-r2/census-report.txt`, SHA256
`0112dfb9817b29509855a295580b356dfb4cafe96a1e1b40397c42cf08424def`;
helper a12b7d013108d64d6c9f5fb4b4123b333845b7038a190a1494c71c734c72dec0.
The helper's first full attempt exposed a row-index mapping error before a result;
fixed and rerun to a fresh output. No production source or Gradle change.
Generated key tables compressed from310MB to36MB; decompressed SHA256s match
each report's grouped-key hash, with a compression manifest. Only those newly
generated originals were removed after verified lossless compression.

Ruling: do not pursue binary reachability pruning on R4 — independent source
review confirms ordinary native adjacency reaches ALL239states from the complete
native start set, including unsupported-field loss1 edges. Optional-admission-only
reachability would incorrectly remove native paths. Thus it saves zero optional
pair keys here and does not amend the full-graph gate. No lazy-kernel experiment
is launched. A bounded NEW local numerical certificate design is being assessed
by Sol/high; it cannot claim equivalence to the full-window quantile/extractor.
Any such policy requires explicit local noise/window/halo/competitor semantics,
unknown-edge expansion/fallback, adversarial controls and full capped feasibility
before adoption. Original engines/defaults remain untouched.
