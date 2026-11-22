# G004 P1 original-reference frame correction

> **Historical focused review:** current integration follows the approved Nov22
> [completion plan](2026-11-22-g004-workflow-completion.md) and canonical execution
> ledger. The source tree/owner and prototype status below describe this dated
> review, not the active implementation. Its prohibition on fictitious original
> affine transforms remains binding; later numerical research is outside G004.

2026-11-21. Focused source/contract review only; no production edits, tests, private cases, installation, commit or publication. The P1 writer remains sole owner of `build/worktrees/g004-ci-freeze-20261120` at `6022cc3a6c09d209dd93c0548e24f4a1af3ffd98`. Reviewed canonical packet SHA-256: `cdefecdb255fc5364f94a3f3c0c281fb9bf49cb76f1ac8cf81aca6b95efc9cee`.

## Finding and decision

The packet's mandatory `RasterMetricTransform originalReferenceAffine` is factually wrong for its eligible managed/visible producers. Retain the **exact original input transform and metric frame**, with original input dimensions and identity. Make a genuine original affine optional. Absence of that affine alone must not make ordinary managed or EPSG:3857 visible sessions unavailable.

Do not fit three points, relabel the evidence output grid, or manufacture a `declaredAffine()` certificate. The smallest truthful P1 change is a value-model/source-retention amendment; P2 then consumes an explicit reference-pixel geometry adapter. A derivative bound is useful for conservative generation/cap certification, but it is not an original image affine and cannot silently replace every reference-distance metric.

## Exact source evidence

Paths are relative to the repository root unless prefixed `W/`; `W` means `build/worktrees/g004-ci-freeze-20261120/`.

* `src/main/java/.../service/evidence/SupportedInputRasterTransform.java:145-197`: managed `WebMercator` retains zoom, world-pixel boundary origin and raster/world scale; geographic-to-raster conversion is the actual logarithmic Mercator formula and subtracts **0.5 after scaling**. `:200-252`: `VisibleWebMercator` retains projected capture origin, projected units/view pixel and raster/view scale; its formula deliberately has **no managed half-pixel phase**. Both records are immutable and carry complete hex-parameter identities.
* `src/main/java/.../model/LocalMetricFrame.java:75-113`: local east/north are scaled longitude/latitude differences, with complete v2 frame coefficients/domain in its distortion certificate. Consequently composition with Mercator is nonlinear in north. The transform is not globally affine in this frame.
* `src/main/java/.../service/ManagedModernPreviewSource.java:194-203,680-690`: the actual managed `Raster` already has the exact transform, input image dimensions, palette, zoom, source identity and generation. Its producer currently passes world-boundary origin divided by two and raster/world scale **2.0**. Retain that actual value; do not infer a replacement from a nominal source-pixel pitch.
* `W/src/main/java/.../service/refinement/RefinementAcquisition.java:38-60`: eligible visible input already constructs the exact `visibleWebMercator` transform from slide-time bounds/scale. `:145-174` uses the actual managed raster transform and selected mapping before metric resampling. These are the original-transform publication seams.
* `src/main/java/.../service/evidence/RasterEvidenceCapture.java:265-271`: the evidence snapshot stores `outputGrid.transform()`, while resampling provenance separately stores original transform kind/parameters and input dimensions. Thus `EvidenceSnapshot.transform()` is the **output resample**. `SupportedInputRasterTransform`'s class contract explicitly says its executable value is not currently retained by evidence snapshots; inert provenance strings are not the proposed executable retention API.
* `W/src/main/java/.../service/refinement/LegacyVisibleSource.java:28-39` and `LegacyReviewOwner.java:81-98,153-165`: original visible pixels/metadata already belong to the original review; pixel freezing is lazy. Keep admitted/cancellable freezing under the explicit operation, preserving the existing zero-extra-work Legacy→Apply path.
* `src/main/java/.../model/RasterTransformCertificate.java:7-34`: current certificates provide declared/exact constructed affine values, not an analytic non-affine approximation proof. Three sample agreements do not qualify one.

The root and W `SupportedInputRasterTransform.java` bytes match: SHA-256 `40f5ce085f7da2f73d579e0f1ec84bf99abf95d9901d8db98c185e14119659b6`.

## Recommended normative P1 amendment

Replace the mandatory affine field and all corresponding “missing affine” eligibility shorthand with this contract (names may follow existing style):

```java
record Frame(String inputHash, String imageIdentity, String palette,
             String mappingIdentity, String intensityMode,
             LocalMetricFrame coordinateFrame,
             SupportedInputRasterTransform originalInputTransform,
             int inputWidth, int inputHeight,
             String inputCoordinateConvention,
             Optional<VerifiedOriginalAffine> originalAffine) {}

// Exact affine producers only; includes the same frame/input/dimension binding.
record VerifiedOriginalAffine(RasterMetricTransform transform,
                              String proofIdentity) {}
```

`originalInputTransform.toRasterCenter(frame.coordinateFrame().toGeographic(p))` defines **original reference-image pixel-center coordinates**, never the currently displayed raster. For `LocalMetricAffine`, validate the identical local frame before retaining a genuine affine. Managed/visible Mercator normally have `originalAffine.empty()` and remain eligible. A future analytic approximation, if approved, needs a distinct bounded-approximation type, domain and error; it must never occupy `originalAffine`.

Frame identity canonically binds all transform parameters/kind, full metric-frame numerical identity and certified domain, input dimensions/convention, original input/image identity, selected palette **and mapping/direct-intensity/aggregate identity**. Bind evidence separately by its own hash, output transform/dimensions, field name and resampling provenance. Require original kind/parameter identity/dimensions to equal that provenance where rectification occurred. Keep these two frames and hashes distinct in diagnostics. A composed candidate retains every source/span binding; one palette's first mapping is not a substitute.

`inputHash` must keep its stated factual meaning: a pre-existing actual input hash or a lazily admitted actual hash. A source label, evidence hash or output-grid hash must not be substituted and described as original input bytes. If the implementation uses an opaque image ownership identity instead, name it as such and export the unavailable original-byte hash honestly. This correction grants no unmetered hash/copy work.

Currentness is separate from geometry: retain the existing external coordinator generation/visible epoch receipt and original selection/root authority. Its safe probe must outlive worker settlement without retaining an entire disposed acquisition. Compare the same original receipt before binding, after conversion, at publication and at existing confirmation/Apply boundaries. P1's lease accounting and grouped evidence charge remain unchanged apart from admitted small descriptor bytes. No raster copy is required merely to retain immutable transform parameters.

Typed unavailable reasons remain for missing **original transform**, frame numerical identity/domain, dimensions, mapping binding, unchanged-pixel receipt, coverage, historical input or stale source. Non-EPSG:3857 visible captures still need their already required factual qualified transform. Do not use `missing-original-affine` for an otherwise complete supported Mercator capture.

## P2 coordinate adapter: exact pixel cap and physical scale

Add a smoothing-only `ReferencePixelGeometry` built lazily from this Frame. P1 need only publish the truthful Frame and binding; it must not fabricate P2 geometry results. Suggested operation seams all receive the existing shared work context:

```java
RasterPoint referencePixel(MetricPoint p, GeometryWorkContext work);
Bound pixelDisplacement(MetricPoint p, MetricPoint q, GeometryWorkContext work);
Jacobian metricToReferenceDerivative(MetricPoint p, GeometryWorkContext work);
DerivativeBounds derivativeBounds(MetricDomain domain, GeometryWorkContext work);
CapCertificate certifyCorrespondingEdgeCap(
    MetricPoint p0, MetricPoint p1, MetricPoint q0, MetricPoint q1,
    double k, GeometryWorkContext work);
// Certified reference arclength and fixed-parent parameter inversion:
LengthTable referenceChainage(List<MetricPoint> parent, GeometryWorkContext work);
```

The pixel cap means `|F(Q(t))-F(P(t))| <= K` at the same inherited occurrence/edge fraction, where `F(p)=originalInputTransform.toRasterCenter(coordinateFrame.toGeographic(p))`. Use K=1/2 without an added approximation allowance. Exact stored-slot evaluations are necessary; a whole-edge certificate prevents a nonlinear mapping from hiding an interior excess. Keep protected/frozen positions bit exact.

There is a cheap analytic continuous certificate for both actual Mercator classes. Let E/N be the retained frame's east/north metres per radian and phi its latitude. Apart from coordinate origins/phases:

```
F_x = a*x + constant
dF_y/dy = -c*sec(phi)/N
managed: a = 2^zoom * 256 * rasterPixelsPerWorldPixel / (2*pi*E)
         c = 2^zoom * 256 * rasterPixelsPerWorldPixel / (2*pi)
visible: a = R*rasterPixelsPerViewPixel / (projectionUnitsPerViewPixel*E)
         c = R*rasterPixelsPerViewPixel / projectionUnitsPerViewPixel
```

On a fixed certified latitude domain containing the parent, all admissible trials and their connecting metric segments, let `bMax=c*max(sec(phi))/N` and `bMin=c*min(sec(phi))/N`; include phi=0 when the domain straddles the equator. No antimeridian wrap/polar extension is admitted. `U=diag(a,bMax)` and `L=diag(a,bMin)` give, in real arithmetic,

```
|L*(q-p)| <= |F(q)-F(p)| <= |U*(q-p)|.
```

For corresponding straight metric edges, their displacement is affine in t, so the maximum of `|U*(Q(t)-P(t))|` is bounded by its endpoint maximum. That proves the full K cap with constant work per edge. Derived bounds must be outward enclosures including coefficient/evaluation roundoff; compare the upper enclosure with K. Do not call a nominal floating estimate a proof or add its error to K. An indeterminate boundary may reject/freeze under the existing local policy, never pass optimistically. Direct original-transform endpoint measurements remain exported alongside the conservative certificate.

This is a **displacement majorant**, not a source affine. In particular, U distances must not be substituted for original-image distances in a lower-bound switchback-width or apex-retention test: an upper-bound norm can overstate the retained width. Use two-sided certified comparisons of the actual F geometry there, with refinement charged to the same budget. Likewise transformed metric segments need not be straight original-pixel segments; do not compute their true continuous distance from mapped endpoints alone. Existing metric exact topology predicates continue to use original stored metric doubles unchanged.

For generation, freeze `n_i` from the same parent/two-reference-pixel windows and set `nu_i=1/|J_F(P_i)*n_i|`, `N_i=nu_i*n_i`. This is factual local metres/reference pixel, explicitly location/direction dependent. Freeze it once; B retains its one parent linearization and linear DOFs. The conservative U cap may reduce a coherent gain; no per-slot clipping, refitting or extra smoothing run. Affine inputs reduce algebraically to the existing `A^-1`, including skew, with the established arithmetic path preserved for public comparator parity.

Reference longitudinal chainage for non-affine F is the arclength of `F(P(s))`, not a current raster pitch and not a vertex-dependent polygonal approximation. Use the analytic derivative above in bounded deterministic integration/inversion, preserving the same continuous parent under collinear subdivision/reversal. Return certified lower/upper bounds; an undecidable threshold is unavailable/refused, not an epsilon enlargement. A bounded interval implementation can refine dyadic panels under the shared visits/row/memory/deadline limits; it must not create an unbounded auxiliary solver. The public affine fast path needs no integration and stays identical. This is the remaining P2 coordinate-adapter implementation/test obligation, not a P1 source-retention obstacle.

Use the derivative **upper** bound to retain the existing safe `.125/||U||F` physical quadrature step; retain the 16/65536 panel limits. Use certified original-reference edge-length upper bounds for source-screen `ceil(max(lengthBefore,lengthAfter)/.25)` sampling. Source intensities/cell certification still come from the exact selected immutable `ImageCostField` and its own output-grid transform. Keep the existing source-fit thresholds/limitations; pixel coordinate conversion must never sample a different palette or mint branch authority.

Ribbon cells remain in metric geometry with the same occurrence ownership and linear lift. Convert original-pixel feature/clearance restrictions to **conservative** metric coefficient/box restrictions using analytic Jacobian intervals over the complete permitted displacement domain; preserve signed coefficient intervals, rather than discarding cancellation into an unsigned scale. Recheck actual nonlinear original-reference feature/width bounds after lifting. A mapped tangent half-plane alone is not a continuous nonlinear certificate. Local inability to certify follows the existing bounded-island retention rules; complete siblings are not globally disabled.

No truthful one-line `A^-1` replacement covers caps, chainage, source-screen step lengths, apex bands and arm separation simultaneously. P2 must inventory those call sites. Keeping this coordinate adapter separate is smaller and safer than changing Legacy/A/B, changing reference units, or introducing a new numerical contender.

## Acceptance tests and spec impact

P1 additions to the existing publication tests:

1. Real managed and visible captures retain exact transform record/hex parameters, original dimensions and convention across worker/publication settlement. Their optional affine is absent, their source remains eligible, and evidence output transforms cannot masquerade as original input transforms.
2. Same-palette non-top mapping and A/B descendant bind their own frame/evidence; selected raw/aggregate/direct-intensity identities are distinct. Existing original-source/currentness and shared/distinct-array accounting tests remain mandatory.
3. Managed known pixel centers exercise `-0.5` exactly; visible exercises no phase. Actual managed scale 2.0 is preserved. Change current MapView, displayed resample dimensions or evidence grid pitch and assert unchanged original Frame and cap measurements.
4. Missing exact transform/frame/dimensions/epoch is typed unavailable with parent usable; stale generation/epoch after settlement still refuses. Ordinary Legacy→Apply counters prove zero new copies, hashing, mapping, bootstrap, native calls or acquisition.

P2/P3 additive public coordinate tests, under unchanged caps:

* Genuine skew affine reduces to existing calculations/fixtures bit-for-bit on its existing path. No fixture, winner criterion, K, fit threshold or method tuning changes.
* Managed and visible Mercator at nonzero latitude/long north span demonstrate non-affinity; a three-point affine and output-resample substitution fail their negative tests. Include endpoints whose local-J cap estimate would undercount toward higher absolute latitude.
* Independent high-precision/bounded analytic checks verify derivative envelopes, full corresponding-edge cap, non-finite/domain/antimeridian refusal, original-pixel width/apex checks and source-screen spacing. Include a case where endpoint-only transformed chords or an upper-distance width substitution would give an incorrect certificate.
* Same physical original parent with collinear subdivision/reversal and resampled evidence yields the same reference chainage/normal windows and common-slot outputs within the existing declared tolerances. Account integration, inverse-chainage, derivative/guard visits and every allocation under the same 8M/256MiB/100s envelope; small budgets/cancel stop inside these paths. No resets or tighter-limit bypass.

S01, S03–S06 and original authority/history/assignment constraints are unchanged. This fixes S02's factual source binding and preserves its physical 1/2-original-image-pixel intent. S07 still needs the actual whole-output guards; exact transform retention alone is not smoothing qualification. P3 remains the frozen affine public A0/A1/B decision with exactly the same fixtures and numerical criteria. The non-affine adapter is coordinate-contract qualification before production use, never another smoother/parameter sweep or permission for private evaluation.

## Quality assessment and remaining uncertainty

**Accept the P1 exact-transform/optional-genuine-affine correction. Reject fake affine fitting and blanket normal-source unavailability.** Source metadata suffices to implement truthful P1 retention now without acquisition or Legacy/A/B numerical work.

The majorant cap proof above is analytic, not three-sample evidence. Its Java outward-rounding/error enclosure, reference-chainage integration, signed ribbon conversion and nonlinear feature/width certification still require implementation and independent tests; this report does not attest that those APIs exist or are within measured cost. Until P2 qualifies them, the non-affine production smoother must remain unqualified rather than being falsely certified by an output affine. This is a precise remaining coordinate task, not a waiver of eligible managed/visible support or S01–S07.

An approximation certificate could replace parts of that adapter only after a separately reviewed **whole-domain** error proof and conservative propagation through every affected guard; current `RasterTransformCertificate` cannot supply it. No bounded affine approximation has been established by this inspection. The optional genuine-affine path is therefore presently for factual affine producers, not ordinary Mercator captures.
