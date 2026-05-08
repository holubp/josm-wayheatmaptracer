# WayHeatmapTracer

`WayHeatmapTracer` is a JOSM plugin for tracing or realigning selected OSM paths, tracks, and roads against heatmap imagery when the visible activity pattern is clearer than the existing mapped geometry.

The plugin is meant for mappers who already inspect imagery manually, but want help turning a clear heatmap corridor into editable OSM geometry. The current visible-layer sliding core is intentionally based on the `0.2.0` rendered-layer algorithm: it renders the visible heatmap layer at the current JOSM view, proposes one or more centerline candidates, previews the result, and applies the chosen alignment only after confirmation.

## Why This Exists

Heatmaps are useful in places where normal imagery is ambiguous, outdated, obscured by vegetation, or unavailable. They can reveal:

- the real worn line of a footpath through woods or fields
- the commonly used side of a broad track or service road
- the center of a trail that has drifted away from older GPS traces
- missing paths visible from repeated activity but not from aerial imagery
- places where two paths merge, split, or run close together

Doing this entirely by hand in JOSM is slow: the mapper repeatedly compares the way, the heatmap band, junction constraints, and downloaded-area safety. `WayHeatmapTracer` exists to make that workflow faster while keeping the mapper in control. It does not upload data, does not decide tagging, and does not remove the need to check the result against local knowledge, imagery, GPS traces, and OSM mapping rules.

## When To Use It

Use this plugin when:

- an existing way is close to a clear heatmap corridor and needs geometric alignment
- a path or track is visible in heatmap data but hard to draw accurately by hand
- a long way should be aligned one junction-bounded segment at a time
- a high-traffic road or path has a broad heatmap band and the likely center needs to be inferred
- you want to compare multiple plausible heatmap ridges before applying a move

Prefer ordinary manual editing when:

- the heatmap is weak, sparse, or clearly offset from other trusted sources
- the heatmap may represent private, temporary, forbidden, or non-mappable activity
- the edit would change complex junction topology that you cannot verify
- the surrounding area is not downloaded, unless you intentionally enable the local/no-download drawing option for scratch work

## How It Works

The normal workflow is:

1. Configure the plugin-managed heatmap source once in `More tools -> Heatmap Layer Settings`.
2. Select one OSM way, or select one way plus two nodes to limit the operation to a segment.
3. For long ways, optionally run `More tools -> Select Longest Heatmap Segment` to select the longest endpoint/junction-bounded section.
4. Run `More tools -> Align Way to Heatmap` or press `Ctrl+Shift+Y`.
5. Inspect the modeless preview, switch ridge candidates if needed, pan/zoom the map, and toggle layers on/off while the preview stays visible.
6. Apply the result only if the proposed geometry is justified by the heatmap and other evidence.

For longer ways, `Select Longest Heatmap Segment` can select the longest section between endpoints or junction nodes, making it easier to work segment by segment without accidentally moving unrelated branches.

The current implementation is designed for private development:
- build a local plugin jar
- install it manually into the JOSM plugin directory on the test machine
- capture diagnostics and logs on the JOSM machine
- move the bundle back to the Codex machine for debugging

## Current Capabilities

- Create or refresh a plugin-managed heatmap TMS layer from user-supplied access values
- Choose Strava activity and color for the managed heatmap layer (`all`, `ride`, `run`, `water`, `winter` and `hot`, `blue`, `bluered`, `purple`, `gray`)
- Align from the currently visible heatmap imagery layer using the `0.2.0` rendered-layer sampler and ridge tracker
- Optionally run the same visible-layer detector with multiple color classifiers (`hot`, `blue`, `bluered`, `purple`, `gray`, internal `dual`, and experimental combined-intensity detectors) and show the resulting candidates in the preview
- Optionally bypass palette color mapping and sample scalar rendered-pixel intensity directly from luminance, max RGB channel, or alpha for non-Strava or diagnostic scalar imagery
- Use cross-section gradient evidence together with intensity/prominence when ranking ridge candidates and confirming longitudinal stability
- Show the rendered tile zoom used by JOSM in the preview dialog when the heatmap layer exposes one
- Optionally allow alignment in local/no-download layers, bypassing downloaded-area checks for heatmap-only drawing
- Optionally allow junction and endpoint nodes to move with the traced heatmap geometry
- Resolve the heatmap source as a visible imagery layer by managed layer, exact selected layer title, or regex
- Align one selected way, or one way plus two selected nodes on that way
- Offer two alignment modes:
  `Move Existing Nodes` keeps the node count and only moves non-fixed interior nodes
  `Precise Shape` rebuilds the selected segment from the traced heatmap centerline, reusing existing nodes where possible and adding or removing interior nodes as needed
- Keep fixed segment endpoints and shared interior nodes anchored while previewing/applying the result
- Treat shared interior nodes as fixed anchors to avoid distorting branching topology
- Select the longest segment of a selected way bounded by endpoints or junction nodes
- Optionally simplify the traced centerline before precise-shape apply; practical tolerances are currently around `0.3` to `1.0`
- Refuse to edit when the selected segment or proposed aligned geometry would extend outside the downloaded JOSM area
- Detect multiple nearby ridge candidates and allow the user to pick one
- Show a modeless preview overlay before applying, including a legend, labeled alternative ridge candidates, and a ridge selector that updates the preview before confirmation
- Export a redacted last-slide debug bundle for remote debugging, including exact settings, sampled color schemes, logs, original/preview geometry, scoring details, CSV calibration metrics, and heatmap tile images
- Package logs on the JOSM machine with a small bash helper

## Current Limits

- Survey mode is not implemented yet.
- Access values are kept out of docs and diagnostics, but the current plugin stores them in JOSM preferences rather than OS-backed secure storage.
- Heatmap interpretation is strongest for `hot`, `bluered`, and `gray`. `blue` and `purple` are supported, but may still need additional tuning in difficult cases. `gray` is treated as a dual-color scheme because high-activity traces can become pink/magenta rather than merely brighter gray.
- Strava's current public access appears to expose signed rendered PNG tiles, not the old raw numeric heat-density tile feed used by Strava Slide. Direct intensity modes therefore operate on rendered pixel channels and are intended for scalar imagery, diagnostics, and future compatible sources.
- Parallel-way awareness is an auxiliary ranking signal. It helps avoid snapping to a neighboring mapped road/path, but the preview still requires mapper review.
- Because the current alignment path intentionally uses the visible rendered heatmap layer, color and tile rendering still come from the current view like `0.2.0`. The detector keeps the search corridor roughly ground-scale stable across zoom levels and reports the effective view scale, search half-width, and step in the preview/debug output.
- Managed Strava settings still create and refresh the visible heatmap layer, but fixed source-tile inference is not used by the current sliding core.

## Build

Requirements:
- Java 17+
- Gradle wrapper included (`sh gradlew`)

Build:

```bash
sh gradlew clean build
```

The plugin jar is produced at:

```text
build/libs/wayheatmaptracer.jar
```

## Optimum JOSM Workflow

### 1. Configure Heatmap Access

1. Install and use the JOSM Strava Heatmap workflow in Firefox so the Strava heatmap is available there.
2. Copy the cookie header from the Firefox/JOSM heatmap helper. The copied text should contain the cookie names `CloudFront-Key-Pair-Id`, `CloudFront-Policy`, `CloudFront-Signature`, and `_strava_idcf`.
3. In JOSM, open `More tools -> Heatmap Layer Settings` or press `Ctrl+Shift+U`.
4. Click `Paste cookie header...`, paste the copied cookie header into the small window, and press `OK`.
5. Check that the four cookie fields were split into the visible fields in the settings dialog.
6. Choose the Strava activity (`all`, `ride`, `run`, `water`, or `winter`) and the visible Strava color (`hot`, `blue`, `bluered`, `purple`, or `gray`).
7. Enable `Use all color schemes for detection` if you want the detector to classify the same visible rendered layer through all supported palette modes and show those alternatives in the preview.
8. Leave `Intensity source` at `Color mapping` for normal Strava heatmap work. Use `Direct luminance`, `Direct max channel`, or `Direct alpha` only for scalar rendered imagery or diagnostics where pixel brightness/opacity already is the intended intensity.
9. The inference zoom, validation zoom, search-width-meters, and sample-step-meters fields are retained as advanced settings, but the current sliding core uses the visible rendered layer. The classic pixel half-width/step settings are interpreted at a normal reference view scale and converted to effective pixels at the current zoom.
10. Press `OK`. If access values are complete, the plugin refreshes the managed heatmap layer and tests that a source tile is usable.

Do not paste cookie examples into files, issues, commits, or screenshots. The debug export redacts credentials, but manually copied cookies are still secrets.

### 2. Recommended Settings

- `Alignment mode`: use `Move Existing Nodes` for normal OSM ways whose node count should remain stable. Use `Precise Shape` when drawing from a rough sketch or when the existing geometry is too coarse.
- `Inference mode`, `Inference zoom`, `Validation zoom`, `Search half-width meters`, and `Sample step meters`: retained for configuration/debug compatibility, but not used by the current visible-layer sliding core.
- `Cross-section half-width px` and `Cross-section step px`: interpreted at the normal reference view scale, then converted to effective screen pixels for the current zoom so the physical search corridor stays more consistent when zooming in or out.
- `Intensity source`: `Color mapping` is the default and should be used for normal Strava heatmap color schemes. Direct modes bypass palette semantics and use rendered pixel luminance, max channel, or alpha as scalar intensity; when a direct mode is selected, multi-color detection collapses to one direct detector because color-scheme alternatives no longer apply.
- `Use all color schemes for detection`: runs multiple palette classifiers on the visible rendered layer and shows their separate ridge candidates in the preview. Multi-color candidate ordering uses calibrated detector ranking from subjective assessments, while diagnostics keep both calibrated and raw scores. Calibration variants include `hot-corridor`, `bluered-cool`, `bluered-corridor`, `dual-corridor`, `gray-magenta`, `gray-corridor`, `gray-strict`, and `purple-strict`. Experimental `bluered-combined`, `gray-combined`, and `multi-combined` modes first fuse named color-to-intensity mappings into one intensity field, then run the same ridge tracker on that fused field.
- `Enable preview candidate rating mode`: default off. Enable only when collecting calibration examples; the preview dialog adds `++`, `+`, `0`, `-`, `--` ratings and negative tags for `off-the-line`, `jumping`, `unnecessary kinks`, and `bad junction shapes`.
- `Use nearby parallel ways as alignment context`: retained in settings, but not used by the current sliding core.
- `Enable simplification`: useful mainly with `Precise Shape`; practical values are usually around `0.3` to `1.0`.
- `Allow aligning without downloaded OSM area`: default off. Enable only for intentional local heatmap-only drawing when no OSM server area is downloaded.
- `Adjust junction and endpoint nodes`: default off. Enable only when you intentionally want selected junction or endpoint nodes to move.
- `Verbose logging` and `Debug overlay`: leave off for routine editing; enable before reproducing a bad slide for diagnostics.
- `Bypass managed tile cache...`: use after expired cookies or failed authentication may have caused JOSM to cache placeholder tiles. It changes the managed layer URL so JOSM fetches fresh visible-layer tiles after you press `OK`.

### 3. Align Existing Ways

1. Download the OSM area around the way unless you intentionally enabled the no-download option.
2. Select exactly one way. To align only part of it, select the way and the two endpoint nodes of the segment.
3. For long ways, select the way and run `More tools -> Select Longest Heatmap Segment`; the plugin selects the longest section bounded by endpoints or junctions.
4. Run `More tools -> Align Way to Heatmap` or press `Ctrl+Shift+Y`.
5. In the preview, inspect the solid blue proposed result, orange dashed original segment, and dashed labeled alternative ridges.
6. Use the ridge selector if another candidate better matches the heatmap and ground evidence.
7. When preview candidate rating mode is enabled in settings, rate candidates with `++`, `+`, `0`, `-`, or `--` and tag negative features. Ratings are exported with the last-slide debug bundle for detector calibration.
8. While the preview is open, pan/zoom the map and toggle layer visibility in the layer list as needed. The preview dialog is modeless, and candidate switching/rating uses the geometry captured at slide time rather than reprojecting through the later viewport.
9. Press `Apply` only when the proposed geometry is justified. Press `Cancel` to leave the OSM data unchanged.

For rough new paths, draw a simple way approximately along the heatmap trace, select it, set `Alignment mode` to `Precise Shape`, and run alignment. Rough sketches no longer force precise-shape mode automatically because the live sliding path is kept compatible with the visible-layer algorithm.

### Menus And Shortcuts

All actions are under JOSM `More tools`:

- `Align Way to Heatmap`: `Ctrl+Shift+Y`
- `Heatmap Layer Settings`: `Ctrl+Shift+U`
- `Select Longest Heatmap Segment`: no default shortcut
- `Export Last Slide Debug Bundle`: `Alt+Ctrl+Shift+D`

## Debugging And Reporting Bad Slides

When a slide is wrong:

1. Open `More tools -> Heatmap Layer Settings`.
2. Enable `Verbose logging` and `Debug overlay`.
3. Re-run the slide and choose/apply/cancel the preview in the same way that produced the problem.
4. Run `More tools -> Export Last Slide Debug Bundle`.
5. Use `Copy file path` or `Copy folder path` from the export dialog.
6. Share the generated zip, not raw cookies or tokenized URLs.

The debug bundle is focused on the latest slide attempt. It includes:

- exact redacted settings used for that slide, including intensity source
- selected activity, visible color, sampled color schemes, and direct intensity source when enabled
- original selected way/segment and preview geometry as OSM
- candidate ridge geometries as OSM, including failed pre-preview candidates
- `candidate-metrics.csv`, with detector, visible color, intensity source, raw score, calibrated score, support ratio, mean intensity, mean gradient strength, longitudinal stability, SNR, ambiguity, roughness, edge-pinning, and safety warnings for each candidate
- `profile-peaks.csv`, with every detected cross-section peak, including offset, intensity, prominence, noise floor, support width, gradient strength/balance, and synthetic-center flag
- `palette-samples.csv`, with per-profile strongest evidence, strongest gradient evidence, and peak counts for quick detector calibration
- selected candidate, raw candidate scores, calibrated ranking scores, SNR/evidence details, sampled offsets, roughness metrics, screen-space ridge points, and projected East/North ridge points
- optional human candidate ratings and negative feature tags entered in the preview dialog, stored in both `candidate-ratings.json` and `status.json`
- visible-rendered-layer sampling details: source tile zoom reported by JOSM, viewport size and bounds, view meters per pixel, oversampled raster meters per pixel, configured and effective cross-section width/step, capture size, and estimated visible tile range
- per-detector profile evidence: cross-section anchors, normals, detected peak offsets/intensities, peak support widths, gradient strength/balance, synthetic center flags, combined detector component weights, and per-detector support statistics
- verbose/debug log lines captured for that slide
- rendered heatmap layer capture used by visible-layer sampling

The export intentionally avoids Strava cookies, signed headers, and full signed URLs.

## Private Install Workflow

1. Build the jar on the development machine.
2. Copy the jar to the JOSM machine.
3. Install it into the local JOSM plugins directory.
4. Start JOSM, then open `More tools` and configure the heatmap layer access values.
5. In the settings dialog, enter the exact cookie values named `CloudFront-Key-Pair-Id`, `CloudFront-Policy`, `CloudFront-Signature`, and `_strava_idcf`, or use `Paste cookie header...` to split a copied cookie header into those fields.
6. Select the desired Strava activity and color for the managed layer.
7. Choose either `Move Existing Nodes` or `Precise Shape`. Enable simplification only when testing `Precise Shape`.
8. Use `Select Longest Heatmap Segment` after selecting a way when you want the plugin to choose the longest endpoint/junction-bounded segment before aligning.
9. Test `Align Way to Heatmap`.
10. If the result is wrong, enable `Verbose logging` and `Debug overlay` before rerunning.
11. Export the last-slide debug bundle from the plugin menu.

Helper scripts:
- `scripts/install-private-plugin.sh`
- `scripts/package-debug-bundle.sh` for older manual log/tile collection workflows
- `scripts/analyze-debug-bundles.py` to aggregate exported debug bundles by visible color, detector, subjective rating, SNR, and roughness

## Extract Tiles From JOSM Cache

If you cannot provide screenshots directly, you can extract cached TMS tiles from a local JOSM cache directory.

1. Close JOSM first, or copy the cache directory to a temporary folder so the cache files are not being written while you read them.
2. List the available TMS layer prefixes:

```bash
sh gradlew extractJosmTmsCache --args="--cache-dir /path/to/JOSM/cache/tiles --list-prefixes"
```

3. Pick the prefix that matches the heatmap layer title and extract a sample set:

```bash
sh gradlew extractJosmTmsCache --args="--cache-dir /path/to/JOSM/cache/tiles --prefix 'Your Heatmap Layer Title' --out-dir build/extracted-tiles --limit 200"
```

4. Share a small subset of the extracted image tiles, not the raw cache database files.

The extractor reads JOSM's current `TMS_BLOCK_v2.key` and `TMS_BLOCK_v2.data` cache format directly.

## Security and Documentation Rules

- Raw tokenized URLs are not shown in docs or example commands.
- Short-lived access values must be treated as secrets.
- Diagnostics exports are redacted by default.
- The plugin name is neutral and does not use third-party product branding.

## Strava Heatmap Support

The plugin explicitly supports a Strava heatmap imagery workflow for OSM improvement, but the plugin itself is branded as `WayHeatmapTracer`.

The documentation avoids publishing a raw tokenized imagery URL, but the settings dialog uses the real cookie field names so users can copy the values accurately.
