# RC10 bounded subpixel quality amendment — review draft

The next RC must improve the user's sparse, overlapping-strand zigzags, not
merely pass arithmetic tests. Existing Ricany/Maganik captures stay primary.
This amendment addresses a reviewed representation defect in R02; it does not
replace the implementation plan or waive any release, safety or resource gate.

## Evidence and scope

Actual Ricany raw states contain half-native-pixel staircases in near-flat unary
regions. Finalization does not add that turning. The reproduced unsupported
straight continuation has0.313246rad of turn; the10-degree target fails.
A wholesale quarter-native grid fails the unchanged128M production work limit.
The reviewed R4 missing-window normalization passes29 controls and improves
Ricany turning only partially; neither issue is declared fully fixed.

Keep the complete existing half-native grid, all required modal centers/peaks,
fixed anchors, old solver arithmetic and exact EAGER/V2 correspondence on an
identical graph. Add at most one optional geometric position per profile when
a unique compatible pair of measured boundary modes defines a straight chord.
This is an additional geometric hypothesis, not a new observed strand or a
mean across multiple branches. No second solver run or post-smoothing is allowed.

## Proposed contract requiring independent review

1. Keep factual left/right direct association reach (capped at20m) separately
   from two-sided coherence. Derive it solely along the existing mutual-best,
   raster-supported adjacent edge chain; absent edges stop it. A left boundary
   requires at least4m of exterior left reach, and a right boundary at least4m
   of exterior right reach, plus locally measured uncensored elementary modes.
   Missing old metadata defaults to zero. Two-sided coherence may remain zero
   at a gap boundary. This reach never grants measured support across a gap.
   Both orientation descriptors must be MEASURED_TWO_SIDED. Use exactly the
   immediate exterior profiles start-1/end+1 of each maximal all-zero-coherence
   run; the selected boundary modes themselves must have positive coherence.
   At selection edges or failed immediate boundaries, emit nothing. Require
   the configured physical descriptor ray length times
   sqrt(mismatchSquared(chordBearing)) <=1.5 times that endpoint's known native
   source pitch at each end. Ray length is max(minimumOrientationRayMeters,
   orientationRayLengthPitches*pitch), from the same immutable Localization
   that created the descriptor. Never extrapolate that local measurement's
   precision over an unobserved gap. This is a geometric eligibility predicate,
   not the existing soft association score. Generate through every weak interior
   row, retain factual local evidence, and do not cherry-pick deeper anchors.
2. Intersect the chord with each existing profile's metric normal line. Require
   finite, well-conditioned geometry, a segment intersection and an offset
   inside the unchanged decision window. Never clamp an outside projection.
   Exact fixed anchors retain their one original state; no chord crosses an
   intervening fixed anchor.
3. Only a unique compatible boundary pair may supply a proposal. If multiple
   physical branches qualify, retain the original graph rather than average,
   choose a branch, or give unsupported profiles an observed branch identity.
   Missing/censored responsibilities and all final direct-support checks remain
   factual. A geometric chord beyond20m does not extend the measured association
   or bridge contract and cannot authorize endpoint/junction movement or Apply.
4. Bound geometric spans to64m and64 profile intervals. Count all boundary mode
   pairs before testing any. More than8 pairs or an oversized span is ineligible
   and keeps its original graph with a diagnostic; never certify uniqueness
   after examining only a prefix. Fully examine every pair when the count fits.
   These are proposal-generation limits, not inferred
   physical coherence. The unchanged production cap is1,000,000 generation
   units over the entire attempt: charge profile/mode scans, pair checks,
   intersection evaluations and emitted positions before work. Check cancellation
   at those boundaries. Refuse typed exhaustion before a partial array can escape;
   charge retained arrays/work counters and release refused/canceled owners.
5. Construct the baseline lattice first, then add a distinct proposal. Preserve
   every previously admitted state and its deterministic relative ordering.
   Recompute physical cell widths deterministically. If the state cap cannot
   retain old states plus the proposal, refuse STATE_LIMIT; do not silently
   remove old states or change the source sampling resolution.
6. Keep geometric provenance separate from measured evidence. A chord provides
   only a coordinate: never copy boundary mode IDs, reliability, orientation or
   coverage into an interior profile. Independent local core ownership remains
   legitimate and uses exactly the original grid's predicate. At a new point,
   evaluate the same actual scalar observation model and branch-owned orientation
   rule used for other cells. Do not bypass image cost by assigning a special
   zero-cost branch label. Empty-profile proposals remain unlocalized.
7. Bind actual production B/Hybrid activation to a distinct state-policy identity,
   diagnostics and replay receipts. Historical bundles remain readable; older
   strict receipts cannot certify the changed graph. A/Image/legacy unchanged.

Focused independent Sol review2026-10-12 resolved the reach/ownership contract:
local DIRECT_TWO_SIDED denotes current-profile cross-section ownership, not
longitudinal gap proof. No Astra consultation is authorized without the user's
prior explicit approval (latest instruction2026-10-12).

Further reviewed correction: actual analytic straight-gap factory profiles14/53
have direction0.234rad and gap-edge16/51 are near vertical; complete exterior
profiles13/54 have0.120rad versus the true direction0.070rad. The descriptor
observes6m rays, so its error is only0.28nativepixel locally. Scaling it to the
unobserved61m gap falsely produces2.86pixels and rejects a valid coordinate
hypothesis. Sol approved the immediate-complete-boundary and local-ray contract;
actual production RED0.32325rad and default/private GREEN still required.

## Execution and acceptance

### 2026-10-13 observed censored-direction defect — conditional correction

This remains R02 numerical/localization work, not a replacement release plan.
The measured-mode reliability correction leaves censored directional priors
unattenuated. The actual M3 excursion at profiles736–739 receives almost all
its4.7336 image-cost gain from four LEFT/-7.01 gradient-supported censored bands.
Those bands retain priors.125–.198 while measured position reliability is only
4e-5–1.4e-4. Holding the same graph, measured modes, censored existence/priors,
and production observation arithmetic fixed, the diagnostic uniform-censor
ablation removes that advantage: old-nearest/current window unaries become
69.949326/69.949439 instead of71.130415/66.396870. This is a causal observation-
model discriminator, not a historical-score, feasible-alternative or quality
certificate. The old route snaps by up to.44m; the current saved route matches
current states within4e-14m. No DP or production change was made by the probe.

Three M3 bands have zero observed lateral extent and the fourth spans.87625m;
all four span only4.67m longitudinally. M1's wrong-side bands69–70 also have zero
lateral extent. Zero extent is not itself a rejection rule: a legitimate narrow
ridge outside the configured decision window can illuminate just its edge.
Scalar amplitude alone is insufficient: M3 edge excess is.259–.435.

Independent Sol review supports designing a directional-only observed-band
corroboration prepass. Production activation is not approved by this diagnosis.
First establish persistent bright/faint clipped positive rasters and a short
edge-island negative. Session29295 completed6tests:5persistent weak/strong,
reversed/resampled positive cases PASS; the short-island case is RED for the
intended unequal unary(2.922128 atcenter vs1.494086 atedge), with observed span
less than8m, no measured mode, and retained censored diagnostics. Test:
V022CensoredDirectionContinuityTest; XML censored-direction-raster-red-xml.

Required contract before implementing the prepass:

- Retain the extractor's factual inward extent and its already computed scalar
  shoulder threshold in candidate-owned censored metadata. Missing older
  metadata remains unavailable. No duplicated peak/noise/threshold calculation.
- Link only adjacent, unique compatible observed censored bands. Same side and
  identical configured boundary do not prove continuity. Prove an authorized,
  raster-valid scalar continuation at the band threshold between those actual
  observed bands; disconnected, invalid, missing, ambiguous or side-switch
  evidence breaks the chain. Do not search farther for a convenient anchor.
- Use the existing physical20m analysis window and factual4m exterior reach in
  each direction for interior directional confidence. Never grant measured
  centers, branch identity, direct support, inferred bridges or turn/motion
  authority. Preserve exact fixed anchors and selection-edge semantics.
- Preserve censored existence/reasons and the existing one-sided density.
  Transfer uncorroborated directional prior mass to the uniform missing
  component. Keep measured-mode reliability, orientation, state space and the
  one exact DP unchanged; no post-smoothing, floor lift or coefficient tuning.
- Charge metadata, retained/scratch arrays, all pair/source-cell/scalar tests
  and copies; check cancellation at these boundaries. Use one cumulative
  attempt scope, not a cap reset per band. Refusal yields no partial applicable
  output. Do not raise production8M/2M/128M or256MiB limits.
- Independently validate the exact scalar-continuity predicate against
  persistent bright/faint clipped bands, short islands, same-side disconnected
  raster, multiple/parallel bands, reversal/resampling and selection ends.
  Preserve impulse,12m faint apex, strong switchback and three-strand oracles.
  Version B activation/receipts separately; Hybrid/A/Image/legacy retain their
  declared baseline capabilities. Actual production bypass must fail a gate.

This contract still needs the exact adjacent-band scalar predicate and focused
review before activation. A same-side profile-count prepass alone is rejected.
Private diagnostic artifacts stay in build/rc10-route-energy-diagnostic; no
capture was uploaded. Astra remains subject to explicit prior user approval.

### 2026-10-13 conditional cross-peak evidence correction

LATEST disposition: the proposed one-valid-outward-sample flank receipt was
implemented and disproven by the exact clipped profile. Brackets at y±.125 have
valid outward samples at±.25; their locally rebased background still admits the
tiny core. Restore ordinary strict RIE, preserve the zero oracle, and retain
receipts as diagnostics until a stronger source-authority contract is
reviewed. Do not replace the failed rule with an arbitrary deeper sample count.
The ordinary tracing path is restored; the inactive candidate-proof helper still
uses an exact-mode receipt as an additional gate, not sufficient proof of the
unresolved boundary contract. It must not be activated on that basis.
Sol identified an observational ambiguity under the currently authorized samples;
Astra consultation is pending explicit user approval.

Pure eligibility prerequisite is implemented and tested87/87, independently
audited before activation. Exact original suppression/exterior identities are
borrowed, output grants no support, and whole-engine correction remains pending.

Continuation evidence: candidate-specific physical raster proof component now
passes10/10Java17 and Sol affected review, before activation. Comparing descriptor
arrays across source-profile and chord normals was rejected; keep exact original
record/Mode integrity, then require one actual uncensored high core containing
the same physical query point in the chord frame. Cumulative limit and owner
refusals are tested. Integration must preserve preregistered record provenance
and the same RIE counters, not reset the cap for recovered candidates.

Separate normal-RIE blocker: clipped five-sample support can rebase noise and
manufacture a complete core. Retain original localized-bridge zero oracle.
Sol's bounded next contract is RIE-local independent flank proof: during opt-in
extraction retain the exact admitted/suppressed Mode object plus its actual
shoulder bracket indices and whether each has valid outward continuation in
the same run. Reuse the existing computed threshold/interval; do not duplicate
peak logic. Charge records/lists/copies and checks to detailed owner/work.
RIE may use only the exact selected mode's receipt and refuses missing/foreign
receipts or a bracket terminal against invalid support. Keep ordinary extractor
admission/output unchanged; this is stronger bridge evidence, not a global
censor redefinition. Test clipped RED, full/faint/faded/near-boundary positives,
rotation/reversal and memory/work/cancellation before activation. An Astra
consultation is pending user approval; none has been performed.

This is a reviewed conditional contract, not an approved activation or release.
Full-window RMS-v2 remains the default. Valley clipping was tested and rejected:
it fixes the impulse but admits recurring noise and a false three-strand side
track. Candidate-height capping also predicts extra spurious mode admissions.

The narrower correction is limited to B's direct-longitudinal path. Add an
opt-in detailed extractor result retaining fully shaped `RMS_ONLY` suppressed
peaks after all other raw/B3/B5, uncensored two-sided and fractional-prominence
gates. Keep the existing default extractor result and arithmetic unchanged.
Suppressed shapes are diagnostics, not direct ownership, orientation or support.
Charge retained mode/list/record and temporary objects to attempt owners.

Freeze independently admitted exterior profiles and their raw-peak census.
An interior, isolated single-profile suppressed mode is eligible only through
one unique reciprocal exterior branch within the existing20m span, final core
center within one factual native pitch of the exterior chord, and no distinct
raw exterior competitors outside the selected cores plus native uncertainty.
Use the final core, not the raw peak. Check every candidate/pair; refuse ties.
No transitive promotions; preserve all baseline mode IDs/order/parallel siblings.
Measure any restored mode's own orientation and use its own local prominence
for amplitude reliability; never borrow exterior amplitude or direction.

Both proposed legs require candidate-specific actual raster evidence. Preserve
normal `RasterIntervalEvidence.support` unchanged. The exact candidate-pair
proof must retain crossed-cell/authorized-window checks, strict scalar sampling,
half-output-raster-pitch queries and unique observed local core ownership at
every query, including the suppressed endpoint. A preregistered candidate leg
may use only a local RMS-suppressed shape that independently passes the other
core gates; never globally disable noise rejection or substitute another peak.
Blank/invalid/censored/valley/parallel substitutions refuse proof. Charge shared
2M sampling and8M feature caps; cache exact immutable pair receipts only, then
let ordinary reliability consume those measured factors. Typed cap/cancellation
must release owners and produce no partial applicable output. Version activation
and numerical receipts explicitly; A/Image/legacy/Hybrid baseline stay unchanged.

Empirical prerequisites: actual reflected impulse provisional raw/B3/B5 centers
are all0, uncensored core[-.25,.25], agreement1; RMS is its sole rejection. The
three-strand scene has raw exterior competitors at every interior profile, so
admitted-mode-only ambiguity checks are insufficient. Both raster-leg proofs
remain unmeasured; do not activate before their positive/negative controls.
Tests must retain unchanged impulse path bound, all51 sparse-profile refusals,
alternating phases, equal parallel branches, blank/invalid/censored/valley legs,
faint/strong12m bends, memory/cancellation/cap and actual production regressions.
This secondary stress correction does not itself close real Ricany quality.

- Review this contract before implementation; resolve ambiguous evidence/branch
  semantics and cap admission explicitly. The exact-Kbest performance contract
  still preserves an input graph; this is a separate front-end quality change.
- TDD the pure proposal generator: analytic chord intersection; physical units;
  rotation/reversal; parallel ambiguity; invalid/censored modes; fixed anchors;
  span/pair/state limits; cancellation and owned-memory refusal.
- Integrate one bounded prepass before the one B DP. Retain raw/final identities,
  immutable Apply and interval ownership. Prove production bypass fails the gate.
- Keep the staircase RED target, faint12m apex, strong switchback and persistent
  parallel branches. Compare complete EAGER/V2 output and independent small-graph
  energy/partition oracles after cell widths change. Keep prior public cases.
- Run actual default-budget private captures and inspect localized excess turn,
  support/gaps, protected geometry and immutable Apply plans. The symmetric
  three-strand mean example is a control, not proof of a measured centerline.
- Do not release until the user's RC7 problems are demonstrably improved, focused
  and full public CI/JOSM gates pass, independent review has no blockers, and
  the actual downloaded RC10 artifact is verified. Use commits/CI artifacts for
  development; no interim public release.
