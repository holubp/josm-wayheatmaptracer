# Development Notes

## Build And Test

Build and run the test suite from repo root:

```bash
sh gradlew test build
```

The built plugin jar is written to `build/libs/wayheatmaptracer-<version>.jar`.

## Release Versioning

Keep releases on `0.x.x` until the maintainer explicitly says the plugin is suitable for broader use by others. Do not use a patch release for changes that add user-visible features, settings, workflow changes, or architecture changes.

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
- the acceptance envelope radius defaults to `15.0` meters and can be overridden with `-Dwayheatmaptracer.fixture.acceptableOffsetMeters=<meters>`
- `acceptable-limits.osm` contains left/right side lines plus rounded start/end cap arcs; `violations.osm` contains traces that exceed either the curve metrics or the acceptance envelope

## Core Runtime Flow

1. `AlignWayAction` resolves the editable way segment, validates downloaded-area coverage unless the opt-in local drawing bypass is enabled, and opens a per-slide diagnostic session.
2. For configured managed Strava access, `TileHeatmapSampler` samples fixed source tiles for each detection color. The default `Stable fixed scale` mode normalizes primary inference to a known-good effective scale, then keeps higher-resolution source tiles for validation. This path must not depend on map viewport, layer visibility, opacity, transparency, or HSL adjustments.
3. `RenderedHeatmapSampler` remains the fallback for unresolved manual imagery layers. It renders the visible imagery layer into an oversampled raster and is therefore view/style dependent by design.
4. No-signal profiles stay empty so the tracker can bridge gaps using later coherent ridge evidence instead of snapping to the source axis.
5. `RidgeTracker` builds one or more ridge candidates from the sampled cross-sections and ranks them by full-segment continuity, curvature, local palette evidence, support width, and oscillation penalties. Seeds are collected across the whole segment so off-axis or sketch inputs are not limited by the first few profiles.
6. Narrow outlier strands are penalized only when the same profile has a stronger dominant center. Do not globally remove low-intensity narrow peaks, because lightly used ways may be represented only by sparse wandering strands.
7. Candidates carry evidence metadata such as total profile count, supported profile count, empty profile count, longest no-signal gap, total/mean intensity, SNR, ambiguity, and consensus modes. All-empty profiles may produce a diagnostic no-signal placeholder, but live alignment must discard no-signal placeholders when any real evidence exists.
8. Managed fixed-tile alignment intentionally does not run iterative internal refinement. One user run samples the full configured search corridor at the stable inference scale, ranks candidates, validates them against source tiles, hard-rejects only structurally unsafe candidates, and keeps weak-but-real candidates previewable with warnings. This avoids accepting a later refinement pass that drifted onto a sparse or parallel trace while still leaving mapper judgment in the preview loop.
9. When multi-color detection is enabled, candidates from all palette classifiers are compared for geometric agreement. Consensus primarily boosts stable per-color ridges by detector agreement; fused consensus geometry is produced only when agreeing ridges are close, smooth, and non-oscillating. Weak/no-signal modes must not boost a candidate. Individual per-color candidates remain available as preview alternatives for diagnostics and mapper judgment.
10. When enabled, parallel-way awareness scans nearby mapped `highway=*` ways and de-prioritizes candidates that appear to match another mapped parallel way. Managed candidates must be compared in projected `EastNorth` space, not against rendered-layer screen pixels. Alternatives remain visible in the preview ridge selector.
11. `AlignmentService` projects the chosen candidate back into map coordinates and prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
12. `ReplaceWaySegmentCommand` applies the precise-shape result by reusing existing nodes where possible, creating additional nodes when needed, and deleting dropped untagged/unreferenced nodes.

Full-way selections with 2-5 nodes are treated as sketch-like input and use precise-shape output automatically, even if the saved alignment mode is `Move Existing Nodes`. Short selected segments of longer existing ways keep the configured alignment mode.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

The preview overlay uses solid blue for the selected result, orange dashes for the original segment, and labeled dashed lines for alternative ridge candidates. The preview dialog is modeless so the mapper can pan/zoom and toggle layer visibility while the overlay remains active. The ridge selector recalculates the preview immediately when the selected candidate changes.

The last-slide debug bundle is created from `DiagnosticsRegistry` and `LastSlideDebugBundle`. It is intentionally focused on the most recent slide attempt and should include redacted settings, sampled colors, selected candidate, candidate evidence/scoring, original/preview geometry, per-slide verbose/debug logs, and fixed-tile source imagery. Never include cookies, signed headers, or full signed URLs in diagnostics.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled screen space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through `MapView`.
- Managed heatmap candidates may carry projected `EastNorth` points from fixed tile-space sampling. Prefer those points over `MapView` projection so preview/apply remains independent of the current viewport.
- Stable fixed-scale inference is the default for managed tiles. Do not make raw high-resolution source tiles the primary ridge input again unless fixture coverage proves it is as stable as the normalized inference path.
- Managed heatmap candidate safety gates should hard-reject self-intersections, missing projected geometry, no signal, unusable selected-area tiles, and extreme edge-of-search-band candidates. Sparse support, long no-signal gaps, weak lower-zoom validation, moderate edge hits, and large low-support displacement should remain previewable with clear warnings and score penalties.
- If managed heatmap tiles for the selected area cannot be fetched or decoded, or look like authentication/error placeholders, fail before preview and tell the user to refresh Strava cookies or bypass the managed tile cache. Do not use unrelated global probe tiles for access validation.
- In precise mode, simplification must not be followed by uniform redistribution of points. The simplified centerline density is intentional and should be preserved.
- Downloaded-area bypass and junction/endpoint movement are both opt-in settings. Keep defaults protective.
- When junction/endpoint movement is enabled in precise mode, simplification is ignored so selected or shared nodes are not simplified out of the way.
- If simplification or junction movement removes existing points in precise mode, dropped untagged/unreferenced nodes must be removed from the dataset to avoid leaving stray unconnected nodes behind.
- Broad/high-traffic heatmap bands may have brighter shoulders than centers in some palettes. Sampling and ridge ranking should prefer the longitudinal center of a coherent conduit over alternating side peaks unless persistent evidence indicates a real separate trace.
- Fixed endpoints in precise mode should use the selected segment's approach direction as a junction guard. Near a junction, a brighter crossing/main-road trace must not pull the preview into a kink unless the heatmap evidence is longitudinally aligned with the selected branch.
- Weak curved traces should fail conservatively. Long no-signal gaps, search-edge riding, or self-intersection are evidence that no stable corridor was found, not a reason to force a preview.

## Palette Notes

- `hot` is a single-ramp brightness scheme: white/yellow center > orange > red/dark red.
- `blue` is a single-ramp blue/cyan scheme: white or light cyan/blue core > medium cyan/blue > dark saturated blue shoulder.
- `purple` is a single-ramp purple/magenta scheme: bright purple/magenta core > medium purple > dark purple.
- `bluered` is a dual-color semantic scheme: red/magenta high-activity center > purple transition > blue/cyan lower-activity shoulder. Hue and saturation must dominate raw blue/cyan vividness.
- `gray` is a dual-color semantic scheme in practice: saturated violet/pink heatmap evidence > pale pink/gray fringe > neutral gray brightness. Treat it like `bluered` for consensus priority, not like a plain grayscale brightness ramp.
- `dual` is an internal classifier retained for palette regression tests and future experimentation. Managed multi-color detection samples the real source color schemes (`hot`, `blue`, `bluered`, `purple`, and `gray`) rather than fetching a non-existent `dual` source tile.

The palette ranking is heuristic and should be changed together with regression tests in `HeatmapFixtureArchiveTest`.
