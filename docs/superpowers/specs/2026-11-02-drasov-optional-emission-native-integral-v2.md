# Optional emission: native integral v2 experiment packet

Status: independently reviewed and ready for bounded implementation and measurement;
Sol reviewed revision e559bd39 and its scoped fixes with no blocking inconsistency.
This does not establish solver feasibility, usable geometry or release readiness.
Standing user authorization covers this experiment and useful follow-on design; this
document creates no additional user approval stop, default adoption or release authority.
Parent: `docs/superpowers/specs/2026-11-02-drasov-optional-emission-amendment.md`.
Policy identity: `optional-emission-native-integral-v2`.

Reviewed A execution addendum: `2026-11-03-drasov-certified-competitive-execution-proposal.md`
supersedes only the upfront all-admitted graph construction prerequisite in §§4/6/8
for its explicit, fully certified experimental executor. The numerical model, complete
native domain, admission predicate, ties, limits and final quality gates remain unchanged.
Implementation/tiny-oracle work is authorized; no A feasibility or successful replay claim
is allowed without its complete closure certificate and remaining acceptance gates.
Existing eager execution and the separate Legacy pipeline retain their original contracts.

## 1. Superseded accounting and actual screens

The previous worst-finite-native-unary consumption debit is SUPERSEDED. The actual
production A allowed states make the known good R4 witness cost 38.2733302032 versus
15.9896833263 for the bad all-adjacent output. Conservative refusal is not a repair.

The replacement objective below was screened on actual production states, not ideal
continuous offsets. Native A state unaries independently reconstruct its native
objective exactly: 3.9059605531366857. No coefficients were searched.

| v2 objective | Actual bad all-adjacent path | Native-state witness rows 0,1,15,16 |
| --- | ---: | ---: |
| A | 12.95460288803066 | 0.4245487604002805 |
| Legacy | 22.078043695788214 | 2.574143056465672 |

Witness maximum error from the independent y=64 image ridge is 0.000522020m for A,
0.071173321m for Legacy. Both have zero backward image-x movement; their largest
consumed source-chainage span is 19.394447762102203m. This establishes one usable
witness beating the existing bad route, not the future DP optimum, admission success,
preservation success or Drasov improvement. See `receipt.txt`,
`revised-accounting-screen.txt`, `a-state-costs.csv`, and `class-identities.json`.

Actual Java17 `RidgeTracker.reliableAnchors` on R4 returned zero entries: index set
`[]`. This was measured in the same screen, not inferred from nominal peak width.

## 2. Immutable factual input and production entry points

Implement under `service/emission/`:

* `OptionalEmissionContext`: immutable L0 scalar source and lineage; explicit
  profile-coordinate-to-L0 scale; factual native resolution; frozen ground/raster
  mapping; N original observation identities and factual source chainages; original
  decision/evidence regions; protected occurrence events; policy identity; shared
  cancellation, work and attempt-memory ownership.
* `OptionalEmissionContextFactory`: producer-specific factories. Arbitrary public
  constructors cannot attest metre units, native resolution or authority.
* `SampledEmissionInput(MultiScaleProfileSet profiles, OptionalEmissionContext context)`.
* `EmissionResult`: full observation census, M geometry vertices, mapping, arc
  evidence/costs, stage identities and actual work. No fabricated N-row winner.

Add experimental sampler overloads returning `SampledEmissionInput`. Retain the
already built unfiltered L0 `ScalarIntensityField` while its producer still owns it;
`MultiScaleProfileSet` currently retains only profile levels. Capture in the existing
`sampleProfilesFromField` producer path, before the field is discarded. Do not
regenerate scalar mapping, synthesize a field from profile rows, resample/blur a
second raster, acquire imagery or change original sample locations. Share immutable
storage and charge its full retained lifetime once. Detached sampling retains the
same scalar values/validity actually used by its profile producer.

The retained field uses base-L0 coordinates, including crop origin. Convert profile
coordinates through the existing `coordinateScale` before sampling. Preserve managed
half-pixel phase and visible-render convention separately. Add a read-only logical
grid adapter exposing original node values, cell validity, dimensions and origin;
no array copies or invented validity. Bind the separately implemented
`BilinearSegmentIntegral` to that adapter; it supplies scalar evidence only, not
decision authority or branch proof.

Factory contracts:

* `fromDetached(...)` binds the actual scalar source, exact shared detached
  sampling frame/transform and original decision region.
* `fromManagedCapture(...)` freezes tile world origin, zoom, oversampling, source
  resolution and captured geographic transform. It does not infer pitch from the
  tracker normalization argument.
* `fromVisibleCapture(...)` freezes capture projection/bounds/scales and source
  resolution while the virtual viewport is active. The first implementation may
  support exact closed Web Mercator producer parameters; unsupported projections
  are explicitly unavailable, never approximated by a later global projection.
* `fromDeclaredSyntheticMetricGrid(...)` accepts explicit test truth, including R4's
  one-pixel/one-metre declaration and native pitch1. This cannot certify production.

Use captured-projection `ProfileSamplingAnchor.pair` or detached factual locations
for production chainages. `rasterOnly` compatibility distances never prove metres.
Validate every row against its retained coordinate owner. The current profile API
does not distinguish trustworthy and compatibility chainages by type; factories
must retain explicit producer provenance, not accept the numeric field alone.

The mapping must support actual raster-to-certified-ground endpoint coordinates and
exact producer correspondence. For optional decision-region admission, prove the
whole actual raster connector under that mapping, using analytic producer bounds
and bounded subdivision if nonlinear; sampled endpoints alone are insufficient.
An unresolved boundary certificate is unavailable. Do not substitute a straight
metric chord for the authority proof of a curved transformed raster connector.

Production classes gain explicit experimental methods:

* `RidgeTracker.trackEmission(SampledEmissionInput, CancellationProbe)`;
* `CorridorAwareTracker.trackEmissionDetailed(SampledEmissionInput, JunctionContext, ... )`;
* `CorridorCenterlineOptimizer.optimizeEmission(..., OptionalEmissionContext)`.

They use the real existing extractor, state constructors, association and tubes.
Tests/replays invoke these production methods, not a parallel test-only solver.
Old entry points remain native and cannot silently opt in. During the isolated
experiment the invocation/policy is internal and explicit; no engine/menu switch.
Unsupported settings/context must be reported as untested/unavailable, not a
successful native fallback. Adoption requires the original whole-plan consumers.

## 3. DAG, original states and mandatory occurrences

Preserve every original state after each engine's existing state construction.
A keeps all `allowedOffsets`; Legacy keeps its existing observed-peak states,
carried-offset fallback families and interval-target fallback families as specified
in section7, plus its existing interval endpoint constraints. No additional
beam, source-forward veto, Jacobian veto or removal of inconvenient states.

A node is `RowState(row, nativeStateId, offset, rasterPoint, metricPoint, stateKind)` or a
`BoundaryEvent(occurrenceId, sourceChainage, exactPosition)`. Row state IDs preserve
native enumeration order. Boundary events are exact captured protected occurrences,
not observations, peaks, profile rows or direct image support.

Order by factual source chainage and original occurrence identity. Each protected
event is mandatory; no edge can cross it. Its immediately bracketing observation
rows are also mandatory, permitting an unambiguous initial mapping primitive whose
consuming arcs have observation vertices at both ends. Equal-chainage row/event
aliases require exact coordinate/occurrence reconciliation; conflicting mandatory
events are typed unavailable, never reordered or merged by proximity. Hard fixed
positions and existing junction displacement bounds remain hard. Movable junction
events require the existing exact proposed-target/assignment contract; they cannot
be silently treated as fixed or omitted. Implement that consumer before claiming
the movable configuration tested.

Ordinary transitions advance one observation/event position using existing native
admission. An optional row-state edge i->j may consume at most16 interior observation
rows (`j-i-1<=16`) and at most20m factual source chainage, and bypass no mandatory
row/event. It additionally needs the complete native connector certificate below.

Original GAP/censored/observed classification is NOT a blanket optional-edge gate.
It is immutable source-observation provenance. New independently measured 2D arc
evidence may cross a missing association without inventing a positional observation
at that source row. Hard forbidden-region/acquisition authority remains binding.

Evidence motivating that distinction: Drasov row202 is an original GAP singleton;
the 201->203 counterfactual connector has17/17 valid positive scalar samples and
decision-region authority, while it does not intersect row202's finite source
section. Therefore its source association is missing, not necessarily its image.
The existing `gap-interpolation-receipt.txt` does NOT prove branch admission or a
repair, and its interpolated point must not be imported as an observed row.

## 4. Connector admission: actual native geometry and branch receipts

`NativeConnectorEvaluator.evaluate(from,to,context)` produces a typed immutable
receipt. For positive-length edges, admission depends only on those exact endpoints,
frozen evidence and source interval. A zero-length optional edge has no new tangent:
its directional receipt additionally binds the declared transported incoming arc,
not a heading reconstructed from a winning path. Enumerate all such canonical
state variants; no winner-dependent pruning or source-forward criterion is allowed.

Stages, with early failure and full charging:

1. Numerical span, mandatory barrier and original authority checks.
2. Strict supercover acquisition support over the whole actual native connector.
   `BilinearSegmentIntegral` supplies native mean/min and actual query counts.
3. Measured endpoint ports and branch binding.
4. Two-sided transverse native mode proof along the actual connector, including
   endpoints and native-cell events, at spacing no greater than half the factual
   minimum native pitch. Use existing versioned
   `LocalScalarProfileExtractor.extractDetailed` localization parameters. Position
   brackets must be observed inside the original decision window; halo/filter
   support outside it cannot become positional authority.

At each probe require one uniquely compatible complete native mode whose measured
center set/localization uncertainty contains the connector position. Reject
unresolved competing modes, censored position, split/merge ambiguity or missing
support. Successive probe modes require unique mutual continuation under the
existing1.5-native-pixel residual bound. The actual segment's bilinear minima must
remain inside the measured support envelope; intensity along an un-emitted bend
cannot authorize its straight chord. No average-only support test.

A complete original state band/peak can bind an endpoint port only when its owned
position agrees with the new native measurement. GAP/censored endpoints cannot
borrow an old center: they require a fresh complete native endpoint port on the
actual segment and unique binding to the selected geometric branch via bounded
direct evidence. If that proof is unavailable, reject that edge; do not reject every
edge merely because an old association is absent. A fixed off-signal boundary is
still an exact boundary, not an image-supported optional endpoint.

This is a local geometric scalar-branch certificate, not carriageway semantics or
permission to move context. Cache only exact endpoint/context identities; no rounded
geometry keys or hidden path-dependent eligibility. Directional endpoint-port
receipts bind ordered endpoint/state identities and the exact direction/transport
key. Only an orientation-independent necessary predicate may be cached by RowState
alone. Do not prune optional edges merely to
fit a work target. Admission optimization must preserve these exact predicates.

## 5. Objective and source/output measures

All native row-unary soft terms are removed uniformly from this experimental
objective, whether a row emits or is consumed. This includes A intensity/local-fit,
plateau/coarse/tube-center and soft endpoint-approach position priors, and Legacy
evidence rewards, local peak penalties and interpolated anchor-target soft costs.
Keep their original tables as native diagnostic/reference data, never selected
costs of consumed rows. No worst-unary debit and no selectively free skipped rows.

This is an explicit changed data objective. It is justified as moving candidate-fit
measurement to the actual emitted 2D route, not as numerical equivalence to native
costs. Original hard state/geometry/authority constraints remain mandatory.
Endpoint-approach observations, reliable-peak quality and source classifications
remain factual diagnostics/eligibility evidence; they do not silently become new
unary rewards. Independent review must assess this declared soft-prior removal.

For an arc a->b, let Δs be its original source-chainage span, p the factual frozen
reference native pitch in metres, and r(t) its actual straight native raster segment.
Define:

`Q(a,b) = (Δs/p) * integral_0^1 (1 - I(r(t))) dt`.

Integrate the retained bilinear scalar field over every crossed cell. Native means
and minima are available only with complete cell support. Every original source
interval belongs to one actual arc and retains its full mass; data terms are not
dropped when rows are consumed. Collinear subdivision preserving the same source
parameterization must preserve Q up to stated arithmetic tolerance. There is no
reward/cost solely for emitting a vertex. Quadrature samples are new direct native
queries on the actual arc, NOT interpolated source observations or N-row offsets.

For a zero-length raster edge, Q is the same source mass multiplied by the actual
native point loss. Strict supercover support at that point is still required for
availability. Such an edge contributes zero physical length and no positive-length
route coverage. It does not acquire a heading merely because its source span is
positive. An entirely zero-length candidate has no positive-length route evidence
and cannot claim repaired usable geometry.

An ordinary native edge with unavailable field support may retain its original graph
admission and receives the explicit conservative model loss1 over that interval;
diagnostics say unavailable, not measured zero intensity. Such a path does not gain
available coverage or applicability from the penalty. Optional edges require
complete measured evidence. Final acceptance cannot count a refusal/unsupported
path as a repaired result.

Keep source-chainage mass, physical emitted-path length and their coverage
denominators separate. The source measure above is for the objective; measured
route coverage is integrated over actual output length. Consumed source rows receive
no automatic direct-position, fit, motion, bend or movement credit.

For A, let `h_r=max(sourcePixelRasterPx, distance(original raster anchors r-1,r))`,
`H_ab=sum(h_r)` over its original observation intervals, and w_r the existing
cleanup-Off continuity coefficient. Native continuity becomes:

`C_A(a,b) = ((u_b-u_a)/H_ab)^2 * sum(w_r)`.

It equals the existing expression on an adjacent pair. These are native raster
prior quantities, NOT metres or the20m authority test. Reference headings use the
existing tube points at the emitting row indices. With θ the actual raster heading
change, φ its tube-reference change, and w_c the existing cleanup-Off destination
acceleration coefficient:

`T_A(a,b,c)=w_c * (wrap(θ-φ)/max(1,(H_ab+H_bc)/(2*sourcePixelRasterPx)))^2`.

For actual certified ground endpoint edge lengths L and ground turn angle θ_ground:

`K(a,b,c)=θ_ground^2/max(1,(L_ab+L_bc)/(2*p))`.

K has fixed unit weight, as screened. The previous finite π² rule for zero-length
edges is REMOVED. A zero edge charges no angular K or geometric T_A; it transports
the incoming positive-length arc unchanged, including its length, heading and native
reference receipt. At the next positive-length edge, K compares against that last
positive-length leg. If none exists, this is a first direction and K is zero, not a
measured zero turn. Original cleanup-only absolute
turn terms retain their own identity and must remain exactly zero when cleanup is
Off. K is the separately named ordinary physical term under this new policy.

The v2 objective is `J=sum(Q+C)+sum(T+K)`; A has no first-vertex unary. Boundary
events carry no scalar evidence or lateral offset. Carry the most recent numeric
row state through boundary events; charge native offset continuity once when the
next numeric row emits, without inventing a boundary offset. A native geometric
T reference requires an actual positive-length emitting leg whose two endpoints
own valid tube row positions. If that leg's reference chord has zero length or a
boundary lacks tube ownership, its reference is unavailable: never atan2(0,0).
Set T_A to the explicitly inapplicable neutral prior when either angular reference
is unavailable, while physical K still uses the real incoming leg. A zero actual
edge never replaces a valid incoming reference with its own source-only tube
motion. At a positive actual leg, retain that leg's reference receipt (available or
unavailable) for the next comparison. Q always follows actual event geometry.

Cleanup-conditioned numerical terms require a separately completed exact mapping
adapter. Initial kernel tests use Off; requested cleanup must remain explicit and
ineligible until its consumer exists, not silently interpreted as fully supported.

## 6. Exact A pair recurrence and deterministic solution

For a path with no zero edges/boundary-only aliases, the recurrence reduces to:

`F(b,c)=Q(b,c)+C(b,c)+min_a[F(a,b)+T(a,b,c)+K(a,b,c)]`.

The implementable canonical state is
`AKey(cursorOccurrence, incomingEffectiveArcOrNone, lastNumericRowOrNone)`.
`incomingEffectiveArc` identifies the two original occurrence/state endpoints of
the last positive-length ACTUAL emitting leg. It retains its ground/raster heading,
physical length, native source-spacing span and optional tube-reference receipt.
It is the effective emitting pair, not two consecutive source observations. The
cursor retains every intervening zero occurrence and its source index; it is not
merged with the effective pair endpoint. `lastNumericRow` is redundant when the
cursor is a row and is transported only through boundary-only events.

For each admitted successor d, relax exactly:

`D(key') = min(D(key') , D(key)+Q(cursor,d)+C(lastNumericRow,d)`
`          +T_A(incomingEffectiveArc,cursor,d)+K(incomingEffectiveArc,cursor,d))`.

On an exactly zero actual leg, key' advances the cursor, preserves the effective
incoming arc/reference receipt, updates lastNumericRow if d is a row, and charges
Q/native offset C only. On a positive leg, K and T compare against the retained
incoming leg, then key' records `(cursor,d)` as its new effective arc with its own
reference receipt. The T_A denominator uses the retained incoming leg's H and the
new numeric leg's H; zero legs do not fabricate geometric intervals or dilute K.
If an offset/reference endpoint is unavailable, record the corresponding native
prior as inapplicable; do not synthesize an offset or heading.

Begin with cursor=start, no incoming physical arc, and its real numeric row if any.
The first positive leg establishes direction without a turn. This remains one exact
second-order geometric process with explicit zero-occurrence transport, not a
second optimization or nonlocal topology state. Prefixes may merge ONLY when their
entire canonical key and phase/boundary context match. Different incoming effective
arcs/reference receipts or scalar memories must not share a predecessor merely
because their current two points coincide. All variants count against work/memory
limits. Backtracking retains every selected occurrence, including each zero edge
and distinct coincident OSM/protected identity; effective-pair transport is not
output coalescing. Require terminal row and all mandatory occurrences.

A performs one exact solve over all admitted states/edges. No beam, global topology
history, hidden refinement or post-solve loop deletion. Retaining original states
does not mean retaining false measurement availability for them.

Ties: cost first; on exact equality fewer consumed observations; then lexicographic
original emitting node IDs/native state IDs. Stable native enumeration is retained.
Tie comparisons must be deterministic and charged; use predecessor records rather
than copying whole histories. Never let hash iteration decide a path.

## 7. Legacy stages, heuristic anchor ownership and smoothing

Legacy has its separately declared existing seed/anchored/smoother pipeline, not a
claim that its assembled final path is the global A-style optimum.

Keep peak extraction/suppression and seed enumeration unchanged. The original code
does NOT use an unsupported row's sampler zero peak as its base-path state:
`bestPathForSeed` carries the preceding offset, while `intervalPeaks` uses the fixed
interpolated interval target. Preserve these actual native state families:

* `ObservedPeak(row, originalPeakId)`: the original retained peak, measured only
  according to its original provenance.
* `BaseCarry(row, offsetOriginId)`: same scalar offset as the incoming numeric row
  state, or the real seed offset before any numeric row. Its origin ID identifies
  that original observed state/seed, transported unchanged through prior carry rows
  and boundary-only events. Reconstruct the point on THIS row's real normal; never
  replace it with the sampler's convenient zero peak. It has no direct source
  position/signal evidence merely because it is a state.
* `IntervalTarget(row, leftAnchorId, rightAnchorId)`: exactly the existing
  `left.offset+(right.offset-left.offset)*(row-left.row)/(right.row-left.row)`
  fallback value for an unsupported row during that anchored interval. It is a
  model-generated unsupported state, not a measured/interpolated source observation.

The base-family domain is the finite union of native offset-origin states and the
seed for that solve. Generate members on demand from canonical incoming states;
ordinary/optional endpoint compatibility requires the incoming offset origin, so
one cannot select a more favorable carry value from another path. An optional edge
may consume an unsupported row without creating a chosen family member for it.
Anchored interval families are fixed by that interval's actual boundary anchors.
Before allocation/count expansion, charge every distinct family member/variant;
if a row's genuine family exceeds the unchanged state ceiling, refuse the experiment
rather than truncate it. Consumed observations retain their source class and have
no chosen carry/target/offset. Family members remain reference possibilities only.

Extend the existing `bestPathForSeed` solve to emitting-pair states. For row endpoints:

`C_L(a,b)=0.16*abs(u_b-u_a)`;

`T_L(a,b,c)=0.12*abs((u_c-u_b)/(row_c-row_b)-(u_b-u_a)/(row_b-row_a))`.

Adjacent terms preserve the native increment formulas. Add the same Q and physical
K. Legacy's canonical key additionally retains
`ScalarMemory(lastNumericRowOrSeed, lastNumericOffsetLegOrNone)`.
This is required because two different normal-row offsets can reconstruct exactly
the same physical point. Offset T_L uses the last two numeric emitted-row increments
even through an actual zero edge; it contains no angle. Boundary-only events do not
reset it or acquire fake offsets. Physical K independently transports the last
positive-length geometric leg exactly as A does. Charge and distinguish all such
scalar/physical memory variants in the Legacy state key.

At a fresh seed, initialize numeric memory from the actual seed, no geometric leg,
and scalar delta0. The first row therefore has the native
`0.28*abs(u_first-seed)` prior (continuity0.16 plus initial increment0.12); its scalar
leg is retained for the next row. This seed is not a sampled occurrence or observed
support. The R4 screen omitted this constant because both compared Legacy paths
retain the identical first native peak.

After first-stage inference, run the EXISTING reliable-anchor proposal logic on
original observations/quality/neighborhoods. Eligibility is now restricted to rows
actually EMITTED by that first-stage path, and only when its proposed peak is the
same owned native peak as the emitted state. Do not evaluate neighbor support on a
compressed profile list. These heuristic image anchors are distinct from hard OSM
protected occurrences. A consumed row has no chosen peak/offset and cannot become
a mandatory anchor merely because its independent observation is strong.

Actual R4 reliable-anchor indices are[] even before that restriction. Thus the new
policy does not reinstate its13 consumed rows during anchored reconstruction.

Retain the existing anchored interval solve stage, extended to the same optional
pair recurrence. Its endpoints are the eligible owned peaks and hard events. All
original internal state families remain available; consumed rows are not prohibited
from emitting again. Initialize each interval with the full actual owned incoming
effective arc/reference receipt and numeric ScalarMemory of its retained prefix,
including zero-occurrence chains through anchor phases. Do not reset a valid
incoming heading, reference or scalar delta to zero at a phase boundary. A genuine
global start has no incoming direction; it must not copy a heading from a future
anchor. Pass the selected interval's resulting transport state to the next phase.
This is bounded second-order boundary state, not nonlocal topology history. Stitch intervals
with explicit ownership and recompute final whole-path J, including boundary terms.
Do not retain native `scoreBonus` as if old unary rewards remained comparable.
Report first-stage J, interval objectives and final stitched J separately.

There is NO third optimization/repair pass. The existing smoother runs once on
maximal consecutive-emission runs using their actual original profile objects.
Endpoints adjacent to consumed arcs remain fixed; consumed rows never enter a
smoothing window. Paths with no consumed rows retain the existing smoother.
Keep inference geometry and smoothed geometry distinct and recompute actual
post-smoothing evidence/costs; do not claim the smoothed path is the DP optimum.

## 8. Actual caps and feasibility before expensive inference

Use one attempt-wide budget/owner across tracks, seeds, existing anchor intervals,
detectors and source preparations. Do not reset128M for each new phase.
Retain existing limits: A's configured21 offset states, caller maximum96 states
where applicable, 8M pair work ceiling,128M work ceiling, existing alternative
limits and256MiB attempt retained-allocation ceiling. Stricter callers prevail.
Legacy exceeding a limit refuses this experiment; it does not trim peaks.

Charge before performing work/allocation. Keep separate counters for potential and
admitted edges, cell/node queries, mode-extractor operations, region operations,
pair records, pair visits, DP triple extensions and retained bytes. Aggregate all
primitive admission/integration/extraction/DP units against128M. EVERY DP predecessor
record visit, candidate triple extension/destination lookup, initialization and
transport-state lookup consumes an8M `maximumPairVisits` unit as well as a128M
work unit. Charge tie/backpointer state visits too; no uncharged zero-edge closure.
Distinct retained effective-pair/canonical-state count is additionally reported and
bounded by the same unchanged ceiling plus the memory ledger. A row's native offset
state/family ceiling is separate from these extra transport-state multiplicities.
128M is not a DP-only allowance on top of unbounded admission work;8M is a stricter
DP visit ceiling, matching the current native budget precheck's use of BOTH limits.

Before expensive native admission, compute cheap potential edges from exact original
state counts, span and mandatory boundaries. For an admitted graph, exact pair/triple
bounds are `E=sum(n_i*n_j)` and
`X=sum_b(indegreeStates(b)*outdegreeStates(b))`, with overflow-safe arithmetic.
Actual state-dependent admission uses actual vertex degrees rather than assuming
all row-state pairs survive. Allocate exact pair tables only after admission and
budget checks. An upper bound exceeding a cap is not alone proof the actual admitted
graph exceeds it; neither is it permission to prune states. Admission itself must
stop on its charged cap and return a typed limit, with no partial winning result.

Cheap screen in `graph-upper-bounds.json`:

| cached state set | potential pairs | triple extensions before image admission |
| --- | ---: | ---: |
| R4, exact16-row+20m gates |26,098|1,878,294|
| Drasov A,16-row gate only |1,533,820|441,927,213|
| Drasov A, exact16-row+20m gates |1,091,848|227,563,996|

R4 has239 states, maximum16 per row. Drasov's actual cached allowed sets have5,334
states over309 rows, maximum21. The added exact20m screen binds all309 frozen
profile chainages bit-exactly to their309 corridor-input locations and original
allowed-state rows. See `chainage-binding.json` and `bounded-graph-receipt.json`;
row-binding SHA256 is
`4f67d5147bbbe7a85538ec5eb01fc7fddd52d444db12cf8bac8262d2f5d147e0`.
It yields3,630 potential row arcs and reproduces the original distance-unbounded
control counts. No constant source spacing was invented. These figures exclude mode/authority
work, mandatory-event changes and additional tracks. They establish a real risk:
Drasov cannot evaluate that entire unfiltered graph within EITHER8M DP visits or
128M total work. Its441,927,213 triples are55.24 times the8M ceiling before even
predecessor visits, admission work or extra zero-transport variants; even the
exact20m graph has227,563,996 triples,28.45 times8M. R4's1,878,294
triples are below8M but leave less headroom than128M alone suggests. These old graph
figures are bounds for the ordinary nonzero-pair special case; zero-transport and
Legacy carried-state family multiplicities must be counted from their full canonical
keys, not assumed covered by E. Exact20m and factual native admission must reduce
actual work substantially. Typed resource refusal
preserves safety but FAILS the experiment's practical quality-success gate.

Admission ordering is preregistered above; add only exact equivalence-preserving
caches/early failures, not source-direction/Jacobian conditions or winner-dependent
candidate filters. If the exact predicate is impractical, report that result before
building more mapping/UI integration around a nonviable solver.

Cancellation checkpoints precede and occur inside region, cell, extraction,
allocation and DP traversal loops. Owner closure releases partial graph/result
storage on cancellation or refusal. No late publication and no partial best-so-far.

## 9. N/M mapping, evidence and consumers

Concrete source inventory: [consumer-index-map.md](consumer-index-map.md). In
particular, `CenterlineCandidate.withProjectedGeometryAndOffsets` enforces equal
geometry/offset counts; `CorridorEngineAdapter` pairs those arrays by index;
`AlignmentService.candidateMetrics` interprets consecutive offsets as observations;
and cleanup currently equates raw geometry with evidence-frame count. New-policy
callers need explicit typed adapters at each boundary. Keep final preview count K
distinct from both original observation count N and inference vertex count M.

`EmissionMapping` owns N original observation links, each `Emitted(vertexIndex)` or
`Consumed(arcIndex)`, and M vertex owners, each original observation or an exact
boundary occurrence. Copy collections; require reciprocal one-to-one emitted links,
strict original row ordering, valid consecutive arc ownership, mandatory rows
emitted, unique boundary identities and full observation accounting. The initial
primitive forbids consuming arcs touching boundary-only vertices; mandatory
bracketing rows above satisfy this without fake evidence. No coordinates/offsets
or source classifications are inferred by this structural primitive.

`EmissionResult` stores N original observed/gap/censored records unchanged, M actual
geometry vertices, native state tables/reference costs, typed mapping, M-1 actual
arc receipts, stage identities and decomposed Q/C/T/K. Consumed rows have no chosen
offset, position, peak or selected-state cost. Boundary vertices have no sampled
profile. Missing old chosen fields are unavailable, not zero/NaN padding.

Update every actual consumer before claiming integration:

* `CenterlineCandidate`/`CandidateEvidence`: explicit optional-emission result;
  retain native observation census separately from route support. Candidate ranking
  uses new J normalized by fixed `max(1,totalSourceChainage/p)` inside existing
  detector/source tiers, not native unary score or a vertex-count bonus. Preserve
  raw detector/native reference scores and never merge policy identities.
* `CorridorAwareTracker`/`RidgeTracker` evidence builders, deduplication and candidate
  geometry: M real points, original N observations. No hidden native N-path solve.
* `CorridorQualityCalculator`/coverage: route metrics from M geometry and native arc
  receipts; original source-row coverage retains its original denominator/classes.
  Unsupported old metrics are explicitly unavailable. New-policy assessment must
  distinguish source-observation gaps from newly measured route coverage rather
  than forcing an old N-selected-profile minimum onto a fabricated winner.
* `CleanupEvidenceFactory`/`FinalPreviewCleanupContext`: explicit mapping adapter;
  no interpolated or consumed row becomes direct cleanup/motion evidence. Until
  supported, requested cleanup is visibly ineligible, not silently successful.
* `AlignmentService`: M projection, actual final reconstruction, metrics/ranking,
  per-detector attempts, preview switching and exact stored final geometry. Move
  mode still emits every original OSM node via its existing reconstruction. Precise
  mode preserves the existing replacement/deletion protections and exact targets.
* `AlignWayAction`/commands: unchanged source freshness and Apply/Undo guards; final
  reconstructed geometry must independently pass contact/topology/support checks.
* Debug/replay/analyzers: dedicated checksummed mapping/arc-cost artifacts and
  versioned schema/policy/work identities; candidate ridges are actual M geometry.
  Original N profile CSVs remain observations. Old chosen-offset columns are
  unavailable where consumed. Preserve historical formats and native strict gates.
* Modern/Legacy adapters and Hybrid parent/refinement consumers must retain M
  geometry plus N ownership, without treating a parent consumption as B evidence,
  widening interval authority, erasing refused intervals or changing native work IDs.

An opt-in kernel harness may consume `EmissionResult` directly while these adapters
are built. Its passing R4 result is not complete application/Hybrid integration.

## 10. Implementation order and acceptance

1. Independent review of this fixed packet; integrate fixes for concrete findings,
   without another general user approval milestone.
2. Structural mapping and strict bilinear integration primitives with meaningful
   RED->GREEN. No branch/authority promises from the math primitive.
3. Factual context retention/factories and typed refusal tests: wrong units,
   rasterOnly provenance, frame/scale/phase mismatch, unsupported transform,
   invalid native cell, stale source identity and original forbidden region.
4. Connector admission tests including source GAP/censored with independently
   valid arc evidence, the opposite unavailable/native-dark cases, competing
   branches, invalid cell interiors and whole-segment authority.
5. One production exact DAG kernel, native A state adapters and Legacy existing-stage
   integration. Exhaustive tiny graph oracle, tie tests, optional count16/17 and
  20m boundaries, mandatory events, shared budget, cancellation and refusal.
6. Invoke both production experimental entry points on unchanged R4 image oracles.
   Require actual GREEN, not merely the cost witness. Freeze objective thereafter;
   no coefficient family if a gate fails.
7. Opposite150arc, supported switchbacks, strong/weak thin parallel strands, dark
   separation and collinear insertion. Confirm no false N-row selected evidence.
8. Complete consumers, actual managed/visible context and original Drasov plus good
   Omice replay. Include both geometry modes, fixed/junction neighborhoods,
   source freshness, candidate switching, confirmation, exact final preview,
   Apply/Undo/Redo and Hybrid interval fallback. Capture actual work/time/memory.
9. Reject the experiment if usable geometry and existing practical limits cannot
   both pass. No extra refusal, safety-only block, default fallback, skipped
   configuration, private-reference guess or increased cap counts as success.

The accepted-blue/private visual reference, public CI and original full-plan gates
remain required for completion/adoption. No production default change or release
is authorized by this packet or its synthetic screen.
