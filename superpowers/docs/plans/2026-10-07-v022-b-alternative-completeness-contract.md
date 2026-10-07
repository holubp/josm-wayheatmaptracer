# Engine B alternative completeness contract

Status: **design independently approved; production gate open and stable v0.22.0 blocked** while real capped/D-unmet
runs have no proof that further admitted distinct alternatives are absent.
This document is a Task 5 design and test packet, not a production certificate.
Basis: approved 2026-10-07 usable-release design and Task 4 source at `82ea716`.
The ordinary managed test RC may proceed with this limitation disclosed.

The governing original plan, §6.4, requests up to eight distinct routes from
up to 32 raw paths and flags truncation when that raw cap prevents diversity
exploration. The approved usable-release design preserves those caps and their
completion semantics. Reaching the requested eight satisfies this bounded
alternative request; it does not purport to enumerate every lattice route.

## What the current result actually proves

The admitted inference lattice is a finite directed acyclic graph of profile
states. For profile `i >= 2`, an extension is identified by its last two states
`(before, prior)` and next state. Admission is decided by the same transition
predicate used by forward/backward inference. For each terminal pair, the
count recurrence adds admitted predecessor counts and saturates at `K+1`, where
`K = min(32, requested raw limit)`. A one-profile graph counts its cells.
The terminal sum also saturates at `K+1`. Thus a reported count below `K+1`
proves that the admitted graph has exactly that many complete paths; `K+1`
proves only that it has **at least** `K+1`. A dead end does not contribute a
complete terminal path. Neither count records geometry, branch identity, rank
of the next route, or how many later routes exist.

Task 4 retains at most `K` prefixes per last-state pair, then materializes at
most `K` raw terminal paths. Its comparison is by rounded binary64 energy and
lexical rank, including the post-addition tie case; this preserves the existing
ordered bounded-prefix output and numerical fingerprints. It does not search
beyond that output. A proof about all admitted terminal paths must traverse
branches discarded by retained-prefix pruning. It cannot infer global top-K
coverage from the current `rawPaths`, especially when rounding collapses
energies and changes lexical tie priority. A score gap alone cannot be an
alternative-completeness certificate without an approved release-contract
change.

`PathAlternativeSelector` greedily scans raw paths in energy order. A path is
discarded if it is *not different* from **any** already selected path. Different
branch signatures are always distinct. For equal signatures, sustained
separation must exceed `max(pitch, 1 m)` for at least `max(10 m, 5*pitch)` of
profile chainage. This duplicate relation is not transitive: at two profiles
12 m apart and pitch 1 m, constant offsets 0, 0.6, and 1.2 m give left≈middle
and middle≈right while left and right are distinct. Scanning left, middle,
right selects two; scanning middle, left, right selects one. A branch-signature
bucket, geometric equivalence class, connected component, or per-route count
therefore cannot replace the ordered greedy computation.

The `Completion` receipt reports the effective caps, terminal count at
saturation, whether raw enumeration capped, and whether the desired distinct
count `D` was reached. `alternativeSearchTruncated` is
`rawEnumerationCapped && !requestedDiversityReached`, the approved bounded
request condition. The ordinary-size three-profile fixture has 45 complete
paths, eight observed labels among the first 32, and a ninth route beyond the
cap. It proves that **bounded satisfaction differs from global exhaustion**.
It does not show a Boolean or strict-validator defect. The one-profile
34-state control isolates the same semantic distinction; the three-profile
case stays within ordinary per-profile state limits.

`ProductionReplayValidator.validateScalar` rejects
`inference.alternativesTruncated()`. A regression carries the 45-path solver's
bounded-result Boolean into a hand-built replay wrapper and confirms that the
strict scalar validator accepts the D-reached result. This is expected under
the approved 32/8 criterion, and the test does not claim a full managed slide
was executed. The existing fine-pitch replay test proves that a *true*
cap-prevented-diversity flag is rejected. A faithful fingerprint of a D-unmet
truncated result is still no strict release pass.

## Three possible proof statements

1. **Full exhaustion:** every complete path in the same admitted finite DAG
   has been visited. A terminal count below `K+1` already proves the path
   count is below the cap; saturated counts require a separate all-path
   pass. Exhaustion says how much graph was searched. It does not change the
   approved target from at most eight retained routes to all possible routes.
2. **Approved bounded request:** the ordered bounded-prefix raw result
   supplied `D` greedily selected distinct routes. This is what the current
   D-reached completion receipt and strict Boolean claim. A ninth route is
   compatible with this contract. When `D` is unmet and the raw cap was hit,
   the current result correctly marks truncation because further diversity
   exploration was prevented.
3. **Exclusion of further distinct alternatives for D-unmet results:** a
   separate proof can establish that every omitted admitted complete path is
   duplicate to at least one *actual retained selected representative* under
   the exact pairwise selector predicate. Then no further distinct route can
   fill the still-unmet bounded request. An uncovered path or uncertain
   proof leaves truncation true. This comparison cannot use transitive
   classes, a probability cutoff, or a ninth-route requirement after D is
   already met. `ambiguityEnergyDelta` affects status, not this proof target.

## Follow-on implementation packet for independent review

**Target and eligibility.** Run an additional certificate only when raw
enumeration saturated and `D` was not reached. Its narrow success statement
is: *every omitted complete path admitted by this exact frozen graph is
duplicate to at least one actually selected retained route under the existing
pairwise `different` predicate*. This leaves the 32/8 caps, D-reached behavior,
bounded-prefix order, status, MAP/posterior and strict validator predicate
unchanged. A certificate may clear the truncation Boolean only for an
eligible D-unmet result that proves that statement. Any uncovered path, budget
limit, cancellation, unavailable evidence or malformed receipt preserves the
current strict refusal. The certificate must bind to the actual selected
representatives; nontransitivity makes a branch-class summary insufficient.

**Cheap sufficient proof.** For each selected route `r`, use the exact
`representativePitch(profiles)` passed to `PathAlternativeSelector`: the median
`LateralStateCell.quadratureWidthMeters`, which is not necessarily source or
rendered pixel pitch. Share that production computation and the exact
`InferenceProfile.point`/`MetricPoint.distanceTo` arithmetic; do not independently estimate the
pitch. Use the same `max(pitch, 1.0)` lateral threshold. If **one fixed** selected
route has, at every
profile, (a) every profile cell's branch label equal to `r.branchSignature()`
and (b) every cell point no farther than the exact lateral threshold from
`r` at that profile, then every admitted complete path has that signature
and no separated profile at all: the implemented `separation > threshold`
condition is false at every index. Its maximum sustained span is therefore
zero, below `max(10.0, 5.0*pitch)`, so the exact selector considers the path
duplicate to `r`. Checking all cells is
an intentional over-approximation of complete-path reachability: a dead-end
or disconnected cell can make the check inconclusive, never falsely prove
success. Use production `MetricPoint.distanceTo` and its exact binary64
comparison; non-finite inputs or a value at an uncertain arithmetic boundary
must fail closed. Do not enlarge the lateral threshold with an unreviewed
tolerance. This check is `O(D * sum_i S_i)` distance/label comparisons and
`O(1)` additional geometric state beyond the frozen profiles and selected
routes. Charge transient `InferenceProfile.point` materializations (or prove
a nonallocating equivalent preserves the same subtraction/`StrictMath.hypot`
order), count comparisons against the attempt budget, and checkpoint
cancellation. Resource refusal stays inconclusive.
`ProbabilisticInference.branchSignature` is the modal label by summed
profile quadrature with a deterministic reverse-key tie break; it is not each
cell's label. The all-cells same-label condition is deliberately stronger:
it proves that every possible path's modal signature equals `r` without
reimplementing weighted label aggregation. A mixed-label path that still
has `r` as its modal signature must remain inconclusive under this cheap
proof. The 45-path test independently demonstrates the actual modal-label
mapping: its middle route label has 10 m quadrature while the outer common
labels total 10 m, and the lexical tie favors the route label. Dependent RED
tests must include both uniform-label success and mixed-label/tie refusal.
This is a sufficient certificate, not an eligibility prediction for the
supplied captures. A wide multi-mode lattice is likely to make it
inconclusive; no current-corpus eligibility measurement was made. The
previous isolated certificate at `514f863` was
ineligible for all eight RC.4 cases; that result neither proves nor disproves
this simpler check on current RC6 inputs.

**Optional exact fallback for small graphs.** If the cheap check is
inconclusive, a separately budgeted depth-first traversal can visit every
admitted terminal path, including discarded K-best prefixes, and compare
each omitted path to each actual selected route using the exact branch
signature and sustained-separation predicate. No score order is needed for
this *fixed-representative coverage* statement; the selected representatives
and their original order remain unchanged. The first uncovered path is a
failure witness. Only exhaustion with no uncovered path proves coverage.
Charge every inspected transition and retained path/stack object to the
attempt owner, check cancellation, and return INCONCLUSIVE on budget,
memory, numeric or admission uncertainty. A streaming traversal uses
`O(number of profiles + D)` path/comparison state but can take
`O(D * n * product_i S_i)` work in the worst case. It is a correctness oracle
and possible small-graph path, not a demonstrated practical solution for the
captured lattices. A compressed proof needs its own sound state abstraction
for modal branch signature and each selected route's sustained separation;
do not merge nontransitive geometric classes or treat predicted coverage as
direct evidence.

**Receipt and checks before implementation.** A dependent reviewed change
needs a versioned receipt carrying the frozen admitted-graph/parameter/pitch
identity, actual selected-route identity hash, effective `K`/`D`, saturated
terminal count, proof mode (`TUBE_COVERED`, `EXHAUSTIVE_COVERED`,
`UNCOVERED`, `INCONCLUSIVE`), logical transitions, charged memory peak and
cancellation/resource outcome. The constructor/codec must reject unknown
versions, stale or mismatched hashes, impossible count/selection claims,
an unproved Boolean clearance, and legacy unattested receipts. Diagnostics
remain coordinate-free and credential-free; Format-15 older readers remain
valid. RED tests must include a D-unmet capped all-duplicate lattice that
the cheap tube proves, a one-profile or small multi-profile late distinct
route that remains truncated, branch-label mismatch, threshold equality and
nextafter boundaries, nontransitive geometry, unreachable/dead-end cells,
rounded ties, last-budget-transition, cancellation, memory-owner release,
and a broken/tampered receipt rejected by strict replay. A small independent
exhaustive oracle checks the sufficient proof against all complete paths.
After the RED tests, run the focused completeness/replay suites, neighboring
fingerprint and memory suites, frozen scalar/final replay comparisons, and
strict private corpus command. Record source hashes, terminal exits, XML,
receipt-version compatibility and the actual proportion of captured D-unmet
runs proved. Only a Sol-high proof review and verified production evidence
can mark a particular saturated D-unmet run complete.

## Gate decision

On this unchanged production source, the required command
`sh ./gradlew --no-daemon test --tests '*V022BCompletenessContractTest' --tests '*V022ProductionReplayTest' --console=plain`
completed with terminal exit 0 (`BUILD SUCCESSFUL in 3m 10s`). XML reports
17 completeness-contract and 49 production-replay tests, each with zero
failures, errors, and skips. The passing
`saturatedDesiredEightPassesApprovedBoundedStrictAlternativeGate`
test demonstrates the approved bounded-request interpretation, using a
hand-built replay wrapper around an actual solver result. This focused run
is separate from Task 4's frozen fingerprint comparison and does not
establish private-corpus fidelity or quality. The corrected two-class rerun
also completed with terminal exit 0 (`BUILD SUCCESSFUL in 2m 57s`); its XML
again reports 17 and 49 tests with zero failures, errors or skips and includes
the corrected bounded-request method names. After strengthening the modal-label
ownership oracle, the exact two-class command completed again with terminal
exit 0 (`BUILD SUCCESSFUL in 2m 43s`). Final-source XML reports 17 and 49
tests, zero failures/errors/skips, at 2026-10-07 10:48-10:50 UTC.

The current source has no proof that the D-unmet capped cases have no further
distinct admitted alternatives. **Production completeness for those cases and
stable-release completeness remain BLOCKED.** The design-only Sol/high rereview approved worker
revision `2734fcc`; its corrected document and tests were integrated as `809505c`.
This records design approval without claiming implementation of a production
certificate. D-reached bounded results retain their existing
accepted meaning. The approved safe test RC path remains independent. Runtime
model/effort was requested as Sol/high; effective metadata and token usage
were not surfaced.
