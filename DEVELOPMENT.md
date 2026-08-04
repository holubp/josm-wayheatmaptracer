# Development Notes

## Build And Test

Build and run the test suite from repo root:

```bash
sh gradlew test build
```

The built plugin jar is written to `build/libs/wayheatmaptracer.jar`.

Debug calibration bundles can be summarized with:

```bash
python3 scripts/analyze-debug-bundles.py /path/to/debug-bundles --raw-csv build/debug-candidates.csv
```

The script expects exported last-slide debug bundles. It reads old bundles with `candidate-metrics.csv` and `candidate-ratings.json`, and uses the corridor CSV files when present. It groups detector performance by visible color, intensity source, subjective rating, SNR, gradient evidence, corridor existence/localization confidence, longitudinal coverage, containment, center wander, lateral acceleration, endpoint approach, Gaussian-scale persistence/drift/conflicts, detector-attempt status, grouping decision, and negative feature tags. Format-5 inputs also report plugin version, ranking components, topology evidence, and physical-distance sanity warnings; format 4 and older are explicitly marked as potentially containing raster-space values in metre-labelled columns.

Format-6 bundles additionally report authoritative projected/ground/native scale fields, canonical bridge ownership, and `detector-performance.csv`. The analyzer reports each detector's sampling, extraction, scale association, tracking/grouping, exact optimization, diagnostics serialization, projection, total/unaccounted time, and transition-to-profile-cost ratio. Format-5 visible-rendered metre fields are explicitly marked untrusted because they used JOSM projection units as metres; managed format-5 source-tile distances remain usable.

Slide roughness and heatmap plateau behavior can be quantified with:

```bash
python3 scripts/analyze-slide-undulations.py /path/to/debug-or-outer.zip --csv build/undulations.csv --json build/undulations.json
```

This script accepts either last-slide debug zips or outer zips containing them. It reports before/after geometry roughness, selected candidate roughness, high-frequency lateral offset residuals, profile support widths, peak counts, gradients, SNR, and source-pixel-scale roughness so detector and smoothing changes can be tuned from exported evidence instead of screenshots.

When consecutive bundles represent repeated slides, the script recognizes that the earlier applied geometry is the later original geometry and reports point growth, length change, bidirectional mean/p95/maximum drift, applicable-candidate count, and warning deltas.

Validate the independent pixel-to-ground conversion matrix with:

```bash
python3 scripts/validate-sampling-scale.py --pretty
```

The validator covers zooms 10-16, 256/512-pixel tiles, representative latitudes, projected capture scales, raster scales 1/6/24, and 1/6/28-metre round trips.

## Coordinate And Scale Contract

| Space | Owning type or field | Permitted use |
| --- | --- | --- |
| Geographic latitude/longitude | `LatLon`, `Bounds` | Downloaded-area checks and ground-distance measurement |
| JOSM projected coordinates | `EastNorth`, `ProjectionBounds` | Capture bounds, OSM geometry, and raster projection |
| Projection units per view pixel | `RenderedCapture.projectionUnitsPerViewPixel` | `MapView.zoomTo` and capture raster transforms only |
| View pixels | cross-section width/step settings | Visible rendered-layer search policy |
| Capture raster pixels | `Point2D`, `SamplingScale.rasterScale` | Scalar sampling, profiles, and candidate optimization |
| Native tile pixels | `SamplingScale.nativeSource*` | Source-resolution uncertainty/normalization only when zoom and tile size are known |
| Ground metres | `ProjectionGroundScale`, profile cumulative distance | Physical diagnostics, gap limits, junction constraints, and corridor-aware quality |
| Legacy compatibility units | `trackerNormalizationMethod=legacy-rendered-pixel-compatibility` | `LEGACY_V02` decision behavior only; never exported as physical/native scale |

One slide constructs one immutable `SamplingScale` and shares it across every mapping and aggregate candidate. Preview pan/zoom must not recompute it.

Rendered palette samples can be collected from debug bundles, calibration tile bundles, extracted JOSM cache tiles, or plain image directories with:

```bash
python3 scripts/heatmap-palette-lab.py /path/to/images-or-bundles --output-dir build/palette-lab --copy-images
```

Use `More tools -> Export Heatmap Calibration Tiles` in JOSM to create a redacted tile bundle for the selected way/segment across `hot`, `blue`, `bluered`, `purple`, and `gray`. The bundle must not contain cookies, signed headers, or signed URLs. Add `--analyze-filters` to emit `filter-summary.csv`, which reports B3/B5 profile-filter center shifts, width changes, and peak-count changes against raw scanlines. Use the palette lab outputs to tune color-to-intensity transformations and convolution filters numerically, then cover any palette or filter behavior changes with fixture or ridge-tracker tests.

## Release Versioning

Keep releases on `0.x.x` until the maintainer explicitly says the plugin is suitable for broader use by others. Do not use a patch release for changes that add user-visible features, settings, workflow changes, or architecture changes.

Use the same release naming convention as the JOSM AudioWptMarker plugin:

- Git tag: `v<version>`, for example `v0.8.5`
- GitHub release title: exactly `v<version>`
- Primary jar asset: `wayheatmaptracer.jar`
- Versioning belongs in the Git tag, GitHub release name, and jar manifest `Plugin-Version`, not in the primary release asset filename.
- Commit subject can describe the change, but the published release name should stay version-only.

JOSM plugin sources expect a stable jar URL. Do not publish a versioned filename such as `wayheatmaptracer-0.8.5.jar` as the primary asset. The stable URL for plugin-list use is:

```text
https://github.com/holubp/josm-wayheatmaptracer/releases/latest/download/wayheatmaptracer.jar
```

When creating GitHub releases from the command line, use `--notes-file` or another newline-safe mechanism so release notes contain real newlines, not literal `\n` text.

After the first broader-use release:

- `1.x.x` releases are for major functionality or architecture changes.
- `1.1.x`-style minor releases are for smaller new features and improvements.
- `1.1.1`-style patch releases are only for bug fixes.

## Fixture-Based Regression Testing

The repository now supports an offline regression harness driven by a fixture archive named `wayheatmaptracer-testing.zip` in repo root.

Expected archive contents:

- `example_before.osm`
- `example_after.osm`
- `ride-hot.zip`
- `ride-blue.zip`
- `ride-bluered.zip`
- `ride-purple.zip`
- `ride-gray.zip`

The regression test:

1. detects only ways whose geometry changed between `before` and `after`
2. extracts the changed subsegment using shared prefix/suffix node refs
3. stitches the per-color cached tiles into offline mosaics
4. runs the production heatmap sampler and ridge tracker against those mosaics
5. compares traced output to the manually checked `after` geometry using tolerant curve metrics
6. enforces a configurable metric acceptance envelope around the full modified `after` way
7. regenerates visual overlay layers for acceptable limits and failing traces

Current scope:

- the harness filters out degenerate cases such as single-node changes and extremely short segments
- it is intended to catch regressions in centerline detection and ridge tracking, not to reproduce every UI detail of JOSM interaction
- if stricter future checks are needed, add a small manifest that records the intended color and selection mode for each changed way
- the acceptance envelope radius defaults to `18.0` meters and can be overridden with `-Dwayheatmaptracer.fixture.acceptableOffsetMeters=<meters>`
- `acceptable-limits.osm` contains left/right side lines plus rounded start/end cap arcs; `violations.osm` contains traces that exceed either the curve metrics or the acceptance envelope

## Core Runtime Flow

1. `AlignWayAction` resolves the editable way segment, validates downloaded-area coverage unless the opt-in local drawing bypass is enabled, and opens a per-slide diagnostic session.
2. `AlignmentService` resolves managed Strava source tiles when complete access values are configured; otherwise it resolves a visible heatmap imagery layer.
3. Managed mosaic origins and tile/crop corners are pixel boundaries. A decoded raster index `(i,j)` is the sample at world-pixel center `(i+0.5,j+0.5)`: world-to-sample conversion subtracts `0.5` before virtual oversampling, and candidate projection adds it back. Aggregate visualization corners remain boundary coordinates. For the visible-layer fallback, `RenderedHeatmapSampler` renders the imagery layer through JOSM into an oversampled raster and records the rendered tile zoom before restoring the user's viewport. The capture uses the required working view resolution over the selected segment extent; if one virtual viewport is too large, the service pans a virtual viewport over the extent and stitches the rendered chunks before sampling. JOSM's capture scale is projection units per view pixel. `ProjectionGroundScale` measures geographic ground scale at slide-time geometry quantiles, while `SamplingScale` separately records ground metres per view/raster pixel, optional native tile resolution, and the tracker normalization policy. The legacy tracker retains its historical six-raster-pixel compatibility normalization; corridor-aware tracking uses factual native resolution when known.
4. No-signal profiles get the 0.2.0 zero-offset fallback peak, but true empty fallback peaks are marked as unsupported so the tracker can bridge them without treating them as real heatmap evidence. Profiles whose anchors are outside the captured rendered raster are marked as off-raster so alignment can fail before applying invented geometry.
5. `TrackerMode.LEGACY_V02` remains the default. It routes profiles through `RidgeTracker` and preserves the 0.2.0-compatible dynamic-programming behavior with the existing longitudinal consistency filter and anchored intervals. Do not change its peak extraction, smoothing, ranking, or fallback behavior as part of corridor-aware calibration.
6. `TrackerMode.CORRIDOR_AWARE` is experimental. It maps source pixels to scalar intensity before filtering, crops to the selected search envelope plus halo, and builds local Gaussian levels. Each next level applies separable B5 `[1,4,6,4,1]/16` in both axes and globally fixed-phase 2x decimation. Managed native mosaics use exact 1x/2x/4x source pitches. The oversampled rendered fallback retains fine rendered L0 for localization and selects the nearest globally phased pyramid reductions around 2x and 4x its reported/estimated source-pixel pitch. Missing kernel support is invalid rather than zero-filled, the estimated extra value/mask storage is capped at 128 MiB, and no synthetic level performs a tile request. Complete all-color detection aggregates native scalar L0 fields first and builds one pyramid from the aggregate. Current-source alternatives each build from their own scalar mapping.
7. Every sampled profile carries one `ProfileSamplingAnchor`: projected source coordinate, sampled-raster coordinate, and monotonic cumulative geographic ground distance in metres. Raster coordinates remain the image-sampling and screen-geometry space. Gap limits, robust-tube windows, endpoint searches, longitudinal coverage, and every other metre threshold consume only cumulative ground distance. The visible path retains its existing selected-vertex anchors and the managed path retains its existing resampled anchors; this contract does not redistribute profiles. Gaussian levels reuse the exact same anchors and normals and express every band back in common L0 sampled-raster coordinates. L0 evaluates two interleaved lateral phases to remove half-source-pixel localization bias, but dilates the B3/B5 kernel indexes so their physical support and source-pixel uncertainty floor stay unchanged; L1/L2 retain one phase. Compatible levels require at least 50% shoulder overlap relative to the narrower band or center separation within `max(0.5 source pixel, combined uncertainty)`. Persistence weights are L0 `0.50`, L1 `0.35`, and L2 `0.15`. Coarse evidence is an uncertainty-gated fallback prior for one fine optimizer, never post-hoc geometry consensus: its positional weight is proportional to `(1 - fine localization confidence)^2`, while persistence remains available at every confidence. A coarse parent shared by fine parallel children contributes persistence but no midpoint; an incompatible nearby coarse trace is a conflict and contributes no prior.
8. Corridor boundaries are linearly interpolated between adjacent valid scalar samples after palette mapping. Invalid raster gaps split intervals and are never interpolated across. Candidate intensity uses the same adjacent-sample interpolation for raw, B3, and B5 values. Source pixels therefore define localization uncertainty and normalization, not the lateral state grid.
9. `CorridorTracker` predicts lateral offset from the preceding physical slope. A residual above 1.5 source pixels requires coherent same-direction support in following profiles; `scaleConflict` and `parentMerge` evidence cannot authorize that move. Unsupported gaps are bounded by both 16 profiles and 20 metres. The higher-index/right observation canonically owns an approved bridge marker regardless of traversal direction, and bidirectional seed merging preserves equivalent bridge approval.
10. `CorridorTubeBuilder` fits robust lines to the selected fine strand against cumulative metres: a local `5 m` half-window clamped to 5-9 observations, a stability `12 m` half-window clamped to 9-17 observations, and a weak-signal reference at `32 m` clamped to 17-33 observations. All use two Huber reweighting iterations. The 32 m reference is blended in continuously only as prominence falls below `0.35` and sustained motion support falls; it does not replace the 5/12 m references. `motionSupport` blends stability toward local geometry only for at least 8 m of coherent same-track motion, including a single sustained apex. Repeated reversals, center disagreement, and scale conflict reduce support. Bounded-interpolation bundle observations carry 45% tube weight. The tube remains pre-optimizer evidence, not replacement geometry or a post-optimization smoother.
11. The optimizer's slope is `deltaOffsetPx / profileSpacingPx`; do not divide that dimensionless value by source-pixel size again. Curvature uses actual candidate segment headings relative to the effective local/stability tube heading and mean source-pixel spacing. Within a broad intensity plateau, variations inside `epsilon=clamp(0.02 + 0.5*mean(|raw-B3|,|B3-B5|),0.02,0.10)` have equal peak-fit cost. Unsupported sub-quarter-source-pixel differences from the stability reference are also equivalent. Non-sustained lateral acceleration receives a `1.15x` multiplier. A continuous weak-prominence/unsupported-motion factor strengthens the tube prior up to its named bound without affecting strong evidence or fully supported motion; low-intensity sine and switchback regressions enforce at least 90% amplitude retention.
12. `CorridorCenterlineOptimizer` keeps at most 21 lateral states per profile, including mandatory raw/B3/B5, core, shoulder, tube, compatible coarse, and endpoint-guide states. It solves the complete second-order dynamic program keyed by `(previousOffset,currentOffset)` with deterministic backpointers. Profile/offset costs, points, headings, and continuity terms are precomputed once, and row-major indexed pair tables preserve the former `LinkedHashMap` insertion and tie order. For `P` profiles and at most `S` states, transition count remains `O(P*S^3)`, expensive invariant evaluation is `O(P*S + P*S^2)`, and pair-state memory is `O(S^2)`; there is no beam pruning, detector pruning, post-optimization moving average, geometry consensus, or hidden rerun. Default data weights are core distance `0.55`, shoulder distance `4.0`, tube center `0.55`, and compatible coarse center `4.0`.
13. Endpoint approaches search 8-15 metres inward for a reliable strand anchor, falling back to the farthest nearer reliable profile. A combined sparse parent may contribute only direct-union, non-multimodal, conflict-free interior evidence; bounded interpolation and ambiguous parent evidence cannot authorize endpoint movement. A cubic Hermite guide joins the constrained endpoint to the selected branch's effective stability/local tube. Fixed endpoints stay exact; movable endpoints remain opt-in and clamped. Precise preview cleanup may remove only a short endpoint-adjacent hook inside the bounded 3-12 m terminal window when a direct shortcut materially reduces the turn; supported turns outside that condition survive. Geometry-dependent safety is then recomputed on the stored final preview.
14. Corridor confidence is continuous. Signal existence combines prominence, boundary gradients, and raw/B3/B5 agreement. Localization confidence combines edge balance, nested-center agreement, core definition, and uncertainty. A weak isolated observation is provisional; longitudinal persistence is required to create a useful track. A persistent fine weak strand remains valid when coarse levels lose it. Do not reintroduce the legacy absolute `0.14`, `0.20`, or `0.30` gates into the corridor-aware path.
15. Elementary strands remain unchanged through longitudinal association. `CorridorGrouping` evaluates all track pairs and forms only all-pairs-compatible groups, preventing transitive A-B-C merging when A-C is incompatible. A sparse parent requires at least 70% direct child-union support and at most 20 degrees tangent difference; interpolation requires evidence on both sides and remains bounded to 16 profiles/20 metres. Strong separation requires both children to cover at least 70% over 20 metres, stable order at least 90%, a deep valley (`<=0.40`) in at least 60% of joint observations, and at least 1.5 source-pixel separation. Dense shallow-valley behavior retains the `>=0.65` parent rule. Parent and child candidates remain visible; heatmap evidence never silently declares lane semantics.
16. Candidates carry diagnostic evidence metadata and projected `EastNorth` geometry for preview/export. Corridor-aware candidates additionally carry unweighted physical metrics: effective-tube residual, overall and non-sustained high-frequency residual, unsupported alternating reversal count/ratio, first/second lateral differences in source pixels, turns and curvature changes in degrees, forward-progress violations, physical gap length, endpoint turn, and true longitudinal persistence. Blocking ripple requires non-sustained p95 above `0.60` source pixel, at least four reversals, and ratio above `0.08`; the sparse quality target is `<=0.40` source pixel. Coverage for a sparse parent counts only direct child-union profiles as observed; approved interpolation is a bridge, never direct support. Debug format 8 adds `corridor-bundles.csv` and `bundle-points.csv` with contributor provenance, uncertainty, occupancy, grouping evidence, and checksums. Formats 1-7 remain readable as unavailable rather than zero-valued sparse evidence.
17. The current-source detector option and the managed all-color aggregate option are independent. When current-source alternative mappings are enabled, detector variants are applied to the selected rendered/manual source or the selected managed color source. In this codebase, detector variant names mean scalar intensity mappings; after intensity conversion, the selected tracker mode runs on that scalar field. When managed all-color aggregation is enabled, source-tile sampling downloads and caches the base Strava colors and adds `all-colors-combined`: each color is converted through its native semantic intensity mapping, the scalar intensity fields are fused with the calibrated weighted power mean (`p=1.25`), and only then are corridors/ridges extracted. The aggregate candidate requires complete matching `hot`, `blue`, `bluered`, `purple`, and `gray` source mosaics; partial aggregates must fail clearly. Finished candidate geometries are never merged across detectors.
18. `ParallelWayContextResolver` and `CorridorAssignmentService` run only when both corridor-aware tracking and the existing opt-in context setting are enabled. They inspect downloaded nearby `highway=*` geometry and limited relevant tags, adjust candidate ranking, and never edit contextual primitives.
19. `AlignmentService` projects every candidate back into map coordinates and stores both the raw tracker ridge and its final candidate-specific preview after fixed-anchor reconstruction and permitted simplification. Corridor-aware candidates also own an immutable stable-node-id to proposed-`EastNorth` map. `PreviewNodeAssignmentPlanner` derives that map without dataset mutation; precise preview safety and `ReplaceWaySegmentCommand` must consume the same assignments. Movable topology endpoints clip the final preview to their bounded local projection, and protected interior nodes are inserted explicitly. Self-intersection, foldback, terminal-turn, and connected-way crossing checks use this final proposed topology. Connected-way findings retain original/proposed junctions, resolved adjacent way and candidate segments, intersection, distance, and tolerance. The chosen candidate prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
20. `AlignWayAction` refuses candidate switching or apply if the modeless preview source way, segment node identities, dataset membership, or source coordinates changed after the slide was computed.
21. `ReplaceWaySegmentCommand` applies the precise-shape result by first validating the candidate-owned protected-node assignments against the shared pure planner, before any mutation. It then assigns reusable mutable nodes monotonically to preview path-length fractions with a bounded sequence dynamic program. Fixed and soft-anchor slots are excluded, unmatched preview slots create nodes, and dropped nodes are removed only when untagged and unreferenced. Reused node target coordinates are immutable command state and must be replayed on every execute/redo.

Full-way selections with 2-5 nodes may be recognized as sketch-like for UI/debug context, but the current visible-layer path keeps the configured alignment mode to preserve predictable runtime behavior.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

The preview overlay uses solid blue for an applicable selected result, dashed red for an inspection-only rejected result, orange dashes for the original segment, and labeled dashed lines for alternatives. The preview dialog is modeless so the mapper can pan/zoom and toggle layer visibility while the overlay remains active. The ridge selector recalculates applicable previews immediately and shows rejected slide-time geometry without enabling Apply.
Candidate changes during preview must use each candidate's slide-time `EastNorth` geometry. Do not reproject candidate screen/raster points through the current `MapView`, because the user may have panned or zoomed before rating or selecting alternatives.

The last-slide debug bundle is created from `DiagnosticsRegistry` and `LastSlideDebugBundle`. It is intentionally focused on the most recent slide attempt and should include redacted settings, sampled colors, intensity source, selected candidate, optional human candidate ratings and negative feature tags from the opt-in preview rating mode, candidate evidence/scoring, raw and calibrated ranking scores, original/preview geometry, candidate ridge geometry, visible-layer sampling metadata, the rendered heatmap capture, managed all-color aggregate visualization when available, and per-slide verbose/debug logs. Format 5 records the runtime plugin version and a short SHA-256 jar identity when available; immutable pre-command geometry in `original-segment.osm`; the proposed result in `preview-segment.osm`; actual post-command geometry in `applied-segment.osm`; raw and final candidate geometry separately; physical profile count/path/spacing; longitudinal coverage and separate measurable-quality/detector-prior ranking components; and structured junction evidence in `junction-safety.csv` plus minimal `junction-context.osm`. Format 7 adds `proposed-node-positions.csv`, original/proposed junction coordinates, and fully resolved proposed candidate/connected segments. Formats 1-6 remain readable and analyzers report proposed topology as unavailable when it was not exported. Existing corridor CSVs remain available. Format-4 post-Apply originals may reflect mutable nodes and its metre-labelled corridor fields may contain raster-space values, so analyzers label those values untrusted rather than silently correcting them. Legacy runs retain compatible empty evidence where applicable. Never include cookies, signed headers, cache credentials, or full signed URLs in diagnostics.

For external regression bundles, both analyzers discover nested debug ZIPs recursively without extracting them first:

```bash
python3 scripts/analyze-debug-bundles.py problems-3.zip --raw-csv build/candidates.csv
python3 scripts/analyze-slide-undulations.py problems-3.zip --csv build/undulations.csv
```

Old bundles remain readable. The analyzers preserve old raw values but flag format-4 physical columns and post-Apply original geometry as untrusted. These commands analyze exported outcomes and do not replay a new tracker implementation against old imagery.

## Corridor-Aware Promotion Gate

Keep `LEGACY_V02` as the default until the maintainer explicitly approves promotion. A promotion review must include the full Gradle suite, `wayheatmaptracer-testing.zip`, palette fixtures for `hot`, `blue`, `bluered`, `purple`, `gray`, and `all-colors-combined`, and rated real-world bundles. Broad-corridor center RMS must remain within half a source pixel, sustained sine/switchback amplitude must retain at least 90%, sparse persistent strands must remain traceable, isolated outliers must not become applicable, endpoint/junction constraints must remain bounded, and parent/child ambiguity must remain visible.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled capture space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through the slide-time capture bounds. Do not reproject them through the current `MapView` after the user has panned/zoomed.
- Metre logic must read `ProfileSamplingAnchor.cumulativeGroundDistanceMeters`; raster anchors and oversampling ratios are not physical distance. Keep visible and managed profile locations unchanged unless a separate sampling change is explicitly approved.
- Visible-layer alignment requires the selected segment to be inside the captured raster. Do not let off-raster fallback profiles silently drive endpoints toward zero offset.
- Managed Strava alignment may route live alignment through `TileHeatmapSampler` when complete managed access values are configured. Keep the legacy visible-layer path as the no-managed-access fallback, and do not add post-hoc geometry consensus, parallel-way scoring, or internal refinement unless the maintainer explicitly asks for that behavior. Combined detectors must operate by intensity-level fusion before ridge detection.
- Stable fixed-scale inference at the default z15 zoom should sample the source tile raster directly. Broad max-dilation at z15 erases cross-section gradients and can make an off-center way appear already aligned; lower-zoom dilation, if used, must remain small and covered by `TileHeatmapSamplerTest`.
- Saturated broad corridors should be centered using their high-intensity core before ridge tracking. Cross-section denoising runs after color-to-intensity mapping and keeps raw, B3 `[1,2,1]`, and B5 `[1,4,6,4,1]` profile evidence. B5 is the primary extraction profile because it kept the fixture regression stable; B3 remains exported as the lighter stability comparison. Both filters use signal-gated power means (`p=2.0` for normal/high signal, `p=1.25` for weak profiles) and conservative blends so empty background is not spread into weak traces. Fine-scale half-phase samples reuse those kernels at doubled index stride, so localization becomes sub-source-pixel without narrowing the denoising filter. Peak and anchor scoring should prefer positions where raw/B3/B5 centers agree. Longitudinal smoothing should treat alternating motion below roughly one source heatmap pixel as aliasing, while preserving sustained low-frequency curvature or switchbacks; cover both behaviors in `HeatmapFixtureArchiveTest` and `RidgeTrackerTest`.
- Rough sketch selections use the configured managed search half-width; do not silently widen them. Candidate applicability must also reject structurally unsafe traces with abrupt lateral jumps or acceleration, because a high-SNR heatmap trace can still be the wrong parallel trace.
- Direct intensity modes are rendered-pixel scalar modes, not a Strava raw-data API. Keep `Color mapping` as the default for Strava PNG tiles unless a documented and permitted raw scalar source is added.
- Repeated-node selections are unsafe because one OSM node cannot carry two independent slide positions. Reject selected segments where a segment node occurs more than once in the way, including occurrences outside the selected range, unless a future implementation explicitly models occurrence identity.
- Modeless previews must be invalidated before candidate switching and before apply when the underlying way node sequence or source node coordinates have changed.
- No-signal candidates may be exported for diagnostics but must not be applicable. Short unsupported runs can be bridged only when there is real heatmap signal before or after the gap.
- Corridor-aware candidates whose informative track terminates locally or contains an unapproved longitudinal gap remain inspection-only. Detector priors must not promote them over complete candidates.
- Connected-way safety checks inspect only segments directly adjacent to the shared junction and ignore intersections inside a source-resolution-aware junction tolerance. Short lateral excursions are measured against the robust corridor tube, so a sharp shape supported by both the tube and heatmap is not rejected merely because raw profile offsets reverse.
- Geometry-dependent safety checks use each candidate's cached final preview after fixed-anchor reconstruction. Debug export must preserve raw tracker geometry separately from final preview geometry, and immutable original geometry separately from actual post-Apply geometry.
- In precise mode, simplification must not be followed by uniform redistribution of points. The simplified centerline density is intentional and should be preserved.
- In precise mode, simplification should run after fixed anchors are restored and per fixed-anchor interval. Do not simplify the whole traced centerline before interval reconstruction, because that can erase all points from one leg of an orthogonal or multi-part segment.
- Downloaded-area bypass and junction/endpoint movement are both opt-in settings. Keep defaults protective.
- When junction/endpoint movement is enabled in precise mode, simplification is ignored so selected or shared nodes are not simplified out of the way.
- If simplification or junction movement removes existing points in precise mode, dropped untagged/unreferenced nodes must be removed from the dataset to avoid leaving stray unconnected nodes behind.
- Tests should protect the 0.2-compatible visible-layer behavior even when that behavior is known to be simpler than the later experimental fixed-tile line.

## Palette Notes

- `hot` is a single-ramp brightness scheme: white/yellow center > orange > red/dark red.
- `blue` is a single-ramp blue/cyan scheme: white or light cyan/blue core > medium cyan/blue > dark saturated blue shoulder.
- `purple` is a single-ramp purple/magenta scheme: bright purple/magenta core > medium purple > dark purple.
- `bluered` is a dual-color semantic scheme: red/magenta high-activity center > purple transition > blue/cyan lower-activity shoulder. Hue and saturation must dominate raw blue/cyan vividness.
- `gray` is dual-color in practice: weak/medium traces may be gray/blue-gray, while high-activity traces can become pink/magenta. The classifier should score both the neutral ramp and the magenta/violet center while still exporting raw scores for calibration.
- `dual` is an internal rendered-layer classifier retained for palette regression tests and preview alternatives. It classifies the same visible rendered layer rather than fetching a separate source tile.
- `bluered-combined`, `gray-combined`, and `multi-combined` are combined-intensity classifiers. Maintain them as weighted compositions of named single classifiers, not as separate ridge-tracker behavior.
- Direct `luminance`, `value`, and `alpha` source modes bypass palette mappings entirely and should be tested against scalar/transparent fixtures rather than palette-ordering assertions.

The palette ranking is heuristic and should be changed together with regression tests in `HeatmapFixtureArchiveTest`.
