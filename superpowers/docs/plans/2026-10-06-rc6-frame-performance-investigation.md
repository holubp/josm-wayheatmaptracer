# RC6 captured frame and inference investigation

Source inspected: `6288aaf`. Existing menu-label/shortcut changes remain separate.
This is verified diagnostic evidence, not implementation or release approval.

## Captures

Validated with production `Format15ArchiveReader` (member checksums) and
`FrozenReplayCodec.decode`; no source coordinates or credentials are recorded here.

| Archive suffix | Archive SHA-256 | Selected nodes | Selected outside certified frame | Context nodes outside frame | Context ways with outside nodes | Junction policy |
| --- | --- | --- | --- | --- | --- | --- |
| 1791322700867 | 89accd3143bc5b3a3997fced962832f683e01b6a3dfb191fbd627db5c4558a2c | 109 | 0 | 99 | 1 | FIXED |
| 1791322862816 | 7ee507411844bc5527b13d6c76e5aee4f2237c036667b10c40b2a6609810b0cd | 86 | 0 | 93 | 1 | FIXED |

Neither complete selected way has any node outside the certified frame. Both
archives support scalar inference and final geometry but not full edit-plan replay.

## Apply blocker

`ModernSingleWayEditPlanAdapter.finalTopologyFindings` converts both endpoints of
every captured way segment through `LocalMetricFrame.toMetric` before checking
whether a segment changed or whether two segments need comparison. Each snapshot
contains a contextual way with coordinates outside the declared frame. That path
throws the exact reported `Coordinate lies outside the certified metric-frame domain`.
These FIXED-policy captures are distinct from the earlier LEGACY_BOUNDED_MOVE
partial-selection edit-region failures. Do not attribute this failure to the
quality-review warnings or to selected endpoints moving.

Required correction: support collision-context geometry without weakening the
certificate or omitting topology checks. Any exclusion must prove a segment cannot
intersect the affected region; outside endpoints alone do not prove exclusion.
Long contextual segments crossing the edited corridor must still be checked.
Retain a synthetic regression with a nearby changed way and a contextual way
whose far vertices lie outside the selected-source frame, plus a crossing case.

## Performance evidence

| Archive suffix | Forward/backward ms | K-best ms | Pipeline inference ms | Finalization ms | Evaluated transitions | Raw paths | Distinct paths |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1791322700867 | 6783 | 71782 | 78982 | 5197 | 102361518 | 32 | 1 |
| 1791322862816 | 15568 | 81122 | 97097 | 7902 | 110007304 | 32 | 3 |

Both have effective raw limit 32, distinct limit 8, raw enumeration capped, and
requested diversity not reached. `SEARCH_TRUNCATED` is therefore truthful. It is
not evidence of an untraced portion of the selected way.

`ProbabilisticInference.enumerateKBest` visits every retained prefix for every
admitted predecessor transition, allocating an extended arena record before
discarding records outside TopK. The 32-prefix expansion is the primary measured
bottleneck; finalization is secondary. A possible exact optimization is ordered
stream merging per pair-state, but it requires proof of identical energy arithmetic,
lexical ties, retained paths, completeness counts, cancellation, memory accounting,
and work-budget semantics. Do not reduce the cap or relabel truncated results as
complete to obtain a speedup.

## Remaining work

1. Fix certified handling of contextual topology and retain crossing regressions.
2. Resolve earlier partial-selection boundary/edit-region contract separately.
3. Design and verify exact K-best acceleration against these frozen captures.
4. Review consequential changes, run focused/neighbouring suites and production
   replay, then build a new testable RC under the existing release gates.

Private input archives remain local and must not be committed or published.
