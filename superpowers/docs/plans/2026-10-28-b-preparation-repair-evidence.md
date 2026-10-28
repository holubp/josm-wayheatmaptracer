# Shared-model preparation repair

Exploratory quality-only continuation of the approved quality-first experiment.
Base: `38588484e74e101c6fe602d13bd6eb05f65e1339`.

The baseline returned eight paths. The original128 candidate attempts returned
none:32 preparation timeouts,48 unresolved point responses,16 positive weighted
underflow exceptions,32 uncertified adaptive edges. These are preparation
failures, not measured tracing-quality failures. No candidate is selected.

## Corrections and regression evidence

- Positive finite products rounding to zero use outward adjacent bounds; the
  upper bound retains the positive tail. Nonfinite products still fail.
  Product-bounds regression was RED, then2 tests GREEN.
- Closed-cap point queries retain the original nominal branch and enclose both
  possible reference branches, including zero. The complete49-context aggregate
  F/C/D retains bitwise nominal parity; invalid support remains unavailable.
  Point-certificate regression was RED, then8 tests GREEN.
- Adjacent-cell interval evaluation supports genuine tiny positive source
  samples and near-face coordinates. Its monotone outward arithmetic does not
  require the former normal-relative-error thresholds. Validity, region,
  cell ownership and finite bounds remain required. Exact face regression RED
  then GREEN; an earlier non-face fixture passed before repair and is not RED
  evidence.
- A later polynomial may recover winner status from RANGE only when its P and
  E lower bounds dominate all prior upper bounds. Unknown support stays sticky.
  Scalar-reducer regression RED then GREEN.
- Exact self-context dominance proves C=F even if the strongest component
  changes along the chord. Every stream is still visited. Self must have exact
  zero displacement/unit weight; every other post-weight upper bound must not
  exceed the nonnegative field lower bound in both programs. Flags reset on
  every panel, including adaptive reuse. The face regression first certified
  slowly, then failed the strengthened one-node/exact-zero assertion, then
  passed. Positive C>F analytic-integral and missing-source controls remain.
- Uncertified edges retain immutable enum/numeric failure counters after owner
  cleanup, without retaining source objects or publishing a partial integral.
  Actual masked-edge regression RED then GREEN.

Combined focused Java run:35 tests PASS in84s on Android/Java17. This includes
the11 existing MaxPanel neighbors. Python runner/metadata suites:27 PASS in
normal and optimized modes. The exact CI preflight subsequently passed40 tests
in89s, including complete tiny-consumer, solver-parity and kernel neighbors.
New test dependencies are all tracked. These checks do not establish original-case
completion, full project qualification, or release readiness.

## Bounded CI continuation

152 original-case attempts are already consumed under the208-call ceiling.
Automatic full baseline/screening jobs are disabled. The repair diagnostic
runs exactly the eight registered screening cases once at gain1/eta1, with
unchanged300s cooperative/360s external limits and no retries. It spends at
most8 more calls (160 cumulative), even when preparation fails before the
solver. This is not configuration selection or confirmation. Further full
screening needs budget reconciliation; do not launch another128-call sweep.

Workflow: `.github/workflows/b-shared-feasibility.yml`.
Single-case command: `python3 scripts/run-b-shared-feasibility.py diagnostic
<bundle> <manifest-sha256> <screening-case-id> <fresh-output-directory>`.
The usual bundle/code/input binding and post-return assessment remain active.
Only public synthetic inputs are uploaded. No release or paid runner is used.

Numerical tolerances, state space, equations, gains/etas, image evidence,
reference geometry and safety oracles are unchanged. Source-operation and
total-memory qualification are explicitly deferred by the approved exploratory
scope; older source-count proofs are not renewed by these unit tests.
