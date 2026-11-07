# Public wide compact quality gate

The required `public-wide-compact-quality` CI job runs
`sh gradlew --no-daemon --max-workers=1 v022WideCompactQuality` with Java 17,
a pinned JOSM 19555 host, headless graphics and a 512 MiB numerical process.
It runs five fixed public analytic scenes and reversed drift, in that order,
and stops on the first failure. This is separate from public JUnit, GUI,
Legacy, private-control and repeated performance qualification.

The dedicated `Wide diagnostic continuation` workflow is restricted to push
on `codex/v022-wide-diagnostics-20261107`. Its separate
`v022WideCompactDiagnostic` task records completed reference and candidate
evidence after typed analytic or physical-comparison quality failures, then
continues to later scenes only when native execution and authority checks are
valid. It is diagnostic-only, always reports `releaseQualified=false`, and
exits nonzero if any required assessment fails. The ordinary task and Build
workflow remain fail-first and unchanged. Its distinct artifact includes the
same frozen manifest; this report cannot qualify a release.

`src/test/resources/public-wide-compact-v1.properties` freezes all six
canonical input hashes and the physical specification. Fixture arithmetic uses
StrictMath. Preflight checks every hash before preparing any scene. The
manifest-generation task constructs raster/request/network values only; it
does not prepare profiles or run inference. No scene changes are permitted
after this freeze to obtain a passing result.

Native pitch is 1.556065358178362 m, ordinary decision width is ±25 m, and
TraceBudgets.defaults(), K32/8, default evidence parameters, Precise Shape
and disabled cleanup apply. The raster contains native-center analytic samples,
valid context beyond the decision boundary and no added blur or oversampling.
The source endpoints are fixed on the analytic corridor. The other scene
extents are 62 native pitches. The faint scene retains the original physical
3 m apex at x=50 m, 12 m support, 0.08 intensity and 0.65 m sigma.

One PRE-FREEZE analytic sampling/fixture-validity adjustment extended only its
right straight tail from 100 to 100.5 m. This consumes the single extent
allowance. It was chosen before any wide preparation/inference/quality result;
it does not establish that a 100 m scene cannot represent the bend within
native localization uncertainty. The existing faint-apex regression remains
unchanged. A pre-freeze authority-construction correction also keeps movable
interior source nodes out of the disjoint removable-node set.

The ordinary candidate request records INVENTORY_PREP_V4 and runs through
ProbabilisticTraceEngine and ModernTracePipeline, including native finalization,
assignments and topology assessment. The harness requires a factual original
dense bound above the unchanged 128M score budget, actual omitted nonmandatory
cells, all original profile rows and mandatory/fine-mode cells, 0/1 refinement,
truthful cumulative native work, conditional posterior semantics and zero
retained owner bytes. It rejects a full branch, an empty route, an oracle
masquerading as the candidate or a false COMPLETE/global posterior claim.

Each scene first tries the historical full production implementation on the
same frozen physical evidence with its known exact parameter identity. A
RESOURCE_LIMIT is retained and is not a quality baseline. Only then may the
separately named full-lattice production-backend numerical oracle run. The
oracle uses CACHED_EXACT_V4, every original cell/unary/quadrature, unchanged
budgets and the real production preparation/materialization/finalization.
Its execution identity is
`full-lattice-production-backend-numerical-oracle-v1/cached-pair-stream-merge-scored-work-v4`.
It is neither strict replay nor the candidate/default backend. There is no
preference, system property, public selector or Format-15 oracle policy.
The minimal package-private probe is thread-owned, rejects nesting and restores
its ThreadLocal in finally; local tests cover exception and foreign-thread
cleanup, exact tiny native output/work equality and actual resource refusal.

The independent analytic checks require strong p95 ≤0.5p and certified maximum
≤p; faint/noisy geometry remains within p, with ≥90% faint apex amplitude.
Remote drift must follow the >20 m central ridge, the hairpin must retain both
ordered legs/apex, parallel corridors must not switch or average, and the
diffuse mean must not gain resolved alternating turns (adjacent extrema
separated by ≤4p with ≥0.5p amplitude). Native authority and hard safety
override distance averages.

Against a completed suitable dense reference, raw same-profile and final
geometry limits are p95/max 0.25p/0.5p for strong scenes and 0.5p/p for weak
scenes. Raw p95 is the left empirical 95th percentile. Final p95 combines both
directions with trapezoidal arc-length weights and the left empirical quantile.
Maximum is certified by sampled maximum + half the maximum sampling gap
(gap ≤p/256), plus p/10000 analytic chord error when applicable and a 1e-9 m
floating-point guard. Reversed drift uses the strong physical limits.

Public artifacts retain frozen inputs, named native outputs/work, all native
raw path order/energies/original state indices, dense census, retained indices,
raw/final geometry, assignments, quality/topology, counters and ownership.
The summary always lists six scenes and identifies the first failed stage;
later cases remain NOT_RUN. A refused dense oracle is unavailable, never a
skip or permission to raise a cap.

Local evidence currently covers only compilation and tiny seam/hash checks.
The first numerical CI run42329157376 stopped at remote-drift reference
suitability: historical full inference refused its cap; the named cached-V4
oracle completed66,143,412scores and one review route, then the native edit-plan
adapter rejected fixture authority. The fixture incorrectly included its
protected fixed endpoints in editableExistingKeys. This is an authority
metadata error, not a passing quality comparison.

The corrective manifest changes only that metadata: editable keys become the
selected way plus ordinary movable interiors, while fixed endpoints remain
protected/read-only. Frozen network/request/input hashes consequently change.
Raster samples, evidence/frame/decision region, source and analytic geometry,
chainage, settings, parameters and all numerical/quality limits remain exact.
An independent failing authority-structure regression precedes the correction;
the fast test also invokes the actual native adapter boundary on all six
requests without preparing profiles or solving. Private before/after per-scene
bit digests preserve the physical identity proof and original CI evidence.
This explicit metadata-only manifest correction requires controller/Astra
review before new numerical CI. It does not consume another extent adjustment
or authorize physical fixture tuning.

The six-scene numerical gate has not run locally and remains unqualified until
the corrected dedicated CI job completes. Wide approximation quality, private high
inference/comparison, GUI, cancellation and repeated latency gates remain open.

## Separate performance-retention assessment

`wide-compact-performance-retention-v1` is a pure, test-side assessment
contract. Its caller must provide execution, identity, native-authority,
branch, safety, and measurement evidence that an actual runner independently
validated. The helper does not establish those facts or qualify a release;
`releaseQualified` is always false. Missing or invalid measurements remain
unavailable.

For suitable dense references, strong scenes use p95/max limits of 0.25p/0.5p
and weak scenes use 0.5p/1p for both raw and final geometry. Supported-bend
retention compares candidate and reference amplitudes on the same declared
physical interval and baseline and requires at least 90%. Absolute analytic
amplitude debt is reported separately and does not rewrite the relative
retention result.

Only `diffuse-three-strand` can receive `UNSUITABLE_REFERENCE`, and only when
the reference fails its independent geometry oracle while the candidate passes
its independent geometry and runner checks. Any measured distance failure
remains `FAILED` alongside that disposition. The exception never bypasses
candidate execution, integrity, native authority, branch, or safety checks.
This assessment does not alter the diagnostic runner's FAILED status, existing
assertions, or release gates; root-owned runner integration and all other
performance qualification remain pending.

The retention helper also keeps the frozen analytic oracle by scene strength:
strong analytic p95 is bounded by 0.5p and weak analytic p95 by p; both retain
certified maximum ≤p. Missing anti-zigzag evidence is unavailable. The faint
absolute-amplitude assertion is explicitly applicable only to the frozen
`faint-apex` scene and requires both candidate and dense-reference outcomes.
Candidate-only, reference-only, and shared baseline debt remain distinguishable. Absolute candidate/reference values carry the same declared interval and baseline identity as the retained-bend measurements; a mismatch or missing required evidence makes the assessment unavailable. These are reported
assessment outcomes, not added release gates.

## Public performance-retention assessment

The separate `v022WideCompactRetention` task runs the six frozen scenes through
actual production full/reference and compact/candidate executions in one
invocation. It reuses those in-memory native outputs for branch/safety,
analytic geometry, raw/final fidelity and faint amplitude measurements; the
historical full attempt and explicit cached-V4 oracle fallback are labeled
separately. It writes a versioned `public-wide-compact-performance-retention`
report beside the unchanged frozen manifest. A suitable-reference retention
PASS or shared baseline-debt result, plus the narrowly classified diffuse
unsuitable-reference result with its distance still FAILED, can pass only this
named task. Missing evidence is UNAVAILABLE and exits nonzero; mandatory
failures are FAILED and exit nonzero. The report always says
`releaseQualified=false`.

The manual `Public B performance retention` workflow has a `workflow_dispatch`
trigger and a push trigger restricted to
`codex/v022-b-retention-qualification-20261107` so the reviewed task can be
launched from a dedicated branch. The original fail-first quality task and
FAILED diagnostic continuation retain their current commands, output paths,
thresholds and statuses. No overall release or performance qualification
follows from this assessment alone.

The bounded local orchestration regression uses a separate eight-profile
straight scene of length `6.5p`, with the same native `p` pitch and complete
`+/-25 m` profile extent. Its historical full reference uses ordinary defaults;
its candidate alone records the test-only `96/8M/1M/32/8` budget. Full admission
must naturally exceed the score bound, and the actual V4 compact run must still
finish with native work, original-cell mappings, zero closed-owner bytes,
safety and analytic geometry evidence. The default public proof rejects this
custom budget. The full-policy negative control reuses the actual reference
output and must fail the compact runner gate. These controls do not change the
six frozen scene inputs or execute their numerical gate locally.

Summary JSON is parsed in regression coverage for faint shared amplitude debt
and partial/unavailable outcomes. Unmeasured or non-finite metrics serialize as
`null` with unavailable evidence, and diagnostic text is JSON escaped.
