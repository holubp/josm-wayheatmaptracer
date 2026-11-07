# Execution ledger: 2026-11-07-omice-v5-budgeted-completion.md

Approved implementation, Tasks 1-4 public scope; parent owns private qualification and final review.
Base: 6286150b1ec3c3acc8830fbae1205d9773505c4b, clean isolated worktree verified.
Production probabilistic/model/replay paths have no difference from pinned 1b220943 baseline.
Weekly telemetry and effective child runtime model metadata unavailable.
Interfaces: Task 1 native scalar witness feeds Task 2 decision; Task 2 canonical witness feeds Task 3 replay receipt. Task 4 consumes actual native output and policy identity.
Known public diffuse-three-strand quality blocker remains; no private inference authorized here.
Task 1: in progress. Native completed witness must be charged before allocation and emitted only before a usable complete return.
Task 1: initial proof/native tests RED absent APIs (21 compile errors); GREEN 78 tests, zero failures/skips, including exact K/merge/inference and memory neighbors. Command: pinned-host Gradle test selectors V022RefinementBudgetProofTest, V022CompletedBPassWorkTest, V022ExactKBestExecutionTest, V022ExactStreamMergeTest, V022ProbabilisticInferenceTest, V022ProbabilisticMemoryTest, V022AttemptMemoryLedgerTest. Commit 4ec4e990.
Task 2: RED absent explicit V6/witness APIs (10 compile errors); first GREEN routing/parity/native fallback run passed. Added nesting and cancellation/finalization tests for the next verification pass.
Task 2: Finalization RED independently confirmed the missing new finish checkpoint (finish probe returned saved pass). Added V6-only checkpoint before/after conditional materialization; GREEN pinned-host Gradle targeted Task 2/3 + source-frontier replay, adapter, pipeline, preview and job cancellation suite (build/v6-task23-green.log).
Ruling: The original materialize-stack cancellation oracle never reached a normal-mass checkpoint in inherited code and tested a nonexistent callback location. Replaced it with (1) actual completed native observer cancellation, (2) completed invocation progress cancellation before the decision, (3) a direct new fallback finish probe and (4) original refinement preparation cancellation. All assert thrown cancellation and owner-zero release, retaining the same no-salvage requirement; old arithmetic and materialization loops remain unchanged. The inherited normal-mass materialization gap is not expanded here; V6 now checks at native completion and adaptive finalization.
Task 3: RED 12 absent receipt/review API compile errors; GREEN native dense managed/visible round trips, missing/tampered witness and runtime binding, review-only finding/hard-block dominance and existing UI/Apply-adjacent regressions. Further public receipt/plan identity tests and Task 4 scaffolding follow.
Ruling: git requires safe.directory for this shared-storage worktree; use command-scoped -c safe.directory=* without modifying user configuration.
