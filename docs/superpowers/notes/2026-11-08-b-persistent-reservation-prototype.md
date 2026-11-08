# Persistent reservation public prototype ledger

Baseada75d0a; only two probabilistic test-support files and this note may change.
Approved plan/spec: A ledger worktree plan2026-11-08-b-persistent-reservation-prototype.md
and private diagnosis/persistent-proposal-design-v2.md. No production tracing,
private sampler/engine/replay, objective/default/width/A/Legacy change is allowed.

Fixed revision: cap8, original25m inventory, core+one-source-pitch neighborhoods,
20m unique-flank gaps, exact common-frame proof or certified state-pair links.
First tests are unchanged20-row unrelated endpoints and joined40-row weaker arc;
substantive supported-control failure must stop this revision, not trigger tuning.
Evidence directory: build/private-drasov-b-resource-20261106/persistent-reservation-prototype.

Rulings: Context shares unchanged/reduced TraceBudgets, cancellation and an
existing caller-owned AttemptMemoryLedger across reserve and at most8 cheap
solves. Work reports pair checks, operations, declared allocation requests and
actual started solves; none are native-B or whole-heap measurements. Each
operation uses a child ownership transaction, transferring retained declarations
only on success; caller closure releases them. Records deep-copy constraints.
Certified non-adjacent links retain their flank-state relation during the
first-order solve with a finite pending-flank key, so per-row constraints cannot
erase a source-owned endpoint pair. Returned states remain original indices;
unary/adjacent arrays are read unchanged. This is constraint bookkeeping, not
second-order inference, new costs or production integration.

RED initial:2 tests/2 intended missing-reservation assertion failures. Initial
GREEN2/2; then15 fixed control tests passed. Strengthened local-ID renaming is
different at every row, and certified gap control crosses an actual normal/frame
change. The required solve-budget negative test exposed an accounting-only
defect: a refused ninth request was counted as a ninth solve. RED1/1 is retained;
refusal now precedes the increment, without changing any geometric rule/cost.
Final focused suite:17 tests, zero failures/errors/skips, BUILD SUCCESSFUL1m39s.
Raw RED/GREEN XML/logs and test-summary.json are retained in the evidence folder.

Verified public outcomes: original20-row right completes while the sustained
unrelated-left rival stays incomplete; joined40-row fork/rejoin retains both
complete proposals, including the higher-original-cost arm on every separated
observed row. One-point detours do not become sustained rivals. Unique short
gaps retain only their left envelope and unsupported rows; censored, overlong,
empty-envelope, empty-state and ambiguous gaps cannot fabricate completion.
Clipped25m left remains incomplete while right completes. Explicit original-state
pair witnesses preserve supported crossings/hairpins and noncomparable gaps;
foreign pairs/missing gap constraints fail. Reverse normals/offsets/state pairs
preserve reversed world geometry. Nine rivals and more than8 port histories
return visible unresolved capacity with no usable path. Ties stay ambiguous.
Cancellation/work/memory refusals return no partial path; caller closure leaves
ledger current bytes0. Eight solves are counted; a ninth is refused without a
charge leak. Cost arrays and full original input inventories remain unchanged.

Independent review R1 confirmed that source-row-only gap uniqueness let an
earlier bridge consume a target before a different-row source could compete.
That review and the original17-test source/receipt remain preserved.

Command: Java17 JAVA_HOME plus shared josm-drasov-gradle.lock, sh ./gradlew
--no-daemon --max-workers=1, official -Pjosm19555HostJar, test
--tests '*V022PersistentProposalExperimentTest'. No old parity/full suite was
repeated because only these new pure test-support files changed. No engine,
sampler, replay, private input, actual second-order characterization or source
production edit occurred. Independent review is next; second-order/native/model
integration, accepted Drasov preservation/adoption gates and release stay held.

R1 repair batch: the reviewer's exact20-row A0/B1.5 counterexample failed RED
because A8→A12 completed before B10→A12 could compete. The new test also checks
per-row local-ID permutation and exact physical reversal of all three retained
observed trace geometries. The source now captures all eligible unmatched source
and destination flanks once, collects every frame-valid/envelope-valid candidate
within the unchanged20m bound, and installs only mutually unique global edges.
No incoming/outgoing count or ambiguity flag changes during collection. This
enforces the existing rule; no new geometric heuristic, threshold or cost exists.

global-gap-red.log/global-gap-red-xml retain1 intended failure. The complete pure
suite now passes18 tests with0 failures/errors/skips, BUILD SUCCESSFUL30s;
global-gap-green.log/global-gap-green-xml and global-gap-test-summary.json retain
the evidence. Existing unique-gap, certified port/gap, clipping, reversal,
capacity, cost-identity and refusal/ownership controls remain green. Caller
closure still leaves accounted current bytes0. git diff --check passes.
The repaired snapshot is frozen for affected Sol review; no native/private or
second-order characterization has begun and no production gate is waived.
