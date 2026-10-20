# Exact cross-stream strict dominance: design for review

Date: 2026-10-20. Status: DESIGN ONLY; pre-triple raw-1 candidate for review.
Resource usefulness remains OPEN. This document does not authorize implementation,
historical retries, fixed-case execution, a policy promotion, or a release.
User approval covers writing this specification; written-spec approval must precede
an implementation plan and its separately authorized execution.

## 1. Purpose and unchanged boundary

Reduce genuinely evaluated losing K-best prefix-extension scores, while retaining
the same exact second-order inference problem and every admitted state and edge.
This is execution-policy work, separate from the rejected G-only observation family.
It cannot repair the intermittent/merge/faint-bend/noise objective preferences.
The representative-recording and distinct-parallel-trail goals remain separate.
Neither obtaining a path on a previously resource-limited case nor numerical
equivalence establishes observation-model qualification or a safe release.

OBS-02, OBS-11, OBS-16–18 remain binding: unchanged source, admission, objective,
negative unaries, cell measure, temperature, tie rules, full forward/backward (FB),
MAP arithmetic, all alternatives, finalization, cancellation, and owned resources.
No state removal, beam, triple tensor, unbounded cache, new source acquisition,
geometry smoothing, objective reweighting, or score/work/memory cap increase.

## 2. Frozen source and evidence

Source checkout: `.worktrees/b-observation-ci-20261018`, HEAD
`bff216512c05bdf4c6691c5867691b717d8f8e2f`.
`ProbabilisticInference.java` SHA-256:
`4e3b10c0c1b589d8457fd6d506d735353b6dd4e62db6258f7557937aa7f8f390`.
Source anchors below refer to that file under `src/main/java/.../probabilistic/`.
Original authority: `2026-10-16-b-observation-contract-specification.md` in this
directory; no old authority, registry, resource, or historical source is repinned.
Retained audit: `.superpowers/sdd/2026-10-16-b-observation-identification-plan/`
`five-case-current-recurrence-feasibility-20261020.json`.

That audit derives current raw-1 totals of 138,991,677–140,067,936 scores for five
graphs; FB alone actually used 92,661,118–93,378,624. These exceed the unchanged
128,000,000 cumulative score limit during K-best. They are facts about this
recurrence, not an impossibility theorem for exact inference or a success forecast.
Existing five-case raw/distinct limits are 1/1; native production is 32/8.
Both remain their own comparisons with separate graph, policy, output and work IDs.

## 3. Actual recurrence and proposed insertion

`enumerateKBest` (589–909) fixes K to `min(32, maximumRawAlternatives)`.
Single-profile and initial-pair paths (596–637) are outside this change.
The exact branch (670–773) visits target `(prior,state)` and then each `before`.
It performs transition admission, updates `nextCounts` capped at K+1, and records
the complete prefix-list size in `logicalKBestExtensions` before evaluating terms.
Its lazy pair/unary evaluation is shared by the target; each nonempty stream then
performs the original three `point()` validations, heading/cache accesses, triple
evaluation, and rounded increment, in that order (687–742).
Each prefix subsequently checkpoints, requests `score(false)`, and computes its
rounded completed energy (746–755). The score request counts even the losing peek.
`DescriptorHeap.strictlyDominated` (1784–1788) cuts only after that evaluated score.

Proposed placement is AFTER original unaryPair, all three `point()` calls and the
original optional scalar-cache allocation attempt, but BEFORE the stream's first
heading/cache-value lookup, wrap/Huber/triple evaluation and completed-prefix score.
Initial scope is K=1 and turnWeight>0 only; all other cases retain original V2.
In particular zero turn must not introduce three previously absent point calls.
Keep admission, path counts, logical extensions and lazy point/allocation failure
order. Orientation may already compute secondHeading while building unaryPair;
that existing computation is retained. Do not claim it is avoided by this bound.
Do not interpret FB as an exported finite certificate: no such interface exists.

## 4. One conservative finite-precision bound

Use Java 17 binary64 round-to-nearest arithmetic, without reassociation or FMA.
Write RN(a+b) for the actual Java addition; this is not a real-valued approximation.
Let U be the actual rounded `unary + pair`, p the sole retained prefix energy,
T the unevaluated source triple and I=RN(U+T). The unchanged score is E=RN(p+I).
U and p may be negative. Never bound E by p alone or add width logs to MAP.
Use only a full K=1 heap with finite worst energy W and exactly one prefix.

Parameters validate finite w>=0 and scale s>0 (`EvidenceModelParameters` 22–30).
After `point()`, MetricPoint's constructor proves finite coordinates. Differences
of finite coordinates may overflow to infinity but cannot be NaN; atan2 remains
finite in its specified phase range, including infinity and signed-zero cases.
Reference: [Java 17 StrictMath.atan2](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/StrictMath.html#atan2(double,double)).
Set A=`Math.nextUp(Math.PI)`; both actual headings have magnitude <= A.
Their rounded difference is finite. Actual `wrap` (1503–1505) is
`atan2(sin(angle),cos(angle))`, NOT a remainder or exact-real modulo operation.
For finite angle, those arguments are finite, and the resulting magnitude <= A.
Thus rounding of the heading subtraction cannot escape this final range bound.

Define up(z)=`Math.nextUp(z)` after the indicated binary64 operation. Every bound
intermediate must remain finite/nonnegative, else mark certificate UNSAFE and
run the original stream without changing its failure classification.
Compute x=up(RN(A/s)). If x<=1, H=up(RN(up(RN(.5*x))*x)); otherwise
H=up(RN(x-.5)). This bounds the actual source Huber, including both branch arms:
on nonnegative finite magnitudes each arm is monotone and joins at .5 for x=1.
Compute m using the EXACT source `max(1e-6, .5*(chainage_i-chainage_i-2))` order.
Finite increasing nonnegative chainages give finite m>0; validate this certificate
condition rather than changing the source floor. Set B=up(RN(w*H)), then
Tupper=up(RN(B/m)). Reject any nonfinite intermediate, including a product which
overflows even if division might otherwise reduce it. Consequently 0<=T<=Tupper.
Compute this scalar stage certificate lazily at its first eligible stream, after
the retained validation/allocation boundary; reuse it only for that same stage.

Set Q=`Math.scalb(Double.MAX_VALUE,-2)`. Require finite |U|<=Q, |p|<=Q and
Tupper<=Q. Then |I|<=2Q; the real sum |p+I|<=3Q has ample overflow margin,
so E is finite after rounding. Compute L=RN(p+U). Rounded addition is monotone:
I>=U, hence E>=L. This includes subnormal/underflow cases without a tolerance.
Skip only when L>W using numerical `>`; equality, including +0/-0 equality, runs
the original score/tie path. `Double.compare` and lexical ranks remain unchanged.
T is NOT computed for a skipped stream. Even if its unknown value happens to be
zero, this is a partial bound, not a completed energy renamed to evade accounting.
Computing RN(p+I) without a score admission is forbidden. Failed certificates do
not throw/clamp; they resume original term arithmetic, checkpoints and scorer.

## 5. Exactness argument and limits

A full raw-1 heap contains one actual descriptor with energy W. If L > W,
the completion in this stream is strictly worse than that descriptor.
It cannot enter the heap, change an energy tie, or affect a later heap threshold.
Induct over the original before-stream order, targets, and stages: retained
descriptor energies, prefix/state references, lexical ranks, selected roots and
their order are identical. Existing log measures, ancestry extension arithmetic,
materialization and terminal/path-count diversity proof remain unchanged.
FB, partition, posterior and credible intervals must retain their original bits.
For a heap not yet full or any failed certificate, take the original exact path.
The partial-order argument extends to K>1 only with a bound on EVERY prefix;
that extension is outside this candidate and requires its own design/cost review.
K>1 retains V2 and all raw/distinct alternatives; native 32/8 is never downgraded.

`strictlyDominated` already stops an ordinary losing stream after its scored head.
Thus skipping a whole stream does NOT prove that K actual reference scores were
saved. `prefixes.size()` describes logical extensions, not avoided scored work.
Only actual new completed scores are reported by the original score counter;
any reference-work delta must come from a separately retained comparison.

## 6. Resource usefulness is a blocking decision

An AFTER-triple bound was considered and is not recommended: it saves a scored
head while adding work without avoiding its expensive terms. The proposed earlier
certificate can avoid firstHeading atan2, wrap sin/cos/atan2, Huber, triple division,
increment, and score/heap work for each skipped stream; cached/orientation headings
reduce that benefit. Stage certificate and per-stream bounds add real work.
CPU benefit and skip frequency remain UNKNOWN, not a five-case completion forecast.
Independent review must verify finite-proof and actual operation/counter boundaries
before implementation; later authorized measurements must report both added and
avoided work, memory and time. A smaller counted total alone is insufficient.

## 7. Honest accounting, cancellation and ownership

Keep `WorkCounter.score` (1865–1874) and its cumulative FB+K-best ceiling unchanged.
Every completed p_j+I evaluation, including a losing peek, needs its original score
admission. Bound tests never reset/subtract scores or alter refused-score reporting.
Add separately labelled stage certificates, bound queries, partial additions,
strict comparisons, skipped streams and skipped logical-prefix counts, with checked
integer accumulation. Never combine their units with scores or claim old receipts
are unchanged. Existing actual/logical counts and elapsed time remain distinct.
For stage dimensions a,b,c, at most a*b*c queries and one stage certificate
are possible. Derive these checked scalar ceilings from existing dimensions,
without a graph scan, allocation, extra admission visit or hidden per-triple cache.
Beyond once-per-attempt A/Q initialization, each certificate uses at most eight
binary64 arithmetic/max operations and five nextUp calls; a query uses two abs
calls, at most one partial addition and one strict comparison, plus its finite,
eligibility and magnitude checks. Count those checks and cache work separately.
Account bounded comparisons/finite tests as bound work too; the implementation
plan must freeze their primitive census, counter limits and overflow refusal.

Use scalar locals and the existing heap; allocate no per-stream object or array.
Any additional accounting object/result copy must be reserved before allocation
under the same attempt owners, with a reviewed complete lifetime/census delta.
The 64MiB experimental envelope and all lower guards remain unchanged; native
production's separately recorded ledger is not spare memory for this comparison.
No memory saving or five-case feasibility is established by this specification.
Keep original pre-bound checkpoints and cleanup. Check cancellation at bound entry
and before accepting a skip; never catch it as certificate failure.
New work changes the deterministic checkpoint schedule,
so exact old poll-count identity is NOT claimed. No cancellation may become success.
Preserve the original optional scalar-cache allocation attempt before the bound.
Only skipped streams defer cache lookup/fill and heading initialization; report
their actual calculations/reuses, not fictional old counts. Surviving evaluations
use the original arithmetic and cached values. Fallback invokes the original scorer;
allocation/numeric/source/cancel failures remain terminal under their existing
contracts, with no partial publication.

## 8. Required future independent oracles and gates (not executed)

- Exhaustively enumerate tiny 3–6-profile graphs independently: K=1,2,32, ties,
  nonuniform widths/anchors and nonunit temperature. Compare every ordered raw
  path, energy bits, lexical tie, partition/posterior and distinct disposition.
- Exercise negative unary/negative prefixes; huge finite magnitudes; Q boundary;
  NaN/infinite intermediates; underflow; signed zeros; rounded I==U; exact L==W;
  one-ULP strict gaps; non-full heap; K>1 unchanged fallback; zero turn and tiny scale.
- Deliberately omit turn/nonnegative or finite certificates and require adversarial
  oracles to detect wrong winners. No rounded tolerance replaces bitwise equality.
- Freeze logical counts independently; ensure every evaluated completed score,
  including cutoffs, is charged; verify new bound-work ceilings and cap refusal.
- Source-derived certificate tests must enclose actual triples for extreme finite
  coordinates/infinite differences, both Huber arms, wrap near pi, tiny spans/scales,
  underflow, w=0 and product-overflow fallback; certify no hidden completed score.
- Sentinel point/potential/cache-allocation failures keep their retained lazy sites.
  Test optional-cache refusal, cancellation at each new boundary,
  numeric fallback, owned cleanup/root closure and atomic failure publication.
- Compare old and proposed explicit policies on identical admitted graphs through
  the actual solver, then actual native entry/final-route ownership. Preserve
  geographic roundtrip, exact endpoints, all alternatives and native work IDs.

Only after written design/plan approval and source/test review may separately
authorized fixed executions assess score savings, bound work, memory and wall time.
Retain all five prior resource failures; one new attempt needs explicit approval,
no automatic repeat, changed state admission, cap increase, or baseline fallback.
Introduce a distinct execution-policy identity; preserve EAGER_V1 and V2 entry
behavior and old frozen source authorities. Version propagated live/replay hashes
must identify changed execution/work/cancellation semantics without implying a new
observation formula. Final source freezing and comparison manifests require review.
Numerical equivalence, resource feasibility, observation qualification and complete
original release gates remain four separate obligations; none is closed here.
