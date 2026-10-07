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
