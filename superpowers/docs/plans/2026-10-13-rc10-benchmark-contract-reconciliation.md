# RC10 benchmark contract reconciliation

Status: APPROVED by the maintainer on 2026-10-14, including the numerical-policy
change and factual read-only fixed-receiver context extension described below.
The named new contract does not turn a changed-geometry run into a pass under
the original strict contract.

## Reviewed contradiction

The approved usable-release plan, Task4
(`2026-10-07-v022-b-usable-release-implementation.md:129`), requires at least
2x median total ordinary-action preview speed on both long cases and identical
geometry to RC6. `scripts/run-v022-benchmark.py` enforces identical selected
raster, final-geometry digest and publication kind across versions, then invokes
the typed comparator. The Java comparator also requires matching request
parameter hashes and complete native final-output component digests.

The approved bounded numerical-quality amendment deliberately changes profiles
and geometry. Exact-Kbest equivalence applies to identical current profiles;
it does not make an improved numerical pipeline identical to RC6. Independent
Sol contract audit found no explicit approved supersession of the old benchmark
requirement. A failed equality check must remain a failed original gate.

## Proposed narrow correction

Retain the pinned RC6 revision and exact five reviewed instrumentation files;
retain the captured cases, original OSM, selected source/raster/validity,
capture geometry, settings, permissions, ordinary budgets and offline-cache
requirements. Do not alter RC6, substitute a different case or raise a cap.

Retain at least one warm-up and three measured runs per case/version, at least
2x median TOTAL action-to-visible-preview speed on each case, and the existing
production cancellation/no-late-publication and cancellation-latency checks.

Require deterministic complete output/selected geometry within each version.
Each version's native input, parameters, receipts, component digests, selected
route and immutable edit plan remain bound and verified independently. Report
cross-version geometry and numerical-policy differences explicitly. Permit only
the already approved numerical-policy changes; do not generally ignore input
or parameter differences, and never rebind old outputs to current receipts.

Retain exact eager/optimized equivalence on identical current profiles as the
optimizer-specific oracle. Independently require current production quality,
final geometry/topology, confirmation, freshness and Apply/Undo/Redo gates.
Faster execution or an internally consistent archive cannot prove fitting or
Apply safety. The painted-connector expectation question remains separate.

## Implementation after approval only

1. Encode a named, explicit comparison contract; keep the old strict contract
   readable and testable. Record its identity in the benchmark report.
2. Add deterministic negative controls for changed raster/validity, source/OSM,
   settings/permissions/budgets, unapproved parameters, altered native bindings,
   nondeterministic within-version geometry, incomplete runs, slow execution,
   cancellation failure, and a bypassed production observer. Establish RED,
   then implement the minimal typed comparison and run neighboring tests.
3. Obtain independent review of the actual comparator/runner delta. Preserve
   existing strict replay rejection and all native-output integrity checks.
4. Run both complete paired cases and cancellation probes, inspect exact
   receipts and quality/Apply evidence, and update the release matrix. Historical
   diagnostic timing is not automatically an approved gate pass. No RC10 until
   every applicable release gate passes.

The original-contract run failed as detailed below. Its baseline receipts
provide diagnostic evidence; the failed gate is not waived.

## Additional capture-contract evidence, 2026-10-13

The original run has now failed before candidate alignment with `Original live
network context differs` (BenchmarkHostMain:168). N1 baseline completed one
warm-up and three measured ordinary-action previews (130.289, 133.957, 131.603,
135.523 seconds); their raster and final-geometry hashes match. No candidate
preview was published, so there is no paired speedup result or passed gate.

An actual current capture of the original OSM and selection proves a strict
extension: old747/current992 primitives, eight added highway ways and237added
nodes, no missing keys, and no changed shared primitive payload or incoming
watch. Collision/edit regions, selected editable occurrence ranges, external
ports, source generation and completeness flags match. No edit or movement
authority was added; five selected nodes lose those permissions, while242nodes
gain protection (237new context nodes plus five selected nodes).

Independent Sol source/history review attributes this to the approved fixed-B
receiver geometry and fixed endpoint-island behavior added after RC6 in
fda2d8a. Current LiveBPreviewService:1046–1069 recaptures fixed islands;
NetworkSnapshotCapture:284–307 includes receiver ways and one factual referrer
hop; LiveBPreviewService:894–933 freezes their selected approach nodes. Actual
partition capture now proves fixed occurrence ranges0–2 and105–108, both
PARTICIPATING_RELATION, and a movable middle range3–104 with engine input2–105.
All five removed movement permissions belong to these fixed sections. Local
evidence: build/rc10-display-proof/context-n1-partition.log.

The revised approval request explicitly covers both these stronger safeguards
and the numerical output change; it replaces the earlier numerical-only
benchmark question. It was approved on 2026-10-14. A correction must prove unchanged
original OSM/selection/raster and shared payloads/watches, current-only context
objects bound to that same OSM and read-only, no additional edit/movement rights,
unchanged authorized regions, and exact frozen-island preservation. Record the
actual traced/frozen intervals and publication kind instead of pretending the
native capture contracts are identical. Retain current semantic-witness,
freshness and Apply guards. No baseline modification or old-output rebinding.

The second case has no fixed partition and matches all909original primitives,
incoming watches and every closure component exactly. Local evidence:
build/rc10-display-proof/context-n2-partition.log. The original two-case runner
is queued again with only case order reversed, so this unaffected case can run
first under unchanged rules. N1 remains mandatory and its failure is not waived.
Measurements wait until full testing and GUI smoke finish; both complete case
inventories, warm-ups/repetitions and original equality/safety checks are retained.

The historical original-contract results remain failed. The original strict
comparison remains available as `rc6-identical-v1`; manifests without an explicit
contract retain it. The approved comparison is named
`rc10-approved-numerical-readonly-v1`. It retains exact comparison within each
version, verifies current single and interval output through native strict
production replay outside the action timer, and reconstructs the stored native
edit plan independently. The report retains actual cross-version geometry,
publication kind, parameter identities, output components and traced/frozen
interval evidence. Current-source factual recapture binds read-only extensions
to the pinned original OSM. The exact reviewed managed B parameter identities
are pinned; a future numerical policy requires a new approved contract.

Complete paired performance/cancellation runs, independent comparator review,
full public testing and release artifact gates remain required. Implementation
or focused test success alone is not a benchmark or release pass.
