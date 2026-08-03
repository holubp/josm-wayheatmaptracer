# Subpixel Corridor and Endpoint Stability

**Status:** Implemented and verified locally on 2026-08-03; release remains separately authorized.

**Goal:** Make corridor-aware hot-source alignment land on the geographic and longitudinal center of broad or sparse trails, reject short-lived lateral strand noise, enter fixed junctions cleanly, and preserve existing OSM node identity without weakening real turns or the legacy tracker.

**Why planning is required:** Two supplied format-6 debug runs expose interacting coordinate, evidence, optimization, endpoint, safety, and OSM-mutation defects. The correction changes the managed-tile pixel-center contract and the experimental corridor-aware objective, while precise replacement must stop reusing existing node identities at unrelated positions. These changes need independently reversible outcomes and explicit regression gates so they do not erase switchbacks, destabilize weak trails, alter `LEGACY_V02`, or corrupt unrelated OSM data.

**Acceptance:** Managed source-tile image sample `(i,j)` round-trips through Web Mercator as world-pixel center `(i+0.5,j+0.5)` at z13, z14, and z15, while raster boundary corners remain unchanged. Corridor-aware L0 localization resolves quarter- and half-source-pixel centers with maximum bias `0.10` source pixel and retains the current physical B3/B5 support. Broad high/medium corridors have high-frequency p95 no greater than `0.25` source pixel; the weak short-run-strand fixture has high-frequency p95 no greater than `0.50` source pixel and mean center bias no greater than `0.25` source pixel. Sustained sine and switchback fixtures retain at least 90% of intended amplitude, sparse persistent trails remain applicable, and parallel fine tracks remain separate. In the two supplied hot-source runs, a native hot candidate remains near or improves on the measured human-reference fit, has no unsupported terminal hook, and is not outranked solely because a noisier alternative mapping escaped safety checks. Fixed endpoints remain exact; movable endpoints remain opt-in and bounded. Existing reusable nodes remain near their original monotonic path fractions through execute, undo, and redo; fixed, tagged, shared, and referenced nodes survive. No extra tiles, zooms, detector runs, post-optimization smoothing pass, geometry consensus, or hidden refinement run are added. Full tests, Javadoc, analyzer compatibility, debug redaction, and packaging checks pass. Release, push, and tag creation remain outside this plan until separately authorized.

## Confirmed Evidence

- Both `last-slide-debug-1785738882918.zip` and `last-slide-debug-1785739240117.zip` sampled managed `hot` z15 tiles in stable fixed-scale mode. The source pixel is approximately `1.553 m`; the selected segment has 189 profiles.
- Against `human-guesstimate2.osm`, fixed-endpoint `hot` is geometrically better than the selected `bluered-combined` candidate: approximately `1.25 m` versus `1.78 m` symmetric mean drift and `2.91 m` versus `4.44 m` p95 drift. Hot is rejected because of its endpoint, not because the whole candidate follows the wrong road.
- The fixed hot family sees a transient junction branch in the first profiles. Hot L0 centers are about `+63 px`, `+105 px`, and `+16 px` at profiles 0-2 before the intended track settles around `+5 px`, `-13 px`, and `-20 px`. The fixed endpoint remains at zero while the next optimized point follows the transient branch, producing the reported 75-76 degree terminal turn.
- In the low-intensity section, candidate high-frequency p95 is roughly `0.8-1.1` source pixels, or about `1.3-1.7 m`. Several worst lateral deviations have almost zero acceleration cost because the existing approximately 5 m robust tube follows the same short-lived strand motion and is then used as the curvature reference.
- The current applicability gate reports source-resolution aliasing only above `1.65` source pixels together with a high wiggle ratio. It therefore calls visibly noisy metre-scale candidates usable.
- `ReplaceWaySegmentCommand.buildReplacementNodes` consumes mutable existing nodes sequentially. With a dense preview, original nodes from far along the selected segment can be moved into the first preview positions, even though overall geometry still looks plausible. This is an OSM identity and undo/redo correctness defect independent of detector quality.
- The working tree already contains approved but uncommitted fine-localization changes: two interleaved L0 lateral phases, physical B3/B5 stride preservation, source-pixel localization uncertainty, and weak-corridor phase regressions. Implementers must build on these changes rather than replace or duplicate them.

## Fixed Design Decisions

- Scope all detector and objective changes to `TrackerMode.CORRIDOR_AWARE`. Do not modify `RidgeTracker` or legacy candidate behavior.
- Treat mosaic origins and visualization corners as pixel boundaries. Treat decoded raster indices as pixel-center samples. Managed candidate sampling and projection must use explicit inverse transforms between those two spaces.
- Keep one exact bounded second-order optimizer. Multi-window tube evidence is prepared before that optimizer; it is not a second optimization or completed-geometry smoother.
- Keep the current local robust tube. Add one wider stability reference and select/blend references from sustained fine-track evidence. Do not globally widen the only tube.
- Absolute signal strength must not decide whether stabilization exists. Medium and weak trails can be stable through longitudinal persistence, raw/B3/B5 agreement, and bounded uncertainty.
- Coarse scale can support persistence but cannot relocate a fine child after a scale conflict or parent merge. Preserve the current `(1 - fine localization confidence)^2` coarse-position weighting unless a new regression proves a change is necessary.
- Repair unsupported endpoint geometry before applicability is decided, then run geometry-dependent safety on the stored final preview. Preserve raw ridge and raw warning metrics for diagnostics.
- Reuse existing mutable nodes by monotonic path-fraction matching. Never identify reusable nodes merely by preview iteration order.
- Do not commit supplied ZIPs, extracted contents, `human-guesstimate*.osm`, or `exceptions.txt`.

### Outcome 1: Freeze the two defects with synthetic red regressions

- Work: In `CorridorCenterlineOptimizerTest` add a broad weak corridor whose local preferred strand stays on either shoulder for short 3-6-profile runs before reversing. Use realistic `1.5-2.6 m` cumulative profile spacing and source-pixel normalization. Assert the existing result fails the acceptance center-bias or high-frequency limit. Keep the existing sustained-sine test as the anti-oversmoothing gate.
- Work: In `CorridorRasterIntegrationTest` preserve the current off-grid phase sweep and add a diagonal weak raster whose scalar maximum lies at `0.25` and `0.50` source-pixel phases. Measure the resulting geometry, not only peak metadata.
- Work: In `AlignmentServiceTest` or a focused package-level endpoint test, reproduce `fixed anchor -> short lateral hook -> supported approach`. Assert the raw diagnostic remains available but the safe final preview connects through the first sane approach and is applicable. Add a true supported turn near an endpoint that must not be pruned.
- Work: In `ReplaceWaySegmentCommandTest` build five original nodes at 0%, 25%, 50%, 75%, and 100% and a dense preview. Assert the current sequential reuse fails because the 25% node is moved near the start. Add exact execute/undo/redo coordinate and membership assertions.
- Work: Keep test data synthetic and unit-explicit. Record the real-bundle baseline only in this plan and analyzer output, not in committed fixtures.
- Verify: `sh gradlew test --tests '*CorridorCenterlineOptimizerTest' --tests '*CorridorRasterIntegrationTest' --tests '*AlignmentServiceTest' --tests '*GeometryPostProcessorTest' --tests '*ReplaceWaySegmentCommandTest'`

### Outcome 2: Make managed raster sample centers geographically exact

- Work: Refactor the coordinate conversion used by `TileHeatmapSampler.sampleProfiles`, aggregated profile sampling, and `projectCandidate` into named inverse helpers. If `originWorldPx` is a mosaic boundary and decoded image index `i` is its sample center, map world to virtual sample coordinates as `(world - originWorldPx - 0.5) * virtualRasterScale`; map a virtual sample coordinate back as `local / virtualRasterScale + originWorldPx + 0.5`.
- Work: Apply the center offset only to image-sample/candidate transforms. Keep `TileMosaic.originWorldPx*`, tile ranges, crop bounds, and the four corners from `buildAggregatedIntensityVisualization` as boundary coordinates. Do not shift downloaded-area checks or tile URL indexes.
- Work: Add package-level round-trip tests in `TileHeatmapSamplerTest` for pixel centers and fractional centers at z13-z15 and representative latitudes near 0, 49.44, and 80 degrees. Require world-pixel round-trip error below `1e-9` and projected ground error below `1 mm` where projection precision permits. Add an impulse/one-pixel ridge test proving that the projected candidate passes through the image sample center rather than its north-west boundary.
- Work: Audit the visible rendered-layer transform separately. Preserve its established slide-time capture projection unless a failing pixel-center test proves it has the same defect; do not apply the managed `+0.5` correction blindly to JOSM screen pixels.
- Risks/open questions: The expected managed correction is one half native pixel east and south from the old projected sample location, approximately `0.78 m` per axis at the supplied z15 latitude. If the round-trip test contradicts that convention, stop and document the actual decoded-tile convention before changing production code.
- Verify: `sh gradlew test --tests '*TileHeatmapSamplerTest' --tests '*SamplingScaleTest' --tests '*CorridorScaleInvarianceTest'`

### Outcome 3: Complete and lock subpixel cross-section localization

- Work: Finish the existing `RenderedHeatmapSampler` two-phase L0 implementation. The physical lateral step remains the source-pixel pitch; L0 evaluates two interleaved phases at half that pitch. B3 `[1,2,1]` and B5 `[1,4,6,4,1]` taps use an index stride of two so their physical convolution support is unchanged. L1/L2 retain one phase unless a separate failing scale-phase test justifies more.
- Work: Keep scalar interpolation after palette/direct-intensity conversion and never interpolate across invalid raster support. `CorridorExtractor` must use native source-pixel pitch as the localization-resolution and uncertainty floor even though candidate states are available at half-pixel or interpolated positions.
- Work: Verify that raw/B3/B5 center agreement and plateau midpoint scoring can select a position between decoded pixels. Do not introduce a fixed `0.5` offset into detector scores; the coordinate correction belongs only in Outcome 2.
- Work: Extend diagnostics with physical lateral step, localization phase count, filter stride, native source-pixel pitch, and pixel-center convention. Keep old bundle readers tolerant of missing fields.
- Verify: `sh gradlew test --tests '*RenderedHeatmapSamplerProfileTest' --tests '*CorridorExtractorTest' --tests '*CorridorRasterIntegrationTest' --tests '*MultiScaleCorridorTrackingTest'`

### Outcome 4: Add a bounded longitudinal stability reference

- Work: Extend `CorridorTubeBuilder`, `LongitudinalCorridorTube`, and `CorridorTubeSlice` so each profile retains both the current local robust line and a stability robust line. Keep the local physical half-window at approximately `5 m`, clamped to 5-9 nearest profiles. Build the stability line over a `12 m` half-window, clamped to 9-17 nearest profiles. At short ends use all available ordered observations; do not fabricate symmetric support.
- Work: Fit both references with the existing confidence-weighted line and two Huber reweighting iterations. Keep `delta = max(0.5 source pixel, 1.5 * weighted median absolute residual)`. Observation weights continue to derive from signal existence, localization confidence, and scale persistence, so a weak persistent strand remains evidence.
- Work: Calculate a deterministic `motionSupport` in `[0,1]` from the selected fine track, not from completed geometry. Full support requires at least 8 m of observations, at least 70% coherent local motion after an uncertainty deadband, raw/B3/B5 center spread no greater than one source pixel, and no fine-scale identity switch. Scale conflict or coarse parent merge contributes zero coarse support but does not erase sustained compatible L0 evidence from the same fine track. A scale-compatible locally curved run may receive support even around a direction reversal when both sides of the apex persist for at least 8 m.
- Work: Export local center/tangent, stability center/tangent, residual scales, support windows, `motionSupport`, and machine-readable reasons that reduced support. Keep existing `center_px` columns readable as the effective pre-optimizer reference and add columns rather than silently changing units.
- Risks/open questions: The 12 m window is a starting calibrated support, not a UI option. If it reduces the sustained sine/switchback below 90% before optimization weights change, stop and shorten the window or improve motion support; do not compensate with a final geometry filter.
- Verify: `sh gradlew test --tests '*LongitudinalCorridorTubeTest' --tests '*CorridorScaleInvarianceTest' --tests '*MultiScaleCorridorTrackingTest'`

### Outcome 5: Reference the exact optimizer to stable heatmap geometry

- Work: Update `CorridorCenterlineOptimizer` without changing its exact pair-state topology or state bound. For each adjacent profile pair, interpolate the reference heading between the stability tube and local tube using `motionSupport`; use circular angle interpolation. At unsupported/noisy profiles the stability heading dominates. At sustained fine-track turns the local heading dominates. Do not use the noisy local tube unconditionally.
- Work: Use the same effective reference in dynamic-programming transitions and reconstructed `CostRow` diagnostics so reported acceleration exactly matches the optimized objective. Preserve continuity normalization from actual profile spacing and source-pixel pitch.
- Work: Let the effective tube-center data prior use the same supported local/stability center. Keep it uncertainty-weighted and weak compared with leaving the selected core/shoulder. Keep the current non-sustained acceleration multiplier as the initial value; tune only if the red weak-strand regression still fails after the reference is corrected.
- Work: Preserve mandatory allowed states for local center, stability center, raw/B3/B5 centers, core/shoulder boundaries, valid coarse center, endpoint targets, and fixed zero. If the mandatory set exceeds the current bound, deduplicate values within `0.05` source pixel before failing clearly; never silently discard endpoint or corridor boundaries.
- Work: Update exact-output checksums only after geometry acceptance passes, and document that the objective intentionally changed. Keep transition/state operation-count assertions and performance instrumentation.
- Risks/open questions: The stability reference must not become an input-way shape prior. Rough 2-5-node sketches still need heatmap-derived turns. The input source geometry is used for sampling and fixed anchors, not as proof that a heatmap curve is noise.
- Verify: `sh gradlew test --tests '*CorridorCenterlineOptimizerTest' --tests '*CorridorRasterIntegrationTest' --tests '*CorridorScaleInvarianceTest' --tests '*CorridorScaleSpacePerformanceTest'`

### Outcome 6: Repair fixed and movable endpoint approaches before safety ranking

- Work: In `EndpointApproachBuilder`, select the first sane interior anchor using the stable/local support from Outcomes 4-5. Skip search-edge bands, scale conflicts, unresolved parent merges, grossly wide/ambiguous cores, and transient centers whose residual exceeds combined uncertainty. Build the approach from the selected branch; never use a connected main-road tangent.
- Work: Keep fixed endpoint offset exactly zero. For movable endpoints retain the original-position prior and current hard displacement bound; movement remains opt-in and must not be justified by a distant interior point alone.
- Work: Retain `GeometryPostProcessor.pruneEndpointClusters` only as a bounded precise-preview fallback. It may remove newly generated endpoint-adjacent points inside the existing 3-12 m terminal window when the immediate turn exceeds 35 degrees and the direct shortcut reduces the turn, preserves forward progress, creates no self-intersection or connected-way crossing, and removes no fixed/shared/tagged/referenced anchor. It must not simplify a supported turn merely because it is sharp.
- Work: Recompute blocking foldback, terminal-turn, self-intersection, and connected-way-crossing checks from `CenterlineCandidate.finalPreviewPoints`. Keep raw optimizer endpoint turns and cleanup decisions in debug output. A repaired final preview must not retain a stale raw `unsupported terminal turn` warning; a still-unsafe final preview remains inspection-only.
- Verify: `sh gradlew test --tests '*CorridorEndpointApproachTest' --tests '*GeometryPostProcessorTest' --tests '*AlignmentServiceTest' --tests '*CorridorRasterIntegrationTest'`

### Outcome 7: Preserve existing node identity at its longitudinal position

- Work: Replace `ReplaceWaySegmentCommand.nextMutableNode` sequential consumption with a monotonic minimum-cost assignment. Build ordered mutable source nodes with original path-length fractions and ordered eligible preview slots with preview fractions. Exclude fixed-anchor slots and soft-anchor targets before matching.
- Work: Use sequence-alignment dynamic programming over source nodes and preview slots. Compare states lexicographically by maximum match count, minimum sum of absolute fraction errors, then deterministic source/preview indexes. Allowed actions are skip source node, skip preview slot, or match; backtrack once to produce `previewIndex -> existingNode`. Complexity is bounded by `O(sourceNodes * previewPoints)` and does not affect ridge optimization.
- Work: Create new nodes for unmatched preview slots. Remove unmatched existing nodes only through the existing `!hasKeys && no referrers` rule. Keep fixed, endpoint, tagged, shared, and referenced soft anchors exact and ordered. Continue storing immutable original and target coordinates so execute and redo apply the same targets and undo restores exact originals.
- Work: Add tests with fewer, equal, and more preview slots than reusable nodes; fixed interior anchors; shared/tagged nodes; execute/undo/redo; and partial-way replacement. Assert node order, approximate original fraction, dataset membership, no orphaned untagged node, and no movement outside the selected segment.
- Verify: `sh gradlew test --tests '*ReplaceWaySegmentCommandTest' --tests '*AlignWayActionTest'`

### Outcome 8: Make ripple safety and ranking reflect supported motion

- Work: Add a quality metric for non-sustained high-frequency motion relative to the effective stability reference. Count only alternating lateral residuals that lack `motionSupport`; exclude sustained same-track turns and switchbacks. Export RMS, p95, count, and ratio in source pixels and metres.
- Work: Start the blocking gate at non-sustained p95 greater than `0.60` source pixel together with at least four unsupported reversals and an unsupported-reversal ratio above `0.08`. Calibrate against synthetic turns and both supplied bundles. If a true-turn regression is rejected, improve support classification rather than raising the gate until the noisy examples pass.
- Work: Keep applicability before ranking. Once the endpoint is repaired, native hot-family candidates on a hot source should be compared in the native source tier before alternative mappings. Do not add a hot-specific geometry hack or change palette mappings in this iteration. Preserve every rejected candidate for preview/rating.
- Work: Extend candidate metrics, status JSON, verbose logs, and `scripts/analyze-debug-bundles.py` with raw versus final terminal turn, local/stability reference residuals, non-sustained wiggles, pixel-center convention, and original-node fraction reuse. Keep format 1-6 reading compatible and keep all credentials redacted.
- Verify: `sh gradlew test --tests '*AlignmentServiceTest' --tests '*CorridorQualityMetricsTest' --tests '*LastSlideDebugBundleTest' && python3 -m py_compile scripts/analyze-debug-bundles.py`

### Outcome 9: Validate the complete behavior and document the contract

- Work: Run the analyzer against both supplied debug bundles and `human-guesstimate2.osm` locally. Compare every native hot-family and selected alternative candidate before and after. Record mean/p95 human-reference drift, high-frequency p95 in source pixels/metres, endpoint turns, applicability, and rank. Do not add these private artifacts to Git.
- Work: Run the fixture archive when available for `hot`, `blue`, `bluered`, `purple`, `gray`, and all-color aggregate. Verify palette ordering is unchanged, the legacy tracker snapshots are unchanged, weak trails remain present, parallel paths remain distinct, and switchback amplitude stays above acceptance.
- Work: Update `DEVELOPMENT.md` with the managed boundary/sample-center transform, half-phase sampling, physical convolution support, dual-window tube parameters, motion support, final-preview safety, and monotonic node assignment. Update `AGENTS.md` only with durable invariants not already present. Update this plan status only after evidence is complete.
- Work: Review the final diff for accidental changes to cookies, cache URLs, supplied diagnostics, version metadata, or release files. Do not bump the version, commit, push, tag, or publish without a separate explicit request.
- Verify: `sh gradlew clean test build javadoc && python3 -m py_compile scripts/analyze-debug-bundles.py && git diff --check`

## Execution Order and Stop Conditions

1. Outcomes 1-3 establish the pixel and subpixel coordinate contract. Do not tune corridor weights before these tests pass.
2. Outcomes 4-5 change only pre-optimizer references and the existing exact objective. Stop if state bounds, deterministic tie behavior, performance bounds, weak-strand applicability, or 90% turn amplitude cannot all be retained.
3. Outcome 6 must make the fixed hot candidate safe through supported branch geometry or narrowly proven cleanup. Do not merely suppress the warning.
4. Outcome 7 is independent of detector tuning and may be implemented after Outcome 1, but its tests must pass before any preview can be considered safe to apply.
5. Outcome 8 thresholds may be calibrated only after geometry improves. Safety must catch the old noisy candidates without rejecting sustained supported turns.
6. Stop immediately if any code path moves, deletes, retags, or simplifies a primitive outside the selected segment; if fixed/shared/tagged nodes are removed; if undo/redo differs; if an extra network request appears; or if debug output contains credentials.

## Handoff Notes for Lower-Cost Implementers

- Read the current working-tree diff before editing. Several Outcome 3 changes already exist and must not be reverted.
- Follow red-green order one outcome at a time. Do not update checksum or numerical baselines until the intended red assertion fails for the documented reason.
- Keep units in names: `worldPx`, `imageSamplePx`, `virtualRasterPx`, `sourcePx`, `groundMeters`, and `EastNorth`. Do not reuse a generic `scale` variable across these spaces.
- Change one parameter family at a time and include before/after metrics in the test failure message. Avoid compensating weight changes across multiple terms.
- Do not interpret a detector name as a source color. The supplied source is hot; alternative mappings are evaluations of that source unless the candidate is the complete managed all-color aggregate.
- Preserve raw bands, raw candidate geometry, final preview geometry, and actual applied geometry as separate debug artifacts.

## Implementation Evidence

- Managed sample-center round trips pass at z13-z15 for latitudes 0, 49.44, and 80 degrees; aggregate boundary coordinates and the visible rendered-layer transform remain unchanged.
- Fine half-phase localization, physical B3/B5 support, weak off-grid phase sweeps, broad-corridor ripple limits, scale invariance, sustained sine amplitude, sparse strands, parallel corridors, and the fixture archive pass.
- The exact optimizer now consumes local/stability references and unsupported-motion plateau deadbands without a second geometry pass. Deterministic offsets, costs, state counts, transition counts, and checksums were intentionally refreshed in regression tests.
- Endpoint hook cleanup and final-preview warning evaluation pass alongside the supported-turn control. Reusable node identities are matched monotonically by path fraction; command execute/undo/redo and orphan cleanup tests pass.
- Format-6 diagnostics add sample-center metadata, dual-window tube fields, support reasons, and non-sustained ripple metrics. Both debug analyzers read the two supplied old bundles, and older missing columns remain optional.
- Verification completed with `sh gradlew test`, the focused fixture/corridor/endpoint/node/sampling suite, analyzer execution on both supplied bundles, and Python compilation. No version, release, tag, push, or private diagnostic artifact was added.
