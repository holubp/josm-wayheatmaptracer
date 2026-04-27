# AGENTS

Repository-specific guardrails for future changes:

- Treat JOSM coordinate spaces explicitly. Downloaded-area checks use `Bounds` with geographic coordinates, while heatmap sampling and ridge tracking operate in oversampled screen space before projecting back to `EastNorth`.
- The downloaded-area bypass is a heatmap-only drawing escape hatch and must remain opt-in. Do not make live alignment skip `Bounds` checks by default.
- Junction and endpoint movement must remain opt-in. When it is enabled in precise mode, do not simplify away selected/shared anchor nodes.
- Do not reintroduce uniform point redistribution after simplification in precise mode. Straight sections should be allowed to collapse more aggressively than curves.
- When precise mode drops interior nodes, clean up only nodes that are both untagged and unreferenced. Shared or tagged nodes must survive.
- Keep verbose/debug logging useful for remote diagnosis. For alignment bugs, log selected segment ids, sampled bands, chosen candidate offsets, and applied node moves.
- Palette changes must come with regression coverage in `HeatmapFixtureArchiveTest` or `RidgeTrackerTest`, especially for `blue`, `gray`, and internal multi-color/dual-color detection, which are easy modes to mis-rank.
- Palette evidence is semantic, not always raw brightness. Keep `hot`/`blue`/`purple` single-ramp ordering separate from `bluered` and `gray`, where hue/saturation identify high-activity centers.
- Ridge tracking should optimize longitudinally coherent corridors across the whole segment, including short no-signal gaps. Do not reintroduce first-profiles-only seeding or zero-offset fallback peaks for empty profiles.
- Multi-color detection should compare classifiers for consensus rather than simply listing independent per-color candidates. Keep semantic dual-color classifiers useful when they agree with other modes; `gray` is dual-color too, not a plain grayscale brightness ramp.
- The preview dialog is the place for ridge selection. Keep candidate labels user-readable and avoid exposing raw internal scores as the primary UI.
- Rough full-way 2-5 node selections are sketch-like input and should keep using precise-shape output automatically; short selected segments of longer ways should keep the configured mode.
- Keep the fixture regression acceptance envelope and `acceptable-limits.osm` generation tied to the same configured metric radius. Visual limit changes must be reflected in the regression oracle too.
- `acceptable-limits.osm` is a regression/visualization artifact only. Do not reuse its envelope logic inside the live alignment workflow.
- Release versioning must match the scope of change. Stay on `0.x.x` until the maintainer declares the plugin suitable for broader use. After `1.0.0`, use `1.x.x` for major functionality or architecture changes, `1.1.x`-style minor releases for smaller features and improvements, and `1.1.1`-style patch releases only for bug fixes.
