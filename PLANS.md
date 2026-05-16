# Implementation Backlog

This file preserves the post-0.11 planning backlog so items can be implemented gradually.

## Remaining Detector And Alignment Work

- Further palette calibration:
  - Recalibrate `blue` with focused real examples.
  - Continue tuning `gray`; it is dual-color/magenta-aware, but gray detectors can still jump in some cases.
  - Reassess `purple` after the current recalibration on fresh subjective examples.
- User-rated detector optimization loop:
  - Ratings and detector metrics are exported.
  - Add automation that consumes many rated bundles and proposes calibrated detector weights/priors.
- More advanced longitudinal reasoning:
  - Current longitudinal stability and topology cleanup are not a full trace identity model.
  - Add explicit reasoning that a ridge remains the same physical trace over distance when parallel traces compete.
- Adaptive smoothing and blur experiments:
  - Current filtering uses signal-gated B3/B5 one-dimensional profile filters.
  - Evaluate multi-blur or multi-filter ensembles where stable maxima across levels are trusted.
  - Consider edge-preserving or anisotropic filters if they outperform current signal-gated filters on calibration bundles.
- Visible-layer all-color aggregation:
  - Managed source tiles can aggregate all base color schemes.
  - Manual visible-layer fallback has only the selected rendered source; true all-color aggregation there would require managed source access or another way to obtain all color rasters.
- Broader topology safety:
  - Current cleanup prunes endpoint clusters and self-intersection loops in precise previews.
  - Add explicit checks against crossing connected or nearby existing OSM ways before junction points.
- Rough sketch workflow improvements:
  - Rough 2-5 node selections are recognized in metadata.
  - Add an explicit wide-search rough sketch workflow/setting instead of silently widening search.
- Debug and analysis automation:
  - Add batch analysis that compares before/after geometry against heatmap intensity fields and ranks likely failure causes:
    `off-center`, `jumped-trace`, `junction-kink`, and `unnecessary-undulation`.
- Optimizing precisely shaped ways:
  - Support cases where a 5-20 Hz recording has a very precise shape but is offset globally or locally.
  - Preserve recorded shape detail while optimizing position against heatmap evidence.
- Missing-way discovery:
  - Find places with strong or reasonably strong heatmap signal where there is no `highway=*` way.
  - The UI should pan to such locations and optionally show a dotted rectangle and dotted candidate line for missing ways to consider.
