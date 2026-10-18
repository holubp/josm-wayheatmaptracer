# Conditional B candidate: area presence and core position

**Status: independently reviewed for bounded test-side TDD and TRAIN-only
falsification; the required outcome clarification is incorporated below.** This is not
approval for full FIT, fresh SELECT, HOLDOUT, production changes or promotion.
OBS-01–20, the identification plan, the reviewed feasibility amendment and all
later production gates still govern. A tested kernel is not a passing model.

The original `joint-core-excess-v1` rejection and TRAIN-screen results retain
their generating source/input identities. The original TRAIN
`sparse-crossing-noisy-strands-vs-persistent-parallel/train/v4/right` screen
completed all 32 configurations: zero passes, zero untested, every exact-anchor
check PASS and every parallel-segment-union check FAIL. It used 81 native profiles,
49,599,354 transitions/configuration, 595,094 ms, peak 9,107,120 bytes, retained
zero bytes, and zero SELECT/HOLDOUT reads. Its source hash is
`f71a908b223b43ce49c3e88abae0bd7dad0ad5aa91ac3fe1626e3bacee307917`;
native hash `7bda9e73e2ab3e6e35c93bcedb707225c7bb03494939c81e1b65fac09a920c5c`;
facts hash `f856b87489ee6249f1f577ef52e46b3f78be844d6e86b8488671f4ce2d6327c1`.
The ignored log `build/observation-train-feasibility-screen.log` has SHA256
`c399f9bb4f426915820129b2aef447de9d046d70c1eff8392e3bb84e94f800b8`;
probe `build/ObservationTrainFeasibilityScreen.java` has SHA256
`32e41e25b8da73690de753784372aeef08725551683fab1835e6457e341bb39c`.

That rejection is bound to the original Math source, not a proof about prospective
StrictMath inputs or this new law. A partial/exhausted screen would not prove
32 failures. Do not run competing families and choose between them using known
SELECT results. Another rejection would not complete the active comparison goal.

## Hypothesis and available observations

The existing field multiplies a core/source-resolution tent by each state's
scalar excess. Thus raster amplitude variations can change both model mass and
conditional position within a measured band. The proposed separation uses own-band
excess area for mass and the original core/resolution tent for conditional position.
It is a new empirical family, not a bug fix or a calibrated generative noise law.
It does not infer real-world presence probabilities.

Facts-v3 already supplies the exact matched ordinary-mode receipts, original
background, RMS/filter descriptors, RAW/B3/B5 centers, RAW core endpoints, shoulder
brackets, physical pitch capability, direct spans and source correlation lineage.
The existing scalar interpolation and admitted cell widths supply the remaining
inputs. No new scan, extraction decision, independent observation, connection
certificate or hidden source-precision assumption is needed.

Area integration may reduce sensitivity to a single peak sample, but this is an
untested hypothesis. Native raster sampling and admitted-cell quadrature can still
change area. A narrow, phase-biased core tent may still prefer unsupported motion.
No claim follows that this law prevents switching or repairs any private bend.

## One exact family

Use every actual V3-admitted cell `s`, coordinate `u_s`, original quadrature width
`Delta_s > 0`, and `L0 = 1 metre`. Retain the existing interpolation helper:
`I_s` is unavailable unless its actual required scalar samples are valid.
Do not remove invalid cells, change widths or clip/recompute cell boundaries.

For elementary measured mode `m`, eligibility, exact original receipt matching,
grouped-parent exclusion and known/unknown source-capability handling are identical
to the reviewed prior family. Use the original RAW nested center, not its winning
raw sample, and require the same available filtered stage(s):

```text
c_m = original RAW center = (a_m + z_m)/2                         [metre]
v_m = max_k abs(c_k-c_m), over available original RAW/B3/B5        [metre]
h_m = (z_m-a_m)/2 + v_m + rho_m/2                                [metre]
N_m = max(original RMS descriptor, original filter descriptor)    [scalar]
d_m = (D_L+D_R)/(40 metres), 0 <= d_m <= 1                        [dimensionless]
T_ms = max(0, 1-abs(u_s-c_m)/h_m)                                 [dimensionless]
```

`a_m,z_m` are the original RAW stage's measured high-core endpoints. `rho_m` is
the factual native pitch when known; otherwise it is the same explicitly tagged
physical sampling-gap descriptor used by the prior family, never a native-pitch
claim. Preserve its existing no-support/unavailable rule. No fitted width or
center adjustment is added. Filter stages remain correlated views.

Let `V_ms=1` exactly when `u_s` is inside the original own shoulder bracket
interval (including its endpoints) and the existing interpolation is available;
otherwise `V_ms=0`. Use only that mode's background and nuisance descriptors.
An unavailable interpolation has zero measured field, with its unavailable
support recorded; it is not a measured zero-intensity sample.

```text
e_ms = max(0, I_s-b_m-lambda*N_m), where V_ms=1; otherwise 0       [scalar]
E_m = sum_s e_ms * Delta_s/L0                                    [scalar]
Z_m = sum_s V_ms * T_ms * Delta_s                                 [metre]
f_ms = V_ms * T_ms / Z_m, when Z_m>0 and E_m>0                     [1/metre]
H_m = G * (1+B*d_m) * E_m, when Z_m>0; otherwise 0                 [dimensionless]
A_ms = H_m * f_ms, when H_m>0; otherwise 0                         [1/metre]
```

`E_m*L0` is an admitted-quadrature estimate of own-shoulder scalar excess area,
not an exact continuous integral or independent-pixel count. It deliberately
includes valid excess outside the tent but inside the original own shoulder.
Conditional `f_m` deliberately does not multiply by per-state excess: it may
support a valid location of low excess within the observed band's uncertainty
envelope. This is the falsifiable positional hypothesis, not fabricated samples.
Invalid locations always have zero measured density.

`G` has inverse-scalar units; `B` and `lambda` are dimensionless. The numerical
grid is exactly `G={1,4,16,64}`, `lambda={1,2}`, `B={0,1,3,7}`. The scalar
convention stays `[0,1]`; the reference length is fixed and never fitted.
The same numerical grid has a different response feature from the old family;
old fitted scores, shortlist, masses or configuration identity cannot be reused.

Deduplicate exact evidence lineage once, as before. Inconsistent aliased receipts
fail evaluation. Distinct ordinary lineages remain separate even if shoulders
overlap; their mixture alternatives are not independent observations or proof of
exclusive pixel ownership. No remote peak, grouped-parent midpoint or completed
path supplies a mode's feature. `d_m` is the existing bounded predictor, not a
positive connection certificate or new ownership permission.

Retain the exact baseline effective censored masses `q_c` and density rows `f_cs`
through the existing shared baseline helper. Let

```text
R = 1-sum_c q_c
D = 1+sum_m H_m
q_0 = R/D
q_m = R*H_m/D
U_s = 1/W, where W=sum_s Delta_s
p_s = q_0*U_s + sum_m q_m*f_ms + sum_c q_c*f_cs                    [1/metre]
```

This is one joint derivation: `q_m*f_ms = R*A_ms/D` for positive support.
The unit null integral `1` is the fixed model-mass scale, not measured absence.
There is no old reliability multiplier, confidence exponent or added signal floor.
Use the existing common reducer/log-sum-exp, `-log(L0*p_s)`, numerical log floor,
FB measure `Delta_s/L0`, temperature and exact-anchor conditioning. Negative
unaries remain valid. Score the same unfloored, unconditioned likelihood through
the existing proper-score path; omit conditioned anchors from score averaging.

## Weak limit and degenerate cases

For fixed finite receipts, masks and cells, if excess tends uniformly to zero,
then `E_m,H_m,q_m` tend to zero and the measured contribution vanishes
continuously. With all measured fields zero, the remainder is exactly `R*U` plus
unchanged censored contributions. This includes zero nuisance with vanishing
scalar contrast. Inherited extraction, bracket/mask and stage-admission changes
are separate discontinuities; no continuous MAP-geometry claim is made.

- Missing required receipts/pitch support or grouped parents retain their existing
  explicit unavailable outcome, zero measured mass and lineage before the following
  support checks. No positive replacement density is fabricated.
- With eligible finite receipts, apply deterministic precedence: first, zero
  available own-shoulder cells (`sum_s V_ms=0`) gives
  `Outcome.UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT`; next, `Z_m=0` gives
  `Outcome.NO_ADMITTED_TENT_SUPPORT`; next, `E_m=0` gives
  `Outcome.OBSERVED_ZERO_EXCESS`. Every such outcome has `H_m=q_m=0` and no
  normalized measured row. The cell count is diagnostic, not independent evidence.
- Positive `E_m` with `Z_m=0` still gives `NO_ADMITTED_TENT_SUPPORT`, with area
  recorded separately. Do not normalize a zero-mass/no-support row or evaluate
  `0*undefined`. Unavailable imagery must not become observed zero excess.
- Unexpected nonfinite/negative integrals, invalid remainder, overflow or loss of
  positive contributions solely to numerical underflow refuse evaluation; no
  epsilon floor, altered normalization or partial route rescues them.
- Empty/invalid censored rows fail under the unchanged baseline contract, even
  at zero censored mass. `R=0` gives no measured/uniform contribution without
  changing censored rows. Exact anchors remain conditioned and exact.

## Preregistered validation before any fresh evaluation

The independent review permits only bounded test-side TDD and TRAIN-only
falsification with the exact capability, outcome, equation/grid and source
identities frozen. Neither the original TRAIN rejection nor passing analytic
tests authorizes full fitting. Do not use old SELECT/private/retired-HOLDOUT
measurements to set any coefficient, width, threshold or availability rule.

The cheapest TRAIN-only mechanism check, if needed for Gate A, uses one fixed
registered original TRAIN parallel case on a bound source/facts/lattice. Without
inference or parameter selection, report both registered nuisance-envelope
values' area `E_m`, old tent-weighted excess, tent support/normalizer, and their
branch/phase variation; retain masks and component lineage. This can falsify the
claimed area/position separation benefit, not certify geometric success. No
additional scene or parameter search follows an unfavorable result automatically.

An independent analytic oracle is `L0=1m`, widths `[1,2]m`, masked tent values
`[1,1/2]`, and excess `[1/5,1/10]` scalar. With `G=1/scalar`, `B=0` and no
censored mass, `E=2/5 scalar`, `Z=2m`, `f=[1/2,1/4]/m`, `H=2/5`,
`q0=5/7`, `qm=2/7`, and `p=[8/21,13/42]/m`; the mixture integral is one.
Replacing excess by zero must remove measured mass, without inventing a density.

The bounded TDD must additionally establish these conditional algebraic oracles
with receipts, masks, cells, spans and parameters fixed:

- Redistribute nonnegative excess while preserving `E_m`: `f_m`, `H_m` and masses
  remain unchanged. This must not mutate a bound integration source and then reuse
  stale facts/source hashes.
- Valid excess outside the tent but inside the own shoulder contributes to `E_m`
  and `H_m` when other admitted cells give `Z_m>0`.
- A valid zero-excess cell with `V_ms*T_ms>0` has positive conditional density
  when the component has positive area elsewhere; an invalid cell has exactly zero
  measured density and remains in full-domain uniform/admission.
- Positive area with `Z_m=0` produces the explicit zero-mass no-tent outcome.
  No available own-shoulder cells must take the unavailable-support outcome before
  the no-tent check; available support with zero tent takes no-tent before zero-excess.

Require analytic RED/GREEN oracles for unequal widths,
area/density units, normalization, the fixed-envelope weak limit, invalid measured
zero with full-domain uniform, no tent support, censored parity, anchors, aliases,
source-resampling controls and the existing cancellation/memory refusal semantics.
Use actual `BObservationAdapter` and exact production inference for geometry;
do not add a shadow observation path, second solve or extra states.

After further explicit qualification, register ONE bounded prospective experiment
under the reviewed TRAIN-feasibility
protocol: score all 32 configurations on original TRAIN; apply every required
applicable TRAIN control before shortlist ranking; record early geometry skips
honestly. One fixed TRAIN hard-gate screen may reject all 32 before opening fresh
SELECT. An incomplete screen does not manufacture failures. Rank only fully
feasible finite-score configurations by the unchanged proper score and tie order,
then evaluate at most four on the single frozen fresh SELECT cohort. Preserve
the baseline comparison, all acceptance bounds and post-freeze fixed regressions.

The new source/catalog/manifest freeze must precede evaluation and include the
prospective StrictMath renderer identity. Old Math-bound results stay separate.
Fresh SELECT groups and aliases, retired SELECT eligibility and replacement
sealed HOLDOUT follow the reviewed feasibility/generator contracts, including
the recorded original-HOLDOUT source exposure and retirement. No HOLDOUT payload
or concrete recipes are read, generated by this proposal, or present in FIT/CI.

Keep the existing per-attempt caps, <=64 MiB resident control, <=2 GiB output,
<=512 catalog rows and one fitting watchdog with any stricter hosted limit.
Precharge allocations, bound kernel work and poll cancellation; incomplete,
limited or failed runs mint no candidate. No automatic second experiment.

Only a frozen passing candidate may enter original Tasks5–6: actual production
engine/default alternatives and finalization, unchanged regression/noise/safety
gates, qualified one-shot sealed HOLDOUT, registered private evaluation, honest
existing-B comparison and independent whole-goal audit. Mass/density changes do
not upgrade direct support or Apply authority. Standalone B alone is in scope;
Legacy/A/Image/Hybrid, defaults and release permissions remain unchanged.
