# B adaptive solver implementation checkpoint

Base: `55e8920f`; isolated `codex/drasov-b-adaptive-solver`. Sole source writer: B worker.
Approved contract: private Astra design and B03 count report in root `build/private-drasov-b-resource-20261106/`.

- B04: immutable retained-index views preserving dense observations/cell quadrature; explicit opt-in only.
- B05: separately named cached exact backend; old default arithmetic/work/replay path remains available.
- B06: at most one all-rival refinement with one cumulative budget; conditional approximation stays review-required.
- Required evidence: focused RED/GREEN, exact differential/exhaustive oracles, memory/cancellation failures, then ONE bounded full Drasov pipeline run with native work/final quality.
- B04/B05 view and cached backend: 18 focused tests passed, including 15 unchanged exact k-best/oracle tests.
- B06 ordinary frozen-request pipeline/refinement: 21 focused tests passed; additional cancellation, memory,
  disconnected/reversed physical geometry, 0-refinement and second-pass budget edge cases passed in the next run.
- Intended behavioral RED evidence: full-view stub failed retention, old backend failed new-policy identity;
  refinement stub failed 1-pass requirement; old pipeline failed frozen adaptive selection; old receipt codec
  failed the new named receipt. An earlier missing-API compile and an overfull synthetic fixture are not RED evidence.
- Focused final GREEN: 41 tests plus JAR build. Broader B/codec GREEN: 379 tests in 32 suites,
  zero failures/errors/skips; XML archived under root private area `b06-broad-b-codec-xml/`.
- ONE full Drasov run performed: RESOURCE_LIMIT at 128M cumulative scores during refined K-best;
  69,570ms, no final routes/quality. Algorithms stopped under the controller's explicit failure rule.
- No commits, live activation, release or publication. Source/quality review and a new design ruling remain pending.
- No Legacy, A sampler/engine, command, release or private-upload changes authorized here.

Ruling: retain original cell objects and select original dense observation rows; do not re-normalize a sparse observation mixture. Adjacent primitive tables are permitted; no cubic cache.

Ruling: the adaptive policy is selected only by two derived, known source-domain parameter hashes (managed and
visible). The ordinary engine and strict replay decode them from frozen requests; unknown hashes retain only
the preexisting exact compatibility route. An independent captured managed RC15 hash caught the initial
visible-only hash mismatch before any private engine run; source-domain identity is now preserved explicitly.

Ruling: refinement expands around every coarse raw alternative and every coarse state in the retained graph's
95-percent credible support (ties all retained), never only the winner. One pass maximum; original 32/8 limits
and one cumulative pair/score budget remain. Output stays conditional/review-required after either pass.

Parent ruling: deterministic versioned original-index hash + dense/retained/refinement native counts suffices
for RC provenance when strict replay reconstructs it; full indices are available through an explicitly scoped
private/test observer. Dense owners stay live through both passes and candidate materialization.

## Native checkpoint

Executed local JAR SHA-256: `a48c95b2bb1985fdca2ff7721debe08799efc87608851683a253650666d3c5a0`.
Original frozen hash `f6af8e102b73d1dd3180f72db9c5d0681b0eb63ccf1e9cfe5cb817719eb339aa`;
derived adaptive frozen hash `9da233879bb1d7daa99e07fae631208b9c801fd06eb48d3c7931b578b3aa7217`.

- Initial 7,256 states: FB 8,685,586 + K-best 69,004,349 = 77,689,935 scores.
- One refinement to 8,784 states (median 25, max 73): FB 24,981,748 + K-best 25,328,317 = 50,310,065.
- Cumulative FB 33,667,334 + K-best 94,332,666 = 128,000,000; one refused uncomputed score.
- Original 300 profiles, ±25m, dense unaries/quadrature and 32/8 limits retained. No K/cap/radius changes.
- Native progress survives interruption: targeted RED reported 0 after 1,024 actual scores; GREEN reports
  1,024 and native adaptive cumulative progress retains completed-first plus available partial-second work.

The native helper's process exit 1 was a post-computation attestation omission. The original capture and exit
are preserved. Metadata-only receipt repair validated the saved actual work and left every original artifact
hash unchanged; no second solver run. Exact source snapshot, counters, index CSV/provenance hash and report
are in root `build/private-drasov-b-resource-20261106/first-production-run/`.

The measured next question is avoiding full initial K32 before an already-needed refinement. This is a
proposal for explicit expert review only, not a sufficient-speedup claim or permission to lower final K.
Generic `inference.*` success-only counters still refer to the first completed pass; top-level execution work
and adaptive first/second totals are current/cumulative. First-pass physical allocation totals are unavailable.
Resolve those diagnostic distinctions before live qualification. No strict engine replay or clean benchmark,
successful dense-control comparison, final-geometry approval, Apply/Undo/device/CI/release proof is claimed.
