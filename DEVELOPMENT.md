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

1. `AlignWayAction` resolves the editable way segment, validates downloaded-area coverage unless the opt-in local drawing bypass is enabled, and resolves the heatmap imagery layer.
2. `RenderedHeatmapSampler` renders the visible imagery layer into an oversampled raster and samples cross-sections perpendicular to the selected way or rough sketch. No-signal profiles stay empty so the tracker can bridge gaps using later coherent ridge evidence instead of snapping to the source axis.
3. `RidgeTracker` builds one or more ridge candidates from the sampled cross-sections and ranks them by full-segment continuity, curvature, local palette evidence, support width, and oscillation penalties. Seeds are collected across the whole segment so off-axis or sketch inputs are not limited by the first few profiles.
4. `AlignmentService` can run a small number of internal refinement passes using the traced ridge as the next sampling axis. A refinement is accepted only when the objective score improves and high-frequency oscillation does not increase.
5. When enabled, parallel-way awareness scans nearby mapped `highway=*` ways and de-prioritizes candidates that appear to match another mapped parallel way. Alternatives remain visible in the candidate chooser.
6. `AlignmentService` projects the chosen candidate back into map coordinates and prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
7. `ReplaceWaySegmentCommand` applies the precise-shape result by reusing existing nodes where possible, creating additional nodes when needed, and deleting dropped untagged/unreferenced nodes.

Full-way selections with 2-5 nodes are treated as sketch-like input and use precise-shape output automatically, even if the saved alignment mode is `Move Existing Nodes`. Short selected segments of longer existing ways keep the configured alignment mode.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled screen space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through `MapView`.
- In precise mode, simplification must not be followed by uniform redistribution of points. The simplified centerline density is intentional and should be preserved.
- Downloaded-area bypass and junction/endpoint movement are both opt-in settings. Keep defaults protective.
- When junction/endpoint movement is enabled in precise mode, simplification is ignored so selected or shared nodes are not simplified out of the way.
- If simplification or junction movement removes existing points in precise mode, dropped untagged/unreferenced nodes must be removed from the dataset to avoid leaving stray unconnected nodes behind.

## Palette Notes

- `hot` is a single-ramp brightness scheme: white/yellow center > orange > red/dark red.
- `blue` is a single-ramp blue/cyan scheme: white or light cyan/blue core > medium cyan/blue > dark saturated blue shoulder.
- `purple` is a single-ramp purple/magenta scheme: bright purple/magenta core > medium purple > dark purple.
- `bluered` is a dual-color semantic scheme: red/magenta high-activity center > purple transition > blue/cyan lower-activity shoulder. Hue and saturation must dominate raw blue/cyan vividness.
- `gray` is treated as a mixed hue scheme in practice: saturated violet/pink heatmap evidence > pale pink/gray fringe > neutral gray brightness.
- `dual` is an internal detection-only classifier used by multi-color detection; it combines warm dual-color centers, purple/violet centers, bright single-ramp centers, and blue/cyan cores.

The palette ranking is heuristic and should be changed together with regression tests in `HeatmapFixtureArchiveTest`.
