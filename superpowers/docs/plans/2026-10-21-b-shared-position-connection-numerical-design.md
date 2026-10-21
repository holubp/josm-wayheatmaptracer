# Shared position and connection evidence — numerical candidate

Date: 2026-10-21. **Draft for review; no implementation or empirical-run authority.**
The user approved developing this direction after reviewing actual switchback
failures. This document supplies a concrete, falsifiable numerical candidate,
not a claim that the candidate works. Baseline behavior, the original goal and
all original observation, safety, comparison and release gates remain binding.

Related authority: `2026-10-16-b-observation-contract-specification.md` (OBS01–20),
`2026-10-16-b-observation-identification-plan.md`, original v0.22 implementation
plan, and specifically corrected later source/numerical contracts. This is a
successor proposal to the rejected area-presence/core-tent plus G-only family,
not a replacement for the complete implementation/release plan.

## 1. Outcome, evidence and boundaries

The tracer should follow the supported overall course, retain tight switchbacks,
and avoid jumping between different legs or oscillating among sparse recordings.
The original way is an acquisition/selection reference, not a truth curve or a
permitted directional/proximity prior. Preserve the full configured +/-25m search.
There is no requirement to identify each individual recording as a separate
track before tracing the representative course.

Actual CI39545054428, source78296ba, returned57-point routes for five thin-line
cases. The frozen eta0/null-potential identified control passed faint and failed
strong, three-recording, five-recording and separated-recording geometry. This
is raw1 experimental inference, not ordinary native32/8 Engine B. Strong has a
12.36m jump and three-recording a13.08m jump, both with diagnostic sampled median
scalar background0.02. Approximate two-path cost decomposition reproduces stored
Java energies: pointwise unary gains outweigh increased soft turning costs.
Endpoint orientation is undirected and does not inspect the connecting pixels.
Region containment does not prove heatmap connectivity.

Those archives and failures remain immutable. The user accepts faint and requires
strong/three/five to work; proposes separated recordings as an optional stress
case. A prospective protocol must explicitly record that disposition and keep its
historical failure. Do not substitute a cleaner case for a required failure.

The earlier G feature exists only in completely established two-flank gap strips.
Its zero-feature intermittent/faint/noise counterexamples mean eta increases are
not a solution. This proposal uses a different shared source response and changes
both conditional position and connection evidence. It does not resurrect endpoint
reference deficits or raw total variation, impose a branch lock, add a preliminary
MAP run, average completed geometries, or alter extraction/admitted states.

## 2. Frozen inputs and capability contract

Borrow only the already acquired selected scalar field, validity/interpolation
masks, certified metric transform, original physical profile mesh, original
elementary mode facts/receipts and admitted cells. No tiles, alternative palettes,
hidden capture, private labels, truth curves or selected route enter construction.
Palette semantics remain upstream of every filter and feature.

Use a fixed factual analysis grid with centres X(v), orthogonal equal-length
metric basis vectors of length rho>0. Bind its origin, axes, dimensions, validity
and source identity. Use the actual input grid when this is factually available;
otherwise a certified fixed metric/rendered grid is a separately named capability,
never reconstructed native pixels. Native pitch alone does not prove a footprint.
Unsupported skew/anisotropy, absent grid/correlation binding or an inaccurate
transform makes this candidate capability UNAVAILABLE, not a successful fallback.

Initial synthetic experiment capability uses the existing exact affine input-grid
receipt and evaluates the authoritative retained scalar field at those actual
centres. It does not recover native scalar values from an unavailable source plane.
Production managed/visible capability needs its own factual physical-grid binding,
source-resolution-aware rho and alias tests before promotion. A fine output grid
may not masquerade as new independent source observations or change rho silently.

At every analysis vertex evaluate the authoritative bilinear scalar response V_analysis(v)
using its real support/mask. Missing is not scalar0. Outside acquisition is missing.
An absent original mode remains absent; no new ridge extraction or association is
performed. Grouped/ambiguous receipts cannot be invented as elementary evidence.
V_analysis is a feature sampled from the retained authoritative field. It is not
the original native input plane. Where the original raw input raster is separately
retained, bind its own frame/hash and preserve it for browser visualization;
otherwise show the available raster with its actual rendered/resampled label.

## 3. One correlated source response

Use separable B3 weights [1,2,1]/4 on the fixed analysis grid, after scalar
mapping and the own-mode excess operation defined below. All nine contributing
V_analysis vertices and their interpolation support must be valid; otherwise
that filtered response is unavailable. There is no invalid-sample renormalization,
lower-zoom source, max-dilated detection image or extra confidence. The filtered
response is a deterministic correlated feature, not a new measurement. Preserve
V_analysis separately; do not replace a separately available original raw browser
raster with a feature or describe feature values as recovered native.

For original profile i and uniquely owned elementary mode m retain:

- a_i and unit normal n_i from its factual metric profile; t_i is the orthogonal
  unit tangent (-n_y,n_x), whose sign does not affect the construction;
- own raw centre c_im, own background b_im, own RMS/filter descriptors and exact
  RAW/B3/B5 centre/core receipts;
- own shoulder bracket [l_im,r_im], preserving its complete/incomplete status;
- physical profile quadrature q_i from original monotonic source chainage.

Use the existing half-neighbour-span quadrature, including its endpoint and
single-profile conventions; do not infer physical span from raster spacing.

Require available original RAW and at least one original filtered-stage receipt.
Let N_im=max(ownRMS,ownFilter), with descriptor units scalar, not a noise SD.
Let d_im be the largest absolute available original-stage-centre disagreement
from c_im. Let w_im be half the original RAW high-core extent, in metres.
Define h_im=w_im+d_im+rho/2 and L_i=max(rho,q_i). Missing required receipt means
unavailable contribution, not guessed h or background. No own-peak maximum,
background, extent or descriptor is borrowed from a neighbouring mode.

For physical point x define u=(x-a_i) dot n_i and z=(x-a_i) dot t_i.
K_h(s)=max(0,1-|s|/h), for h>0. At analysis vertex X(v), define

```
e_im(v) = max(0, V_analysis(v)-b_im-N_im)
J_im(v) = B3_2D(e_im)(v)
R_im(v) = K_Li(z) * K_him(u-c_im) * J_im(v)
```

Each e_im tap is capped to the mode's own shoulder bracket in its physical
profile coordinates: outside that component support its numeric response is0,
not another mode's excess. Within the bracket, missing V_analysis is unknown,
never an observed0. J_im requires the complete valid source stencil; do not fill
an invalid tap using a known outside-component zero. This conservative source
rule distinguishes source support from that component's geometric support.
R_im is available only when J_im is available and u lies in the mode's original
own shoulder bracket; otherwise record unavailable/outside-support separately. Numeric0 is
used for an observed zero response or outside that component's support. It is
not positive support. Finite scalar response keeps 0<=R_im<=1 in real arithmetic.
The shoulder cap prevents a positional component from claiming a separate mode
across its intervening valley. The broad source response J alone does not add a
component or grant DIRECT ownership.

Each R_im is a field on the fixed grid, with bilinear interpolation only through
fully available cells. Retain its original receipt lineage. Duplicate aliases of
one raw receipt within a profile cannot create another component mass: conflicting
elementary claims are unavailable, as in the existing source-ownership guard.

Define the shared positional field F(v)=max_im R_im(v), with0 when all applicable
contributions are observed zero/absent and explicit unknown status when required
source data are unavailable. An unavailable receipt may not be silently skipped
as a known absent contribution: its support must be conservatively bounded and
marked unknown, or the field capability is unavailable when no such bound can be
obtained. An all-unknown field is not a successful zero field. The max combines correlated views without summing
repeated profile evidence. It is not proof of statistical independence, complete
track identity or representative-centre correctness. Do not infer confidence
from the number of contributing profiles, modes or filters.

Implementation must stream bounded contributor footprints into the global field;
do not retain a profiles-times-modes-times-raster tensor. Retain per-mode data
needed for local evaluation as facts, not full per-mode raster copies.

The excess-before-filter order is normative. Applying the original raw-sample
nuisance threshold after B3 can erase a thin faint feature merely because the
filter lowers its peak. For example, background1/50, nuisance1/25 and one valid
pixel of value2/25 give raw excess1/50 and two-dimensional B3 centre excess1/200.
Filtering scalar values first gives centre7/200, below background+nuisance3/50,
and would produce0. This exact arithmetic counterexample motivates the chosen
order, not a new noise calibration. No claim that every positive impulse is a
coherent trace follows: the original fleck/noise and geometry gates remain.

## 4. Joint measured masses and conditional densities

For each original admitted profile/cell s evaluate its own component field
r_im(s)=R_im(a_i+n_i*u_s), using the same fixed-grid definition and complete-cell
support, and explicitly set this component's response0 outside its original
own shoulder bracket even if grid interpolation would extend a boundary value.
Unknown source support gives no measured density. Never substitute C
from section5 for r, and never use a chosen route or truth curve.

With cell widths d_s and reference length L0=1m, define

```
A_im = sum_s r_im(s) * d_s                       [metres]
H_im = gain * A_im/L0                           [dimensionless]
f_im(s) = r_im(s)/A_im                          [1/metre]
q_im = R * H_im/(1+sum_m H_im)
q_0  = R/(1+sum_m H_im)
p_i(s) = q_0/W_i + sum_m(q_im*f_im(s)) + sum_c(q_c*f_c(s))
unary_i(s) = -log(L0*p_i(s))
```

W_i=sum_s d_s spans every original admitted cell, including invalid image support.
Frozen censored masses/densities retain R=1-sum_c q_c and their existing meaning.
The measured mass and conditional density derive from one integrated response,
rather than shoulder excess for mass plus an unrelated core tent for density.
The correlated physical core kernel supplies localization uncertainty without
turning a raw winning pixel into a precise centre or narrowing to a subpixel grid.

If A_im=0 or no admitted valid support exists, measured mass is0, its diagnostic
record remains, and no f division or0*undefined is evaluated. Positive
underflow/nonfinite inputs remain numerical failure. Preserve existing stable
log-sum-exp conventions, permitted negative unary, actual cell measures and
exact-anchor zero unary/log measure. An arithmetic log floor is not measured mass.

No direct-span multiplier is introduced. Existing direct spans remain diagnostics
and unchanged baseline consumers; they are not independent likelihood factors.
The proposal does not promise that overlap/noisy recordings already yield the
correct representative centre: this is a mandatory empirical geometry gate.

## 5. Source-bound connection deficit

Nearby positive evidence supplies a *negative-context* envelope, not positional
support. Let R_context=4*rho. At fixed analysis vertex v define

```
C(v) = max_w [ F(w) * max(0,1-distance(X(v),X(w))/R_context) ]
```

where w ranges over analysis vertices within R_context, including v. Require
the complete declared neighbourhood/source footprint before claiming C available;
invalid or uncaptured support is not dropped to manufacture an observed maximum.
On an orthogonal equal-pitch grid at most81 candidate vertices are inspected.
C>=F>=0 in real arithmetic. This envelope is never fed to component densities,
does not fill a positional valley, and is not an all-color aggregate visualization.

For the same gain used in section4 define at valid vertices

```
D(v) = log1p(gain*C(v)) - log1p(gain*F(v)) >= 0
```

Let D(x) be bilinear inside cells whose four D vertices are available. The fixed
4rho context is a source-resolution feature radius, not a minimum bend wavelength,
a lateral search limit, a trace-merging radius or a newly certified uncertainty.
Its adequacy is falsifiable; no claim that a finite local context excludes every
detour. Source-field boundary/unknown escape is a required adversarial test.

For each original adjacent-state chord e, use its *actual physical geometry*:

```
J_e = integral_e D(x) d_length/L0
E_new(S) = E_new_local(S) + eta * sum_e J_e
```

E_new_local replaces only measured unaries through section4; original guide,
orientation, turn terms, temperature, integration measure and exact inference
stay fixed. The new edge is a shared-image CRF feature, not an independent edge
likelihood. Its source construction is frozen before any inference and identical
for every route under one parameter configuration. Unknown lengths are exported
separately and add no fabricated dark/positive support. Qualification must detect
a selected path escaping through missing/unknown fields; passing resource counts
or a finite energy cannot qualify such a result.

In contrast to old G, D does not require two matched flanks or a completely
identified between-mode strip. In contrast to an endpoint-reference deficit, D
is defined everywhere on its available source domain before endpoints are known.
It compares the local response with source-owned neighbourhood context, never
with the proposed edge's endpoint brightness. In contrast to a raw TV penalty,
it integrates observed positional deficit, not intensity oscillation/reversal.

The state graph and every alternative remain. A genuinely connected bright turn
can remain low deficit; a chord cutting its dark interior can be costly. A coarse
chord between genuine apex samples may still cut a corner: source-resolution/
sampling-phase apex tests must quantify this, not grant a new blanket turn bypass.
Do not use final geometry repair or smoothing to rescue a failed candidate.

## 6. Integration arithmetic and exact inference

Freeze complete J_e tables for the original graph; compute initial pairs,
forward/backward and K-best from the same table. No triple tensor, beam,
state pruning, hidden refinement, restart or alternate partial result.

Within each crossed valid analysis cell D along a straight chord is quadratic
in chord parameter. Its real cell integral equals Simpson's nonnegative form
length_cell*(D_entry+4*D_mid+D_exit)/6. This specifies integration of the declared
bilinear D field, not a continuous-ridge certificate about unknown source pixels.
Traverse all crossed cells; half-pixel point probes cannot replace this traversal.
Zero-length edges have zero integral and explicit support status.

Canonicalize the two finite metric endpoints lexicographically for table
evaluation, preserving stored point coordinates, so chord reversal reuses the same
computed value. Separate real integral subdivision identity from binary64 sum
rounding: derive an operation-count error envelope for source interpolation,
log1p difference, integration and table aggregation. Do not promise bitwise
subdivision equality or change old baseline arithmetic to get it.

The implementation plan must specify robust cell-boundary/touch handling and
the exact StrictMath/evaluation order before code. D's numerical nonnegativity
follows monotone log1p when C>=F is maintained; a violated/nonfinite bound is a
numerical failure, not permission to clamp unexplained errors or fabricate0.
Any integration acceleration must match this declared evaluator under its
reviewed numerical contract and include its real added work in diagnostics.

Keep the exact solver's negative-unary handling and log cell measure. MAP does
not acquire a hidden log-width term. Full partition/marginals refer to the new
finite CRF, not a likelihood-calibration claim. Original execution-policy
identities and raw/distinct alternatives retain separate receipts. The raw1
strict-dominance bound remains applicable only after its unchanged finite-U/p
guards succeed; validate new pair use instead of assuming the old proof covers
an altered order, failure boundary or source admission.

## 7. Declared null/weak limit and limitations

Define an operational input envelope over every valid source sample participating
in each J_im stencil and every eligible original mode's physical field support:
V_analysis(v)-b_im-N_im<=epsilon. This is a declared descriptor-based nuisance class,
not a Gaussian/quantization model or proof that real noisy imagery satisfies it.
RMS/filter descriptors are not SDs and no source precision is invented.

In real arithmetic on a finite frozen graph, e_im,J_im,R_im,F,C<=max(0,epsilon),
because B3 is a convex combination, other kernels are in[0,1] and F/C use maxima.
Thus H_im<=gain*epsilon*W_im/L0;
all changed measured masses vanish as epsilon tends to0. Also
0<=D<=log1p(gain*epsilon)<=gain*epsilon, so the complete available new connection
potential vanishes continuously for finite chord length. Gain0, a diagnostic
limit outside the fitted positive family, nulls both new contributions.

This is a joint changed-term limit relative to the original frozen censored/
uniform reference. Original orientation/guide terms remain unchanged; no new
whole-objective noise theorem is claimed. Derive finite-precision residual bounds,
retain exact representable constant/null toys, and test extractor/admission
boundary changes separately. Uniform bright imagery does not create a ridge:
own background contrast must be zero or original extraction unavailable. An
actual uniform/null counterexample rejects this candidate, not that requirement.

Spatially correlated artifacts outside the declared envelope remain empirical
adversaries. Weak concentrated and diffuse signals need not have the same
descriptors/shape; neither low amplitude alone nor many pixels proves their
interpretation. No promised monotonic MAP geometry at genuine branch rank swaps.

## 8. Finite family and preregistered identification

Version candidate `shared-position-connection-v1`; fixed B3 field, own kernels,
R_context=4rho and nuisance multiplier1. Positive family:

```
gain in {1,4,16,64}
eta  in {0,0.25,1,4}
```

Sixteen empirically identifiable CRF candidates; these values are a finite
geometric-range identification family, not coefficients fitted from the observed
12m/13m jump energies or selected defaults. Gain controls measured-vs-uniform
competition and appears jointly in D. Eta0 is the new local-only control, not
old B0 parity. Native production and old identified B0 remain separately callable
and unchanged. No extra exponent, noise multiplier, radius sweep or positive
force floor is approved by this family.

Before any fitting, freeze equations, exact evaluator/version, source closure,
input capabilities, all resources, scoring rule, candidate order and complete
case/split schedule. Use the existing allowed conditional local log score only
where its observation/label model is applicable; set geometry is an acceptance
criterion, not invented calibrated branch probabilities. Eta cannot be ranked
by a local score independent of eta: ties use ascending eta and otherwise the
original registered order. At most4 fully TRAIN-passing candidates receive ALL
required SELECT controls under the frozen complete schedule; no first-pass early
stop, winner retuning or gate-derived parameter expansion. No qualifying
candidate means rejection, not a relaxed oracle or another unapproved sweep.

The implementation plan must reconcile this new16 family with existing protected
authority manifests; old authorities must refuse new source, not be repinned.
No sealed HOLDOUT/private gate access for numerical design, and no permission
to interpret current read-only fixture feedback as new fitting labels.

## 9. Required controls and resource gates

Tiny independently calculated tests before full solves:

- normalized mixture, mass transfer, positive underflow, negative unary, zero
  gain, exact anchors, missing/censored support and duplicate receipt ownership;
- constant J/F, one representable excess, max-envelope overlap, R_context edge,
  each mask failure, valid dark versus unknown, full source-footprint bounds;
- cellwise bilinear integral with analytic polynomial oracle; horizontal,
  vertical, diagonal, grid-boundary/touch, zero-length, reversal and subdivision;
- tiny actual exact DP versus exhaustive MAP/partition/marginal enumeration,
  baseline unchanged under the old API and null-configuration parity;
- field construction/table/refusal/cancellation/close/late-access paths, no
  fallback result and actual source/pair/solver observer bypass detection.

Required actual geometry controls include original weak/noise/parallel/merge/
intermittent/6m/12m/displaced/full-width controls, strong and faint thin
switchbacks, three and five recordings, phase/reversal/resampling and hard
connected-junction/false-link/remote-detour/unknown-gap/source-boundary adversaries.
Separated-three remains reported stress data under the explicit prospective
decision. No safety gate is removed when a stress fixture becomes optional.

Reserve original attempt64MiB and128M cumulative solver-score caps; keep96 state,
8M pair-visit, finite source/evidence operation guards and no retry. Nothing in
this design increases a cap. Build/copy field planes, contributor records,
complete pair table, support/unknown data, scratch, metadata and output ownership
inside that same attempt. A registered additive feature-work counter must state
primitive units and complete checked input-dependent upper bound; do not reuse
the old enormous G-build bound as alleged spare work. Its numerical limit must
be reviewed in the implementation/resource contract before execution, not
invented from an observed convenient workload.

At N analysis vertices, M eligible mode receipts and P admitted adjacent pairs,
streamed bounded footprints avoid M*N retained arrays. V_analysis acquisition is
charged separately. Each per-mode J_im evaluation needs9 excess/source reads per
contributor vertex, not9 shared reads per global vertex. Let Q be the sum of all
checked contributor-footprint vertex visits; charge those9Q reads, own-bracket
predicates, excess arithmetic and every local row interpolation/recomputation.
C needs at most81 field reads/global vertex. Building each footprint, evaluating local
rows, index geometry, all crossed-cell integrations and pair table storage are
additional declared work. A complete worst-input census and64MiB peak proof
remain entry requirements; asymptotic notation alone is not a resource proof.
No assertion of current production applicability or measured speedup follows.

Full numeric comparisons run only in GitHub CI, never on the tablet. Freeze one
explicitly authorized batch after review and preserve exact host JUnit method,
one-attempt/entry/return/cancel/closure evidence, raw routes, actual work and field
identities. Reuse corrected bounded hashed-filename XML discovery. Missing host
or partial runs remain failures; do not reconstruct historical PASS receipts.

## 10. Integration, diagnostics and completion

Initial code remains read-only experiment behind a named opt-in; do not alter
CURRENT_VERSION, native Engine B32/8, legacy engines or ordinary UI defaults.
Eventually production integration requires the original source/coordinate,
finalization, geometry/topology, preview, assignment, Apply/Undo/Redo, stale
publication and no-late-mutation gates. New image support supplies neither
DIRECT authority nor endpoint/junction movement permission.

Reproducible comparison must report three distinct identities: unchanged native
production B, unchanged identified B0 control, and this candidate. Preserve each
version's native route alternatives, work/policy/resource refusals and source
receipt; no raw1 result is presented as native32/8 behavior or CPU speedup.
Retain old failed results and supply exact source-bound reproduction commands.

Export V_analysis, own-excess/filtered-response provenance e_im/J_im, positional F,
negative-context C, D and validity/
unavailable reasons separately, plus component masses/rows, selected edge lengths,
integrals, unknown lengths, baseline/new term attribution, source/kernel/rho and
operation/peak memory receipts. C is never labelled observed bright support.
The browser view overlays actual returned geometry on the separately bound
original raw input when available, otherwise on the honestly labelled available
rendered/resampled raster, with per-case feedback. Do not substitute V_analysis
or a filtered response for an available raw input. No smoothed/cleaned curve substitutes for the
tested path.

Completion needs all original/new required controls, unchanged baseline evidence,
full native comparison, independent substantive review and appropriate project
checks. An accepted written design or finite first comparison is not completion.
No release/tag/RC is authorized by this document.

## 11. Review disposition and remaining entry work

Independent pre-implementation arithmetic pins (rational arithmetic, not product
tests or new inference): one measured component, three equal-width cells,
r=[0,1/2,1], gain4, no censoring. For widths1m, A=3/2m, H=6, q0=1/7,
qm=6/7 and densities[1/21,1/3,13/21]/m integrate to1. For widths1/10m,
A=3/20m, H=3/5, q0=5/8, qm=3/8 and densities[25/12,10/3,55/12]/m
again integrate to1: a negative unary at density55/12 is legitimate.
For bilinear D(u,v)=u+v+2uv in a unit cell, its diagonal chord integral is
(5/3)*sqrt(2); Simpson values[0,3/2,4] give5/3 per unit chord length.
These pins were checked with Python Fraction without loading Java, capture,
profile assembly or inference. They do not establish whole-family correctness.

Controller self-review completed for scope, equations/units, valid0 versus missing,
no positive-context leakage, normalization, declared weak limit, baseline
separation, derivative-work accounting and original gate retention. This is a
concrete *proposed family*, not accepted empirical evidence or an executable plan.

Independent numerical/source review and user review of this written specification
are pending. Before implementation the plan must fix exact arithmetic/error
bounds, source-capability binding, owned payload census, additive work limit and
complete resource preflight. Those are explicit derivations from this candidate,
not permission to choose another formula during implementation. If any conflicts
with the approved OBS contract, identify the specific clause and seek a narrowly
reviewed correction; do not waive the original user outcome.
