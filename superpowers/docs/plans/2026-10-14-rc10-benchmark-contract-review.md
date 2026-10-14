# RC10 approved benchmark contract: independent review closure

Scope: approved `2026-10-13-rc10-benchmark-contract-reconciliation.md`, on
HEAD `23363c85527787d6921ff102aa3d4014253d343d` plus the stable benchmark
implementation delta. Sol 6.1/high implemented; a separate Sol 6.1/high
reviewer inspected actual source, tests and retained final XML. No Astra used.
The earlier numerical/source audit remains a separate qualifying review.

The initial independent audit found two important gaps: current interval
authority/partition was not bound to fresh original-OSM capture, and the actual
SINGLE-to-INTERVAL entry point lacked integrated coverage. Both were repaired
and re-reviewed. Final verdict: no blocking findings in this contract delta.

Verified behavior:

- Strict RC6 comparison remains the default; the approved mode is explicit.
- Complete current authority and partition match factual original capture,
  excluding only run-local dataset identity; publication kind matches capture.
- An independently produced public compatibility fixture exercises the actual
  comparator entry point with factual context extension and reduced movement
  rights. This is not historical RC6 execution or timing evidence.
- Rechecksummed forged authority is refused. An extra-freeze case remains
  internally replay-consistent but is refused against factual capture.
- Native partition is deterministically recomputed and proof-checked, then
  compared with fresh partition. A redundant same-authority mutation test is
  absent; the reviewer found no resulting gap in those checks.
- Current native replay checks complete output components and execution work;
  immutable plan validation is reconstructed using the existing adapter.
- Plan/before/after identity and exact planned geometry are bound for historical
  plans when present too. Inherited-only verifier restrictions remain intact.
- Parameter pins independently match actual RC6/current managed hash recipes.
- Source/raster/validity/frame/settings/budgets, two cases, repetitions, 2× total
  speed and production cancellation gates remain enforced. Replay comparison
  is outside timed action latency and has an explicit bounded timeout.

Final retained Java XML: 105 tests, zero failures/errors/skips across benchmark
integration (68), final output (12), adapter (8), frozen frame (13), host (3)
and scalar comparator (1). Author's scripts unittest: 33 passed. Controller's
full scripts pytest: 185 passed plus five subtests, 139.91s. Python compilation
and `git diff --check` passed. Exact commands, RED/GREEN logs, XML and source
hashes are in ignored `build/rc10-benchmark-contract/` and adjacent log files.

This audit certifies source/test correctness only. Fresh pinned RC6/current
paired benchmark and cancellation, public CI/GUI, quality/Apply and downloaded
artifact gates remain controller-owned. No release is certified by this review.
