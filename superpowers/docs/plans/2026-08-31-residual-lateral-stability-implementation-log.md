# Residual Lateral Stability Implementation Log

## Scope

Implemented the patch-safe path from the residual lateral stability plan against
commit `96e21297a90c5b75b07e3b4e21e04af04c8efd59` (`0.19.2`). Changes affect only
explicitly enabled corridor-aware geometry cleanup. The maintainer explicitly
authorized publication as `0.19.3` on 2026-08-31; the release procedure is recorded
separately from the implementation evidence below.

## Decisions

- Replaced raw local-center reversal counting with a robust affine trend over a
  contiguous direct-evidence window, minimum physical span, source-pixel residual
  amplitude, and typed no-intervention reasons.
- Kept short-wave attribution separate from trend authorization. Scale conflict,
  parent merge, insufficient evidence, and tiny residuals cannot authorize motion.
- Added a Huber absolute turn component with calibrated weight `0.20`, deadband
  `0.02 rad/source-px`, scale `0.05 rad/source-px`, and knee `1.0` inside the same
  exact pair-state DP. No state, transition, tie-order, or second-pass change.
- Kept plateau targeting unchanged because the conditional evidence gate was not met.
- Kept all cleanup presets/defaults and ranking semantics unchanged.
- Bumped debug bundle schema to format 10 and preserved formats 1-9 as readable.
- Replaced duplicate analyzer ZIP traversal with a bounded shared reader and added
  a redacted manifest/split-lock command for explicitly scoped private archives.

## Safety

- `LEGACY_V02` remains isolated.
- Cleanup-disabled ripple and absolute-turn costs are exact zero; existing optimizer
  offsets, checksum, state counts, transition counts, and profile-cost counts remain
  covered by regression tests.
- Boundary-censored windows report factual direct coverage and cannot authorize
  intervention. One endpoint-ownership gate attenuates every cleanup-only tube,
  continuity, relative-curvature, and absolute-turn term. Direct provenance, physical span,
  source-pixel scale, anchors, final topology, immutable assignments, raw fallback,
  preview/apply identity, and undo/redo retain their existing gates.
- Private archives were opened read-only from one non-symlink snapshot and never
  extracted or committed. The strict scan found zero quarantines among 14 scoped archives;
  quarantined inputs are excluded from split assignment. Existing untracked user files
  were not modified.

## Calibration Limits

The old private corpus contains outcome and profile evidence but no accepted cleaned
candidate suitable for an honest old/new holdout claim. A fresh manual JOSM run and
format-10 debug export are required before release. The implementation therefore
remains an explicit calibration limitation for the maintainer-authorized `0.19.3`
release and must not be presented as completed replay evidence.

The existing `longitudinalStability` ranking field intentionally remains unchanged:
the approved patch contract requires unchanged ranking semantics. Format-10 exports
the absolute-turn objective separately for calibration. Promoting it into ranking is
a possible `0.20.x` behavior change and is not hidden inside this patch candidate.

## Automated Verification

- `sh gradlew --stop && sh gradlew clean test build javadoc`: passed, 317 tests,
  zero failures/errors, and no Javadoc warnings.
- `python3 -m pytest -q scripts/tests`: passed, 29 tests and 5 subtests.
- `python3 -m py_compile scripts/*.py scripts/wayheatmap_analysis/*.py`: passed.
- `python3 scripts/validate-sampling-scale.py --pretty`: passed 1,945 cases;
  maximum round-trip error `0.03146144458831657 m`.
- Strict corpus manifest: 14 validated, zero quarantined, split 9/3/2,
  manifest SHA-256 `7a69647240e06f06ca6d2567f9e28e2a132725955a713d097063c4953c2d20fb`.
- `git diff --check`: passed.
- The pre-release implementation build used version `0.19.2`; the final release
  build and digest are recorded in the `v0.19.3` release record.

Independent Java and Python reviews were run twice. All important findings were
addressed. The one deliberately unresolved behavioral suggestion is incorporating
the new absolute-turn metric into ranking; that conflicts with this patch plan's
unchanged-ranking gate and is deferred to an explicitly versioned behavior change.
