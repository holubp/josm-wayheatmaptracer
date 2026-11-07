# Omice Budgeted Completion with Drasov Preservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit experimental B policy that returns a fully computed first retained graph for review only when a sound work lower bound proves that existing V5 refinement cannot complete, while preserving accepted Drasov geometry and authority exactly.

**Architecture:** Keep the existing objective, V5 retained states, exact pair-state solver, K32, finalization and mutation boundaries. Introduce a completed-pass work witness and one deterministic refinement decision: `PROVEN_UNAFFORDABLE` permits first-graph completion; `UNKNOWN`, including equality, executes the original V5 refinement unchanged. Historical policies remain unchanged; a distinct parameter/execution identity and additive receipt bind the new behavior.

**Tech Stack:** Java 17, Gradle, JUnit 5, JOSM 19555; existing immutable trace models, attempt memory ledger, Format-15 frozen replay and modeless review UI.

**Spec:** This plan implements the user's 2026-11-07 planning request and the bounded option in `build/private-drasov-b-resource-20261106/omice-v5-evaluation/astra-analysis.md`. That diagnosis, this plan's preservation contract, root `AGENTS.md`, and the unchanged public quality contracts are required reading. This is PLAN ONLY; implementation, tests and native evaluations have not been authorized or run by its preparation.

## Global Constraints

- Defaults remain `maximumStatesPerProfile=96`, `maximumPairVisits=8000000`, `maximumTransitions=128000000`, `maximumRawAlternatives=32`, `maximumDistinctAlternatives=8`.
- Preserve original +/-25 m Omice/Drasov search, 1.56 m configured profile step, frozen evidence, permissions, fixed junctions and disabled cleanup. No subsampling, selected-range change, radius override or state-cap change.
- Preserve every initial V5 original state, mode, mandatory/exact anchor, source-pitch frontier and continuation state. No beam, mode averaging, post-fit smoothing, objective change, extra solver pass or hidden retry.
- Default capture remains V4. V5 remains unpromoted. New policy is explicit direct-B + Precise Shape opt-in through typed requests only; no new ordinary UI setting or default, no Legacy/A/Hybrid change.
- A cancellation or failed first pass can never return a previous candidate. Finalization, whole-preview topology, immutable assignments, source revalidation, review confirmation and `ApplyAlignmentEditPlanCommand` remain authoritative.
- All private evidence remains local. No private geometry, fixture bytes, credentials, absolute archive paths, release, push or workflow dispatch is authorized by this plan.
- Preserve the known diffuse-three-strand failure and existing six-scene, high-retention, two-case speed, repetition and production-observer gates. No tolerance or exception changes.

## Review Focus

- A pessimistic upper bound exceeds budget while the old execution actually fits: fallback must remain ineligible (Task 1/2).
- Cancellation arrives after first-pass completion or during finalization: no saved-pass salvage, owner leak or stale publication (Task 2/3).
- V6 request/work is mislabeled as V5, or a receipt is removed/tampered: replay must reject the binding, while old captures stay readable (Task 3).
- A first-graph route is individually safe but misses a parallel corridor: retain truncation/ambiguity and conditional evidence; never claim full diversity or unconditional posterior (Task 2/4).
- New parameter identity changes plan hashes despite identical geometry: preserve semantic assignments/authority and bind confirmation to the new actual hash; do not reuse old confirmation (Task 3/4).

---

## Preservation contract and proof

Use `B_ROOT=build/private-drasov-b-resource-20261106` as documentation shorthand only. The accepted baseline is **`B_ROOT/frontier-v5-qualification/drasov_precise_candidate_one`**, not an older V4 plot or original Move Existing Nodes capture. Its actual derived mode is Precise Shape. It produced one native usable route, disposition REVIEW_REQUIRED, 300 raw/325 final points and 91018982 scores in 95419 ms. It has no review confirmation, fresh session authority, ordinary timing qualification or release qualification.

Original frozen input SHA: `f6af8e102b73d1dd3180f72db9c5d0681b0eb63ccf1e9cfe5cb817719eb339aa`.
Accepted derived input SHA: `4ed55f11d81817cc7e4defdc79549f8ebf3c0628911be328a8d8eb69d9f1f653`.
Runtime source: `1b22094392779139a1cdb2b6d98ea54032e04551`.
Runtime JAR SHA: `d4905145c2814e8e3f41a6770f1688150c470999d570f9623ffbf257e5a1a9a6`.

| Baseline artifact | SHA-256 |
| --- | --- |
| `geometry.csv` | `c6346145e69ae3347c0eb4c9041c1da3b71bbf9f35c24467229af1d3f216fa9f` |
| `assignments.csv` | `c36f1666fd1596701eaa26c33dc974b44f756ab3bf7f644705e7660eb98b0ff1` |
| `continuous-authority.csv` | `23f1c9b27ab5a6db3fcfe2878d94514189e301cca1f9c1bad65086aca0349851` |
| `native-raw-path-order.csv` | `1a72d8bd3a1dd6b30caf0d70e34efbf4bdf69c848441a2f37693a010f50136c5` |
| `retained-original-indices.csv` | `fc6ee3d650a9635ef7dba383862c5236219ebc2a902944dad60856ccf8b05527` |
| `quality-topology.txt` | `5b342203dc9ff645f3ca4dfe350eafba76b59fcac0e0c3db108b2f1bcf35b2ef` |
| `production-capture.zip` | `c65b66780fe57a814f6ac86892385c5d8664a7104f08182fac178f784e777106` |
| ZIP `raw-route.json` | `9328f0f086d4ebe2c8a1ba1dc3d3e293f035f183f0d76601749be338eba2321b` |
| ZIP `final-route.json` | `10ffe39e7d825611ff70441fe79a07f65d6dd6e328edc5f3b0e8a8ef02ffe688` |
| ZIP `private/final-output-components.bin` | `20ca52ed526fae70808129d0bd9f821870eb71ae40291c0a88ba7295d7c523b0` |
| ZIP `frozen-edit-plan.bin` | `2878f403b12c8e089d13e86cef9ab9cd07288370cfd9756ec26622f7f93b77e8` |

Baseline plan hash is `f69ae5493fe131d37bb2ab24c2a8e4a00a3af269844e2e4867614fd1a71c6a0a`; before-network hash is `193108fe8602af3b816e09893d98f8854f5d09d2c92ae8b536c8937c8f6a8589`; after-network hash is `39a483245d60a51b98f13b8e6ce2a41d0a77c120e89d878d22ab7bd4f9e3a08e`.

**Structural safeguard:** Let `F` be completed first-pass forward/backward scores, `H` its scored K-best predecessor heads, `S` its total scores, and `R=budget-S`. Existing `AdaptiveSolverView.refined` only adds original states; original cell costs/measure, point coordinates, pair admission and objective remain unchanged. A successfully completed second invocation of the unchanged cached V4 backend must repeat at least those `F` scores and at least one head for every previously reachable triple (`H`). Consequently `L=F+H` is a lower bound. Choose fallback **only if `L>R`**. An affordable old completed execution has actual second work `W<=R` and `L<=W`, so cannot satisfy this condition. Do not substitute `DenseBAdmission`'s conservative upper bound, potential triple counts, time remaining, heap estimates or probabilistic confidence for this proof.

For Drasov, `S=30566216`, `F=15141344`, `H=7570672`, `L=22712016`, `R=97433784`: UNKNOWN, original refinement proceeds and completed work remains 91018982. For Omice, `S=74006408`, `F=37818535`, `H=18909255`, `L=56727790`, `R=53993592`: PROVEN_UNAFFORDABLE. These values are test inputs, never dataset-specific runtime branches.

This guarantees **decision preservation under the stated backend/nesting contract**, not all possible Java/runtime behavior. Bit-exact native Drasov replay and tests remain mandatory fail-closed integration gates. A changed graph/energy/admission/kernel invalidates the proof and disables fallback; the plan does not certify general unseen-scene quality or unchanged wall-clock time.

## Source base, scope and file map

Implement only after approval in an isolated worktree based on **`6286150b1ec3c3acc8830fbae1205d9773505c4b`**. It contains the reviewed public runner. A planning-time `git diff --name-only 1b220943... 6286150...` found no differences in production `service/tracing/probabilistic`, `model` or `diagnostics/replay`. Recheck this and relevant finalization/Apply/UI differences before implementation; do not inherit the dirty main checkout. Record the chosen source and exact task delta in this plan's execution ledger. Native Drasov parity decides any integration uncertainty; do not normalize geometry to accommodate it.

In the file map below, `P` means the exact prefix `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`; `T` means `src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`. These abbreviations apply to every task path.

| Files | Responsibility |
| --- | --- |
| New `P/service/tracing/probabilistic/CompletedBPassWork.java`, `BRefinementBudget.java` | Immutable completed-pass counts, sound decision, bounded canonical decision witness |
| Existing `P/service/tracing/probabilistic/ProbabilisticInference.java` | Opt-in completed-pass observer; no changed solver arithmetic, state enumeration or allocation architecture |
| Existing `P/service/tracing/probabilistic/BPolicy.java`, `AdaptiveBInference.java`, `AdmittedBInference.java`, `AdaptiveSolverView.java`, `ProbabilisticTraceEngine.java`, `ProbabilisticProfileFactory.java` | Explicit new policy routing, same V5 initial/refined graph, first-graph decision and native metadata |
| New `P/diagnostics/replay/format15/Format15BRefinementBudgetReceipt.java`; existing `Format15ExecutionWorkCodec.java`, `Format15NumericalPolicyReceipt.java`, `Format15DenseBAdmissionReceipt.java`, `Format15ProductionBundleFactory.java`, `Format15ReplayRunner.java` in that directory | Bind new policy and actual decision/work; preserve old formats |
| Existing `P/service/tracing/ModernTracePipeline.java`, `P/service/quality/FinalGeometryEvaluator.java`, `P/actions/AlignWayAction.java` | Add fallback-only review reason and readable preview explanation without changing safe-path geometry or ranking |
| New tests listed below; existing `build.gradle.kts` and public test helper files only as named in Task 4 | Verification and explicit V6 runner; no default workflow activation |

Do **not** include slab allocation, cache reuse, lazy K enumeration, marginal approximation or weak-evidence localization in this change. Those alter more proof assumptions or incur unrelated risk. Allocation speed alone cannot solve Omice's 128M conflict. A later optimization needs its own exact-parity contract; this plan must stop if its bounded implementation misses the deadline.

Recommended execution: controller implements inline with TDD and one final independent Sol 6.1 substantive review. No per-task subagent/reviewer churn. Review scope includes full original requirements, all task commits and the final source/artifact identities.

## Task 1: Native completed-pass witness and sound refinement decision

**Files:** Create the two probabilistic files above; modify only the observer plumbing in `ProbabilisticInference.java`; create `T/service/tracing/probabilistic/V022RefinementBudgetProofTest.java` and `V022CompletedBPassWorkTest.java`.

**Interfaces:** New package-private `record CompletedBPassWork(long forwardBackwardScores, long kBestHeadScores, long scoredTransitions, long pairVisits)` validates nonnegative counts, `F+H<=S` and overflow. New public final `BRefinementBudget` has public nested `Decision { UNKNOWN, PROVEN_UNAFFORDABLE }`; package-private `static Decision assess(CompletedBPassWork first, long remainingScores)` is for the live solver only. Add package-private `ProbabilisticInference.CompletedPassObserver { void completed(CompletedBPassWork work); }` and an overload of existing `solveCachedVersion(List<InferenceProfile>, EvidenceModelParameters, TraceBudgets, MetricRegion, CancellationProbe, AttemptMemoryLedger.Owner, ProbabilisticExecutionPolicy.Mode, WorkProgress, CompletedPassObserver)`. Old overloads have no observer and no new witness allocation. The observer is per invocation, never static/thread-local.

- [ ] **Write RED tests.** `omiceLowerBoundProvesImpossible` asserts 56727790>53993592; `drasovRemainsUnknown` asserts UNKNOWN at 22712016 versus 97433784; `equalLowerBoundDoesNotFallback` asserts UNKNOWN at equality. `upperBoundCannotAuthorizeFallback` supplies an enormous dense upper estimate but a fitting actual completed small graph and asserts UNKNOWN. `invalidOrOverflowedWitnessIsRejected` checks negative, inconsistent and overflowing inputs rather than saturating into a fabricated proof.
- [ ] **Write native witness RED tests.** On small cached-V4 graphs compare captured `F` with `InferenceWorkAccounting.forwardBackwardScores()`, captured `S` with actual computed scores and `H` with independently counted reachable predecessor triples. Tiny exhaustive graph enumeration is the oracle; test sparse legal edges, ties, nonfinite/refusal and cancellation. Assert observer called exactly once only after a usable complete solve and never for resource/numeric/no-route outcomes. Run old exact-K tests with observer absent and compare all existing semantic/work results.
- [ ] **Run RED:** the common Gradle command below with `--tests '*V022RefinementBudgetProofTest' --tests '*V022CompletedBPassWorkTest'`; expect failure for absent APIs/assertions, not environmental errors.
- [ ] **Implement the witness and assess method.** Capture `messages.transitions()` and `MergedWorkCounter.headScores` directly after successful full solve, before its owner exits. Require mode `CACHED_EXACT_V4`, completed terminal proof and usable status. Charge the witness/holder through the passed owner before allocation; transfer only immutable scalar values. No reads from `ModernDiagnosticCounters` may authorize fallback. Keep hot-loop scores and cancellation checkpoints unchanged. Use checked integer arithmetic; UNKNOWN never means admitted/fits.
- [ ] **Run GREEN** for the two new classes plus `V022ExactKBestExecutionTest`, `V022ExactStreamMergeTest`, `V022ProbabilisticInferenceTest` and existing memory-owner tests selected by Task 4. Require zero skips and unchanged raw paths/energy bits/work.
- [ ] **Commit scoped files:** `feat: prove unaffordable B refinement from completed work`.

## Task 2: Explicit policy with an unchanged affordable path

**Files:** Modify the six routing/retention files in the map; create `T/service/tracing/probabilistic/V022BudgetedFrontierCompletionTest.java`; extend existing `V022SourceFrontierRetentionTest.java` and `V022AdaptiveBSolverTest.java`.

**Interfaces:** Add enum `BPolicy.INVENTORY_FRONTIER_BUDGETED_V6`. Define `BUDGETED_FRONTIER_VERSION = INVENTORY_FRONTIER_VERSION + "/proven-unaffordable-refinement-v1"`; full/compact execution versions use the existing full/compact cached-V4 suffixes and this new parent. Parameter-hash domain is exactly `live-probabilistic-inventory-frontier-budgeted-v6`. Add no default selection. Reuse the V5 retained-index hash domain because graph construction is unchanged; bind the distinct policy separately.

`BRefinementBudget` also defines public `Outcome { DENSE, NOT_NEEDED, ORIGINAL_REFINEMENT, FIRST_GRAPH_PROVEN_UNAFFORDABLE }` and public `record Witness(Outcome outcome, Optional<CompletedBPassWork> first, OptionalLong remainingScores, OptionalLong lowerBoundScores, String originalIndexHash)` plus public `String canonical()`, `static Witness parse(String text)` and `static Optional<Witness> fromExplanation(String text)`. Replay code uses these public outcome/canonical/parser APIs; completed-work internals remain package-private and are validated inside `BRefinementBudget`. Canonical form is one fixed-key `b-refinement-budget-v1|...` line, <=2048 ASCII bytes; missing values are `unavailable`, not zero. Parser rejects unknown/duplicate keys, negative counts, bad hashes, inconsistent decisions and overflow. This exported witness is evidence, never an input to the live decision.

- [ ] **Write RED policy tests.** Managed/visible V6 hashes differ from V5, V4/hash defaults remain exact, unknown domains reject, and explicit V6 rejects non-direct-B or non-Precise requests. `affordableV5AndV6MatchExactly` compares raw state sequence, rounded objective, all marginals, credible sets, refined indices and actual counts on complete two-pass public inputs. Include an intentionally loose upper-bound case to demonstrate no premature fallback.
- [ ] **Write RED nesting/fallback tests.** `refinedViewPreservesEveryOldCellAndCostBit` checks old original indices are a subset and point/unary/guide/measure/component identities remain exact for anchored/multimodal/missing/censored rows. `provedImpossibleReturnsOnlyCompleteFirstGraph` asserts one solver invocation, first-pass K32 completion unchanged, `refinements=0`, zero second-pass scores, conditional posterior unavailable for unconditional claims, and REVIEW_REQUIRED. A small injected backend seam may supply completed work for routing tests, but at least one small real native case must hit the condition; no synthetic receipt may satisfy qualification.
- [ ] **Run RED:** new completion class and expanded retention/solver classes. Preserve each meaningful failing assertion.
- [ ] **Implement V6 routing only.** Extend explicit inventory-policy checks in `ProbabilisticProfileFactory`, `ProbabilisticTraceEngine`, `AdmittedBInference`, `AdaptiveBInference` and `AdaptiveSolverView` so V6 uses V5's exact initial mask and owned-region preparation. Do not replace V5 comparisons globally. Dense-admitted execution stays identical and records DENSE. After a completed first solve, compute the existing centers/refinement-needed decision unchanged. If no refinement, record NOT_NEEDED. If requested, capture `R` from the original budget minus actual first scores and call Task 1 only under the fixed cached-V4 + original-state-superset contract. On UNKNOWN/equality/unavailable proof, execute the original refinement and record ORIGINAL_REFINEMENT; do not label it guaranteed affordable. On PROVEN_UNAFFORDABLE, checkpoint cancellation and return `finish(first, firstProfiles, 0, firstScores, 0, ...)` as conditional review, with FIRST_GRAPH_PROVEN_UNAFFORDABLE. Never construct/refine another graph, allocate the second kernel, or execute a second solve in that branch.
- [ ] **Preserve publication/ownership.** Recheck cancellation immediately before returning and throughout ordinary downstream finalization. If anything fails afterward, publish that native failure, not the completed pass. Keep owners live until native result materialization/transfer completes. Retain current actual-work receipt with first-pass counts only; lower bounds are never charged as actual work. Emit the witness line only for V6. Add `bBudgetedFirstGraphV1=1.0` to hypothesis diagnostics only on the actual fallback branch; normal V6 hypotheses must remain byte-equivalent to V5 except externally bound policy metadata. Do not alter alternative/truncation/ambiguity flags or supported geometry.
- [ ] **Run GREEN and failure-path tests:** cancellation before/after first completion, before decision, inside original second-pass preparation, during fallback finalization and after a newer attempt takes ownership; no cancelled route/Apply, no overwritten newer result, owner returns to zero. Missing work proof must continue old behavior, not fallback. Compare actual old V5 execution with unchanged existing tests.
- [ ] **Commit:** `feat: add explicit budgeted frontier completion policy`.

## Task 3: Truthful replay, review and Apply boundaries

**Files:** The six Format-15 files and new receipt in the map; `ModernTracePipeline.java`, `FinalGeometryEvaluator.java`, `AlignWayAction.java`; create `T/diagnostics/replay/format15/V022BudgetedFrontierReplayTest.java` and `T/service/tracing/V022BudgetedFrontierReviewTest.java`; extend existing `V022SourceFrontierPolicyReplayTest`, `V022ModernCandidateAdapterTest`, `V022ModernTracePipelineTest`, `V022PreviewStateTest`, `V022AlignmentJobCancellationRegressionTest` and applicable Apply command tests.

**Interfaces:** New package-private `Format15BRefinementBudgetReceipt.ARTIFACT = "b-refinement-budget.json"`; `static boolean required(String parameterHash)`, `static Format15Artifact artifact(Format15Bundle bundle, FrozenReplayInput input, TraceHypothesisSet run)`, `static void validate(Format15Archive archive, FrozenReplayInput input, String inputHash)`, `static void validateActual(Format15Archive archive, List<TraceHypothesisSet> actual)`. Follow `Format15DenseBAdmissionReceipt`'s existing binding pattern. Schema 1 has exact keys `schema`, `sourceIdentityHash`, `inputHash`, `parameterHash`, `executionWorkHash`, `witness`, maximum 16 KiB. JSON encodes the bounded canonical witness string, not route coordinates.

- [ ] **Write RED replay tests:** V6 dense, no-refinement, affordable-refinement and fallback native round trips; managed/visible binding; each old-policy capture unchanged. Missing/tampered witness, forged arithmetic, wrong retained-index hash, V5 work relabeled V6, V6 work downgraded V5, or changed source/input hash must reject. A self-consistent forged file cannot pass `validateActual` against the native rerun. Cancellation/unattested archives stay diagnosable and cannot claim successful numerical replay.
- [ ] **Write RED preview/Apply tests:** fallback safe final route requires explicit session-local confirmation and displays the copy below; a topology/assignment/source failure stays hard blocked. Changing source coordinates, occurrence sequence, dataset membership, candidate or attempt sequence invalidates confirmation. Off-EDT Apply still rejects; Undo/Redo/rollback retain exact authority. Never infer review status solely from a display string.
- [ ] **Run RED** for new replay/review classes and affected existing tests.
- [ ] **Implement receipts:** whitelist V6 full/compact versions in existing execution/numerical policy readers and add V6 to dense-receipt requirement. Export the new companion only for V6 native runs carrying actual accounting and a valid witness. Validate presence, hashes, arithmetic, outcome and actual recomputed witness at the same replay boundaries as dense admission. Existing schema/whitelists otherwise remain unchanged; cancelled absence stays unavailable. Preserve original state census and native receipt counts. No runtime decision reads archive witness fields.
- [ ] **Implement fallback-only review meaning:** append `FinalGeometryEvaluator.FindingCode.REFINEMENT_NOT_PERFORMED_BUDGET` with REVIEW severity to the ordinary final evaluation when the native fallback marker is exactly 1.0; preserve HARD_BLOCKED dominance and all other findings. No marker is added to normal V6/V5. In the existing modeless review reason rendering in `AlignWayAction`, use: **“Refinement could not fit within the work limit. This preview uses the completed initial search and requires review.”** Keep diagnostics export accessible. Preserve normal candidate ranking; the new finding must not add a numerical ranking penalty/bonus or alter geometry. Standard review confirmation binds the actual new immutable plan hash.
- [ ] **Run GREEN** and assert all four completed outcomes use actual output identities. Check a plan with identical before/after state but changed parameter hash gets a new confirmation identity; old confirmation is rejected. Do not compare entire capture ZIPs for geometry parity.
- [ ] **Commit:** `feat: attest budgeted completion and require explicit review`.

## Task 4: Public behavior and exact Drasov preservation gates

**Files:** Create `T/service/tracing/probabilistic/V022BudgetedFrontierQualityTest.java`, `T/diagnostics/replay/format15/BudgetedFrontierParity.java` and `V022BudgetedFrontierParityTest.java`; add `WideBudgetedFrontierRetentionMain.java` beside existing `WideFrontierRetentionMain.java`; extend the existing shared `WideCompactRetentionFlow.java` only with the explicit V6 policy whitelist/input-identity binding. Add an explicit `v022WideBudgetedFrontierRetention` task in `build.gradle.kts`, copying current runner resource limits without changing V4/V5 tasks. Private helper copies/outputs belong under a new `B_ROOT/budgeted-frontier-v6` folder at implementation time only.

**Interfaces:** New test helper `BudgetedFrontierParity.assertPreserved(Path baselineDirectory, Path candidateDirectory, boolean parameterPolicyChanged)` validates pinned input/artifact bindings and exact semantic parity. Its test-only `normalizeParameterForComparison(AlignmentEditPlan candidate, String baselineParameterHash)` reconstructs a plan changing only `parameterHash`; all other fields must match. No normalization helper is production code. New public main uses the same fixed six scenes, fixed manifest and common acceptance method as `WideFrontierRetentionMain`, with typed V6 transformation; no caller-selectable case omission, tolerance or scene-label override.

- [ ] **Write RED quality tests:** public split/rejoin channels, two separated sustained corridors, weak coherent bend, strong hairpin, incomplete/censored support, protected endpoints and strict-budget boundary. Compare eligible first-graph output against the exact original first graph: identical K ordering, branches and objective, no mode fusion or flattening. Assert conditional/truncated status when diversity is unresolved. Existing absolute-quality limits apply; no fallback quality allowance is invented.
- [ ] **Write RED comparator tests:** reject one changed raw/final coordinate bit, state index, path order, branch, support ownership, assignment occurrence/id/coordinate, protected anchor, permissions, before/after primitive membership, tags/references, topology finding or cleanup status. Reject changed input bytes beyond the one explicitly typed numerical-policy transformation. Permit only source/runtime/policy/receipt identity changes; recompute each expected native identity instead of deleting arbitrary JSON fields. A parameter-only normalization must reproduce baseline frozen plan bytes/canonical hash; the candidate's real new plan remains unmodified and differently hashed.
- [ ] **Run RED, implement helpers/runner, then GREEN.** Exact Drasov comparator requires the table's CSVs, raw/final JSON and final-output component bytes to match, before/after network hashes above to match, all 32 raw path sequences/energies to match, unchanged refined indices and all quality findings to match. It must assert two-pass execution, no fallback marker/reason, total scores 91018982 and pair visits 639864. Pin the already accepted files rather than regenerating expected data from the candidate. No private coordinates/bytes go into committed tests or public CI.
- [ ] **Run the public regression suite**, then the unchanged V4/V5 gates and explicit V6 six-scene runner under their existing resource/time limits. Existing V5 diffuse-three-strand is a known FAILED absolute-quality case. Record it honestly. All required public acceptance must pass before the private qualification sequence below; this plan does not repair that objective-semantic blocker or permit reclassification as PASS. The unchanged default and experimental implementation can be reviewed, but full task qualification remains blocked until the separate approved quality issue is resolved.
- [ ] **Prepare pinned private qualification after public acceptance.** Copy the existing reviewed `B_ROOT/frontier-v5-helper-prep/frontier-v5-policy.sh`/qualification helper into the new private folder; permit only typed V6 input transformation, actual run outputs and the comparator. Audit its source/class/runtime/input hashes before execution. For high case preserve input source `46f0ad8c51133f6143f4dbff239c2184bd60576073a7194e30c0edc9acbc9b6b`, accepted geometry SHA `0d1f0233fd1e28dc7f441c2dfa0bb2e4202361ef9ab9aec9769e9b24a86d01e1` and native path-order SHA `779c733b2ac7f81ccfaef66224269da1478c7dbbc99434f0059d79ada745abd3`; preserve existing high exact-retention assessment/reference scope, without inventing physical truth.
- [ ] **Run one pinned Drasov V6 comparison and the existing bounded high-retention check**, only when separately cleared by the public gates. Use the unchanged capture derivations and budgets. Native Drasov semantic comparator must PASS exactly; any mismatch blocks integration immediately. Do not accept visually similar geometry, modify expectations or add a case-specific fallback exclusion. If old V5 itself fails reproducibility on the new host, diagnose environment/source as a blocker rather than weakening the comparator.
- [ ] **Commit public code/tests only:** `test: gate budgeted completion on exact preserved geometry`. Keep private evidence local and report its hashes/status in the execution ledger.

## Task 5: Whole-task review and one Omice evaluation

**Files:** This plan's execution ledger and private `B_ROOT/budgeted-frontier-v6` evidence only; scope fixes belong to their owning task files/tests.

- [ ] **Verify the final stable delta:** no unrelated changes, default promotion, changed objective/lattice/radius/caps, hidden retry, Legacy/A edits, disabled tests or new waiver. Run the full public test suite and `git diff --check`; validate Gradle XML has zero unexpected failures/skips. Reuse valid earlier evidence unless changes invalidate it. Verify accepted Drasov semantic artifacts against the exact final source/runtime, not an earlier binary.
- [ ] **Obtain one independent Sol 6.1 review** of the complete task delta and native evidence. Focus on the lower-bound proof, observer/owner lifetime, unchanged affordable branch, source/parameter/receipt binding, cancellation/stale state, final safety and exact Drasov comparison. Reviewer does not edit code. Repair substantive findings and rerun affected checks; no automatic second Astra review.
- [ ] **Authorize exactly one Omice V6 evaluation only after required public/high/Drasov gates pass.** Reuse the original frozen Omice authority and settings; transform only typed policy. Pin old derived input `8459ca7357e7724fbb07db8500d47d01dfdfa9abf0ebd29627af5b5e3fc86136` and original input `d4fd31a430ba85c141e863ca99e020249cc32ebe2d72500b109c19feeb8a5cb4`; bind the new derived hash before execution. Keep Java 17, 512 MiB, 100-second cooperative deadline, 120-second outer timeout and the shared lock. Record monotonic native time and process timestamps separately. Never rerun unchanged failure or start a parameter sweep.
- [ ] **Evaluate go/no-go:** require completed actual work, expected FIRST_GRAPH_PROVEN_UNAFFORDABLE decision, no second solver/kernel entry, unchanged first graph/native K/branch data, owner-zero release, final geometry/assignment/authority checks, an inspectable REVIEW_REQUIRED route with no hard blocker, and native pipeline <=100000 ms within all original budgets. Export actual geometry beside the original, with the uncertainty and review reason visible. User inspection of marked forks is additional shape evidence, not a substitute for safety/absolute-quality gates. If no route, unsafe geometry, mismatch, cancellation, missing attestation or deadline failure occurs, STOP and report exact status. No automatic allocator/solver redesign follows.
- [ ] **Close honestly:** distinguish implemented/tested experimental behavior, exact Drasov preservation, Omice result and still-open six-scene/default/speed/release requirements. Publish nothing. A known diffuse failure means no overall qualification or promotion; a one-case completion means no ordinary speed claim.

## Verification commands and execution ledger

All commands below are **planned**, not executed while preparing this document. In the isolated implementation worktree use the existing pinned host dependency and one worker:

```sh
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk \
flock /data/data/com.termux/files/usr/tmp/josm-drasov-gradle.lock \
sh gradlew --no-daemon --max-workers=1 \
  -PjosmVersion=19555 \
  -Pjosm19555HostJar=/data/data/com.termux/files/usr/tmp/v022-official-josm19555-host/JOSM-Linux-java11-19555.jar \
  test --tests '*V022RefinementBudgetProofTest'
```

Replace the final test selector with each task's explicit list; omit selectors for the full public suite. Native six-scene tasks are `v022WideCompactRetention`, `v022WideFrontierRetention` and new `v022WideBudgetedFrontierRetention`, run through the existing reviewed clean-host gate procedure when authorized. Confirm runtime availability and host pin before any test; an environment failure is not RED. Private helper execution commands must be frozen with their reviewed new source/runtime receipt before authorization; do not repurpose the old V5 wrapper to mislabel V6.

At each milestone record source commit/diff, exact commands, test counts, artifact hashes, native statuses, outstanding requirements, owner and next safe action here. Weekly allowance telemetry is unknown unless surfaced; reserve capacity for Task 5 and repairs. Current ledger: **planning only; Tasks 1–5 not started; no tests/native runs; no integration/release claim**.

## Plan self-review and unresolved prerequisites

- Coverage: sound decision/counter oracle → Task 1; policy/retention/affordable-path identity → Task 2; replay/UI/cancel/stale/Apply → Task 3; public/high/Drasov preservation → Task 4; exact-source audit and bounded Omice result → Task 5. Every Review Focus item has explicit tests.
- Type consistency: new witness/callback/decision APIs are defined here; existing `conditionalReview`/`finish`, native work and replay patterns are reused. `UNKNOWN` never authorizes fallback or asserts a fit. `CompletedBPassWork` is live-produced and cannot be reconstructed from diagnostics to authorize execution.
- Scope: no numerical optimizer/allocator rewrite, no weak-evidence tuning, no runtime dataset whitelist. Only the proof-triggered branch changes output; acceptable old executions follow old behavior. Minor plumbing overhead still needs actual deadline and memory checks.
- Prerequisites: user approval to implement; verified isolated source base and dependency pin; immutable private artifacts accessible; independent final review; a resolution of the existing public diffuse-quality blocker before full private qualification. No successful Omice shape is currently available, so a small quality impact cannot be promised.
- Guarantee: Drasov's fallback-ineligibility is structural under fixed backend contracts; its final geometry/authority preservation is an exact, pinned, fail-closed integration condition. No claim of universal certainty is made before implementation and native verification.
