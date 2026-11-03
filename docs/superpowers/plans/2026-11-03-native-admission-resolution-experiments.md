# Native admission resolution experiments

> **For agentic workers:** Use superpowers:subagent-driven-development for bounded isolated implementation and superpowers:executing-plans for controller integration. This is a subplan of the approved Drasov plan, not a replacement.

**Goal:** Find a measured, correct admission implementation that can evaluate the actual R4 graph within existing limits before implementing its inference consumers.

**Architecture:** Retain the dense transverse-query baseline. Measure an exact necessary endpoint gate and two explicitly versioned lateral-resolution alternatives. All use the existing extractor, continuous scalar proof, conservative region authority and full original state graph. Query-policy changes are numerical experiments, never labelled exact optimizations.

**Tech Stack:** Java17/JUnit5, existing retained scalar/context, ignored standalone benchmark and public CI.

**Spec:** Approved optional-emission amendment and native-integral-v2 under `docs/superpowers/specs/`; existing declared-resolution scope review remains binding. Standing user authorization covers related experiments/plans; adoption/release gates remain in the original Drasov plan.

## Design and measured reason

Reviewed native-connector4704668 passed123focused tests. Complete actual R4 graph
has239states/26098pairs; dense admission stopped at128Mwork after550receipts
(184admitted),8.423s,10.9MiBpeak tracked memory. Extraction used72.08Munits,
node reads22.24M, regions22.12M. This is negative admission feasibility, not a
solver result. Reference native tracking is outside that measurement's accounting.

Considered approaches: exact necessary-owner rejection is low-risk but cannot
reduce work of already-admitted arcs; exact whole-profile authority/cell invariants
can avoid repeated work but do not alone remove the dominant extraction cost.
The implementation-added p/8 transverse clamp is therefore also tested explicitly
at lower declared resolution. v2's p/2 ALONG-connector probe rule remains intact;
it does not prescribe the transverse step. No claim that lateral policies agree.
The captured d is the REQUESTED step; the existing producer's interleaved L0 step
is max(d,r)/2, which is0.5m on R4. Neither proposed formula claims general producer
equivalence. Controller chose these bounded experiments under standing authority;
they are not adopted tracing policies.

## Global Constraints

- Same128Mwork/8Mpair/256MiB limits per independent attempt, original239states and26098potential pairs; no beams, narrowing, cap raises or catch-and-continue.
- Keep source regions, masks, native resolution, original source classes, state owners, scalar minima/thresholds and mandatory events authoritative.
- Along-connector spacing remains at most half minimum native pitch plus native grid events.
- Current dense API/default and all live engine defaults remain intact. New policies are internal experimental constructor arguments with explicit receipt identity.
- No engine/kernel/UI/transaction/release change. No private captures uploaded.

## Review Focus

1. Phase-shifted thin ridges and split/parallel modes must not gain unsupported connectors through coarser lateral samples.
2. Original faint synthetic fallback remains GAP; a fresh port does not fabricate original ownership.
3. Invalid/corner cells, authority gaps and ambiguous flanks remain unavailable.
4. Terminal work/memory/cancellation cannot reset across sessions.
5. A partial graph or fewer refusals is not solver or route-quality success.

### Task1: Bound the benefit of the exact owner predicate

**Files:** ignored benchmark helper only; report in existing ledger.
**Interface:** actual native R4 state inventory and retained context; calculate the existing endpoint Euclidean necessary condition with actual row-normal metric pitch and source-pitch uncertainty.

- [x] Count states/pairs surviving the necessary condition across the entire unchanged graph, without mode extraction or inference. GAP/censored ports stay unknown, not falsely observed/blocked.
- [x] Record counts, input/state hashes and actual measured/proxy work boundaries. Do not claim the necessary predicate proves admission.
- [x] Use this census to decide whether an early-owner implementation alone is worth a full capped comparison; preserve typed early-refusal provenance if implemented.

Task1 evidence:239states/26098pairs asserted unchanged;233eligible/6ineligible,
0unknown states;1173pairs excluded,24925remaining (95.505%). At equal pair cost
only1.047x benefit; actual per-pair skew unmeasured. Controller does not implement
this small prefilter first because it cannot plausibly explain the48x work gap
by pair count. Java17 helper/source-only census, no field extraction or state
pruning; ignored artifacts `20261103-owner-census/` and Termux report
`drasov-owner-census-report.md`. Task2 now delegated to isolated Luna/medium.

### Task2: Explicit query-resolution policies and retained controls

**Files:** `service/emission/NativeConnectorEvaluator.java`, `service/NativeConnectorEvaluatorTest.java` only.
**Interface:** add enum `QueryPolicy` and constructor `(SampledEmissionInput, Domain, QueryPolicy)`; existing two-argument constructor selects `DENSE_EIGHTH_NATIVE_V1`.

Let d be captured requested lateral step, p the minimum factual native pitch and r the factual output-raster pitch. Policies:
`DENSE_EIGHTH_NATIVE_V1=min(d,p/8)` (exact existing numerical path);
`HALF_NATIVE_L0_V1=min(d,p/2,r)`;
`PIXEL_CAPPED_L0_V1=min(d,p,r)` (experimental requested/native/raster-pixel cap,
not the producer's effective L0 step).
Record policy plus actual samples in immutable receipts; version identity must distinguish them. Do not change longitudinal probes, mode parameters or thresholds.

- [x] Add assertion-level RED for explicit policy identity/step and unchanged dense sample geometry; implement the minimal constructor/step switch.
- [x] Parameterize genuine R4 A/Legacy witness, no-ridge, scalar dip, mask/corner, region gap, fresh GAP, broad/faint, fork and zero-transport controls over policies with independently fixed expectations. Add phase-shifted thin ridge controls; retain originals and record any changed mode/flank/censor outcomes.
- [x] Run Java17 offline focused NativeConnectorEvaluatorTest plus integral/context/extractor/region neighbors. No weakened assertions or brighter fixtures to force agreement.
- [x] Independently review numerical scope and implementation. A failing variant stays experimental/refused; do not automatically choose the fastest policy.

Task2 complete at7e6c64f (24be56d+a170478+7e6c64f), isolated query worktree.
Assertion-level policy-version RED preceded implementation. Final focused125tests
and controller-expanded185tests/14XML (including region, budget and scalar-grid
neighbors) pass with0failures/errors/skips; Java17 offline Gradle48s. Independent
Sol/high actual-source review and focused closure find no blocking defect. The
unchanged masked-ridge fixture explicitly admits dense measured support and
rejects coarser NO_COMPLETE_MODE: numerical policies are not equivalent. Original
faint Gaussian/ownership evidence is preserved, not brightened/deleted. No engine
default or adoption changed. Source/class/JOSM fingerprint
`4e6ca424eb2478b90fa9795095daab3fd6fbf61706f746bd1d020464b32a8f06`
is frozen after final build/review. Task3 three-policy capped runner launched;
results remain pending. Comparer now rejects missing/mixed/different input hashes
and retains unmatched/unmeasured distinctions; its self-test passes. Java17 runner
compile was verified before launch, with actual track and refusal-count scopes fixed.

### Task3: Same-input capped graph comparisons

**Files:** ignored versioned benchmark runners/receipts; durable ledger/spec updates only.

- [ ] For each policy, run a fresh independent attempt with identical scalar/state/chainage identities, complete graph and same caps. Record each ordered row/state pair's outcome/reason and work deltas, admitted-set changes, elapsed/peak memory and terminal refusal. Keep all old receipts immutable.
- [ ] Public synthetic CPU runs may move to CI; no private upload. Compare new policies against a freshly instrumented dense baseline rather than inventing missing per-pair results from the old aggregate receipt.
- [ ] A stopped baseline's remaining pairs are UNMEASURED, never rejected. Compare admission outcomes only on completed common pairs; report unmatched pairs separately. Independently assess new/coarse-only admissions from frozen image evidence before adoption, including mode/flank/censor/split cases.
- [ ] Only after a correct policy/exact optimization passes controls AND complete admission feasibility, freeze the next exact-kernel packet. Otherwise record the new limiting evidence and select the next bounded exact optimization. Original Drasov/Omice, engine consumers, topology, replay and release gates remain open.

Review: focused independent Sol6high plan review permits execution after the two
clarifications above (producer naming and unmeasured baseline evidence). No new
user approval gate. Review receipt remains in controller Termux tmp as
`drasov-query-resolution-plan-review.md`.
