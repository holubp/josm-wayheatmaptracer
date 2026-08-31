# Implementation Backlog

## Tile reliability hardening (v0.20.1 candidate)

The checkpointed execution plan is
[`superpowers/docs/plans/2026-08-31-v0.20.1-tile-reliability-execution.md`](superpowers/docs/plans/2026-08-31-v0.20.1-tile-reliability-execution.md).
It introduces one bounded privacy-safe coordinator for plugin-direct managed
tile requests, decouples diagnostic aggregate visibility from alignment source
planning, makes cache generations consistent, adds an asynchronous selected-source
check, and adds format-11 safe acquisition diagnostics. The 0.20.x candidate
retains strict whole-slide failure for an explicitly requested incomplete
all-color detector. Graceful selected-source continuation and background
alignment remain separately gated minor-version work.

## Corridor-aware default promotion (v0.20.0 release authorized)

The checkpointed promotion plan is
[`superpowers/docs/plans/2026-08-31-v0.20.0-corridor-aware-default-promotion.md`](superpowers/docs/plans/2026-08-31-v0.20.0-corridor-aware-default-promotion.md).
It promotes the regression-covered corridor-aware tracker to the public default,
preserves explicit legacy selection, and leaves geometry cleanup disabled by
default. It does not activate the deferred plateau experiment or change palettes,
sampling, detector ordering, or topology gates.

## Residual lateral stability (released in v0.19.3)

The checkpointable execution plan is
[`superpowers/docs/plans/2026-08-31-residual-lateral-stability-execution.md`](superpowers/docs/plans/2026-08-31-residual-lateral-stability-execution.md).
The patch-safe path strengthens only explicitly enabled geometry cleanup: robust
trend-residual ripple attribution, a gated absolute short-wave turn cost in the
existing exact DP, format-10 diagnostics, and bounded private-archive analysis.
Plateau-target changes were not enabled because their evidence gate was not met.
The implementation was released as `v0.19.3`; its raw/default behavior remained
unchanged so that default promotion could be reviewed separately for `v0.20.0`.

This file preserves the post-0.11 planning backlog so items can be implemented gradually.

## v0.19.0 Geometry Cleanup Status

The checkpointable implementation plan is
[`superpowers/docs/plans/2026-08-04-230815-01-plan-v0.19.0-geometry-cleanup.md`](superpowers/docs/plans/2026-08-04-230815-01-plan-v0.19.0-geometry-cleanup.md).

- CP1-9: complete, including configuration/migration, the dedicated settings dialog, retained cleanup evidence, ripple regularization, constrained smoothing and reduction, final-preview integration, and additive format-9 diagnostics.
- CP10: complete; calibration, physical-scale controls, performance evidence, documentation, Javadoc, Java tests, and Python tests are green.
- CP11: complete; independent review blockers were fixed and `v0.19.0` was published from commit `ee0edb0` with a remotely verified jar digest.

The cleanup settings are intentionally future-slide-only and disabled by default. The raw candidate remains available whenever optional cleanup is skipped or rejected. This roadmap is complete because the implementation, diagnostics, documentation, test, review, and release gates passed.

## Remaining Detector And Alignment Work

- Further palette calibration:
  - Recalibrate `blue` with focused real examples.
  - Continue tuning `gray`; it is dual-color/magenta-aware, but gray detectors can still jump in some cases.
  - Reassess `purple` after the current recalibration on fresh subjective examples.
- User-rated detector optimization loop:
  - Ratings and detector metrics are exported.
  - Add automation that consumes many rated bundles and proposes calibrated detector weights/priors.
- More advanced longitudinal reasoning:
  - Implemented for 0.18.0: complementary intermittent tracks can form an all-pairs-compatible sparse parent with direct-union coverage and bounded interpolation, while elementary children and persistent parallel interpretations remain available.
  - Implemented for 0.18.0: weak unsupported motion uses an additional robust physical-distance reference, while sustained low-intensity turns and switchbacks retain local geometry.
  - Reviewed and hardened for 0.18.1: predicted sparse geometry no longer counts as observed signal or turn support; interpolation-only parent windows remain physically bounded; persistent parallel separation requires independent longitudinal proof.
  - Endpoint approaches derive their entry direction from a reliable interior anchor on the selected branch; sparse parents require direct, non-multimodal evidence.
  - Continue calibration on rated real bundles before considering it a replacement for the legacy tracker.
- Adaptive smoothing and blur experiments:
  - Current filtering uses signal-gated B3/B5 one-dimensional profile filters.
  - Evaluate multi-blur or multi-filter ensembles where stable maxima across levels are trusted.
  - Consider edge-preserving or anisotropic filters if they outperform current signal-gated filters on calibration bundles.
- Visible-layer all-color aggregation:
  - Managed source tiles can aggregate all base color schemes and can show/export a diagnostic aggregate intensity visualization layer.
  - Manual visible-layer fallback has only the selected rendered source; true all-color aggregation there would require managed source access or another way to obtain all color rasters.
- Broader topology safety:
  - Current cleanup prunes endpoint clusters and self-intersection loops in precise previews, while corridor-aware physical gates reject foldbacks, unsupported short excursions, unsafe terminal approaches, vertex contacts, and collinear overlaps.
  - Connected-way segments adjacent to a shared junction are checked for crossings or remote contacts outside the junction tolerance.
  - Add explicit checks against crossing other nearby existing OSM ways that are not connected at the selected junction.
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
