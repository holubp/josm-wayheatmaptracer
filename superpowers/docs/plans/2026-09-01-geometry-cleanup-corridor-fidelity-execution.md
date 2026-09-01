# v0.20.2 Geometry Cleanup and Corridor Fidelity Execution

**Source plan:** `$HOME/downloads/josm-wayheatmaptracer-post-tile-geometry-cleanup-and-corridor-fidelity-plan.md`
**Execution baseline:** `aa4b31c841688e0895be321996bfed3d7ea9f3ac` / version `0.20.1`
**Public ancestor:** `v0.20.0` / `46b2fa1d7a89b93505a520b53f2fc2177ed85a63`
**Worktree:** isolated temporary implementation worktree; local path intentionally omitted
**Branch:** `codex/geometry-fidelity-0201`
**Release target:** `0.20.2`, explicitly selected by the maintainer after execution began
**Status:** implementation, verification, and independent review complete; release pending

## Scope Contract

Implement the source plan orthogonally to the already verified `0.20.1` tile
reliability work. Geometry code must not alter networking, tile cache, retry,
credential, aggregate-source, or successful raster-input behavior. Preserve
`LEGACY_V02` exactly and preserve cleanup-disabled corridor-aware output unless
the CP11 evidence gate is satisfied. Private archives and OSM references remain
read-only under the original clone and never enter Git.

## Baseline Evidence

- `v0.20.0` is an ancestor of the execution baseline.
- Tile coordinator, source-plan, health-probe, and format-11 tests are present.
- Java: OpenJDK 21.0.12; project target remains Java 17.
- Gradle: 9.4.1; JOSM main version: 19555.
- `sh gradlew test build javadoc`: green after restoring ignored fixture archives.
- Java tests: 344 total, 0 failures/errors, 1 skipped.
- Python: 29 tests plus 5 subtests passed.
- Sampling scale: 1,945 cases; maximum round-trip error 0.03146144458831657 m.
- Original checkout differences: three pre-existing untracked diagnostic files;
  no tracked modifications.
- Private candidate inventory was re-read only through bounded archive/XML
  tooling. The ignored manifest found 28 archives and four OSM references;
  pairing remained ambiguous, so no private-example tuning was performed.

## Checkpoint Ledger

| Checkpoint | Status | Evidence / verification | Rollback / notes |
| --- | --- | --- | --- |
| CP0 integrated baseline and red reproductions | complete | baseline above; activation, preview, context, and simplifier defects reproduced in focused tests | rollback `aa4b31c` |
| CP1 private corpus, secure readers, pairing | complete with calibration stop | bounded nested-ZIP/XML readers, redacted manifest, deterministic pairing tests; private pairing was ambiguous | ignored analysis only; no private tuning or tracked data |
| CP2 failure attribution | complete | activation, preview selection, global evidence rejection, and scale-entangled shape classification isolated independently | no geometry changes |
| CP3 effective cleanup choice/schema v2 | complete | one effective choice; schema-1 disabled and malformed preferences migrate to Off | schema tests |
| CP4 preview preference and observability | complete | changed applicable cleaned/partial sibling initially selected without ranking changes | action tests |
| CP5 interval-local cleanup context | complete | unsafe local evidence freezes/splits intervals; global structural defects still fail closed | partition/service tests |
| CP6 offline multiscale shape analysis | complete | coordinate-free 6/10/20 m, three-pass robust analyzer and invariance/gap tests | no private content emitted |
| CP7 production shape evidence | complete | shared Java wrinkle/bend/ambiguity evaluator with source-scale and supported-turn regressions | format-12 evidence |
| CP8 cleanup-only exact-DP integration | complete | compatibility adapter; disabled and zero-intervention costs remain exact zero | optimizer/ripple tests |
| CP9 smoother gating/partial acceptance | complete | interval-local backtracking, normal-only bounded moves, bend/ambiguity freezes | at most one sibling |
| CP10 shape-aware simplification | complete | frozen-span splitting and supported bend/ambiguity retention preserve existing safety gates | simplifier tests |
| CP11 conditional cleanup-off correction | skipped, not justified | no evidence justified changing cleanup-disabled optimizer behavior | baseline preserved exactly |
| CP12 frozen calibration/holdout | complete for public fixtures | synthetic and repository fixtures validate classifier and cleanup behavior; private pairing ambiguity stopped private tuning | no guessed pairing |
| CP13 diagnostics/UI/docs | complete | format 12, local-shape CSV, initial/base/current preview identities, README/DEVELOPMENT/AGENTS | old bundles readable |
| CP14 verification/review/release | verified; release pending | clean Java build/Javadoc: 369 passed, 1 skipped; Python: 50 passed + 5 subtests; sampling: 1,945 cases; independent re-review found no Critical/High defect | release target `v0.20.2` |

## Final Review Notes

- Review found and regression-tested cleanup leakage into Move Existing Nodes;
  detection and final-preview construction now receive disabled cleanup outside
  Corridor Aware + Precise Shape.
- Protected anchors may borrow only immediately adjacent profile evidence.
- Cleaned candidates are rejected before preview when simplification would make
  their preserved topology target inconsistent with the apply command.
- A generated command-consistent cleaned sibling now has an integrated
  Apply/Undo/Redo identity regression, including a connected way and restored
  dropped nodes.
- Visible and managed cleanup-configuration plumbing was confirmed by
  independent inspection and the shared effective-config regression. Java and
  Python classifier implementations have independent synthetic coverage but no
  shared canonical parity fixture; private calibration remained stopped at the
  ambiguous pairing gate and did not influence thresholds.

## Acceptance

- Named cleanup choices enable their intended future operation without enabling
  cleanup for users whose old effective mode was None.
- A valid changed cleaned sibling is initially previewed while its base sibling
  remains selectable and detector ordering is unchanged.
- Local evidence defects freeze only local intervals; protected/frozen points
  remain exact and final topology/assignment checks remain fail-closed.
- One deterministic multiscale shape sequence distinguishes unsupported wrinkles
  from supported bends and gates every cleanup-only operation.
- Cleanup-only exact-DP costs remain non-negative and exactly zero when disabled;
  one bounded exact second-order optimizer remains.
- Smoothing is normal-only and wrinkle-authorized; simplification preserves
  supported bends, apexes, anchors, heatmap fit, containment, and topology.
- Tile reliability remains green; diagnostics remain redacted and backward
  compatible; no private content or absolute paths are tracked.
- Full Java/Python/Javadoc/build/sampling checks, independent review, and a
  reproducible handoff pass before publishing `v0.20.2`.

## Stop Conditions

Stop rather than weakening safety if private pairing is ambiguous, evidence
cannot separate bends from wrinkles, cleanup-off output would change without
CP11 proof, topology or protected-node gates would need relaxation, tile behavior
changes, performance exceeds the plan's hard thresholds, or private/credential
content reaches tracked output.
