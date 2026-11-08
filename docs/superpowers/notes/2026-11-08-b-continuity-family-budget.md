# Pure continuity family-budget execution plan

Base896669fdd6fb78a9b75c9df51bb26f9ec93c6138. Binding contract:
live-continuity-adoption-design.md, shared work/raw32 section and cheapest test3.
Scope: one package-private opt-in ledger and nested reservation/receipt types,
pure fake executors and tests. No native adapter/call, source association,
sampler, engine activation, UI/default/A/Legacy edit or private feature work.

1. RED quota/order/inventory controls, then fixed planning: at most8 predeclared
   required families, raw32 floor/remainder in physical order, no label/unary
   ordering or eviction. Compute exact full-cell adjacent pair slots from each
   original row-count inventory; reserve all repeated preparation before calls.
2. RED actual/failure/unknown-work and zero/cancel/ownership controls, then one
   sequential lease boundary. Every lease sees only remaining pair/score caps,
   a fixed raw quota/distinct<=quota, the same outer deadline/cancellation and
   one attempt owner. No retries, slot refunds or completed-family salvage as a
   fully evaluated winner when a required family is missing.
3. Verify focused pure tests, Legacy58 and diff-check. Freeze scoped commit and
   evidence for independent source review; no native reference/CI cohort rerun.

Rulings: physicalOrdinal is an upstream predeclared physical rank, not a label
or unary score. This pure ledger verifies a unique contiguous rank inventory;
it does not certify source histories or manufacture their physical order.
Preparation pair reservation is separate from actual work: unexecuted reserved
slots must never be reported as measured work. Exact/failed cumulative receipts
debit actual preparation plus native pairs and scores; missing final work stops
with UNAVAILABLE and retains the known prefix. Raw quotas are never recycled.
Reports contain at most8 conditional family receipts, not a pooled posterior,
global exactK32 or a final geometry/candidate selection API.

Future native integration is explicitly held: existing progress must attest
preparation as well as successful/failed native work and all applicable source
work caps. A missing preparation/progress witness is UNAVAILABLE, not zero.
This packet will not invent an adapter, source-cap proof or inference receipt.

Evidence: build/private-drasov-b-resource-20261106/continuity-family-budget.

Implementation frozen for independent source review. Only the new ledger,
its pure test and this note are in the source delta; no existing producer,
inference, engine/default/null path or source authority is changed.

Planning sorts only predeclared physicalOrdinal, requires a unique contiguous
inventory, and refuses the ninth family rather than truncating to8. The label
is diagnostic and has no sorting/tie/eviction role. Original row-state counts
produce exact adjacent full-cell slot sums; total repeated preparation is
reserved against one pair cap before any executor. The reservation is not
reported as measured work. Only actual preparation, native pairs and computed
scores debit the known prefix. Native remaining pairs exclude all still-required
preparation; remaining scores reflect every prior successful/failed receipt.
No state-count formula promises that scoring will fit.

One thread-confined revocable lease exists at a time. Raw quotas are fixed
before entry; no unused/duplicate/failed slot is returned or assigned to another
family. Each lease receives positive remaining TraceBudgets with distinct<=raw,
the identical outer cancellation/deadline probe and scratch/result children of
the same attempt owner. Zero remaining work is a typed RESOURCE_LIMIT stop,
never a zero-valued TraceBudgets constructor. Scratch always closes. Successful
conditional payloads transfer to the caller attempt owner; failed/cancelled
payloads do not become a current-family result. The caller retains ownership
of inputs, completed diagnostic results and metadata until closing its attempt.

Known cumulative progress cannot decrease or exceed the aggregate caps. Invalid
or over-cap claims are retained as rejected evidence; exact total work becomes
UNAVAILABLE rather than being clamped or fabricated as zero. Missing final work
or executor exceptions retain the known prefix and stop later calls/retries.
Attested failure work is debited too. Any required missing/failed/refused family
keeps the aggregate incomplete. A cancellation/deadline at completion discards
the current conditional value. Prior complete families remain diagnostic only;
there is no aggregate winner/candidate or global posterior/exactK32 API.
Reports contain at most8 family receipts. finalCandidateLimit is the accepted
attempt distinct cap (default8), a downstream ceiling rather than an implemented
native candidate-merging/selection stage.

Scope limitation is explicit: this ledger accounts pair/score/raw reservations;
source-specific geometry/sampling caps are not reimplemented, guessed or reset.
A later native/source adapter must share their real controls and attest all
preparation plus completed/failed native work. Current native progress that
cannot attest a preparation/failure prefix must map to UNAVAILABLE. No adapter
or source-cap proof is manufactured here. Source associations/certificates are
still unavailable, so no real-case or engine use follows from these pure tests.

TDD: quota RED1/1 (empty plan), work RED2 tests/1 intended execution failure,
then initial GREEN2/2. Expanded13 pure fake-executor tests passed0 failures/
errors/skips. Final affected29/29 (13 ledger +16 existing owner tests),0 failures/
errors/skips, BUILD SUCCESSFUL1m34s. Controls cover H1/2/3/8, ninth/empty/ambiguous
order, label/input permutation, exact preparation preflight, zero pair/score
remainder, fixed/unrecycled quotas, partial failed work, missing/invalid receipts,
revoked/active leases, shared cancellation/deadline, owner/resource closure and
conditional-result retention. Saved logs/XML/test-summary retain every stage.

Commands: Java17 JAVA_HOME, shared josm-drasov-gradle.lock, sh ./gradlew
--no-daemon --max-workers=1 -Pjosm19555HostJar=official19555 test
--tests '*V022ContinuityAttemptLedgerTest'; final additionally
--tests '*V022AttemptMemoryLedgerTest'. No full/broad/native cohort repeated.
Native calls0, engines0, samplers0, private/public feature extraction0; only
fake executor/metadata/ownership operations ran. Legacy58 guard and diff-check
pass. Source/class/runtime/evidence pins are frozen for one independent review.

## Review repair R1/R2

Independent review found premature manual settlement from inside runAll could
leave H1 COMPLETE or H2 READY when the callback subsequently threw or returned
into a second settlement. Four H1/H2 throw/return regressions failed RED. Reduced
distinct-cap1/4 reports also failed RED (advertised8); default8 and ordinary
managed/manual success controls passed. The complete RED was8 tests/6 intended
failures and remains preserved with cdc07 source bytes under repair/.

The managed callback now cannot settle its controller-owned lease. Work already
attested by the premature outcome is accepted as a cumulative prefix before the
protocol error is raised. The callback has not returned a final receipt, so the
failure keeps exact total work UNAVAILABLE, closes per-family scopes and forbids
subsequent reserveNext/runAll calls. Only the controller settles a returned
managed outcome; manual reserveNext/finish remains valid outside managed mode.
Unexpected exits after a revoked lease also cannot retain READY/COMPLETE.
The immutable report now carries the accepted maximumDistinctAlternatives,
including reduced1/4 limits, without merging candidates or posterior evidence.

Affected regression receipts, current source/class pins and Legacy58 guard are
frozen under repair/ for one affected independent review:37/37 (21 ledger and
16 owner tests),0 failures/errors/skips, BUILD SUCCESSFUL1m38s. No native, engine,
sampler, source adapter, feature/private operation or new model was added.
