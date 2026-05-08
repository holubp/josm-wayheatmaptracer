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

The script expects exported last-slide debug bundles. It reads `candidate-metrics.csv` and `candidate-ratings.json`, then groups detector performance by visible color, intensity source, subjective rating, SNR, gradient evidence, longitudinal stability, roughness, and negative feature tags.

Rendered palette samples can be collected from debug bundles, calibration tile bundles, extracted JOSM cache tiles, or plain image directories with:

```bash
python3 scripts/heatmap-palette-lab.py /path/to/images-or-bundles --output-dir build/palette-lab --copy-images
```

Use `More tools -> Export Heatmap Calibration Tiles` in JOSM to create a redacted tile bundle for the selected way/segment across `hot`, `blue`, `bluered`, `purple`, and `gray`. The bundle must not contain cookies, signed headers, or signed URLs. Use the palette lab outputs to tune color-to-intensity transformations numerically, then cover any palette behavior changes with fixture or ridge-tracker tests.

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
2. `AlignmentService` resolves a visible heatmap imagery layer. Managed Strava access is used to create/refresh that visible layer, but the current sliding core does not sample fixed source tiles directly.
3. `RenderedHeatmapSampler` renders the visible imagery layer into an oversampled raster and records the rendered tile zoom when JOSM exposes it. The rendered colors still come from the visible view, but cross-section half-width/step are converted from reference-view pixels to effective current-view pixels so the physical search corridor is roughly ground-scale stable.
4. No-signal profiles get the 0.2.0 zero-offset fallback peak, but profiles whose anchors are outside the captured rendered raster are marked as off-raster so alignment can fail before applying invented geometry.
5. `RidgeTracker` uses the 0.2.0 dynamic-programming behavior with a lightweight longitudinal consistency filter for multimodal cross-section noise: seeds come from the first informative profiles, fixed-tile validation is not applied, and long unsupported runs keep the previous state. Candidate scoring uses both scalar intensity and cross-section gradient evidence; gradient strength is also exported for longitudinal stability diagnosis.
6. Candidates carry diagnostic evidence metadata and projected `EastNorth` geometry for preview/export. Baseline detector scores must remain the 0.2-style scores; multi-color default ordering may apply a separate calibrated ranking score, which must be exported alongside the raw score.
7. When multi-color detection is enabled, each palette classifier is run independently against the same rendered layer, and 0.9.x also adds experimental combined-intensity classifiers. Combined classifiers such as `bluered-combined`, `gray-combined`, and `multi-combined` fuse named color-to-intensity mappings before peak extraction and before `RidgeTracker` runs. They do not merge finished candidate geometries. Experimental `*-corridor`, `*-cool`, `gray-magenta`, `*-strict`, and `*-combined` detectors are calibration variants; exported subjective ratings may be used to tune their default ordering, but baseline detector behavior must remain available for comparison. If `IntensitySamplingMode` is direct luminance/value/alpha, palette color mapping is bypassed and detection uses one direct scalar detector.
8. `AlignmentService` projects the chosen candidate back into map coordinates and prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
12. `ReplaceWaySegmentCommand` applies the precise-shape result by reusing existing nodes where possible, creating additional nodes when needed, and deleting dropped untagged/unreferenced nodes.

Full-way selections with 2-5 nodes may be recognized as sketch-like for UI/debug context, but the current visible-layer path keeps the configured alignment mode to preserve predictable runtime behavior.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

The preview overlay uses solid blue for the selected result, orange dashes for the original segment, and labeled dashed lines for alternative ridge candidates. The preview dialog is modeless so the mapper can pan/zoom and toggle layer visibility while the overlay remains active. The ridge selector recalculates the preview immediately when the selected candidate changes.
Candidate changes during preview must use each candidate's slide-time `EastNorth` geometry. Do not reproject candidate screen/raster points through the current `MapView`, because the user may have panned or zoomed before rating or selecting alternatives.

The last-slide debug bundle is created from `DiagnosticsRegistry` and `LastSlideDebugBundle`. It is intentionally focused on the most recent slide attempt and should include redacted settings, sampled colors, intensity source, selected candidate, optional human candidate ratings and negative feature tags from the opt-in preview rating mode, candidate evidence/scoring, raw and calibrated ranking scores, original/preview geometry, candidate ridge geometry, visible-layer sampling metadata, the rendered heatmap capture, and per-slide verbose/debug logs. For visible-layer alignment, sampling metadata must distinguish JOSM's reported source tile zoom from the effective viewport resolution: viewport bounds, view meters per pixel, raster meters per pixel, configured and effective cross-section width/step, capture size, and estimated visible tile ranges are diagnostic inputs, not algorithm inputs. Per-detector profile diagnostics should include cross-section anchors, normals, peak offsets/intensities, prominence, estimated noise floor, support widths, gradient strength/balance, synthetic-center flags, and combined detector component weights so palette/ridge calibration can be done from exported data. The bundle also exports `candidate-metrics.csv`, `profile-peaks.csv`, and `palette-samples.csv` for numerical analysis. Never include cookies, signed headers, or full signed URLs in diagnostics.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled screen space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through `MapView`.
- Visible-layer alignment requires the selected segment to be inside the captured raster. Do not let off-raster fallback profiles silently drive endpoints toward zero offset.
- Do not route live alignment through `TileHeatmapSampler`, fixed source-tile validation, post-hoc geometry consensus, parallel-way scoring, or internal refinement unless the maintainer explicitly asks to leave the visible-layer line. Combined detectors must operate by intensity-level fusion before ridge detection.
- Direct intensity modes are rendered-pixel scalar modes, not a Strava raw-data API. Keep `Color mapping` as the default for Strava PNG tiles unless a documented and permitted raw scalar source is added.
- In precise mode, simplification must not be followed by uniform redistribution of points. The simplified centerline density is intentional and should be preserved.
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
