# AGENTS

Repository-specific guardrails for future changes:

- Treat JOSM coordinate spaces explicitly. Downloaded-area checks use `Bounds` with geographic coordinates, while heatmap sampling and ridge tracking operate in oversampled screen space before projecting back to `EastNorth`.
- Do not reintroduce uniform point redistribution after simplification in precise mode. Straight sections should be allowed to collapse more aggressively than curves.
- When precise mode drops interior nodes, clean up only nodes that are both untagged and unreferenced. Shared or tagged nodes must survive.
- Keep verbose/debug logging useful for remote diagnosis. For alignment bugs, log selected segment ids, sampled bands, chosen candidate offsets, and applied node moves.
- Palette changes must come with regression coverage in `HeatmapFixtureArchiveTest` or `RidgeTrackerTest`, especially for `blue` and `gray`, which have been the easiest modes to mis-rank.
- Keep the fixture regression acceptance envelope and `acceptable-limits.osm` generation tied to the same configured metric radius. Visual limit changes must be reflected in the regression oracle too.
- `acceptable-limits.osm` is a regression/visualization artifact only. Do not reuse its envelope logic inside the live alignment workflow.
