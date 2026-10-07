# Engine B usable-release design basis

Status: approved by maintainer on 2026-10-07 after independent Astra review.
Baseline: `6288aaf` / `0.22.0-rc.6`.

## Authority

Continue the original `josm-wayheatmaptracer-v0.22.0-implementation-plan.md` and
`2026-09-22-v022-release-hardening.md`, with the subsequent approved B-first
release focus and fixed-junction safe-interval policy. Original requirements
remain unless an explicit approved change modifies them. Old ledger statuses
are historical evidence and must be reconciled with current source.

The confirmed failure evidence is in
`2026-10-06-rc6-frame-performance-investigation.md`. The supplied RC6 archives
remain private inputs, not public fixtures. This design adds no new engine,
detector scoring policy, junction optimization capability, or geometry smoothing.

## Required user outcome

A normal user can select a way or safe subrange, run ordinary managed B alignment,
inspect the exact final preview, confirm review-level uncertainty, and Apply a
safe immutable plan. Undo restores the original dataset and Redo replays stored
coordinates. Internal coordinate/authority failures must not block a safe slide.
Genuine safety failures remain blocked with a concrete recovery instruction.

Default/fixed junctions and tagged/relation-dependent junction surroundings
remain untouched. Multiple frozen junction islands must not disable independent
safe intervals. Manual optimization instructions apply only to the affected
junction; no instruction may imply that every REVIEW finding requires editing OSM.

Both geometry modes are required: Precise Shape may add shape nodes; Move Existing
Nodes preserves the complete way node sequence and node count. A dense-enough
existing-node way must successfully Apply in Move mode. If sparse nodes cannot
represent the supported curve safely, the existing PRECISE_SHAPE_REQUIRED block
remains and the UI offers an explicit one-shot rerun in Precise Shape. An already
aligned result is a successful no-op, without a command or Undo entry.

## Proposed implementation decisions

1. For FIXED-policy topology validation, clip unchanged contextual segments to
   the geographic rectangle of the existing certificate before metric projection.
   Use the frame's longitude branch. A proved-empty intersection can be omitted;
   an outside-endpoint test alone cannot omit a segment. Changed segments must
   remain fully certified and authorized. Retain original segment index/identity
   and distinguish artificial clipping endpoints from real OSM endpoints.
2. Under LEGACY_BOUNDED_MOVE, keep a selected boundary fixed when it is an interior
   occurrence of the selected way. Moving that node changes an unselected adjacent
   segment; this plan chooses preservation over expanding edit authority. Actual
   full-way endpoints retain existing opt-in bounded movement. Explicit REATTACH
   remains governed by its separate reviewed junction contract.
3. Optimize bounded K-best selection without changing its 32-raw/8-distinct
   limits, numerical policy, ordering, objective, or completeness semantics.
   Compare lightweight extension descriptors before allocating ancestry records.
   Prefix ordering must be re-established after binary64 addition: unequal prefix
   energies can round to equal extension energies and reverse lexical priority.
4. Preserve existing logical transition-budget behavior and export physical
   descriptor/ancestry work separately. Do not claim fewer mathematical transitions
   merely because fewer discarded ancestry records are allocated.
5. A capped search with unresolved alternatives remains review-required.
   Exact speed optimization does not close strict completeness. A separate reviewed
   proof/design gate must identify what the existing strict release predicate can
   certify. No gate waiver, larger unbounded search, or false completeness claim is
   authorized. If the proof cannot be completed, deliver the safe test RC and record
   the remaining final-release blocker.
6. Group repeated UI findings by code/severity and count, preserving detailed
   locations and reasons in diagnostics. Show actual plan availability separately
   from review confirmation and image quality. Preserve wrapping and bounded size.
7. Support current-source alternative mappings and complete managed five-palette
   scalar aggregation through existing tile coordination and native semantic
   conversion. All-color detection requires hot, blue, bluered, purple, gray in
   one frame; no subset or completed-geometry voting is permitted. Direct scalar
   modes retain their existing behavior and ignore color-only options.
8. Normalize B's effective invocation before capture: unsupported legacy cleanup
   and simplification choices cannot create a successful preview with permanently
   unavailable Apply. Keep saved preferences unchanged, disclose suppressed choices,
   and carry requested versus effective settings separately through stale checks.
   Tests cover ordinary action in both modes and each one-shot action invoked
   from both persisted mode settings; its effective mode follows the action.
9. Visible-layer source freshness requires a reviewed production receipt/validator
   design. Current generic visible layers have no epoch and always refuse Redo;
   rendered managed layers refuse first Apply, and partitioned visible previews
   refuse Apply. These are unresolved required source capabilities, not acceptable
   completion evidence. Implement and test a trustworthy supported receipt before
   declaring visible parity. The first usable RC may demonstrate the ordinary
   managed B workflow in both modes while this final-release gate remains explicit.

## Acceptance and exclusions

All newly introduced interfaces and tests must be specified in the execution plan.
Safety regressions include far contextual geometry, long crossings with outside
endpoints, partial-range preservation, floating-point lexical ties, missing palette,
stale review/Apply/Redo, and mixed safe/frozen intervals.

The supplied captures must reproduce production inference/final output at their
declared level; do not claim FULL_EDIT_PLAN replay for archives that lack it.
Use host integration tests for Apply/Undo/Redo and preserve truthful capability
declarations. Fourteen-case corpus membership/annotations and every remaining
original release gate require explicit evidence before stable publication.

Deferred by maintainer: shared 256 MiB whole-attempt memory gate and METHODS.md to
0.22.1. Fork attraction/local branch-selection tuning remains a 0.22.x improvement.
Do not restart A/Image/Hybrid development; required existing-engine regression and
original-scope dispositions remain part of release reconciliation.
