# Reviewed contextual topology correction

Authority: approved 2026-10-07 Engine B usable-release plan, Task 2.
Initial local implementation: `c9e03f2`, 82 tests GREEN but NOT approved/integrated.
Independent Sol/high review found overbroad reattachment preflight and missing
adapter provenance coverage. Focused Astra/high proof consultation then rejected
the clipped-endpoint safety argument with two executed deterministic failures.
Requested model routes are recorded; runtime metadata/allowance not surfaced.

## Blocking synthetic counterexamples

All coordinates below are synthetic `(latitude, longitude)` pairs, not private
capture evidence.

1. Frame origin `(0,0)`, SW `(-1e-5,-1.5e-10)`, NE `(1e-5,1.5e-10)`;
   changed segment `(2e-13,0)` to `(-9e-6,0)`; context `(0,-.01)` to `(0,.01)`.
   Strict frame creation and changed inset pass. After clipping, the actual
   classifier returns NONE despite a perpendicular crossing. Its product threshold
   suppresses the crossing when the contextual chord becomes short.
2. Frame origin `(50,14)`, SW `(49.998999000000005,13.9988)`, NE `(50.0011,14.0013)`;
   changed segment `(50,14)` to `(49.9995,14.0005)`; context `(49.99,13.98)` to
   `(50.01,14.02)`. Distinct OSM identities are required. Changed inset passes,
   but rounded clipped interpolation produces NONE instead of an endpoint-to-interior
   touch. A separately certified same-origin reference frame admitting the original
   context endpoints returns VERTEX_TOUCH. Production certificate expansion is not
   authorized by that test oracle.

## Superseding implementation contract

Use original unwrapped geographic chords as topology authority. Robust/exact
orientation signs detect proper crossings before tolerance degeneracy handling.
The local frame is affine with positive axis scales on its admitted longitude
branch, so incidence can be proved without projecting outside points. Rounded
artificial endpoints must not control safety or gain OSM endpoint identity.

Retain both original physical tolerance units: coordinate/overlap `1e-8 m` and
orientation `1e-8 m²`. Derive conservative separation/proximity bounds with
distinct east/north scales, both segment lengths and bounded binary64 uncertainty.
Exact incidence alone is insufficient for existing conservative contact behavior.
Only proved separation permits NONE or context exclusion. Numerical, branch,
antipodal, corner or interval ambiguity must block explicitly. Changed geometry
still uses strict frame admission; no extrapolation or authority expansion.

Scope the editable reattachment conversion/preflight to its actual component;
unrelated far context remains in the full read-only collision snapshot. Preflight
must precede incident reconstruction. Genuine unsupported components use the
typed manual path. Original identities/indices and unselected continuations stay
exact; crossing or overlap cannot be excused by a separate shared endpoint.

## Required evidence

Retain both counterexamples through actual adapter decisions, plus reversal,
asymmetric clipping, real/artificial endpoint, near-tolerance, short-segment,
corner/tangent/overlap/antimeridian and safe-far-context controls. Independently
review the derived separation/uncertainty proof and source delta. Rerun the four
required classes on corrected final source before integration. A passing test
count alone cannot close this proof gate.

Detailed temporary proof artifacts are in this plan's SDD
`task-2-astra-proof.md`; this durable correction preserves the decisions and
fixtures independently of that scratch workspace. Implementation/re-review pending.

## Reviewed sole-shared-contact correction

The revised exact-chord implementation also failed independent review: an early
VERTEX_TOUCH could hide near-parallel overlap; moving the tolerant-collinear
branch earlier then rejected safe opposite rays with zero overlap. A focused
Astra/high follow-up specifies witness aggregation, rather than another enum
priority change. The bounded packet is SDD `task-2-shared-contact-contract.md`.

For each consistent longitude branch collect all four endpoint-to-other-segment
witnesses (exact, outward tolerant and conservative proximity), plus proper
crossing, positive exact/tolerant overlap and uncertainty flags. A tested endpoint
is owned only when its original real OSM key and exact branch coordinates coincide
with an endpoint of the other segment. Union all branches. An owned shared contact
cannot erase a remote witness or another branch's contact; artificial endpoints
never own incidence. Any blocking flag or unowned contact blocks. Only an exact
shared incidence whose entire witness set is owned can receive the exemption.

With shared S=(0,0), changed S→(1,0) and context S→(1,1e-9) in synthetic metric
units, positive near-parallel overlap blocks. Context S→(-1,1e-9) has exactly zero
dominant-axis overlap at S and remains allowed when no remote witness exists.
Distinct OSM keys at coincident coordinates remain blocked. Exact positive overlap
blocks however short. Axis/branch or interval uncertainty remains fail-closed;
correlated projection of the same shared coordinate must be preserved.

Remote witnesses must be evaluated independently of all-four collinearity.
S=(0,0), U=(1,1), V=(0.5,0.50000025), with chords S→U and V→S, provides a
remote outward-tolerance witness even when another orientation fails its outward
bound. The fixed-epsilon exact affine theorem differs from these origin-dependent
outward predicates; exemption does not rely on that delicate implication.

Retain reversal/pair-order permutations, perpendicular shared safe controls,
positive-overlap and opposite-ray controls, remote/distinct-node contacts,
cross-branch aggregation, antimeridian identity and degenerate refusal. Source
and proof receive scoped independent review before another long regression gate.
This corrects Task 2's safety mechanism without narrowing its scope or waiving
the normal-user Apply requirement.
