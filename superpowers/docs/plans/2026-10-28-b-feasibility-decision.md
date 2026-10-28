# Shared-position model: practical evaluation

Date:2026-10-28. Evaluated implementation:`488ed7d714f25b57fb49e9df82134d893653df24`.
Evidence class:`EXPLORATORY_QUALITY_ONLY_V1`.

## Recommendation

Stop pursuing this certified implementation as the route to a usable tracer
under the approved constraints. It did not finish a single representative
attempt or reach the path solver after the numerical repairs, exact source
reuse and removal of deferred proof-cost enforcement from the exploratory
entry. This is a practical implementation failure, not a measured rejection
of the underlying mathematical model's tracing quality.

Independent Sol outcome/completion audit supports this stop recommendation,
with no numerical must-fix identified. This recommendation must not be reported
as a working model, a full quality pass or a release.

## Actual original-case results

[CI40683227458](https://github.com/holubp/josm-wayheatmaptracer/actions/runs/40683227458)
completed successfully as orchestration. All eight native receipts are
INCONCLUSIVE, have solverInvocations0, fail in SHARED_TABLE and close their
tracked roots to zero. Five hit the300s cooperative deadline; three fail the
retained64MiB tracked-memory envelope. No deferred proof-envelope overflow
was the reported blocker. Qualified prospective envelopes are UNAVAILABLE.

The controller independently checked host/context/code/settings/snapshot
bindings with the existing runner, verified exact screening membership and
recounted the eight raw receipts. The monitor initially misreported4/4; that
prose is superseded by the verified5/3 count below.

| Case | New implementation result | Elapsed seconds | Minimum tracked memory base, bytes |
|---|---|---:|---:|
| strong-modulated-versus-quiet-faint | Preparation timeout; no path |300.006|—|
| thin-strong-sharp-bend | Preparation timeout; no path |300.009|—|
| switchback-thin-strong | Memory envelope refusal; no path |6.112|67,510,208|
| faint-twelve-metre-bend | Preparation timeout; no path |300.004|—|
| thin-three-strands-intermittent | Preparation timeout; no path |300.009|—|
| thin-strands-merge-separate | Preparation timeout; no path |300.009|—|
| same-trail-three-strong | Memory envelope refusal; no path |7.862|67,867,072|
| same-trail-three-separated-strong | Memory envelope refusal; no path |11.051|69,416,392|

Memory limit:67,108,864 bytes. The refused one-node pool would additionally
require352 bytes. These are tracked reservations/envelopes, not measured
process resident memory or a claim about the theoretical model's minimum
memory. Increasing a limit was not used to turn a failure into success.

The independent source audit also establishes that tuning gain/eta cannot
avoid these three refusals in the current implementation. Original profiles
and lattices are built before gain; the kernel and observation adapter retain
all original cells/maps and dimensions; the table checks that identity. The
memory expression uses retained charges and dimension-derived table/workspace/
solver/scratch storage, not gain-dependent costs. Eta is consumed only after
table construction. A different gain either reaches the same failed memory
admission or fails earlier numerically, which cannot qualify a configuration.
Therefore another128-attempt sweep cannot produce an all-required-case
passing configuration without an implementation/reservation change. This is
not a claim that the mathematical model has an irreducible64MiB requirement.

Timeout stacks include actual component/max/source work and, for the faint
bend, polynomial log-integral moments. Unit work reductions from caching did
not establish a completed-case speedup. There is no returned geometry on which
to compute new-model centering, corridor hopping, supported bend retention or
relative path-quality scores.

## Authentic reference comparison

The approved source117/defaults offline Engine B reference returned paths on
the same eight snapshot hashes in CI40599959077. This is not a claim about an
exact historical user-tested JAR. The offline reference had the separately
approved1,024M score/900s cooperative/960s external allowance; candidates kept
128M scores/300s/360s. Reference timings are therefore not evidence that all
eight fit an ordinary production budget.

| Case | Reference elapsed seconds | Existing hard oracle |
|---|---:|---|
| strong-modulated-versus-quiet-faint |52.247|PASS|
| thin-strong-sharp-bend |115.998|PASS|
| switchback-thin-strong |316.374|FAIL|
| faint-twelve-metre-bend |86.168|PASS|
| thin-three-strands-intermittent |482.981|FAIL|
| thin-strands-merge-separate |298.859|FAIL|
| same-trail-three-strong |405.517|FAIL|
| same-trail-three-separated-strong |270.598|FAIL|

This reproduced the reference's sparse/representative-shape weaknesses. The
new implementation supplied no geometry to demonstrate an improvement or
non-regression on intense traces. Do not substitute unit fixtures or a plausible
reference line for an actual candidate path.

## Implementation and verification delivered

- Physical response, measured rows, context/connection integrals and actual
  solver consumption exist; complete tiny source-to-solver and mutation controls
  pass. No alternate toy engine was used for the original-case attempts.
- Retained numerical fixes cover positive underflow bounds, closed-cap branch
  unions, tiny valid raster tails, later maximum recovery and exact self-context
  zero integrals. Source cache tests preserve carrier bits, invalid support,
  context/node resets, cancellation and ownership.
- Fixed cache storage is funded before capacity selection. The explicit
  exploratory entry retains actual owner allocations, source validity, numerical
  errors/unknown support, complete graph space,96states,8Mpairs,128M scores and
  attempt deadlines; prospective qualification bounds are unavailable.
- The54-test Java preflight passes with0failures/errors/skips; Python27 normal
  and27 optimized tests and protocol validation pass. Independent source review
  accepted the coherent implementation before original-case CI.
- Full project/source/runtime/total-process-memory qualification is not claimed.
  The production plugin and existing engine were not replaced or released.

## Approved-plan disposition

| Quality-first task | Disposition |
|---|---|
|1: freeze protocol and authentic reference|Implemented and tested; eight actual reference paths, suitable suite, unchanged frozen input identities.|
|2: real exploratory consumer|Implemented and tested, including explicit separation from deferred proof accounting;54-test preflight and independent source audit.|
|3: original screening|Initial128 attempts plus two8-case repair batches completed without a solver invocation. Latest repaired implementation tested at gain1/eta1; no qualifying configuration selected. Quality evidence remains INCONCLUSIVE, not a measured geometry REJECT.|
|4: freeze/confirm on all32|Not executed: no eligible candidate. The remaining24 reference cases and full32 confirmation are not passed or waived.|
|5: inspect actual geometry and decide|No new-model geometry exists to inspect. Practical stop decision independently supported; mathematical quality unmeasured.|
|6: resume original qualification/release work|Not executed; no positive quality decision justifies it. Original requirements remain deferred/open, not complete.|

Actual original-case attempts spent:168 of208, leaving40 unspent. Failed
preparation jobs that never invoked an original case added no case attempts.
No more parameter sweeps, limit increases, original cases or releases are
authorized by this report. A future continuation would need a separate concrete
decision about numerical architecture/resource policy and its evaluation budget.

Local native evidence: `build/shared-preparation-ci-488ed7d-final/`, including
`root-verified-summary.json`, receipts, host files and bound context. Historical
reference evidence: `build/shared-feasibility-extended-summary/`. These are
generated artifacts; their absence in a fresh checkout is not a zero result.
The [committed compact results](../evidence/2026-10-28-b-feasibility-results.json)
retain all eight outcomes, matching snapshot identities, reference results,
resource limits and native receipt/host/context hashes without private captures
or machine-specific command paths.
