# WayHeatmapTracer

`WayHeatmapTracer` is a JOSM plugin for tracing or realigning selected OSM paths, tracks, and roads against heatmap imagery when the visible activity pattern is clearer than the existing mapped geometry.

The plugin is meant for mappers who already inspect imagery manually, but want help turning a clear heatmap corridor into editable OSM geometry. With managed Strava access configured, it samples fixed-resolution source tiles. Without managed access, it asks JOSM to render the heatmap layer over the selected segment at the required working resolution. The default corridor-aware tracker retains full cross-section intensity profiles, checks corridor stability in a local Gaussian scale space, follows broad or sparse corridors longitudinally, and presents ambiguous parallel interpretations for review. The proven `0.2.0`-compatible tracker remains available as an explicit fallback. Both paths preview candidates and apply nothing until the mapper confirms.

Geometry cleanup is a separate, opt-in stage for future slide candidates. It is configured independently from heatmap sampling and never changes existing OSM geometry merely because its settings dialog was opened or accepted.

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
- Choose the default `Corridor-aware (recommended)` tracker, which uses nested corridor boundaries, continuous signal/localization confidence, longitudinal strand identity, and second-order centerline optimization, or explicitly select the `Legacy v0.2-compatible` fallback
- In corridor-aware mode, combine complementary intermittent recording strands into an additional `sparse corridor` candidate when their longitudinal union is coherent. Direct child evidence and bounded interpolation remain distinct, every elementary child stays selectable, and persistent deep-valley parallel roads remain separate.
- In corridor-aware mode, map RGB to scalar intensity first, interpolate corridor edges and intensity evidence between source samples, then build local L0/L1/L2 Gaussian levels from the imagery already acquired for the slide. Source pixels define evidence uncertainty, not a grid to which the result must snap.
- In corridor-aware mode, associate the same longitudinal strand through short gaps, fit confidence-weighted 5 m and 12 m robust corridor references, and add a 32 m reference only for weak signal without sustained motion. One exact second-order centerline optimization follows that evidence; temporary side traces cannot authorize a lateral switch, while sustained weak turns and switchbacks retain the local reference.
- In corridor-aware mode, constrain fixed anchors before geometry is built, derive endpoint approach direction from the selected branch rather than the connected road, and keep movable endpoints/junctions within both a source-position prior and a hard 10 metre/configured-search limit. Missing reliable branch-approach evidence lowers candidate ranking and remains visible in diagnostics, but does not alone disable Apply; independently detected foldbacks, terminal kinks, or genuine pre-junction crossings still do.
- Show the slide-time rendered tile zoom used by JOSM in the preview dialog when the heatmap layer exposes one
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
- Configure optional future-slide geometry cleanup, which can reduce heatmap-traced points and, in its combined mode, apply constrained smoothing before reduction while preserving fixed anchors
- Refuse to edit when the selected segment or proposed aligned geometry would extend outside the downloaded JOSM area
- Refuse to apply a preview if the selected way or source node coordinates changed while the modeless preview was open
- Refuse unsafe repeated-node selections where a selected node also occurs elsewhere in the way
- Keep no-signal, too-weak, and structurally unsafe detector outcomes visible for diagnosis; inspection-only candidates are dashed red and cannot be applied, while short unsupported gaps may still be bridged between real signal. Corridor-aware short-excursion warnings measure deviations from the robust tube rather than raw offset reversals on supported curves.
- Detect multiple nearby ridge candidates and allow the user to pick one
- Show a modeless preview overlay before applying, including a legend, labeled alternative ridge candidates, and a ridge selector that updates the preview before confirmation
- Export a redacted last-slide debug bundle for remote debugging, including exact settings, sampled color schemes, logs, original/preview geometry, scoring details, sparse-bundle/direct/interpolated CSV evidence, and heatmap tile images
- Package logs on the JOSM machine with a small bash helper

## Current Limits

- Survey mode is not implemented yet.
- Access values are kept out of docs and diagnostics, but the current plugin stores them in JOSM preferences rather than OS-backed secure storage.
- Heatmap interpretation is strongest for `hot`, `bluered`, and `purple`. `gray` and `blue` are supported but may still need additional tuning in difficult cases. `gray` is treated as a dual-color scheme because high-activity traces can become pink/magenta rather than merely brighter gray; `purple` uses the real purple/lavender palette path rather than strict old magenta-only hue matching.
- Strava's current public access appears to expose signed rendered PNG tiles, not the old raw numeric heat-density tile feed used by Strava Slide. Direct intensity modes therefore operate on rendered pixel channels and are intended for scalar imagery, diagnostics, and future compatible sources.
- The corridor-aware tracker is the default from `0.20.0`. Sparse-corridor parents remain hypotheses for review, not declarations that several traces are one OSM way. The legacy tracker remains available for compatibility and comparison.
- Parallel-way awareness is opt-in and applies only to the corridor-aware tracker. It uses downloaded nearby `highway=*` ways as read-only ranking context; it never edits those ways and cannot prove lane or carriageway semantics on its own.
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
10. Press `OK`. If access values are complete, the plugin refreshes the managed heatmap layer and asynchronously checks one selected-color z15 tile near the current map center. The result distinguishes a fresh network tile, plugin cache, authentication failure, rate limiting, no tile at that coordinate, network failure, and unusable imagery. Layer recreation itself is not proof of access.

Do not paste cookie examples into files, issues, commits, or screenshots. The debug export redacts credentials, but manually copied cookies are still secrets.

### 2. Recommended Settings

- `Alignment mode`: use `Move Existing Nodes` for normal OSM ways whose node count should remain stable. Use `Precise Shape` when drawing from a rough sketch or when the existing geometry is too coarse.
- `Ridge tracker`: use the default `Corridor-aware (recommended)` tracker for broad-corridor centering, sparse longitudinal inference, parallel parent/child candidates, and pre-optimized endpoint approaches. Select `Legacy v0.2-compatible` only when compatibility or direct comparison with the older tracker is required. A preview label such as `Hot detector - sparse corridor 1` is the additional parent hypothesis; ordinary `strand` candidates are its retained elementary alternatives. This is an independent choice from `Alignment mode`.
- `Inference mode`, `Inference zoom`, `Validation zoom`, `Search half-width meters`, and `Sample step meters`: used by managed source-tile alignment. `Stable fixed scale` uses the calibrated fixed-scale sampling parameters and avoids broad z15 heat dilation; `Raw high-resolution` uses the source tiles directly. When no managed access values are configured, the plugin uses the legacy visible-layer path instead.
- Rough 2-5 node sketches use the same configured `Search half-width meters`; the plugin does not silently widen the search. Increase this setting deliberately only when the rough source geometry is genuinely far from the intended heatmap trace.
- `Cross-section half-width px` and `Cross-section step px`: used by the legacy visible-layer fallback. Fixed source-tile alignment uses the meter-based fields above and converts them to the same 0.389 m/px reference view scale reported in the preview.
- `Geometry cleanup...`: opens the dedicated cleanup dialog. The same dialog is available directly as `More tools -> Geometry Cleanup Settings...`. Cleanup is disabled by default and affects only candidates produced by later slides; it is never an immediate edit operation.
- `Geometry cleanup mode`: choose `None`, `Reduce points only`, or `Constrained smoothing + reduce points`. The last mode first suppresses short, unsupported lateral ripples with a constrained normal-only Laplacian step and then performs heatmap-constrained point reduction. An enabled mode may add one separate cleaned candidate when eligibility, safety, and geometry-change checks pass; the raw candidate remains available for comparison or fallback. The preview summary reports the captured mode, preset, and number of cleaned alternatives. The separate `Legacy post-slide simplification` row describes the older simplifier and is reported as inactive while geometry cleanup owns the slide.
- `Geometry cleanup preset`: `Conservative` uses a 6 m ripple scale, `Balanced` uses 10 m, and `Strong` uses 20 m. The preset also supplies associated smoothing, pass-count, reduction-deviation, and fit-retention defaults. Custom numeric values remain available in the dialog.
- `Intensity source`: `Color mapping` is the default and should be used for normal Strava heatmap color schemes. Direct modes bypass palette semantics and use rendered pixel luminance, max channel, or alpha as scalar intensity; when a direct mode is selected, multi-color detection collapses to one direct detector because color-scheme alternatives no longer apply.
- `Run alternative detector mappings on current source`: applies the detector variants to the currently sampled source. With a manual visible layer, this means the single rendered heatmap layer on screen. With managed source-tile alignment, this means the selected managed color source. Every base mapping has a corridor-response alternative: `hot-corridor`, `blue-corridor`, `bluered-corridor`, `purple-corridor`, `gray-corridor`, and the internal `dual-corridor`. These apply the same shoulder-preserving response after native palette-to-intensity conversion and then run the selected shared tracker. Additional calibration variants include `bluered-cool`, `gray-magenta`, `gray-strict`, and `purple-strict`. Experimental `bluered-combined`, `gray-combined`, and `multi-combined` modes first fuse named color-to-intensity mappings into one intensity field, then run the same ridge tracker on that fused field.
- Candidate ordering first separates genuine source evidence from alternative mappings. A complete managed `all-colors-combined` field and mappings native to the configured source palette rank ahead of cross-palette mappings when applicable; quality metrics determine ordering within that tier. Every requested mapping remains listed with its outcome.
- `Aggregate all managed color schemes into one intensity map`: with managed Strava access, downloads and locally caches the base `hot`, `blue`, `bluered`, `purple`, and `gray` source tiles for the selected segment, converts each through its native semantic intensity mapping, fuses those intensities into an `all-colors-combined` field, and runs ridge tracking on that fused field before showing candidates. The aggregate candidate requires all five base source colors to be available in the same sampling frame; if a required color cannot be fetched or decoded, the slide fails clearly instead of silently using a partial aggregate. This option needs managed access values; the manual visible-layer fallback cannot fetch other color schemes independently.
- `Show aggregate intensity layer`: adds a non-editable `WayHeatmapTracer aggregate intensity` layer after the settings dialog is accepted, even if the aggregate detector candidate itself is disabled. The layer sits immediately above the managed Strava layer, fetches the managed base colors for the visible map area, and visualizes the scalar aggregate field as white on transparent at 80% opacity. A tile is displayed only when all five source colors are usable; incomplete, authentication-blocked, and rate-limited states remain explicitly unavailable rather than rendering a misleading subset. Aggregation uses weighted native semantic intensities with a conservative power-mean emphasis on high-intensity evidence. This visualization is diagnostic only: enabling it does not add palettes to an alignment run or change candidates.
- `Enable preview candidate rating mode`: default off. Enable only when collecting calibration examples; the preview dialog adds `++`, `+`, `0`, `-`, `--` ratings and negative tags for `off-the-line`, `jumping`, `unnecessary kinks`, and `bad junction shapes`.
- `Use nearby parallel ways as alignment context`: default off. In corridor-aware mode, downloaded nearby parallel `highway=*` ways provide soft tag, distance, lateral-order, and topology context for candidate ranking. The plugin does not move or retag contextual ways. This setting has no effect on the legacy tracker.
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
11. If geometry cleanup is enabled, compare the raw candidate with its separately labeled cleaned candidate. The cleaned candidate is applicable only when its retained heatmap evidence, protected anchors, assignments, and topology checks all pass.
12. Press `Apply` only when the proposed geometry is justified. Press `Cancel` to leave the OSM data unchanged.

The plugin also refuses repeated-node selections where a node in the selected segment appears more than once in the same way, because the same OSM node cannot safely represent two independent slide positions. Split the way or select a simpler segment before aligning.

For rough new paths, draw a simple way approximately along the heatmap trace, select it, set `Alignment mode` to `Precise Shape`, and run alignment. Rough sketches no longer force precise-shape mode automatically because the live sliding path is kept compatible with the visible-layer algorithm.

### Menus And Shortcuts

All actions are under JOSM `More tools`:

- `Align Way to Heatmap`: `Ctrl+Shift+Y`
- `Heatmap Layer Settings`: `Ctrl+Shift+U`
- `Geometry Cleanup Settings`: no default shortcut
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
- immutable original selected geometry, proposed preview geometry, and, after Apply, the actual resulting segment as separate OSM files
- raw candidate ridge geometries and final fixed-anchor preview geometries as separate OSM files, including inspection-only rejected candidates
- `candidate-metrics.csv`, with detector, visible color, intensity source, source tier, applicability, raw score, measurable-quality score, detector prior, calibrated score, longitudinal coverage and gap details, support ratio, mean intensity, mean gradient strength, objective-derived and physical longitudinal stability, SNR, ambiguity, corridor existence/localization confidence, normalized optimizer cost, containment, tube residuals, source-pixel high-frequency residuals/deltas/acceleration, turns, forward-progress violations, unsupported excursions, endpoint quality, edge-pinning, and safety warnings for each candidate
- `profile-peaks.csv`, with every detected cross-section peak, including offset, intensity, prominence, noise floor, support width, gradient strength/balance, native-vs-filtered maximum agreement, raw/B3/B5 center positions, scale agreement, center uncertainty, filter parameters, and synthetic-center flag
- `palette-samples.csv`, with every profile's slide-time raster anchor/normal, strongest evidence, strongest gradient evidence, and peak counts for quick detector calibration and geometric reconstruction
- `profile-intensity.csv`, with every in-raster and off-raster cross-section offset plus native, B3, B5, and profile-normalized scalar intensity
- `corridor-bands.csv`, with nested shoulder/core boundaries, robust centers, valley ratios, confidence, uncertainty, and parent/child identity
- `corridor-tracks.csv`, with longitudinal associations, bridged gaps, support, parent/child grouping, and lane/carriageway ambiguity evidence
- `corridor-tube.csv`, with the robust physical-distance center/tangent, core and shoulder bounds, uncertainty, confidence, scale conflicts, and raw/B3/B5 center evidence
- `association-decisions.csv`, with the selected strand transitions, predicted and observed offsets, source-pixel residuals, and gap decisions including profile count and physical distance
- `endpoint-approaches.csv`, with fixed or movable boundary constraints, selected branch anchors, Hermite guide targets, and explicit unsupported reasons
- `junction-safety.csv` and `junction-context.osm`, with the exact final-preview junction crossing evidence and only the adjacent connected segments that were evaluated
- `optimizer-costs.csv`, with the selected offset and data, continuity, acceleration, tube, endpoint, containment, and total costs plus exact-state/evaluation counts for every profile/track
- `detector-performance.csv`, with per-detector sampling, extraction, scale association, tracking/grouping, exact optimization, diagnostic serialization, projection, total/unaccounted time, and operation counts
- `parallel-context.json`, with redacted nearby OSM ids, assignment-relevant tags, distances, directions, overlap, and candidate reservation costs
- selected candidate, raw candidate scores, calibrated ranking scores, SNR/evidence details, sampled offsets, roughness metrics, screen-space ridge points, and projected East/North ridge points
- optional human candidate ratings and negative feature tags entered in the preview dialog, stored in both `candidate-ratings.json` and `status.json`
- `detector-attempts.json`, with every requested source/mapping pair, terminal status, reason, and produced candidate ids
- `tile-acquisition.json`, with the numeric cache generation, required/optional request purpose, safe tile identity, cache/network status, HTTP status, content type, attempt count, elapsed time, circuit state, cache-hit counts, and suppressed-retry counts. It contains no Cookie header, signed URL, response body, or credential-derived fingerprint.
- `scale-space.csv`, with L0/L1/L2 transforms, extracted bands, persistence, compatible coarse centers, conflicts, and parallel-parent merges
- visible-rendered-layer sampling details: source tile zoom captured while JOSM rendered the slide viewport, whether a virtual viewport/chunked capture was used, requested and actual capture bounds, viewport size and bounds, projection units per view pixel, measured ground metres per view/raster pixel, optional native tile resolution and raster footprint, tracker normalization policy, configured and effective cross-section width/step, physical profile count/path length and min/median/p95/max profile spacing, capture size, chunk count, and estimated visible tile range
- managed all-color aggregate visualization, when source mosaics are available, as `aggregate-intensity/all-colors-combined-z*.png` plus `aggregate-intensity/metadata.json`
- per-detector profile evidence: cross-section anchors, normals, detected peak offsets/intensities, peak support widths, gradient strength/balance, synthetic center flags, combined detector component weights, and per-detector support statistics
- verbose/debug log lines captured for that slide
- rendered heatmap layer capture used by visible-layer sampling

Format-11 adds `tile-acquisition.json` without changing existing geometry artifacts. Format-10 keeps robust ripple trend/residual evidence plus a separate cleanup-only absolute short-wave turn cost. Older formats remain readable and missing newer fields are unavailable rather than false zeros. Format-6 introduced runtime/build identity, unit-explicit sampling scale, bridge ownership, checksummed detailed CSV artifacts, and per-detector performance. Format-5 visible-rendered metre fields remain untrusted, and format 4 also has mutable post-Apply original-geometry risks. The export intentionally avoids Strava cookies, signed headers, response bodies, and full signed URLs.

### Managed Tile Troubleshooting

1. Save settings while the map is centered on a location that should have the selected activity/color tile, then read the selected-source check result. A visible JOSM fallback tile does not prove that the direct z15 check succeeded.
2. For authentication failure, paste fresh access values and save. Credential changes advance the numeric tile generation automatically.
3. For a suspected stale or bad cached response, use `Bypass managed tile cache...`, accept settings, and let the selected-source check run again.
4. For rate limiting, wait until the reported retry time. Repainting or toggling the aggregate layer does not bypass the pause.
5. `No tile at probe coordinate` is location-specific; center the map on another known heatmap location and save settings again.
6. JOSM may display fallback imagery and its own `No tiles at this zoom level` notice at the same time. The plugin reports direct selected-source access, diagnostic five-color completeness, and JOSM display state separately.

For local numerical analysis of nested bundles and repeated slides:

```bash
python3 scripts/analyze-debug-bundles.py problems.zip --raw-csv build/debug-candidates.csv
python3 scripts/analyze-slide-undulations.py problems.zip --csv build/undulations.csv --json build/undulations.json
python3 scripts/validate-sampling-scale.py --pretty
```

For a bounded privacy scan and deterministic manifest of many local archives,
run `scripts/calibrate-lateral-stability.py` with an explicit `--archive-root`,
include patterns, an ignored `build/` output directory, and
`--manifest-only --strict`. The shared reader takes one immutable snapshot of each
non-symlink outer file, validates nested depth, paths, entry types, CRCs, bounded
stored/deflated/BZIP2/LZMA content, compression ratios, encryption, and duplicate
contents without extraction. It scans the validated text inventory and ZIP comments
for credential-like material without echoing values. Quarantined archives are
excluded from the training/validation/holdout split lock.

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
7. Choose either `Move Existing Nodes` or `Precise Shape`. Configure future cleanup through `Geometry cleanup...`; leave the mode at `None` when the raw slide candidate is required.
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
