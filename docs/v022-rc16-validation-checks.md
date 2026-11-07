# RC16 validation check mapping

## Required public checks

The normal `build.yml` push, tag, and pull-request workflow keeps these checks
unconditional and failing on their own errors:

| Workflow job | Evidence it gates |
| --- | --- |
| `public-validation` | Public build and test validation with the pinned JOSM 19555 host. |
| `public-wide-compact-retention` | Six frozen public scenes through actual production dense-reference and compact-candidate runs, including native identity, authority, safety, branch, geometry, and raw/final distance evidence. |
| `public-gui-smoke` | Both public GUI smoke variants; still needs `public-validation`. |
| `legacy-normal-performance` | Three fresh Legacy normal-policy forks and their gate report. |
| `legacy-tracker-performance` | Three fresh Legacy tracker-policy forks and their gate report. |

The performance-retention job runs `v022WideCompactRetention` and uploads
`build/reports/wide-compact-retention/` with the unchanged public scene
manifest. Supported-bend retention must be at least 90% of the dense B
reference on the same physical interval and baseline. Candidate native,
identity, geometry, branch, and safety checks remain mandatory. The narrowly
reviewed diffuse unsuitable-reference disposition preserves its measured
distance failure and does not excuse missing evidence or candidate failures.

Relative retention and absolute analytic quality are separate outcomes. The
retention report keeps the existing 3 m target and 2.7 m minimum absolute debt
visible for both reference and candidate. Passing the retention job does not
resolve that debt or qualify a release. The job has no condition or
`continue-on-error` that can mask failed production execution or integrity
checks.

## Separate absolute-quality analysis

`wide-absolute-quality-analysis.yml` runs the unchanged strict
`v022WideCompactQuality` task manually or on exactly
`codex/v022-absolute-quality-analysis-20261107`. It uploads the actual
`build/reports/wide-compact-quality/` report and frozen manifest even when the
strict task fails. Its failure remains model-quality debt; it is not renamed or
converted into a performance-retention pass. The workflow uses the pinned
JOSM host, public fixtures, read-only contents permission, and no
`continue-on-error`.

The existing absolute task, its strict exit behavior, assertions, thresholds,
and report path remain unchanged. The workflow split changes only which named
check gates the performance-retention work.

## Remaining qualification

These public workflow checks do not establish release readiness. Private
intense-control qualification, repeated production-observer runs and timing,
cancellation checks, and the remaining integration and release gates are still
pending. No private fixtures, numerical thresholds, model behavior, or
publication settings are changed here.
