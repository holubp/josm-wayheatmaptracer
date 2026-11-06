# Public Legacy normal-policy performance harness

`legacyNormalPerformance` runs one fresh public synthetic fork of the retained
Legacy discovery path. It uses no private capture, live OSM, network access,
preview/run/refinement stage, or B execution. Each output directory is new and
contains `observations.csv` with every native alternative and `summary.json`
with class, fixture, and result hashes plus raw paired discovery times.

Run exactly three independent JVM forks, using distinct output directories:

```sh
for fork in 0 1 2; do
  sh gradlew legacyNormalPerformance \
    -PlegacyNormalPerformanceFork="$fork" \
    -PlegacyNormalPerformanceOutput="build/reports/legacy-normal-performance/fork-$fork" \
    --console=plain
done
```

Gate all three retained summaries with:

```sh
python3 scripts/legacy-normal-performance-gate.py \
  --fork-dir build/reports/legacy-normal-performance/fork-0 \
  --fork-dir build/reports/legacy-normal-performance/fork-1 \
  --fork-dir build/reports/legacy-normal-performance/fork-2 \
  --report build/reports/legacy-normal-performance/gate-report.json
```

The gate validates fork indices 0–2, all eight scene/source groups, exactly 20
matched baseline/candidate pairs per group and fork, positive finite raw times,
and matching fixture and class hashes. It recomputes medians and nearest-rank
p95 values from raw pairs. A group is blocked when either the median overhead
exceeds `max(5 ms, 10% of median baseline)` or p95 overhead exceeds
`max(10 ms, 20% of p95 baseline)` in at least two forks. One breached fork is
inconclusive and exits nonzero. Only zero breaches pass. CI uploads all raw fork
outputs and the gate report even when a run or gate fails.

The harness also requires stable result hashes and profile counts by scene,
source kind, and policy across repeated pairs and all forks, full support for
each accepted alternative, unchanged exact fixed endpoints, and no new safety
warnings. Straight/parallel outputs must match by native identity; the Gaussian
candidate must stay within 3 m of its analytic ridge without backward travel;
parallel results must retain both distinct strands within factual source-pixel
uncertainty. The hairpin control checks policy nonregression against the immediate
baseline within source-pixel uncertainty. Its managed immediate baseline misses
the analytic apex by about 116.5 m, so it does not establish true-apex fidelity.
If a fork fails after earlier discoveries, its CSV and an explicitly failed
summary retain the completed observations; the gate rejects that partial fork.

These synthetic results are a public performance regression check. They do not
establish quality on captured data or replace the production/ordinary-action
benchmark and cancellation gates.
