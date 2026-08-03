# Visible Rendered Pixel-to-Ground Scale Repair

**Status:** Implemented and verified for target `v0.16.3` on 2026-08-02.

**Target release:** `v0.16.3` bugfix release. This plan changes coordinate-unit handling, diagnostics, and corridor-aware physical normalization. It must not retune palettes, ridge extraction, smoothing, candidate ordering, or the legacy v0.2-compatible detector.

**Implementation evidence:** `SamplingScaleTest` covers the zoom/latitude/tile-size/projected-scale/raster-scale matrix and explicit invalid inputs. The independent Python geographic oracle reports maximum tile-resolution relative error `0.001119`, maximum physical round-trip error `0.0315 m`, zero zoom-ratio error, and no material oversampling drift. Visible capture now freezes tile zoom, native source metadata, projected bounds, and projected scale before restoring the user's viewport. Format-6 exports separate projected, measured ground, native source, decision, and legacy-reference units; format-5 visible physical fields remain readable but are marked untrusted. The full clean Gradle build, Javadoc, Python suite, analyzer replay, manifest check, and diff gate passed.

**Companion plan:** Repeat-convergence, direction-independent bridge approval, exact optimizer performance, and shared format-6 timing diagnostics are specified in [`2026-08-02-223920-01-plan-corridor-repeat-convergence-and-performance.md`](2026-08-02-223920-01-plan-corridor-repeat-convergence-and-performance.md). Both plans target the same bugfix and share the format-6 release gate.

**Goal:** Make every conversion among JOSM projection units, view pixels, oversampled raster pixels, native heatmap tile pixels, and ground metres explicit and numerically validated at every supported sampling resolution. Preserve the current visible-layer capture and legacy detector geometry while correcting physical diagnostics and corridor-aware metre calculations.

**Why planning is required:** The managed source-tile path has a valid ground-resolution formula, but the visible rendered-layer fallback currently names the value passed to `MapView.zoomTo` as metres per pixel. JOSM actually interprets it as projection units per view pixel. At non-equatorial latitudes this produces materially false metre values and false native-source-pixel normalization. A direct global replacement would change the rendered raster, aliasing, profile locations, search corridor, detector decisions, and safety behavior that repository guardrails require the bugfix to preserve.

## Confirmed Baseline

- `TileHeatmapSampler.metersPerPixel(zoom, latitude)` uses the standard Web Mercator resolution for 512-pixel Strava source tiles. Numerical comparison with geographic great-circle distance over zooms 10-16 and latitudes from -70 to +70 degrees has a maximum relative difference of approximately `0.1122%`, attributable to the Web Mercator sphere radius versus the geographic distance model.
- The managed fixed-scale transform uses `sourceMetersPerPixel / 0.389 * 6.0` raster pixels per native source pixel. A 28-metre virtual offset round-trips with a maximum observed error of approximately `0.032 m` in the same numerical matrix.
- `ProfileSamplingAnchor` already derives cumulative longitudinal distance by converting source `EastNorth` anchors to geographic coordinates and summing great-circle distance. Existing tests prove this distance remains invariant at raster scales 1, 6, and 24.
- `AlignmentService.captureVisibleHeatmap` passes `0.389` to `MapView.zoomTo`. That is a projected-coordinate scale, not a ground-metre scale. The corresponding raster-to-`EastNorth` transform is geometrically correct and must remain unchanged in this bugfix.
- The visible path then reuses that projected scale as `viewMetersPerPixel`, `sourceMetersPerPixel`, and the basis of `sourcePixelSizeRasterPx`. Those physical meanings are false away from the equator.
- In Web Mercator at the current fixed projected capture scale, representative true ground resolutions are approximately:
  - latitude 0 degrees: `0.3886 m/view-px`, close to the current `0.389` label;
  - latitude 49.44 degrees: `0.2527 m/view-px`, so 18 view pixels span about `4.55 m`, not `7.00 m`;
  - latitude 70 degrees: `0.1329 m/view-px`, making the current metre label wrong by roughly `193%`.
- A z15, 512-pixel source tile pixel occupies approximately `36.84` pixels in the six-times oversampled rendered capture at the current projected scale, independent of latitude in Web Mercator. The current visible diagnostics and tracker normalization report `6.0`. Expected values are approximately `147.37` at z13, `73.69` at z14, `36.84` at z15, and `18.42` at z16.
- The focused scale and profile suites currently pass, but they do not compare visible capture projection units with ground distance. Existing JSON fixture tests encode the incorrect visible field semantics.

## Recommended Design

Introduce one immutable, unit-explicit sampling geometry contract and keep detector policy separate from factual geometry:

1. The **capture transform** records projection units per view pixel, raster oversampling, capture bounds, and slide-time raster-to-`EastNorth` conversion. It controls where the rendered image and candidates are located.
2. The **physical scale** records measured ground metres per view/raster pixel, optional native source metres per pixel, optional native source pixels in the raster, the source of each value, and spatial variation across the selected extent. It controls honest diagnostics and corridor-aware physical normalization.
3. The **legacy compatibility scale** records the historical six-raster-pixels-per-rendered-pixel normalization used by `TrackerMode.LEGACY_V02`. It controls only the decisions that must remain byte-for-byte or tolerance-for-tolerance compatible. It must never be exported or labeled as native source resolution or physical metres.

For managed source tiles, the physical and decision scales are the same. For visible rendered layers, physical and legacy compatibility scales may differ. This deliberate separation repairs the unit contract without changing the image presented to the legacy ridge detector.

### Mathematical Contract

- Managed or recognized source-tile ground resolution:

  `source_mpp = cos(latitude) * 2 * pi * 6378137 / (tile_size * 2^zoom)`

  Keep the existing 512-pixel overload for Strava and add an explicit tile-size overload for validated generic tile sources.

- Visible capture transform:

  `raster_x = (east - min_east) / projection_units_per_view_px * raster_scale`

  `raster_y = (max_north - north) / projection_units_per_view_px * raster_scale`

  The inverse transform must use the same slide-time bounds and projected scale. No current `MapView` state may participate after capture.

- Visible physical scale at a slide-time anchor:

  `east_ground_per_projection_unit` and `north_ground_per_projection_unit` are measured by central differences: transform the anchor plus/minus a finite projected delta through the active projection and use geographic great-circle distance. The representative conformal scale is the geometric mean of both axes. Record the axis values and anisotropy rather than assuming they are identical.

  `ground_m_per_view_px = projection_units_per_view_px * representative_ground_per_projection_unit`

  `ground_m_per_raster_px = ground_m_per_view_px / raster_scale`

- When native source resolution is known:

  `native_source_px_in_raster = native_source_mpp / ground_m_per_raster_px`

- Search width and step on the visible fallback remain configured in view pixels. Export their measured physical extent as:

  `effective_half_width_m = effective_half_width_view_px * ground_m_per_view_px`

  `effective_step_m = effective_step_view_px * ground_m_per_view_px`

  Do not describe the historical `pixels * 0.389` reference calibration as actual metres.

## Alternatives Considered

### Option A: Change the visible capture to a constant ground resolution

Calculate a projected `MapView` scale for exactly `0.389 ground m/px` at the selected latitude. This is conceptually clean, but it changes the JOSM-rendered raster, requested tile level, source-pixel footprint, aliasing, search extent, and every legacy candidate. It is a separate sampling feature or major detector recalibration, not a safe patch repair.

### Option B: Correct only field names and logs

Rename `viewMetersPerPixel` to projected units and leave all downstream physical calculations untouched. This protects legacy results but leaves corridor-aware thresholds, source-pixel normalization, junction displacement limits, and quality metrics numerically wrong. Reject this as incomplete.

### Option C: Replace every use with measured ground metres

This gives physically correct values but silently changes legacy tracking, safety, ranking, and applicability. It violates the explicit v0.2-compatible guardrail. Use measured values for corridor-aware and diagnostic paths while retaining a narrowly named compatibility value for legacy decisions.

### Option D: Infer visible native source resolution from raster texture

Estimating tile pixels from image gradients or resampling artifacts is nondeterministic and palette-dependent. Prefer layer metadata and recognized Strava URLs. If source resolution is unknown, state that explicitly and use the historical rendered-pixel normalization only as a labeled compatibility fallback.

## Non-Goals

- Do not alter color-to-intensity mappings, blur kernels, peak extraction, longitudinal optimizer weights, broad-corridor centering, endpoint shaping, or simplification.
- Do not change the current fixed projected scale, chunked virtual viewport capture, capture bounds, source profile locations, cross-section sample indexes, or raster-to-map projection.
- Do not introduce fixed-tile inference into the visible fallback.
- Do not alter managed tile fetching, cache behavior, all-color aggregation, or aggregate visualization except to share the corrected scale metadata.
- Do not loosen or add candidate safety gates as compensation for changed metrics.
- Do not include user files, coordinates, archives, cookies, signed URLs, or cache credentials in tests or documentation.

## Acceptance Criteria

- Legacy visible-layer fixtures produce the same candidate ids, profile sample coordinates, ridge offsets, ordering, applicability, and structural warnings as before this patch. Any changed legacy result is a stop condition unless an existing test was proving a physically impossible assertion and the maintainer approves the behavior change separately.
- Managed source-tile candidate geometry and ranking remain unchanged within floating-point tolerance.
- Every public/internal scale field has a unit in its name or documentation. No value in projection units is called metres.
- At zooms 10-16, tile sizes 256 and 512, latitudes -70 to +70 degrees, view projected scales from `0.09725` through `1.556`, and raster scales 1, 6, and 24:
  - analytical source metres per pixel agree with the geographic oracle within `0.15%`;
  - one-raster-pixel and 1, 6, and 28-metre round trips agree within `0.05 m` or `0.15%`, whichever is larger;
  - changing raster oversampling changes pixel counts but not reconstructed ground displacement;
  - changing zoom changes native source-pixel size by the expected factor of two without changing a fixed physical candidate displacement.
- Visible format-6 diagnostics at latitude near 49.44 degrees report approximately `0.253 m/view-px`, `0.0421 m/raster-px`, and approximately `36.84 raster-px/source-px` for a recognized z15 512-pixel Strava source, subject to the geographic-distance tolerance.
- Unknown visible source layers export `nativeSourceMetersPerPixel: null`, `nativeSourcePixelSizeRasterPx: null`, and a machine-readable unknown/fallback reason. They never report the legacy value `6` as native source resolution.
- Corridor-aware physical thresholds consume measured ground scale. Legacy decisions consume an explicitly named compatibility scale. Tests prove the two paths do not leak into each other.
- Debug analyzers read formats 1-6, flag old visible physical fields as untrusted, and preserve trusted managed format-5 values.
- Full Gradle tests, Javadoc, Python analyzer tests, `git diff --check`, jar manifest validation, and repository review gates pass before release.

## Expected File Ownership

- `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/AlignmentService.java`: construct and route the capture transform, physical scale, compatibility policy, diagnostics, and decision-specific scale consumers.
- `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/TileHeatmapSampler.java`: expose the tile-size-aware source-resolution formula without changing the managed 512-pixel default.
- New focused service types under the same package: `SamplingScale`, `ProjectionGroundScale`, and `VisibleSourceResolutionResolver`, unless an existing local type clearly owns one responsibility. Keep these separate from palette and tracker classes.
- `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model/AlignmentDiagnostics.java`: render unit-correct preview summaries.
- `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/LastSlideDebugBundle.java`: publish format 6 and preserve privacy/redaction.
- Tests under the matching `src/test/java/.../service` and `.../diagnostics` packages: numerical matrix, source-resolution resolver, legacy golden behavior, managed golden behavior, debug schema, and integration invariance.
- `scripts/analyze-debug-bundles.py`, `scripts/analyze-slide-undulations.py`, new `scripts/validate-sampling-scale.py`, and `scripts/tests/`: backward-compatible analysis and a cheap independent Web Mercator oracle.
- `AGENTS.md`, `DEVELOPMENT.md`, and the README debugging section: durable coordinate-unit contract and user-facing interpretation.

## Outcome 1: Lock the Defect with Numerical and Behavioral Tests

- **Dependencies:** None.
- Add a focused `SamplingScaleTest` or equivalently named test class before changing production code. Use JOSM's active projection and geographic distance implementation, not a second copy of the production formula as the only oracle.
- Cover zooms 10-16, latitudes `-70`, `-49.44`, `0`, `49.44`, and `70`, source tile sizes 256 and 512, projected view scales `0.09725`, `0.1945`, `0.389`, `0.778`, and `1.556`, and raster scales 1, 6, and 24.
- For each matrix point, compare the analytical native tile resolution with geographic distance between adjacent world-pixel coordinates. Assert the `0.15%` tolerance and finite positive values.
- Test raster-to-map-to-geographic round trips for one raster pixel and for requested physical offsets of 1, 6, and 28 metres in east, west, north, and south directions. Assert the `0.05 m`/`0.15%` envelope.
- Add explicit expected-ratio assertions for z13-z16 native source pixels in a six-times visible rendered capture. These catch a recurrence of the false constant `6` source-pixel size.
- Add invalid-input tests for non-finite latitude, impossible zoom, zero/negative tile size, zero/negative projected scale, and zero/negative raster scale. Fail with unit-specific messages rather than producing `NaN` downstream.
- Extend `RenderedHeatmapSamplerProfileTest` to prove that changing only physical scale metadata does not move source or raster anchors.
- Add a legacy golden regression around a deterministic rendered fixture. Capture candidate ids, detector labels, exact profile offsets, candidate ordering, applicability, and warnings before production changes. Avoid serializing scores that are intentionally diagnostic-only unless they affect ordering.
- Add a managed golden regression showing current fixed-tile candidate geometry and source-pixel normalization before refactoring.
- **Stop condition:** Do not continue if a test cannot distinguish JOSM projection units from ground metres or if the legacy baseline depends on current `MapView` state after capture.
- **Verify:** `sh gradlew test --tests '*SamplingScaleTest' --tests '*RenderedHeatmapSamplerProfileTest' --tests '*TileHeatmapSamplerTest' --tests '*CorridorScaleInvarianceTest' --tests '*AlignmentServiceTest'`

## Outcome 2: Introduce Explicit Capture and Physical Scale Types

- **Dependencies:** Outcome 1.
- Add a small immutable `SamplingScale` service type, or a name consistent with neighboring records, with documented fields:
  - sampling source type;
  - projection units per view pixel, optional where not applicable;
  - raster oversampling scale;
  - measured ground metres per view pixel;
  - measured ground metres per raster pixel;
  - optional native source metres per pixel;
  - optional native source pixel size in raster pixels;
  - native-resolution known flag and resolution method;
  - east/north ground metres per projection unit;
  - minimum/median/maximum measured scale along the selected source geometry;
  - anisotropy and longitudinal variation ratios;
  - tracker normalization pixels and normalization method.
- Validate all required values in the constructor. Optional native values must be both present and positive or both absent. Do not encode unknown values as `0`, `1`, `6`, or `NaN` in the model.
- Split the current `EffectiveSampling` responsibilities. Keep configured/effective cross-section pixel policy there, but delegate physical conversions and tracker normalization to `SamplingScale`. Avoid a large record whose same scalar can mean physical truth in one branch and compatibility in another.
- Rename visible capture fields and methods from `viewMetersPerPixel` to `projectionUnitsPerViewPixel`. Rename `visibleCaptureMarginMeters`, `expandedBounds(... marginMeters)`, and analogous local variables to projected-unit terminology without changing values or arithmetic.
- Keep `toCaptureRasterPoint` and `projectRenderedCandidate` formulas unchanged except for unit-correct names and use of the immutable capture transform.
- Add Javadoc to every new class, record component, constructor, and nontrivial method. State units, coordinate space, whether a value is factual or compatibility policy, and valid ranges.
- **Stop condition:** Search production code for any capture projected scale still named `meters`, or any method that can return a fake native source resolution for an unknown layer.
- **Verify:** `! rg -n 'capture.*MetersPerPixel|viewMetersPerPixel|marginMeters' src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service` and `sh gradlew test --tests '*SamplingScaleTest' --tests '*AlignmentServiceTest'`

## Outcome 3: Measure Visible Ground Scale at Slide Time

- **Dependencies:** Outcome 2.
- Add a `ProjectionGroundScale` helper, or equivalent, which accepts the active JOSM projection, slide-time `EastNorth` anchors, projected view scale, and raster scale. It must not inspect the current modeless preview viewport.
- Measure central-difference east and north scale at source-geometry quantiles 0%, 25%, 50%, 75%, and 100%. Use a projected delta equal to 100 slide-time view pixels, `projectionUnitsPerViewPixel * 100`, so it is tied to the actual capture transform and large enough to avoid floating-point cancellation. Validate this baseline against shorter and longer deltas in the numerical tests.
- Convert perturbed `EastNorth` values through `ProjectionRegistry.getProjection().eastNorth2latlon` and use `LatLon.greatCircleDistance`. Use the geometric mean of east/north scale as the representative value for conformal projections. Export both axes and the anisotropy ratio.
- Use the median representative value across the five anchors for all global lateral conversions in this bugfix. Preserve min/max and relative variation. Do not add per-profile tracker normalization here; that would change the tracker contract and belongs in a separately approved detector improvement.
- If a required conversion is non-finite, non-positive, east/north anisotropy exceeds 2%, or longitudinal representative-scale variation exceeds 2%, fail corridor-aware detection with an actionable scale error. Legacy visible detection may continue with compatibility policy, but diagnostics must say physical scale is unavailable rather than fabricating metres.
- The 2% guard is part of the initial contract. Tests must cover realistic selected-way lengths and projection locations. Changing it requires evidence and an explicit plan update, not an implementation-time guess.
- Replace visible diagnostic `dist100PixelMeters = projectedScale * 100` with a true slide-time geographic measurement. Keep `mapScale` separately and label it projection units per view pixel.
- **Stop condition:** Pan or zoom the preview in an integration test and prove all scale values and candidate geometry remain tied to slide-time capture state.
- **Verify:** `sh gradlew test --tests '*SamplingScaleTest' --tests '*AlignmentServiceTest' --tests '*RenderedHeatmapSamplerProfileTest'`

## Outcome 4: Resolve Visible Native Source Resolution Without Guessing

- **Dependencies:** Outcome 3.
- Add a `VisibleSourceResolutionResolver`, or equivalent isolated helper. Its result contains optional zoom, optional tile size, optional native source metres per pixel, a confidence/known flag, and a machine-readable method.
- Resolution priority:
  1. the plugin-managed Strava layer id and recognized Strava `globalheat` URL contract use 512-pixel source tiles;
  2. a supported `TMSLayer` may use public tile-source metadata when its reported tile size and zoom are valid;
  3. an unrecognized or non-tile visible layer remains unknown.
- Do not use reflection, protected API access, image-gradient inference, current screen DPI, or `ImageryLayer.getPPD()` as native source resolution.
- Generalize `TileHeatmapSampler.metersPerPixel` with an explicit tile-size overload. Retain the existing overload as the tested 512-pixel Strava default so managed behavior does not change.
- For known visible source resolution, compute native source pixels in the rendered raster from the measured physical scale. For unknown sources, leave native fields absent.
- Supply a separate tracker-normalization method:
  - managed source tiles: factual native source pixels in raster;
  - recognized visible Strava/TMS source in corridor-aware mode: factual native source pixels in raster;
  - legacy visible mode and unknown rendered sources: historical `RenderedHeatmapSampler.RASTER_SCALE`, labeled `legacy-rendered-pixel-compatibility`.
- An unknown native resolution alone must not turn a previously usable legacy visible candidate into no-signal. It does prevent claims and tests based on native-source-pixel physical accuracy.
- Add resolver tests for managed layer id, recognized Strava URL, valid generic 256-pixel TMS metadata, invalid zoom, invalid tile size, and unknown imagery layer.
- **Stop condition:** No code path may serialize `6.0` under `nativeSourcePixelSizeRasterPx` unless it was actually calculated from known native and ground resolutions and happens to equal six.
- **Verify:** `sh gradlew test --tests '*VisibleSourceResolutionResolverTest' --tests '*SamplingScaleTest' --tests '*TileHeatmapSamplerTest'`

## Outcome 5: Integrate the Scale Contract While Isolating Legacy Behavior

- **Dependencies:** Outcomes 2-4.
- Build the visible `SamplingScale` immediately after capture from slide-time bounds, projected scale, source geometry, raster scale, layer metadata, and rendered tile zoom. Pass the immutable object through detection, metrics, diagnostics, and safety annotation instead of reconstructing scale ad hoc.
- Preserve visible sample anchors, cross-section offsets, no-signal fallback, first-informative-profile seeding, long unsupported-run behavior, candidate labels, detector priors, and candidate geometry.
- In `TrackerMode.LEGACY_V02`, use the compatibility tracker normalization and the existing decision calibration for all behavior-affecting legacy tracking, ranking, and safety. Use the factual physical scale only for corrected diagnostics in additional fields. Do not change a legacy decision by replacing its old reference calibration during this patch.
- In corridor-aware visible mode, use measured ground scale for metre thresholds and known native source resolution for source-pixel-normalized residuals. If native source resolution is unknown, use the explicitly labeled rendered-pixel fallback and export that source-normalized quality is uncalibrated.
- Keep visible cross-section configuration in pixels. The current conversion through reference `0.389` should not rescale configured pixel counts at the fixed capture scale. Refactor it into a clearly named compatibility/reference policy, then export measured ground width and step separately.
- Audit `sourceMetersPerPixel`, `sourcePixelSizeRasterPx`, `rasterMetersPerPixel`, `effectiveHalfWidthMeters`, and `effectiveStepMeters` consumers. Classify each as:
  - geometry transform;
  - legacy decision compatibility;
  - corridor-aware physical decision;
  - display/debug only.
  Add a short table to `DEVELOPMENT.md` and remove ambiguous direct accessors.
- Specific consumers to inspect include tracker calls, candidate metric construction, robust tube residual conversion, endpoint/junction displacement limits, self-intersection and lateral-excursion tolerances, candidate quality/ranking inputs, and verbose/CSV export.
- Add assertions that one slide uses one immutable scale contract for every detector mapping and all-color aggregate candidate. Alternative detector mappings may change intensity semantics but never geometry scale.
- **Stop condition:** If the legacy golden regression changes, revert the behavior-affecting use and keep only the factual diagnostics correction. Do not update the baseline to bless an unexplained delta.
- **Verify:** `sh gradlew test --tests '*RidgeTrackerTest' --tests '*FixtureRegressionTest' --tests '*AlignmentServiceTest' --tests '*CorridorScaleInvarianceTest' --tests '*CorridorRasterIntegrationTest'`

## Outcome 6: Prove Managed and Visible Resolution Invariance End to End

- **Dependencies:** Outcome 5.
- Extend `CorridorScaleInvarianceTest` with equivalent synthetic traces rendered at:
  - visible projected scales `0.1945`, `0.389`, and `0.778`;
  - raster scales 1, 6, and 24;
  - native tile zooms 13-16;
  - representative latitudes 0, 49.44, and 70 degrees.
- For corridor-aware candidates, compare physical lateral offsets, search corridor extents, tube residuals, endpoint approach distances, acceleration in physical units, and junction displacement tolerances. Require equivalent values within `0.05 m` or `0.15%` where discretization does not dominate; use a documented source-pixel envelope for peak quantization.
- Verify that a one-source-pixel lateral displacement produces twice as many metres at z14 as z15, while a fixed six-metre displacement remains six metres at either zoom.
- Verify all four cardinal displacement directions and diagonal normals. This catches accidental use of only east-west scale in cross-sections.
- Add a multi-scale detector test proving L0/L1/L2 downsampling changes raster coordinates by the scale factor while reconstructed source geometry and physical distances remain invariant.
- Add junction tests proving the same configured maximum movable-node distance and crossing tolerance at different latitude, zoom, and raster scales. Keep junction movement opt-in and fixed anchors exact.
- Add a long-way scale-variation test. Corridor-aware mode must either use adequately local physical values or fail/mark inspection-only at the documented variation threshold; it must not silently apply a materially inaccurate midpoint conversion.
- Run existing managed all-color aggregation tests and prove all base mosaics share the same zoom, tile size, bounds, and `SamplingScale` before intensity fusion.
- **Stop condition:** Do not loosen candidate safety limits to make the invariance matrix pass. Repair the unit used by the limit.
- **Verify:** `sh gradlew test --tests '*CorridorScaleInvarianceTest' --tests '*MultiScaleCorridorTrackingTest' --tests '*CorridorEndpointApproachTest' --tests '*AlignmentServiceTest' --tests '*HeatmapFixtureArchiveTest'`

## Outcome 7: Publish an Honest Format-6 Debug Contract

- **Dependencies:** Outcomes 5-6.
- Increment last-slide debug format from 5 to 6 because existing visible physical fields have incorrect semantics. Preserve readers for formats 1-5.
- Add a `samplingScaleVersion` and export at least:
  - `projectionUnitsPerViewPixel`;
  - `groundMetersPerViewPixel`;
  - `groundMetersPerRasterPixel`;
  - `nativeSourceMetersPerPixel`, nullable;
  - `nativeSourcePixelSizeRasterPx`, nullable;
  - `nativeSourceResolutionKnown`;
  - `nativeSourceResolutionMethod`;
  - `trackerNormalizationRasterPx`;
  - `trackerNormalizationMethod`;
  - east/north ground scale and anisotropy;
  - min/median/max scale over selected anchors and relative variation;
  - configured/effective half-width and step in view pixels;
  - measured effective half-width and step in ground metres;
  - projected capture bounds, raster scale, source zoom, tile size, chunk count, and slide-time center latitude.
- Retain old field names only where compatibility requires them. In format 6, either populate them with corrected physical values and document the semantic change, or set them absent and migrate every in-repository reader. Never leave duplicate fields with contradictory meanings.
- Add `legacyCompatibilityScaleUsed` and its method as explicit booleans/strings, but do not present compatibility values as real metres or native pixels.
- Update `AlignmentDiagnostics` preview text. For visible capture, show both `capture 0.389 projection-units/view-px` and measured ground/raster resolution. For known sources, show `source z15, 512 px tiles, ~36.84 raster px/source px`; for unknown sources, say `native source resolution unavailable`.
- Update verbose logging and every CSV/JSON diagnostic that currently copies ambiguous scale fields. Candidate physical offsets must use factual ground scale; legacy decision values may be exported in separately named compatibility columns only.
- Update `scripts/analyze-debug-bundles.py` and `scripts/analyze-slide-undulations.py`:
  - format-6 fields are authoritative;
  - format-5 managed source-tile physical fields remain trusted;
  - format-5 and older visible `viewMetersPerPixel`, `rasterMetersPerPixel`, and derived native-source fields are marked untrusted rather than silently recalculated without enough context;
  - nested archive discovery and current CLI options remain compatible.
- Add generated format-5 managed, format-5 visible, and format-6 fixtures to `scripts/tests/test_debug_analyzers.py`. Do not use user debug data.
- Preserve all existing privacy redaction and candidate rating exports. No layer URL containing a signature or cookie value may appear in the new resolution metadata.
- **Stop condition:** Open one generated format-6 bundle and trace every `_m`, `_m_per_px`, `_projection_units`, and `_raster_px` field back to a unit-explicit source. Any ambiguous field blocks release.
- **Verify:** `sh gradlew test --tests '*LastSlideDebugBundleTest' --tests '*AlignmentServiceTest' && python3 -m unittest discover -s scripts/tests -p 'test_debug_analyzers.py' && python3 -m py_compile scripts/analyze-debug-bundles.py scripts/analyze-slide-undulations.py`

## Outcome 8: Add a Reproducible Scale Audit Tool and Documentation

- **Dependencies:** Outcome 7.
- Add `scripts/validate-sampling-scale.py`, implemented with the Python standard library only, to emit a concise table or CSV for the Web Mercator source-resolution matrix. It should accept zooms, latitudes, tile size, projected view scale, and raster scale as arguments, validate inputs, and compare analytical and geographic distances.
- Keep Java/JOSM tests authoritative for projection integration. The Python script is an inexpensive calibration/debug aid and must clearly state that it validates Web Mercator math, not arbitrary JOSM projections.
- Add Python unit tests for argument validation, known z15 values, zoom factor-of-two behavior, raster-scale invariance, and machine-readable output.
- Update `DEVELOPMENT.md` with a coordinate-space table: geographic coordinates, JOSM `EastNorth` projection units, view pixels, capture raster pixels, native tile pixels, longitudinal ground metres, and legacy compatibility units. For each, list the owning type and permitted consumers.
- Update `AGENTS.md` to guard against reintroducing the defect: `MapView.getScale()` and `zoomTo` use projected units per pixel; only geographic measurement yields ground metres; native source-pixel size requires known tile zoom and tile size; legacy compatibility normalization must remain isolated and explicitly named.
- Update the README debugging section so users can interpret the sampling line and understand why projected and ground resolution can differ. Do not expose internal compatibility scores as primary UI.
- Cross-link this plan from the implemented `v0.16.2` plan as a corrective follow-up without rewriting historical findings or status.
- **Verify:** `python3 -m unittest discover -s scripts/tests -p 'test_sampling_scale.py' && python3 -m unittest discover -s scripts/tests -p 'test_debug_analyzers.py' && python3 scripts/validate-sampling-scale.py --zooms 13,14,15,16 --latitudes 0,49.44,70 --tile-size 512 --view-projection-scale 0.389 --raster-scale 6 && sh gradlew javadoc`

## Outcome 9: Complete Regression and Release Gate

- **Dependencies:** Outcomes 1-8.
- Run the full repository suite and inspect failures by coordinate contract. Do not update broad expected outputs until the legacy and managed golden tests prove detector geometry did not change.
- Run fixture archives for blue, gray, purple, bluered, hot, aggregate all-color, weak sparse strands, broad corridors, sustained turns/switchbacks, fixed endpoints, opt-in movable junctions, and no-signal candidates. This is regression protection, not a palette recalibration.
- Exercise both inference modes and both data sources:
  - visible rendered fallback with legacy tracker;
  - visible rendered fallback with corridor-aware tracker;
  - managed stable fixed scale;
  - managed raw high resolution;
  - native detector mappings and all-color aggregate.
- Manually inspect one generated format-6 visible bundle and one managed bundle. Confirm source zoom, tile size, capture bounds, projected scale, ground scale, raster scale, physical search width, source-pixel size, and tracker normalization are internally consistent.
- Review the diff for any accidental change to palette mappings, profile sampling positions, legacy ridge code, smoothing, ranking, safety thresholds, endpoint handling, simplification, undo/redo, cache, aggregate rendering, or preview projection.
- Run repository review-and-commit and quality-gate workflows. Keep `exceptions.txt`, `human-guesstimate.osm`, user archives, extracted images, generated CSVs, and credentials untracked and unstaged.
- Release only as patch `v0.16.3`, with primary asset `wayheatmaptracer.jar` and manifest `Plugin-Version: 0.16.3`, after explicit execution authorization. Planning alone does not authorize commit, push, tag, or release.
- After release, request one fresh format-6 export from the same way sampled through managed and visible fallback modes. Compare physical geometry and scale metadata; do not use old format-5 visible metre fields as a ground-truth oracle.
- **Verify:** `sh gradlew clean test build javadoc && python3 -m unittest discover -s scripts/tests -p 'test_*.py' && python3 -m py_compile scripts/*.py && git diff --check && unzip -p build/libs/wayheatmaptracer.jar META-INF/MANIFEST.MF | rg 'Plugin-Version: 0.16.3'`

## Execution Order and Stop Conditions

1. Complete Outcome 1 and preserve the red tests plus golden behavior before production edits.
2. Complete Outcomes 2-4 as the unit model and scale-source foundation. Stop if unknown source resolution is represented by a plausible-looking number.
3. Complete Outcome 5 and run legacy/managed golden tests immediately. Stop on any unexplained geometry, ordering, applicability, or warning delta.
4. Complete Outcome 6. Repair the coordinate conversion, not thresholds, when invariance fails.
5. Complete Outcome 7 before requesting any new user debug archive. The next archive must contain enough information to audit the conversion without guessing.
6. Complete Outcome 8 and independently compare the Python matrix, Java tests, and one generated bundle.
7. Complete Outcome 9 only after all unit-bearing fields and behavior-affecting consumers have been audited.

## Handoff Notes for Lower-Cost Implementers

- Read this plan, `AGENTS.md`, and the coordinate/runtime sections of `DEVELOPMENT.md` before editing.
- Treat variable names as part of the correctness contract. `projectionUnitsPerViewPixel`, `groundMetersPerRasterPixel`, and `nativeSourcePixelSizeRasterPx` are different quantities even when two happen to be numerically close near the equator.
- Do not change `captureVisibleHeatmap` scale or raster-to-`EastNorth` formulas in this patch. Rename them accurately and attach measured physical metadata.
- Do not pass the factual visible native-source scale into `RidgeTracker` under `LEGACY_V02`. The required compatibility value is a detector policy, not a statement about the source tile.
- Do not use `EastNorth.distance` as ground metres. Convert through the active projection and use geographic distance.
- Do not use the current map viewport after capture. Preview pan/zoom and layer toggles must not alter slide-time scale or candidate projection.
- Add tests before changing each behavior-affecting consumer. A passing numerical helper test is insufficient if `AlignmentService` still sends the wrong scale to tracking, safety, or ranking.
- Keep source-resolution unknown when evidence is missing. Honest null metadata plus labeled compatibility behavior is preferable to a false precise number.
- Run the focused verification command after every outcome. Do not defer all failures to the full suite.
- Before commit, inspect `git status`, `git diff --check`, test output, Javadoc, analyzer output, jar manifest, and staged paths. Never stage user-provided diagnostics or ground truth.
