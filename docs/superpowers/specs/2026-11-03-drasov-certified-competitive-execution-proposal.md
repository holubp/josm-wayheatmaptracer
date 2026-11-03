# Proposed amendment: certified competitive execution for A optional emission

**Status: PROPOSED, not active.** This changes only the execution and proof gate in
`2026-11-02-drasov-optional-emission-native-integral-v2.md` §§4, 6 and 8. Independent contract and
correctness review must accept it before code or an A feasibility run. The present v2
all-admitted-graph rule continues to govern until then. Legacy's measured 133-pair global DENSE
result and its separate kernel gate remain unchanged.

## Motivation and unchanged contract

The reviewed A R4 STOP run at `53b2ef8` exhausted the shared 128M work cap after 1,937 of 26,098
potential optional keys (656 admitted, 1,281 rejected); 24,161 admission outcomes remain unknown. It
does not yield an A winner or prove a practical solver. The proposed execution may defer costly mode
certification only when it proves that **every complete path using a deferred transition loses
strictly to a certified feasible incumbent**. It retains every original A state and potential edge,
ordinary native admission, mandatory occurrences and exact source row identities, the positive/zero
native connector predicate, Q/C/T/K formulas and arithmetic, effective incoming direction and zero
transport, deterministic objective/ties, cancellation, and the shared 128M work, 8M pair-visit,
256MiB memory limits. No beam, reduced search, new scalar policy, source-forward screen, hidden
second trace or partial best-so-far output is authorized.

## Replacement execution rule for §§4, 6 and 8

1. **Inventory.** Enumerate all original native states and potential ordinary/optional edges under
   the unchanged original-row, 16-interior-row, 20m, mandatory-event and endpoint rules. Keep exact
   source/row/state/occurrence identities and a deterministic order. This census is charged before
   use; potential counts are complete, while image admission status is initially `UNMEASURED`.
   Ordinary edges remain governed by existing native admission, including its unsupported-field loss
   of `1`. Native ordinary admission alone does not certify a costed prefix: its actual Q must be
   measured on complete field support or assigned the explicit unavailable loss 1, and its C/T/K must be
   evaluated in the unchanged order. A path used as upper bound must have every edge genuinely
   admitted under its applicable rule and every Q/C/T/K term evaluated; an already available full
   ordinary path is eligible only after that complete check. If none is established, U=+∞ and the
   attempt may reach its typed cap with no output.
2. **Frontier.** Seed every allowed initial canonical A key. A certified prefix has every prior
   ordinary or optional edge genuinely admitted and its actual Q/C/T/K evaluated in the original
   arithmetic order. An ordinary edge whose scalar support or cost is unresolved remains the first
   unmeasured transition even if its native graph admission is known. Every reached exact canonical
   prefix (cursor occurrence, effective positive arc/reference or none, last numeric row,
   phase/boundary context and tie metadata) creates a pending transition for every eligible successor. A positive
   optional edge's mode admission is endpoint/context dependent; a zero edge's directional receipt
   is additionally keyed by its actual incoming origin. An admitted transition may create a cheaper
   version of a prior key; regenerate its outgoing pending transitions. Do not discard an old
   version unless dominance is proved for the original arithmetic and final tie order. A path
   containing an as-yet-unmeasured edge has a **first** such edge after a certified prefix, so it is
   represented in this frontier. Certified admission is cached only by exact owned key and frozen
   source/parameters. Complete relaxation and queue insertion for every newly reached prefix and
   zero-direction variant before testing closure.
3. **Bound and order.** Priority is a conservative lower bound `LB(prefix,edge)` on the cost of
   **any complete path extending that transition**, with stable native-ID tie order in the queue. It
   includes an enclosure of the prefix's already evaluated cost and any safe lower bounds on the
   next edge and remaining source interval. Compute the unchanged C/T/K from the exact canonical
   prefix and candidate geometry/reference before global mode proof where defined; include their
   downward enclosures only after proving coefficients and terms nonnegative, otherwise use a safe
   lower bound or `-∞`. For scalar Q, an exact same-arithmetic bilinear preflight result may be
   reused on later admission. For every remaining source-chainage mass `S` not covered by an exact
   Q, fixed positive factual pitch `p`, and a certified global native-intensity upper bound `Imax`,
   use an outward lower enclosure of `(S/p) * min(1, 1-Imax)`. The `1` covers an ordinary edge with
   unavailable field support and its explicit loss 1; `1-Imax` covers every measured ordinary or optional edge.
   The source binding must certify `S≥0` and `p>0`; otherwise this bound is unavailable.
   If `Imax>1`, this bound is negative over **all remaining source mass**. If a finite safe intensity,
   geometry, coefficient or arithmetic enclosure cannot be established, use `LB=-∞` and certify the
   transition. No assumption that native mean ≤1 or all increments are
   nonnegative is permitted. A relaxed suffix can ignore admission and constraints only when doing
   so cannot raise its lower bound. Numeric bounds enclose IEEE operations, interpolation and final
   accumulation in the unchanged order; ambiguous near ties remain open.
4. **Certify on pop; close strictly.** Pop the smallest live pending bound. If it cannot be proved
   `LB>U`, apply the existing charged source/span, whole-segment authority/support, endpoint, mode,
   mutual continuation and slab tests in their approved order; record the actual `ADMITTED` or typed
   refusal, and reuse any owned scalar preflight without duplicate native I/O or double charging. A
   scalar preflight never publishes a refusal or reorders final reason priority; if its
   arithmetic/support is unresolved, retain `LB=-∞` and run the original stages. Relax admitted
   transitions using unchanged Q/C/T/K and canonical zero transport. Recompute U only from a
   completely certified feasible path. Queue versions and any invalidated bounds are updated
   deterministically; an obsolete entry is ignored only with a recorded dominance or replacement
   proof. Closure occurs only when a finite certified U exists and the minimum bound over **every
   live unmeasured frontier transition** is strictly greater than U. `LB==U`, overlapping intervals
   and any tie uncertainty require admission, because consumed-row and lexicographic ties can change
   the winner. If work, pair, memory or cancellation ends first, return the existing typed
   limit/cancel result and no candidate.
5. **Final exact solve and report.** Run the same exact canonical pair-state DP in native state/edge
   order over the measured admitted closure, preserving cost accumulation and tie order; this is the
   one published solver result. The proof above makes every path using a deferred transition
   strictly worse than the certified incumbent, so this winner must match full admission if the
   bounds and frontier invariant hold. Report total potential states/edges; measured
   ordinary/optional admitted and refused counts/reasons; actual measured E/X and pair visits;
   deferred `UNMEASURED_CERTIFIED_NONCOMPETITIVE` count with bound certificate;
   scalar-preflight/cache/query work, queue/bound/dominance operations, peak and final owned bytes,
   and the proof's final `minLB>U`. Never report deferred admission as `REJECTED`, claim full-graph
   admitted E/X, or call a cap-limited partial graph complete. Keep source, execution-version and
   numerical-policy identities separate.

**Ownership and work:** Scalar preflight and later evaluator admission share one attempt-owned
exact-key certificate (source/grid/region/parameter fingerprint, cell support, Q and query receipt);
no rounded geometry key, duplicate integral, uncharged temporary allocation or token surviving its
owner. All census, bound, queue, cache, cell/region/mode, DP, tie and backpointer operations are
charged before execution under the existing shared caps. Every canonical
predecessor/destination/transport/initialization visit during **both** frontier relaxation and final
DP consumes the 8M pair counter as well as 128M total work; an extra proof pass is not free. An
unbounded potential or canonical variant family is typed refusal, not truncation.

## Proof obligations and acceptance before any adoption

The closure invariant must be independently proved for first-unmeasured-edge coverage,
exact-key/directional receipt reuse, canonical-prefix dominance, negative-Q-safe suffix bounds, IEEE
outward enclosures and strict tie behavior, including zero physical legs and multiple protected
occurrences. A fully admitted tiny-graph oracle must compare the exact winner, objective components,
emitted/consumed provenance, tie IDs and zero/boundary cursors to demand execution. Required
adversaries include a late cheap optional edge behind an expensive-looking prefix, an ordinary
incumbent overturned near the last transition, nearly equal floating costs, negative-Q or unknown
scalar range, stale prefix versions, and an intentionally broken closure bound that the test must
reject. A cap or cancellation must publish no partial winner, with memory returning to baseline. The
unchanged Legacy and existing evaluator numerical regressions remain gates.

Only after independent review accepts this proposal **and** the Legacy-first gate is stable may one
bounded, fully charged R4 A attempt measure whether competitive closure finishes under the unchanged
caps, including final DP and all proof work. A successful R4 attempt is not Drasov A or
field-quality qualification; the original full replay, geometry and safety gates remain. If R4
reaches a cap, record failure instead of relaxing bounds, predicates or limits.
