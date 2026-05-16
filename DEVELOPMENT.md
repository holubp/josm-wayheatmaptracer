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

Slide roughness and heatmap plateau behavior can be quantified with:

```bash
python3 scripts/analyze-slide-undulations.py /path/to/debug-or-outer.zip --csv build/undulations.csv --json build/undulations.json
```

This script accepts either last-slide debug zips or outer zips containing them. It reports before/after geometry roughness, selected candidate roughness, high-frequency lateral offset residuals, profile support widths, peak counts, gradients, SNR, and source-pixel-scale roughness so detector and smoothing changes can be tuned from exported evidence instead of screenshots.

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
3. For the visible-layer fallback, `RenderedHeatmapSampler` renders the imagery layer through JOSM into an oversampled raster and records the rendered tile zoom when JOSM exposes it. The capture uses the required working view resolution over the selected segment extent; if one virtual viewport is too large, the service pans a virtual viewport over the extent and stitches the rendered chunks before sampling.
4. No-signal profiles get the 0.2.0 zero-offset fallback peak, but true empty fallback peaks are marked as unsupported so the tracker can bridge them without treating them as real heatmap evidence. Profiles whose anchors are outside the captured rendered raster are marked as off-raster so alignment can fail before applying invented geometry.
5. `RidgeTracker` uses the 0.2.0 dynamic-programming behavior with a lightweight longitudinal consistency filter for multimodal cross-section noise: seeds come from the first informative profiles, fixed-tile validation is not applied, and long unsupported runs keep the previous state. Candidate scoring uses scalar intensity, cross-section gradient evidence, native-vs-filtered peak agreement, and raw/B3/B5 scale agreement; these fields are exported for longitudinal stability diagnosis. The tracker then resolves intervals between reliable supported anchors so short weak edge excursions are pulled back toward the anchored corridor while sustained bends can still win when backed by persistent heatmap evidence. Candidates without enough real signal are filtered before preview/apply.
6. Candidates carry diagnostic evidence metadata and projected `EastNorth` geometry for preview/export. Baseline detector scores must remain the 0.2-style scores; multi-color default ordering may apply a separate calibrated ranking score, which must be exported alongside the raw score.
7. The current-source detector option and the managed all-color aggregate option are independent. When current-source alternative mappings are enabled, detector variants are applied to the selected rendered/manual source or the selected managed color source. When managed all-color aggregation is enabled, source-tile sampling downloads and caches the base Strava colors and adds `all-colors-combined`: each color is converted through its native semantic intensity mapping, the scalar intensity fields are fused, and only then are peaks and ridges extracted. The aggregate candidate requires complete matching `hot`, `blue`, `bluered`, `purple`, and `gray` source mosaics; partial aggregates must fail clearly rather than biasing the result toward whichever colors happened to load. On the visible rendered-layer fallback, only the selected rendered source exists, so all-color aggregation is unavailable there. Combined classifiers such as `bluered-combined`, `gray-combined`, and `multi-combined` fuse named color-to-intensity mappings before peak extraction and before `RidgeTracker` runs. They do not merge finished candidate geometries. Experimental `*-corridor`, `*-cool`, `gray-magenta`, `*-strict`, and `*-combined` detectors are calibration variants; exported subjective ratings may be used to tune their default ordering, but baseline detector behavior must remain available for comparison. If `IntensitySamplingMode` is direct luminance/value/alpha, palette color mapping is bypassed and detection uses one direct scalar detector.
8. `AlignmentService` projects the chosen candidate back into map coordinates and prepares either:
   `Move Existing Nodes` preview geometry
   `Precise Shape` preview geometry
9. `AlignWayAction` refuses candidate switching or apply if the modeless preview source way, segment node identities, dataset membership, or source coordinates changed after the slide was computed.
10. `ReplaceWaySegmentCommand` applies the precise-shape result by reusing existing nodes where possible, creating additional nodes when needed, and deleting dropped untagged/unreferenced nodes. Reused node target coordinates must be replayed on every execute/redo.

Full-way selections with 2-5 nodes may be recognized as sketch-like for UI/debug context, but the current visible-layer path keeps the configured alignment mode to preserve predictable runtime behavior.

`Select Longest Heatmap Segment` is a helper action for the alignment workflow. It selects the longest stretch of the selected way bounded by endpoints or nodes shared with another way, producing the way-plus-two-node selection expected by alignment.

The preview overlay uses solid blue for the selected result, orange dashes for the original segment, and labeled dashed lines for alternative ridge candidates. The preview dialog is modeless so the mapper can pan/zoom and toggle layer visibility while the overlay remains active. The ridge selector recalculates the preview immediately when the selected candidate changes.
Candidate changes during preview must use each candidate's slide-time `EastNorth` geometry. Do not reproject candidate screen/raster points through the current `MapView`, because the user may have panned or zoomed before rating or selecting alternatives.

The last-slide debug bundle is created from `DiagnosticsRegistry` and `LastSlideDebugBundle`. It is intentionally focused on the most recent slide attempt and should include redacted settings, sampled colors, intensity source, selected candidate, optional human candidate ratings and negative feature tags from the opt-in preview rating mode, candidate evidence/scoring, raw and calibrated ranking scores, original/preview geometry, candidate ridge geometry, visible-layer sampling metadata, the rendered heatmap capture, and per-slide verbose/debug logs. For visible-layer alignment, sampling metadata must distinguish JOSM's reported source tile zoom from the effective capture resolution: requested/capture bounds, virtual viewport size, chunked capture flag/count, view meters per pixel, raster meters per pixel, configured and effective cross-section width/step, capture size, and estimated visible tile ranges are diagnostic inputs, not algorithm inputs. Per-detector profile diagnostics should include cross-section anchors, normals, peak offsets/intensities, prominence, estimated noise floor, support widths, gradient strength/balance, synthetic-center flags, and combined detector component weights so palette/ridge calibration can be done from exported data. The bundle also exports `candidate-metrics.csv`, `profile-peaks.csv`, and `palette-samples.csv` for numerical analysis. Never include cookies, signed headers, or full signed URLs in diagnostics.

## Guardrails

- JOSM downloaded-area validation must use geographic `Bounds` and `LatLon`, not projected `EastNorth` against `DataSourceArea`.
- Raster candidate points are tracked in oversampled capture space and must be divided by `RenderedHeatmapSampler.RASTER_SCALE` before converting back through the slide-time capture bounds. Do not reproject them through the current `MapView` after the user has panned/zoomed.
- Visible-layer alignment requires the selected segment to be inside the captured raster. Do not let off-raster fallback profiles silently drive endpoints toward zero offset.
- Managed Strava alignment may route live alignment through `TileHeatmapSampler` when complete managed access values are configured. Keep the legacy visible-layer path as the no-managed-access fallback, and do not add post-hoc geometry consensus, parallel-way scoring, or internal refinement unless the maintainer explicitly asks for that behavior. Combined detectors must operate by intensity-level fusion before ridge detection.
- Stable fixed-scale inference at the default z15 zoom should sample the source tile raster directly. Broad max-dilation at z15 erases cross-section gradients and can make an off-center way appear already aligned; lower-zoom dilation, if used, must remain small and covered by `TileHeatmapSamplerTest`.
- Saturated broad corridors should be centered using their high-intensity core before ridge tracking. Cross-section denoising runs after color-to-intensity mapping and keeps raw, B3 `[1,2,1]`, and B5 `[1,4,6,4,1]` profile evidence. B5 is the primary extraction profile because it kept the fixture regression stable; B3 remains exported as the lighter stability comparison. Both filters use signal-gated power means (`p=2.0` for normal/high signal, `p=1.25` for weak profiles) and conservative blends so empty background is not spread into weak traces. Peak and anchor scoring should prefer positions where raw/B3/B5 centers agree. Longitudinal smoothing should treat alternating motion below roughly one source heatmap pixel as aliasing, while preserving sustained low-frequency curvature or switchbacks; cover both behaviors in `HeatmapFixtureArchiveTest` and `RidgeTrackerTest`.
- Rough sketch selections use the configured managed search half-width; do not silently widen them. Candidate applicability must also reject structurally unsafe traces with abrupt lateral jumps or acceleration, because a high-SNR heatmap trace can still be the wrong parallel trace.
- Direct intensity modes are rendered-pixel scalar modes, not a Strava raw-data API. Keep `Color mapping` as the default for Strava PNG tiles unless a documented and permitted raw scalar source is added.
- Repeated-node selections are unsafe because one OSM node cannot carry two independent slide positions. Reject selected segments where a segment node occurs more than once in the way, including occurrences outside the selected range, unless a future implementation explicitly models occurrence identity.
- Modeless previews must be invalidated before candidate switching and before apply when the underlying way node sequence or source node coordinates have changed.
- No-signal candidates may be exported for diagnostics but must not be applicable. Short unsupported runs can be bridged only when there is real heatmap signal before or after the gap.
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
