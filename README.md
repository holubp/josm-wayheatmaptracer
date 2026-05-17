# WayHeatmapTracer

`WayHeatmapTracer` is a JOSM plugin for tracing or realigning selected OSM paths, tracks, and roads against heatmap imagery when the visible activity pattern is clearer than the existing mapped geometry.

The plugin is meant for mappers who already inspect imagery manually, but want help turning a clear heatmap corridor into editable OSM geometry. With managed Strava access configured, the current sliding core samples fixed-resolution source tiles. Without managed access, it falls back to the `0.2.0`-style rendered-layer algorithm: it asks JOSM to render the heatmap layer for the selected segment extent at the required working resolution, proposes one or more centerline candidates, previews the result, and applies the chosen alignment only after confirmation.

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
- Align from managed fixed-resolution Strava source tiles, or from the JOSM-rendered heatmap imagery layer using the `0.2.0` rendered-layer sampler and ridge tracker when managed access is unavailable
- Optionally run the same visible-layer detector with multiple color classifiers (`hot`, `blue`, `bluered`, `purple`, `gray`, internal `dual`, and experimental combined-intensity detectors) and show the resulting candidates in the preview
- Optionally bypass palette color mapping and sample scalar rendered-pixel intensity directly from luminance, max RGB channel, or alpha for non-Strava or diagnostic scalar imagery
- Use cross-section gradient evidence, intensity/prominence, raw/B3/B5 peak-center agreement, and source-pixel roughness when ranking ridge candidates and confirming longitudinal stability
- Resolve ridge geometry through reliable anchor profiles and constrained intervals, reducing short weak side excursions near crossings
- Treat source-tile resolution as a real evidence limit: sub-source-pixel alternating wiggles are penalized, while sustained bends remain available as candidate geometry
- Show the rendered tile zoom used by JOSM in the preview dialog when the heatmap layer exposes one
- Optionally allow alignment in local/no-download layers, bypassing downloaded-area checks for heatmap-only drawing
- Optionally allow junction and endpoint nodes to move with the traced heatmap geometry
- Resolve the heatmap source as a managed source-tile configuration or as a visible imagery layer by managed layer, exact selected layer title, or regex
- Align one selected way, or one way plus two selected nodes on that way
- Offer two alignment modes:
  `Move Existing Nodes` keeps the node count and only moves non-fixed interior nodes
  `Precise Shape` rebuilds the selected segment from the traced heatmap centerline, reusing existing nodes where possible and adding or removing interior nodes as needed
- Keep fixed segment endpoints and shared interior nodes anchored while previewing/applying the result
- Treat shared interior nodes as fixed anchors to avoid distorting branching topology
- Select the longest segment of a selected way bounded by endpoints or junction nodes
- Optionally simplify the traced centerline in `Precise Shape`; simplification is applied per fixed-anchor interval after anchors are restored so one part of a multi-leg segment is not collapsed by another part
- Refuse to edit when the selected segment or proposed aligned geometry would extend outside the downloaded JOSM area
- Refuse to apply a preview if the selected way or source node coordinates changed while the modeless preview was open
- Refuse unsafe repeated-node selections where a selected node also occurs elsewhere in the way
- Reject no-signal or too-weak ridge candidates before preview/apply, while still bridging short unsupported heatmap gaps between real signal
- Detect multiple nearby ridge candidates and allow the user to pick one
- Show a modeless preview overlay before applying, including a legend, labeled alternative ridge candidates, and a ridge selector that updates the preview before confirmation
- Export a redacted last-slide debug bundle for remote debugging, including exact settings, sampled color schemes, logs, original/preview geometry, scoring details, CSV calibration metrics, and heatmap tile images
- Package logs on the JOSM machine with a small bash helper

## Current Limits

- Survey mode is not implemented yet.
- Access values are kept out of docs and diagnostics, but the current plugin stores them in JOSM preferences rather than OS-backed secure storage.
- Heatmap interpretation is strongest for `hot`, `bluered`, and `purple`. `gray` and `blue` are supported but may still need additional tuning in difficult cases. `gray` is treated as a dual-color scheme because high-activity traces can become pink/magenta rather than merely brighter gray; `purple` uses the real purple/lavender palette path rather than strict old magenta-only hue matching.
- Strava's current public access appears to expose signed rendered PNG tiles, not the old raw numeric heat-density tile feed used by Strava Slide. Direct intensity modes therefore operate on rendered pixel channels and are intended for scalar imagery, diagnostics, and future compatible sources.
- Parallel-way awareness is an auxiliary ranking signal. It helps avoid snapping to a neighboring mapped road/path, but the preview still requires mapper review.
- With complete managed Strava access values, `Stable fixed scale` and `Raw high-resolution` align from fetched source tiles rather than the current screen capture. This makes sliding independent of the current JOSM zoom and allows the selected way to extend outside the current viewport.
- Without managed Strava access values, alignment falls back to the legacy visible rendered-layer path. In that fallback mode, the plugin temporarily renders the selected segment plus the search corridor through a normal-resolution virtual JOSM viewport. If one viewport would be too large, it pans that virtual viewport over the extent and stitches the rendered chunks for sampling, then restores the user's previous viewport.
- Fixed source-tile inference uses the configured inference zoom, validation zoom, search half-width meters, and sample step meters. The default fixed-scale search is calibrated to the good z15 setup: source tile z15, 6.0x reference raster, 0.389 m/px reference view, about 7.01 m search half-width, and about 1.56 m sampling step.

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
7. Enable `Run alternative detector mappings on current source` if you want palette classifier alternatives for the currently sampled source. Enable `Aggregate all managed color schemes into one intensity map` if you want managed source tiles from all base colors fused into an `all-colors-combined` candidate.
8. Leave `Intensity source` at `Color mapping` for normal Strava heatmap work. Use `Direct luminance`, `Direct max channel`, or `Direct alpha` only for scalar rendered imagery or diagnostics where pixel brightness/opacity already is the intended intensity.
9. For fixed-resolution sliding, start with `Stable fixed scale`, inference zoom `15`, validation zoom `13`, search half-width `7.01`, and sample step `1.56`. These reproduce the z15/reference-raster setup that has been working well in recent tests.
10. Press `OK`. If access values are complete, the plugin refreshes the managed heatmap layer and tests that a source tile is usable.

Do not paste cookie examples into files, issues, commits, or screenshots. The debug export redacts credentials, but manually copied cookies are still secrets.

### 2. Recommended Settings

- `Alignment mode`: use `Move Existing Nodes` for normal OSM ways whose node count should remain stable. Use `Precise Shape` when drawing from a rough sketch or when the existing geometry is too coarse.
- `Inference mode`, `Inference zoom`, `Validation zoom`, `Search half-width meters`, and `Sample step meters`: used by managed source-tile alignment. `Stable fixed scale` uses the calibrated fixed-scale sampling parameters and avoids broad z15 heat dilation; `Raw high-resolution` uses the source tiles directly. When no managed access values are configured, the plugin uses the legacy visible-layer path instead.
- Rough 2-5 node sketches use the same configured `Search half-width meters`; the plugin does not silently widen the search. Increase this setting deliberately only when the rough source geometry is genuinely far from the intended heatmap trace.
- `Cross-section half-width px` and `Cross-section step px`: used by the legacy visible-layer fallback. Fixed source-tile alignment uses the meter-based fields above and converts them to the same 0.389 m/px reference view scale reported in the preview.
- `Intensity source`: `Color mapping` is the default and should be used for normal Strava heatmap color schemes. Direct modes bypass palette semantics and use rendered pixel luminance, max channel, or alpha as scalar intensity; when a direct mode is selected, multi-color detection collapses to one direct detector because color-scheme alternatives no longer apply.
- `Run alternative detector mappings on current source`: applies the detector variants to the currently sampled source. With a manual visible layer, this means the single rendered heatmap layer on screen. With managed source-tile alignment, this means the selected managed color source. Calibration variants include `hot-corridor`, `bluered-cool`, `bluered-corridor`, `dual-corridor`, `gray-magenta`, `gray-corridor`, `gray-strict`, and `purple-strict`. Experimental `bluered-combined`, `gray-combined`, and `multi-combined` modes first fuse named color-to-intensity mappings into one intensity field, then run the same ridge tracker on that fused field.
- `Aggregate all managed color schemes into one intensity map`: with managed Strava access, downloads and locally caches the base `hot`, `blue`, `bluered`, `purple`, and `gray` source tiles for the selected segment, converts each through its native semantic intensity mapping, fuses those intensities into an `all-colors-combined` field, and runs ridge tracking on that fused field before showing candidates. The aggregate candidate requires all five base source colors to be available in the same sampling frame; if a required color cannot be fetched or decoded, the slide fails clearly instead of silently using a partial aggregate. This option needs managed access values; the manual visible-layer fallback cannot fetch other color schemes independently.
- `Show aggregate intensity layer`: adds a non-editable `WayHeatmapTracer aggregate intensity` layer after the settings dialog is accepted, even if the aggregate detector candidate itself is disabled. The layer sits just above the managed Strava layer, fetches the same managed base colors for the visible map area, and visualizes the scalar aggregate field as white on transparent at 80% opacity. Aggregation uses weighted native semantic intensities with a power-mean emphasis on high-intensity evidence, so very strong traces dominate weaker shoulders. It is diagnostic only and does not change alignment.
- `Enable preview candidate rating mode`: default off. Enable only when collecting calibration examples; the preview dialog adds `++`, `+`, `0`, `-`, `--` ratings and negative tags for `off-the-line`, `jumping`, `unnecessary kinks`, and `bad junction shapes`.
- `Use nearby parallel ways as alignment context`: retained in settings, but not used by the current sliding core.
- `Enable simplification`: useful mainly with `Precise Shape`; practical values are usually around `0.3` to `1.0`.
- `Allow aligning without downloaded OSM area`: default off. Enable only for intentional local heatmap-only drawing when no OSM server area is downloaded.
- `Adjust junction and endpoint nodes`: default off. Enable only when you intentionally want selected junction or endpoint nodes to move.
- `Verbose logging` and `Debug overlay`: leave off for routine editing; enable before reproducing a bad slide for diagnostics.
- `Bypass managed tile cache...`: use after expired cookies or failed authentication may have caused placeholder or low-quality tiles to be cached. It changes the managed layer URL and starts a new plugin source-tile cache generation, so both JOSM's visible managed layer and the fixed-resolution sampler fetch fresh tiles after you press `OK`.

Shortcuts:

- `Ctrl+Shift+Y`: align using the mode saved in settings.
- `Alt+Ctrl+Shift+S`: align once in `Precise Shape` mode without changing saved settings.
- `Alt+Ctrl+Shift+M`: align once in `Move Existing Nodes` mode without changing saved settings.

### 3. Align Existing Ways

1. Download the OSM area around the way unless you intentionally enabled the no-download option.
2. Select exactly one way. To align only part of it, select the way and the two endpoint nodes of the segment.
3. For long ways, select the way and run `More tools -> Select Longest Heatmap Segment`; the plugin selects the longest section bounded by endpoints or junctions.
4. The selected segment does not need to be fully visible on screen. With managed Strava access the plugin samples source tiles; without managed access it temporarily renders the selected extent through one or more normal-resolution JOSM viewport captures and then restores the previous viewport.
5. Run `More tools -> Align Way to Heatmap` or press `Ctrl+Shift+Y`.
6. In the preview, inspect the solid blue proposed result, orange dashed original segment, and dashed labeled alternative ridges.
7. Use the ridge selector if another candidate better matches the heatmap and ground evidence.
8. When preview candidate rating mode is enabled in settings, rate candidates with `++`, `+`, `0`, `-`, or `--` and tag negative features. Ratings are exported with the last-slide debug bundle for detector calibration.
9. While the preview is open, pan/zoom the map and toggle layer visibility in the layer list as needed. The preview dialog is modeless, and candidate switching/rating uses the geometry captured at slide time rather than reprojecting through the later viewport.
10. Avoid editing the selected way while the preview is open. If the way nodes or source coordinates change, the plugin refuses to switch/apply the stale preview and asks you to run the slide again.
11. Press `Apply` only when the proposed geometry is justified. Press `Cancel` to leave the OSM data unchanged.

The plugin also refuses repeated-node selections where a node in the selected segment appears more than once in the same way, because the same OSM node cannot safely represent two independent slide positions. Split the way or select a simpler segment before aligning.

For rough new paths, draw a simple way approximately along the heatmap trace, select it, set `Alignment mode` to `Precise Shape`, and run alignment. Rough sketches no longer force precise-shape mode automatically because the live sliding path is kept compatible with the visible-layer algorithm.

### Menus And Shortcuts

All actions are under JOSM `More tools`:

- `Align Way to Heatmap`: `Ctrl+Shift+Y`
- `Heatmap Layer Settings`: `Ctrl+Shift+U`
- `Select Longest Heatmap Segment`: no default shortcut
- `Export Heatmap Calibration Tiles`: `Alt+Ctrl+Shift+P`
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
- `candidate-metrics.csv`, with detector, visible color, intensity source, raw score, calibrated score, support ratio, mean intensity, mean gradient strength, longitudinal stability, SNR, ambiguity, roughness, source-pixel-scale roughness, edge-pinning, and safety warnings for each candidate
- `profile-peaks.csv`, with every detected cross-section peak, including offset, intensity, prominence, noise floor, support width, gradient strength/balance, native-vs-filtered maximum agreement, raw/B3/B5 center positions, scale agreement, center uncertainty, filter parameters, and synthetic-center flag
- `palette-samples.csv`, with per-profile strongest evidence, strongest gradient evidence, and peak counts for quick detector calibration
- selected candidate, raw candidate scores, calibrated ranking scores, SNR/evidence details, sampled offsets, roughness metrics, screen-space ridge points, and projected East/North ridge points
- optional human candidate ratings and negative feature tags entered in the preview dialog, stored in both `candidate-ratings.json` and `status.json`
- visible-rendered-layer sampling details: source tile zoom reported by JOSM, whether a virtual viewport/chunked capture was used, requested and actual capture bounds, viewport size and bounds, view meters per pixel, oversampled raster meters per pixel, configured and effective cross-section width/step, capture size, chunk count, and estimated visible tile range
- managed all-color aggregate visualization, when source mosaics are available, as `aggregate-intensity/all-colors-combined-z*.png` plus `aggregate-intensity/metadata.json`
- per-detector profile evidence: cross-section anchors, normals, detected peak offsets/intensities, peak support widths, gradient strength/balance, synthetic center flags, combined detector component weights, and per-detector support statistics
- verbose/debug log lines captured for that slide
- rendered heatmap layer capture used by visible-layer sampling

The export intentionally avoids Strava cookies, signed headers, and full signed URLs.

## Palette Calibration Workflow

For color-scheme tuning, use `More tools -> Export Heatmap Calibration Tiles` after selecting the relevant way or way segment. The plugin downloads and exports redacted tile images for the same selected segment across the base Strava color schemes: `hot`, `blue`, `bluered`, `purple`, and `gray`. The bundle contains mosaics, source tiles, and tile metadata, but not cookies or signed URLs.

Analyze a calibration bundle or an existing last-slide debug bundle offline:

```bash
python3 scripts/heatmap-palette-lab.py /path/to/heatmap-calibration.zip --output-dir build/palette-lab --copy-images
```

The script also accepts image directories and extracted JOSM cache tiles. It writes `images.csv`, `palette-clusters.csv`, and `scheme-summary.csv`; add `--write-pixels` when you need per-color samples for deeper fitting. Add `--analyze-filters` to write `filter-summary.csv`, which compares the planned B3 and B5 profile filters against raw cross-section centers for each exported image. This is intended to let palette parameters and convolution filters be tuned numerically from real rendered tiles instead of by visual guessing.

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
- `scripts/heatmap-palette-lab.py` to extract rendered heatmap palette clusters and current color-to-intensity scores from debug bundles, calibration bundles, or image/cache tile dumps

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
