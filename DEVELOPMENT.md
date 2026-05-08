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

The script expects exported last-slide debug bundles. It reads `candidate-metrics.csv` and `candidate-ratings.json`, then groups detector performance by visible color, subjective rating, SNR, roughness, and negative feature tags.

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
- the acceptance envelope radius defaults to `15.0` meters and can be overridden with `-Dwayheatmaptracer.fixture.acceptableOffsetMeters=<meters>`
- `acceptable-limits.osm` contains left/right side lines plus rounded start/end cap arcs; `violations.osm` contains traces that exceed either the curve metrics or the acceptance envelope

## Core Runtime Flow

1. `AlignWayAction` resolves the editable way segment, validates downloaded-area coverage unless the opt-in local drawing bypass is enabled, and opens a per-slide diagnostic session.
2. `AlignmentService` resolves a visible heatmap imagery layer. Managed Strava access is used to create/refresh that visible layer, but the 0.8.x sliding core does not sample fixed source tiles directly.
3. `RenderedHeatmapSampler` renders the visible imagery layer into an oversampled raster and records the rendered tile zoom when JOSM exposes it. The rendered colors still come from the visible view, but cross-section half-width/step are converted from reference-view pixels to effective current-view pixels so the physical search corridor is roughly ground-scale stable.
4. No-signal profiles get the 0.2.0 zero-offset fallback peak.
5. `RidgeTracker` uses the 0.2.0 dynamic-programming behavior: seeds come from the first informative profiles, no consensus fusion or fixed-tile validation is applied, and long unsupported runs keep the previous state.
6. Candidates carry diagnostic evidence metadata and projected `EastNorth` geometry for preview/export. Baseline detector scores must remain the 0.2-style scores; multi-color default ordering may apply a separate calibrated ranking score, which must be exported alongside the raw score.
7. When multi-color detection is enabled, each palette classifier is run independently against the same rendered layer. The preview can show those alternatives, but 0.8.x does not synthesize fused consensus geometry. Experimental `*-corridor`, `*-cool`, `gray-magenta`, and `*-strict` detectors are calibration variants; exported subjective ratings may be used to tune their default ordering, but baseline detector behavior must remain available for comparison.
8. `AlignmentService` projects the chosen candidate back into map coordinates and prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
12. `ReplaceWaySegmentCommand` applies the precise-shape result by reusing existing nodes where possible, creating additional nodes when needed, and deleting dropped untagged/unreferenced nodes.

Full-way selections with 2-5 nodes may be recognized as sketch-like for UI/debug context, but 0.8.x keeps the configured alignment mode to preserve the 0.2-compatible runtime behavior.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

The preview overlay uses solid blue for the selected result, orange dashes for the original segment, and labeled dashed lines for alternative ridge candidates. The preview dialog is modeless so the mapper can pan/zoom and toggle layer visibility while the overlay remains active. The ridge selector recalculates the preview immediately when the selected candidate changes.
Candidate changes during preview must use each candidate's slide-time `EastNorth` geometry. Do not reproject candidate screen/raster points through the current `MapView`, because the user may have panned or zoomed before rating or selecting alternatives.

The last-slide debug bundle is created from `DiagnosticsRegistry` and `LastSlideDebugBundle`. It is intentionally focused on the most recent slide attempt and should include redacted settings, sampled colors, selected candidate, optional human candidate ratings and negative feature tags from the opt-in preview rating mode, candidate evidence/scoring, raw and calibrated ranking scores, original/preview geometry, candidate ridge geometry, visible-layer sampling metadata, the rendered heatmap capture, and per-slide verbose/debug logs. For 0.8.x visible-layer alignment, sampling metadata must distinguish JOSM's reported source tile zoom from the effective viewport resolution: viewport bounds, view meters per pixel, raster meters per pixel, configured and effective cross-section width/step, capture size, and estimated visible tile ranges are diagnostic inputs, not algorithm inputs. Per-detector profile diagnostics should include cross-section anchors, normals, peak offsets/intensities, prominence, estimated noise floor, support widths, and synthetic-center flags so palette/ridge calibration can be done from exported data. The bundle also exports `candidate-metrics.csv`, `profile-peaks.csv`, and `palette-samples.csv` for numerical analysis. Never include cookies, signed headers, or full signed URLs in diagnostics.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled screen space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through `MapView`.
- In 0.8.x, do not route live alignment through `TileHeatmapSampler`, fixed source-tile validation, consensus fusion, parallel-way scoring, or internal refinement unless the maintainer explicitly asks to leave the 0.2-compatible line.
- In precise mode, simplification must not be followed by uniform redistribution of points. The simplified centerline density is intentional and should be preserved.
- Downloaded-area bypass and junction/endpoint movement are both opt-in settings. Keep defaults protective.
- When junction/endpoint movement is enabled in precise mode, simplification is ignored so selected or shared nodes are not simplified out of the way.
- If simplification or junction movement removes existing points in precise mode, dropped untagged/unreferenced nodes must be removed from the dataset to avoid leaving stray unconnected nodes behind.
- 0.8.x tests should protect the 0.2-compatible behavior even when that behavior is known to be simpler than the later experimental fixed-tile line.

## Palette Notes

- `hot` is a single-ramp brightness scheme: white/yellow center > orange > red/dark red.
- `blue` is a single-ramp blue/cyan scheme: white or light cyan/blue core > medium cyan/blue > dark saturated blue shoulder.
- `purple` is a single-ramp purple/magenta scheme: bright purple/magenta core > medium purple > dark purple.
- `bluered` is a dual-color semantic scheme: red/magenta high-activity center > purple transition > blue/cyan lower-activity shoulder. Hue and saturation must dominate raw blue/cyan vividness.
- `gray` is dual-color in practice: weak/medium traces may be gray/blue-gray, while high-activity traces can become pink/magenta. The classifier should score both the neutral ramp and the magenta/violet center while still exporting raw scores for calibration.
- `dual` is an internal rendered-layer classifier retained for palette regression tests and preview alternatives. In 0.8.x it classifies the same visible rendered layer rather than fetching a separate source tile.

The palette ranking is heuristic and should be changed together with regression tests in `HeatmapFixtureArchiveTest`.
