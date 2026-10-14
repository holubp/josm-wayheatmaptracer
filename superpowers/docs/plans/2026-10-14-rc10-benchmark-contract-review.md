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

## Native benchmark reference repair

Independent Sol/high affected-delta review against `ace8a75`: no blockers.
The historical host uses fixed benchmark settings, not bookmark settings.
Candidate preflight now binds the runner's validated actual RC6 warm-up archive
and receipt by hashes, producer, case, baseline version, publication kind, nonce,
artifact name and directory, and independently verifies native output.
Original bookmark source, frame, selection, validity, context, permissions and
budgets remain bound. Native RC6/current settings and scalar equality remain
strict. Bookmark settings semantics remain unverified; its observed scalar
differences are reported, not substituted into native comparison or tolerated
across versions. Historical outputs are not rebound; strict remains default.
Fatal host failures exit with a fixed safe message rather than entering JOSM's
nonterminal bug-report queue.

Reviewer verified all eight frozen source hashes and inspected 118 passing
Java tests (zero failures/errors/skips) and 34 passing script tests. RED proves
the previous fatal-host hang and missing runner reference interface. Evidence
is under ignored `build/rc10-benchmark-contract/reference-*`. Fresh paired
performance/cancellation and public CI remain required for this repaired tree.

## Ordinary user fixes

Independent Sol/high review of nine scoped files against `849584a`: no blockers.
Modern producer normalization bounds are independently verified (bluered1.32,
bluered-cool/dual1.30); legacy raw scores and strict scalar rejection are
unchanged. A valid RGB pixel reproduces the reported guard failure, but the
exact original RC7 pixel array is unavailable. Current immutable failed-attempt
and owner guards prevent stale failure export; fixed codes and hashed identities
remain redacted and no Apply capability is fabricated. Only worker-failure
notices become modeless; critical source warnings retain their behavior.
Cookie opening focus, Ctrl+V, Enter and Escape were checked with real Swing
events and synthetic credentials. All nine frozen source hashes match.
Retained Java XML85focused/81neighbors/6final has zero failures/errors/skips.
Full final-tree CI and required benchmark/plan/publication gates remain open.
