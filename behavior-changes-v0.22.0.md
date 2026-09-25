# WayHeatmapTracer 0.22.0 behavior changes

This ledger distinguishes compatibility behavior from intentional changes in the
new tracing architecture. It is part of the release contract, not a claim that
an experimental engine is calibrated for unattended use.

## Current development-build status

The ordinary workflow supports Legacy and Corridor-aware tracing. Persisted
Probabilistic B, Hybrid A+B, and Directional Image selections route to their
detached live-preview pipeline instead of the legacy profile tracker. Explicit
visible preview actions are available for A, B, Hybrid, and Directional Image;
managed preview actions are available for A and B. A guarded visible-source
Apply is available only for fixed-anchor A/B Precise Shape color-mapping routes
within its documented validation boundary. Detached component tests and these
bounded live paths do not establish full modern refitting, network reconstruction,
replay, calibration, or release-gate completion.

A preview captured by rendering the plugin-managed Strava layer cannot Apply:
JOSM rendering settings can change pixels outside the layer's revision guard.
Use direct managed tile acquisition for an applicable managed-source preview.
Generic visible-source Apply/Redo remains subject to its separate source
freshness decision and is not certified by this managed-source guard.

The modern behavior and safety boundaries below remain mandatory release
requirements. They are not claims that this intermediate build implements them.
Release requires the complete approved scope, independent review, production
replay and the mandatory external validation gates.

## Unchanged compatibility paths

- `LEGACY_V02` retains its existing sampling, peak extraction, fallback,
  tie-ordering, ranking, and simple apply behavior.
- The raw Corridor-aware (A) proposal remains independently selectable and
  available in diagnostics.
- Corridor-aware (A) remains the default after migration. Unknown or blank
  stored engine values still resolve to A.
- Wider discovery, junction reattachment, and incident-way reconstruction are
  separate permissions and remain off after upgrade.

## Required modern behavior

- Final geometry is assessed after anchor reconstruction and any authorized
  refit or cleanup. A cleaned label cannot bypass a dogleg, foldback, touch,
  overlap, or unsupported terminal defect.
- Complete useful routes rank ahead of short high-scoring fragments. Raw solver
  objectives are never compared across engines.
- Smoothing-enabled A, B, Hybrid, and Direction-aware candidates may use the
  explicit image-supported refitter. Cleanup Off and Reduce points only retain
  their previous coordinate semantics.
- Local cleanup can process independent eligible intervals while freezing an
  unrelated missing, protected, or ambiguous interval. Partial and skipped
  outcomes remain visible in preview.
- Probabilistic longitudinal (B), Hybrid recovery (A+B), and Direction-aware
  image search are selectable experimental engines. Their uncertainty and
  resource-limit states are shown rather than converted into normal success.
- Explicit junction reattachment rebuilds affected way node sequences and is
  reviewed/applied as one immutable multi-way edit plan. It is not the old
  bounded shared-node movement option.

## Required safety boundaries

- Modern computation consumes detached evidence and network snapshots. It does
  not retain live JOSM primitives, map views, credentials, or preferences.
- Geographic coordinates and ordered primitive occurrences are authoritative
  for stale-state and apply identity. Metric/raster coordinates are derived
  numerical views with explicit transforms and units.
- Preview confirmation binds to the complete edit-plan hash. A relevant way,
  node, relation, referrer, setting, evidence source, or permission change
  invalidates it.
- Only the transactional edit-plan command may apply a modern multi-way change.
  Failure rolls the complete affected closure back and does not add a partial
  operation to Undo/Redo.
