# Corridor Repeat Convergence and Exact-Optimizer Performance

**Status:** Implemented and verified for target `v0.16.3` on 2026-08-02.

**Target release:** `v0.16.3` bugfix release, together with the visible rendered pixel-to-ground scale repair. No new detector family or palette calibration is introduced.

**Implementation evidence:** Direction-independent tracker tests prove the higher-index boundary owns approved gaps in either traversal direction, while unapproved, over-16-profile, and over-20-metre gaps remain incomplete. Exact optimizer snapshots preserve complete offset sequences, costs, checksums, tie behavior, and transition counts. Profile-cost evaluation is bounded by profile states and is at least 20 times lower than transition count on the 400-profile regression. The same local non-gating JUnit case reported approximately `0.53-0.56 s` after the refactor versus `71-185 s` from an isolated unmodified `v0.16.2` source export; elapsed time is supporting device-specific evidence, while operation counts and exact checksums are the deterministic gate. The supplied repeat control remains an exact prior-output input match and reports `0.285/0.622/1.124 m` mean/p95/maximum drift with seven applicable candidates. Format-6 exports per-detector timings and operation counts without duplicating full profile arrays in summary JSON.

**Goal:** Make corridor-aware results remain applicable when the same tracker-approved short gap was traversed backward, prove repeat slides converge without losing all applicable candidates, and materially reduce CPU time without changing the exact optimizer objective, candidate geometry, detector coverage, or deterministic ordering.

**Why planning is required:** `problems-5.zip` contains one confirmed direction-dependent coverage defect and exposes a CPU hot path spanning tracker state, second-order dynamic programming, eager diagnostics, candidate preview reconstruction, and safety checks. A tempting detector reduction, approximate beam search, or broad parallelization could improve wall time while silently changing the candidate that users now consider acceptable.

**Acceptance:** A tracker-approved bounded gap is complete regardless of tracking direction, while an unapproved or over-limit gap remains inspection-only. A synthetic slide followed by a slide of its result retains at least one applicable native candidate and does not accumulate an unsupported excursion, self-intersection, or endpoint crossing. Existing `LEGACY_V02` output remains unchanged. The corridor optimizer evaluates the same state space, objective terms, tie order, selected offsets, total cost, diagnostics rows, and transition count as before, but computes each profile/offset-only cost once. On the representative 369-profile/35-track workload shape, expensive profile-cost evaluations fall from transition scale to profile-state scale, at least 20-fold, and a non-gating local benchmark shows at least a twofold median optimizer speedup. Every requested detector still runs and appears in detector-attempt diagnostics. Format-6 debug output records per-detector sampling, extraction/association, tracking, optimization, serialization, preview, and safety timings plus operation counts. Full Java, Python, fixture, Javadoc, and packaging checks pass.

## Confirmed Evidence from `problems-5.zip`

- All three bundles are from plugin `0.16.2`, managed stable fixed-scale z15 inference with z14 validation, a `7.01 m` search half-width, `1.56 m` step, visible `hot`, alternative mappings enabled, and managed all-color aggregation enabled.
- Tile acquisition takes only `97-103 ms`. The combined ridge phase takes `19.970 s`, `5.988 s`, and `6.613 s`; network and cache access are not the bottleneck.
- The runs contain 369/150/152 profiles, 35/24/20 optimized tracks, and `9,222,245` / `3,516,362` / `3,631,283` exact dynamic-programming transitions.
- Gaussian scalar-field and pyramid construction totals only a small fraction of ridge time: individual native mappings take roughly `0.8-15.6 ms`, and the all-color aggregate takes `6.4-21.8 ms`.
- `CorridorCenterlineOptimizer.solveExact` recomputes `profileCost(...)` inside every pair-state transition. That cost performs intensity interpolation, scans profile samples for the band maximum, calculates plateau/core/tube/coarse evidence, and looks up constraints even though the result depends only on profile index and current offset, not the previous pair state.
- The 369-profile bundle contains about `42.5 MB` of uncompressed debug data, including `9.9 MB` of `diagnostics.json` profile JSON that substantially duplicates dedicated profile and corridor CSVs.
- The user's two visually workable modified-way candidates are ranked first and second: `hot-corridor/strand-9` and `hot-strict/strand-2`. The selected candidate is complete and applicable.
- The also visually plausible `hot/strand-9` is inspection-only solely because of two one-profile gaps. In `corridor-tracks.csv`, gaps `99..101` and `355..357` have `left_bridged=true` and `right_bridged=false`. `CorridorCoverageCalculator` checks only `track.points().get(right).bridged()`, so both tracker-approved backward gaps are mislabeled unapproved.
- The new-way first run and repeat are reproducibly stable: the first applied geometry exactly equals the repeat's original geometry within serialization precision. The repeat changes it by approximately `0.278 m` bidirectional mean and `1.124 m` maximum, adds two points, and remains applicable. This is useful control evidence, not a failing case.
- The reported repeat of the modified way where every result became inspection-only is not included. The directional bridge defect is nevertheless independently reproduced in the included first bundle and explains why equivalent strand association can change completeness with seed/direction.
- The unreproduced occasion where no hot-family candidate appeared has no corresponding bundle. Current bundles contain a `hot` attempt every time. Preserve complete attempt diagnostics and add phase/status evidence; do not invent a detector fix without a reproduction.

## Fixed Design Decisions

- Preserve every detector mapping requested by the two independent settings. Do not skip low-ranked mappings, reduce Gaussian levels, lower profile density, prune optimizer states, or stop after finding one applicable candidate.
- Preserve the exact second-order optimizer objective and transition count. Performance comes from memoizing invariant values, indexed state storage, and reduced allocation, not approximation.
- Keep deterministic detector and candidate order. Do not parallelize detectors in this patch. Parallel execution can be reconsidered only after exact single-thread optimization and phase timings show a remaining need.
- Do not move managed alignment to a background thread in this patch. `AlignWayAction` currently touches JOSM datasets, layers, and preview state on the Swing event thread; splitting those ownership boundaries is an architectural/UI-responsiveness task rather than a computational bugfix.
- Normalize tracker-approved bridge direction at the tracker boundary. Coverage remains strict and continues to accept only explicit tracker-approved spans within 16 profiles and 20 metres.
- Keep full detailed calibration CSVs available for last-slide export. Remove duplicated profile arrays from the summary JSON only after proving every field remains present in a dedicated format-6 artifact and analyzers use the canonical file.
- Treat supplied archives and notes as private, untracked evidence. No coordinates, imagery, hashes, or copied profile values enter committed fixtures.

### Outcome 1: Capture Direction-Independent Bridge and Repeat Regressions

- **Dependencies:** None.
- Work: Extend `CorridorTrackerTest` and `CorridorCoverageCalculatorTest` with the same bounded one-profile gap traversed forward and backward. Both resulting tracks must identify the identical approved span and produce complete coverage. A control gap without tracker approval and controls beyond either 16 profiles or 20 metres must remain incomplete.
- Work: Include a gap immediately adjacent to the bidirectional seed. This proves the backward bridge marker survives merging the forward and backward maps instead of being overwritten by the seed's unbridged copy.
- Work: Extend `CorridorRasterIntegrationTest` with a deterministic curved/broad corridor containing a short ambiguous profile and a fixed interior junction. Run the sampled/tracked result through the same raster a second time. Both runs must retain an applicable/complete native candidate; the repeat must stay within one source pixel, preserve endpoints, and introduce no new unsupported excursion.
- Work: Extend `scripts/analyze-slide-undulations.py` to recognize consecutive bundles where an earlier `applied-segment.osm` equals a later `original-segment.osm` within a documented small tolerance. Report point-count growth, length change, bidirectional mean/p95/maximum drift, selected candidate, applicable-attempt count, and warning deltas. Preserve standalone and nested-archive behavior.
- Risks/open questions: The missing modified-way repeat cannot be replayed exactly. The regression must model the confirmed directional marker failure rather than copy private data or assert speculative detector behavior.
- Verify: `sh gradlew test --tests '*CorridorTrackerTest' --tests '*CorridorCoverageCalculatorTest' --tests '*CorridorRasterIntegrationTest' && python3 -m unittest discover -s scripts/tests -p 'test_*undulation*.py'`

### Outcome 2: Make Bridge Approval Independent of Tracking Direction

- **Dependencies:** Outcome 1.
- Work: Give `CorridorTracker` one canonical bridge-marker convention: the higher-index/right observed boundary represents an approved missing span. When `advance` rejoins while moving backward, transfer approval to the prior higher-index boundary rather than the newly added lower-index point.
- Work: Merge forward and backward points without losing bridge state on the shared seed. When both maps contain the same profile, preserve the point identity and OR only equivalent bridge approval; reject conflicting band identity instead of silently choosing one.
- Work: Keep `CorridorCoverageCalculator` strict and simple: it may continue checking the canonical right boundary. Do not change it to accept either adjacent boolean because one boolean could otherwise accidentally approve two adjacent gaps.
- Work: Export association and track rows with canonical bridge side and explicit left/right profile indexes in format 6, so future reports can distinguish tracker approval from coverage interpretation.
- Work: Re-run the private bundle analyzer locally and confirm the two included `hot/strand-9` spans are recognized as approved by the corrected direction contract. Do not claim the absent repeat is reproduced.
- Risks/open questions: Existing track ids and scores must remain stable. Bridge-marker normalization changes coverage/applicability only; it must not change transition scoring, optimized offsets, or candidate ordering before applicability is considered.
- Verify: `sh gradlew test --tests '*CorridorTrackerTest' --tests '*CorridorCoverageCalculatorTest' --tests '*CorridorRasterIntegrationTest' --tests '*AlignmentServiceTest'`

### Outcome 3: Lock Exact Optimizer Output Before Refactoring

- **Dependencies:** None.
- Work: Extend `CorridorCenterlineOptimizerTest` with deterministic broad-corridor, sparse-gap, sine, fixed-endpoint, and movable-junction snapshots. Assert the complete selected offset sequence, total and per-row objective components, maximum offset/pair states, and transition evaluation count from the current implementation.
- Work: Add operation counters to `OptimizationResult`: profile-cost evaluations, point constructions or point-table entries, and retained pair-state allocations. These are diagnostics, not objective terms. The pre-optimization baseline test should demonstrate profile-cost evaluations scale with transitions.
- Work: Add a non-gating local benchmark test or script using 369 profiles and representative 16-18 allowed states. Warm up the JVM, run several iterations, report median time, and compare checksums of offsets/costs. Wall-clock assertions must not be part of the normal Gradle suite.
- Risks/open questions: Do not snapshot unstable elapsed times or map iteration implementation details that are not part of deterministic candidate output. The transition count is part of the exact-state-space acceptance and must remain stable.
- Verify: `sh gradlew test --tests '*CorridorCenterlineOptimizerTest'`

### Outcome 4: Precompute Profile-State and Adjacent-Geometry Invariants

- **Dependencies:** Outcome 3.
- Work: In `CorridorCenterlineOptimizer`, build one immutable state table per profile after `allowedOffsets`: sorted offsets, source point for each offset, data-plus-constraint profile cost, and any profile-local evidence reused by diagnostics. Evaluate each profile/offset cost once.
- Work: Build one adjacent-profile table for heading and continuity values for every previous/current offset pair, plus one tube reference heading and physical/source-pixel spacing per profile transition. Preserve the existing arithmetic expression and floating-point operation order for every objective term.
- Work: Replace repeated `pairStateComparator()` allocation with one static deterministic comparator. For a fixed pair key, compare cost first and retain the first state on an exact tie, matching current `LinkedHashMap` behavior.
- Work: Initially route the existing map-based exact dynamic program through the precomputed tables. Verify exact snapshots before changing state storage. This isolates arithmetic caching from allocation reduction.
- Work: Profile-cost evaluations must be no greater than the total number of allowed profile states. For a 369-profile, 18-state upper-bound track this is at most `6,642`, rather than hundreds of thousands of transitions.
- Risks/open questions: Computing a cost once can expose hidden mutation. All profile, tube, endpoint, scale, and junction inputs must be immutable for one optimization; fail tests if a consumer relies on evaluation side effects.
- Verify: `sh gradlew test --tests '*CorridorCenterlineOptimizerTest' --tests '*CorridorEndpointApproachTest' --tests '*CorridorRasterIntegrationTest'`

### Outcome 5: Replace Pair-Key Allocation with Deterministic Indexed State Tables

- **Dependencies:** Outcome 4.
- Work: Represent current pair states as a rectangular indexed table over the previous and current profile's sorted offset indexes. Iterate indexes in the same lexicographic order in which the existing `LinkedHashMap` first inserts pair keys.
- Work: Keep primitive best costs separately and allocate a predecessor state only when a transition strictly improves the same pair. Preserve predecessor reconstruction, exact tie behavior, maximum pair-state count, and transition evaluation count.
- Work: Use the precomputed point/heading/continuity tables in the inner loop. Curvature/acceleration remains evaluated for every exact transition because it depends on the predecessor heading; do not approximate or prune it.
- Work: Add an implementation-level checksum over selected offsets and objective rows to the non-gating benchmark. Require byte-for-byte identical formatted optimizer CSV rows before and after indexed storage on all snapshot fixtures.
- Work: Accept the optimization only if the local warmed median is at least twice as fast and profile-cost evaluations drop at least twentyfold on the representative workload. If indexed storage does not add material benefit after Outcome 4, retain the simpler map-based version and record the measured reason in this plan before proceeding.
- Risks/open questions: Double offsets can include signed zero. Index identity must come from the existing sorted allowed list; do not normalize doubles or rebuild values from arithmetic.
- Verify: `sh gradlew test --tests '*CorridorCenterlineOptimizerTest' --tests '*CorridorAwareTrackerTest' --tests '*CorridorRasterIntegrationTest'`

### Outcome 6: Remove Duplicate Runtime Diagnostics and Add Phase Timings

- **Dependencies:** Outcomes 2 and 5; format-6 schema from the scale-repair plan.
- Work: Add per-detector timing and operation records for scalar sampling/pyramid construction, extraction/scale association, longitudinal tracking/grouping, exact optimization, diagnostic row construction, candidate projection, final-preview reconstruction, and safety annotation. Aggregate totals must reconcile with the top-level ridge duration within a documented small accounting remainder.
- Work: Record profile count, extracted-band count, track count, allowed-state count, transition evaluations, profile-cost evaluations, retained-state allocations, diagnostic characters/bytes, and candidate point count. Timings are observational and never affect ranking.
- Work: Stop embedding the complete per-profile JSON array inside format-6 `diagnostics.json` when the same raw values are already present in `profile-peaks.csv`, `profile-intensity.csv`, `corridor-bands.csv`, and `scale-space.csv`. Replace it with artifact names, row counts, and checksums. Keep old readers for format 1-5.
- Work: Preserve on-demand maximum-detail export. Do not suppress CSV rows based on candidate applicability or detector rank. Candidate ratings, rejected attempts, raw scores, and safety evidence remain available.
- Work: Update `scripts/analyze-debug-bundles.py` to summarize phase time and operation counts, flag unreconciled timing, and identify the top detector hot paths. Add generated format-5/format-6 fixtures.
- Risks/open questions: Eager CSV construction may remain a secondary cost after the exact optimizer repair. Do not introduce a broad lazy object graph unless format-6 phase evidence proves serialization still dominates.
- Verify: `sh gradlew test --tests '*LastSlideDebugBundleTest' --tests '*AlignmentServiceTest' && python3 -m unittest discover -s scripts/tests -p 'test_debug_analyzers.py'`

### Outcome 7: Integrate, Document, and Verify the Combined Bugfix

- **Dependencies:** Outcomes 1-6 and every outcome of `2026-08-02-221142-01-plan-visible-rendered-pixel-ground-scale-repair.md`.
- Work: Update `DEVELOPMENT.md` with canonical bridge ownership, exact optimizer caching, deterministic state iteration, phase timing interpretation, and why detector pruning/parallelism/background execution remain deferred.
- Work: Update `AGENTS.md` with guardrails that bridge approval is direction-independent, exact optimizer optimizations must preserve transition/objective results, and every requested detector remains represented in attempts even when it yields no candidate.
- Work: Update README only where the debug workflow exposes format-6 performance and repeat-convergence summaries. No new setting or UI claim is needed.
- Work: Run `scripts/analyze-debug-bundles.py` and `scripts/analyze-slide-undulations.py` on `problems-5.zip` locally. Confirm the analyzer reports the included repeat control, bridge-direction defect in format 5, and operation/timing baseline without copying private output into the repository.
- Work: Run full legacy, corridor, managed-tile, visible-layer, palette, aggregate, endpoint, topology, simplification, undo/redo, modeless preview, diagnostics, and analyzer regressions. Review the diff for detector output changes outside the intended bridge applicability and factual scale corrections.
- Work: Update the plan status to implemented only after all acceptance evidence passes. Version metadata may move to `0.16.3`, but commit, push, tag, release, and asset publication require a separate explicit request.
- Risks/open questions: The user's unreproduced missing-hot event remains an evidence gap. Format-6 attempts and timings are the diagnostic completion criterion; do not claim that event fixed without a new bundle.
- Verify: `sh gradlew clean test build javadoc && python3 -m unittest discover -s scripts/tests -p 'test_*.py' && python3 -m py_compile scripts/*.py && git diff --check && unzip -p build/libs/wayheatmaptracer.jar META-INF/MANIFEST.MF | rg 'Plugin-Version: 0.16.3'`

## Execution Order and Stop Conditions

1. Complete Outcomes 1 and 3 before production edits. Preserve the directional failure and exact optimizer baseline.
2. Complete Outcome 2. Stop if candidate offsets, track ids, or scores change; only canonical bridge metadata, coverage, applicability, and ranking prefix may change.
3. Complete Outcome 4 and compare exact snapshots plus operation counts.
4. Complete Outcome 5 only after Outcome 4 is exact. Stop and retain the map implementation if indexed storage changes any selected offset, cost row, transition count, or tie result.
5. Execute the scale-repair plan before finalizing shared format-6 diagnostics.
6. Complete Outcome 6 and use its timings to verify that the optimization addressed the measured hot path.
7. Complete Outcome 7. Do not release merely because runtime improved; at least one applicable native candidate must survive every strong, medium, sparse, broad, curved, endpoint, and repeat fixture.

## Handoff Notes for Lower-Cost Implementers

- Read this plan, its companion scale plan, `AGENTS.md`, and the corridor runtime section of `DEVELOPMENT.md` first.
- The bridge bug is not a reason to loosen `CorridorCoverageCalculator`. Fix the directional marker where `CorridorTracker` creates or merges it.
- Add the red backward-gap test before changing tracker code. Confirm it fails because the left boundary is marked and coverage checks the right boundary.
- Treat optimizer work as an exact refactor. Do not change weights, allowed offsets, sorting, tie handling, source-pixel normalization, or candidate ranking.
- Keep operation-count acceptance separate from wall-clock observations. Operation counts are deterministic; benchmark elapsed time is supporting evidence only.
- Do not parallelize detector runs or move JOSM work off the event thread under this plan.
- Preserve all detector attempts and raw calibration data. A faster slide that silently omits candidates is a regression.
- Never stage `problems-5.zip`, extracted bundles, `exceptions.txt`, `human-guesstimate.osm`, generated private analysis, or credentials.
