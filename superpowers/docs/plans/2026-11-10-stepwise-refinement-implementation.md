# Stepwise Legacy Refinement Implementation Plan

> **Current execution — 2026-11-22:** use the approved
> [completion plan](2026-11-22-g004-workflow-completion.md). Execution is ACTIVE;
> implementation and release qualification remain partial. The Nov22 amendment
> restores configurable Legacy/A/B starts and repeated A/B/Smoothen operations,
> including after smoothing, modeless hide/reopen review, cached Undo/Redo and
> menu/shortcut-only diagnostics. Conflicting Legacy-only requirements below
> are historical. Preserve original Tasks1–8 and unwaived safety gates; do not
> restart verified work or begin numerical research.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for coupled implementation. The controller may delegate isolated, explicitly bounded tasks through superpowers:subagent-driven-development; do not create a fresh worker/reviewer for every step. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace automatic Hybrid with user-selected, reversible preview-only A/B refinement of Legacy results, preserving both alignment modes and delivering safe interval fallback and a simpler menu.

**Architecture:** One immutable original-OSM authority and one Legacy-root review session own a linear stack of immutable stages. An explicit refinement operation binds the selected parent, samples factual imagery, runs the production A/B adapter in the unchanged effective mode, assembles parent-backed results and reassesses the exact final geometry. Only final Apply mutates OSM.

**Tech Stack:** Java17, JOSM19555, Gradle/JUnit5, Swing modeless UI, existing production A/B and Legacy services, plugin tile coordinator and Format-15 diagnostics.

**Spec:** [Approved specification](../specs/2026-11-10-stepwise-refinement-design.md), including its eight binding Astra corrections. [Goal G004](../goals/2026-11-10-g004-stepwise-refinement.md) is the scope/status record. Design approved by user on 2026-11-10. This document is a plan, not execution or publication authorization.

## Current execution authorization — 2026-11-19

User selected G004 as the active goal, with completion/checkpoint by10%weekly
allowance. Begin from committed d1570b7f9a6a21c7099c60fd062411e82b74aca9 in
build/worktrees/g004-stepwise-20261119, branchcodex/g004-stepwise-20261119;
this reconciles the RC15 base and subsequent committed A fixes, excluding held
G006 prototypes. Ledger: build/g004-execution-20261119/ledger.md. The previous
wait for G006 release is superseded for independent G004 engineering; smoothing
qualification remains required and cannot be represented by an applicable stub.
Latest weekly telemetry19%available; recheck after coherent batches and settle
workers/test evidence/checkpoint before10%. No scope reduction or release waiver.

Task1 interface ruling: Candidate uses explicit bounded RefinementPayload
snapshots to defend mutable screen points/images/mosaics. Shared payload identity
is explicit, no global cache; size checks precede copying. Live Node/Way handles
are identities, with original sequence/coordinate values frozen separately.
Fallible oversized-history creation leaves the existing Legacy preview/Apply
available to its caller. StageKind and smoothing lineage distinguish current
owner/choice from the pinned unsmoothed input during strength replacement.

Task2 reconciliation: use the concrete [parent-reference contract packet](../../../build/g004-execution-20261119/task2-contract.md).
An additive, binder-validated ParentTraceReference must reach native A/B entry,
A source-protection/endpoint checks and final reconstruction. Retain historical
TraceRequest decoding and existing LegacyB restrictions. The packet's production
four-cell and exact-row-mapping tests are required; sampling a parent curve alone
does not implement this task. Replace Task1's opaque authority handle with typed
detached root authority when implementing this consumer. Latest checked weekly
availability18% at2026-11-19T22:07:16.022Z; the10% checkpoint threshold is unchanged.

## Binding Legacy-only first pass — 2026-11-19

Legacy alone starts new live sessions. A/B are explicit review refinements only;
remove the primary-method selector and standalone/Hybrid launchers. This replaces
earlier plan requirements to preserve standalone A/B/Image live choices. Keep
their internal adapters, identities and versioned replay compatibility. Preserve
Precise/Move defaults/shortcuts and all unrelated source/protection preferences.
Optional smoothing can follow Legacy or any eligible A/B descendant; Medium and
Strong use the same pinned unsmoothed parent, never cumulative hidden smoothing.
Task1/5 must test successful strength replacement, cancelled/failed replacement
retaining the existing valid choice, and exact Back without adding another stage.
Task7 must remove the selector, migrate all obsolete primary choices to live
Legacy, preserve factual historical decoding, and avoid modal settings notices.

User-confirmed workflow tests are mandatory: `Legacy→Apply` without running
anything else; `Legacy→Smoothing`; `Legacy→A→B`; `Legacy→B→A`; repeated A/B and
optional smoothing. Task1/5 tests must walk Back more than one level, restoring
exact stage geometry, candidate and palette (e.g. B hot→A gray→Legacy blue), then
choose another palette and refine it. Candidate selection must not rerun an
engine or blend palettes. Failed/cancelled refinement and unavailable A/B must
not disable an otherwise valid Legacy Apply. Task6 must prove every intermediate
preview leaves OSM/history unchanged and final Apply alone creates one command.
Keep all active ancestor versions; refuse another stage at a limit rather than
evicting Back history. New successful work after Back replaces forward history.

## Required smoothing amendment — 2026-11-19

**Superseding user decision, 2026-11-21:** integrate the existing visually
reviewed Gaussian Medium/Strong method for G004. Improved turn/switchback and
new ribbon/DTW/cubic-versus-fairing research belongs to G008. Preserve factual
source, ownership, final geometry safety and S01–S06/S07 hard safety; a gallery
is not Apply authority. The older numerical tournament prerequisites below
are historical where they conflict. No Legacy/A/B optimization is authorized.

Concrete execution detail is now frozen in
[`2026-11-21-g004-qualified-smoothing.md`](2026-11-21-g004-qualified-smoothing.md),
after independent planning review and its two canonical clarifications. It
preserves this whole-goal plan and S01–S07; planning approval is not numerical
qualification or permission to adopt the held G006 prototype. Follow its public
qualification and integration dependencies before enabling Smooth.

User-requested specification S01–S07 is now part of G004 scope. Preserve all
existing tasks; this is an additive stage operation, not a new automatic Hybrid.
No current smoothing prototype is production-qualified. Stage eligibility and
noncompounding strength replacement are resolved by the Legacy-only amendment;
qualify G006's turn-preserving algorithm before calling its adapter implemented.

- Task1: add an explicit smoothing operation/stage identity, pinned selected
  parent, bounded actual-work ownership and reversible stage selection. Test
  cancellation, late publication, failed refinement and Back without data edits.
- Task2: add a bounded smoothing adapter alongside A/B, consuming the exact
  Legacy/A/B parent and factual capture frame. Test both modes, Legacy's absent
  proposed-node map, retained/protected original occurrence correspondence and
  complete rebuilt final assignments. Do not manufacture A control provenance
  for Legacy or use another candidate's evidence. Do not run tracking to smooth.
- Tasks3–4: bind existing factual selected-parent imagery/coverage; smoothing
  does not silently request five new palettes or expand root edit authority.
  Retain unsupported/frozen interval geometry and reassess the exact assembled
  output. Resource/cancellation failure does not allocate another hidden pool.
- Task5: provide explicit Medium/Strong controls after eligible selected stages,
  retain the unsmoothed choice, preserve Back and accessible modeless UI; no new
  top-level smoothing launchers. Test a non-top Legacy palette, A/B descendants,
  mode preservation and the resolved strength-change semantics.
- Task6: export exact parent/strength/capture/work/result identities, keep
  failure diagnostics accessible, and prove one final Apply/Undo/Redo over the
  complete chain. Repeat final topology/source/assignment/stale checks.
- Tasks7–8: include smoothing in menu-cleanup parity and whole-delta review;
  retain useful wrinkle improvements and accepted Drasov geometry while testing
  every marked turn/switchback/beak case and rejection of new self-intersections.
  A green adapter stub or unvalidated geometric preview is not completion.

## Global constraints

- Use committed d1570b7f9a6a21c7099c60fd062411e82b74aca9 as the reconciled descendant of released RC15 `111e5027c4d09e8a8ef672c2c58045edc0ce2663` in the isolated G004 worktree. Never inherit the dirty historical root or unqualified G006 prototypes wholesale.
- Keep Legacy mathematics, accepted Drasov geometry and production A/B objectives unchanged. Change their live orchestration only: Legacy starts, A/B refine. Retain Image/internal engine IDs for historical replay, not as live starting choices. Do not adopt G002's coarsened-model helpers or remove image/intensity-source settings.
- Effective mode = settings plus invocation override, frozen at root: both Precise Shape and Move Existing Nodes must genuinely work. No silent switch and no engine-only stub.
- Every stage has the same original selected occurrence range/edit authority. Protect tagged/shared/relation-constrained nodes and frozen vicinities. Respect opt-in junction movement; descendants freeze authorized parent boundary positions and cannot accumulate displacement beyond original authority.
- All computation is preview-only. One final immutable edit plan, Apply and Undo entry; retain existing stale Redo/upload deletion/history semantics. No temporary dataset mutation to make an engine reference.
- Preserve native maximum96 states/profile, 8,000,000 pair visits, 128,000,000 scores, raw32/distinct8 and existing memory/source limits. A refinement operation shares remaining work/deadline across palettes, whole attempt and fallback intervals. No hidden per-interval pool, retry, wider search or larger solver cap.
- First-pass settings, managed source generation and original collision authority remain bound through the session. Extra imagery never expands edit permission. Palette variants and aggregate follow their actual source contracts.
- Existing previews remain modeless; preserve accessible failure exports and button layout. No credentials, private traces or private tiles in public CI.
- Use Sol for contracts/integration; Luna for isolated mechanical tests/UI once interfaces are frozen. Reuse the Astra design review; do not automatically request more Astra. One independent whole-delta Sol review before delivery, with affected re-review of repairs.
- Do not execute G002 or change another saved goal's status. Update G004 and this plan's execution evidence only.

## Concrete bounds and scheduling

Implement `RefinementLimits` with maximum8 retained stages **including root**, maximum32 retained review candidates per stage, and maximum64MiB owned retained-history payload. Charge immutable geometry, assignments, provenance and retained image buffers once by identity; managed cache limits remain separate and cannot be described as history ownership. Legacy root execution retains its existing geometry/work behavior; limits apply when retaining refinement history. Oversized root/history refuses refinement clearly without disabling an otherwise valid root Apply.

These are retention limits, not extra solver budgets. A new explicit click allocates one existing bounded engine-work operation; stage fallback/palettes consume its remaining budget. Keep cumulative session actual-work receipts. Never drop preceding stages to make another refinement fit. Refuse another stage at the history limit and preserve Back and Apply. Pin retention estimates conservatively before allocation; partial allocation rolls back.

Canonical palette traversal: the pinned parent's actual base palette first when identifiable, then remaining `hot`, `blue`, `bluered`, `purple`, `gray` in that order; requested variants/aggregate follow in existing stable detector order. Each palette tries whole range before its eligible intervals in original occurrence order. Stop starting work on native resource/deadline refusal. Reserve assembly/assessment/export capacity before engine entry using sound bounds; if unavailable, refuse entry rather than spending the final budget then publishing unassessed geometry. Export all requested/attempted/unattempted sources and actual work. A finite budget does not promise that all requested palettes finish.

For retained review alternatives, admit one usable candidate from every completed base palette before filling remaining32 slots in deterministic palette/native order. Preserve alternate-palette provenance when identical geometry is deduplicated. Do not compute Cartesian products of interval hypotheses. Use a primary assembled candidate and bounded explicit single-interval replacements, each independently assessed. Engine objectives cannot compare palette/engine quality; use the approved common assessment and no refinement bonus.

## Review focus and owning tests

1. A removed Precise occurrence becomes a trimmed fallback boundary: exact edge subdivision, not nearest-point guessing (Task2).
2. A→B partially fails: fallback preserves selected A geometry, including previously refined sections and authorized junctions (Task4).
3. Cancel/restart on the same parent or Back while late results arrive: exact operation identity prevents stale publication (Tasks1/5).
4. Whole-range B spends the budget: no fresh fallback/palette pool, false partial Apply or lost failure export (Tasks3/4/6).
5. Move raw solver geometry cannot be represented by existing nodes: inspectable blocked output, no hidden insertion or mode switch (Tasks2/7).

## File map and interfaces

Prefixes: `P=src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`, `T=src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`. New orchestration lives in `P/service/refinement/`; do not add this state machine to the already large `AlignWayAction`.

| Component | Named files/responsibility |
| --- | --- |
| Session model | New `RefinementModels.java`, `RefinementLimits.java`, `RefinementSession.java`: immutable stages, choices, linear history and operation tickets |
| Reference/mode | New `ParentReferenceBinder.java`, `RefinementEngineAdapter.java`, `CorridorRefinementAdapter.java`, `ProbabilisticRefinementAdapter.java`: original authority plus selected parent, both modes |
| Source | New `RefinementSourcePlanner.java`; adapt `service/LegacyBSourceCapture.java`, `LegacyManagedProfileSampler.java`, `LegacyVisibleProfileSampler.java` only for factual parent-centered sampling reuse |
| Fallback | New `RefinementIntervalPlanner.java`, `RefinementPipeline.java`, `ParentBackedComposer.java`; reuse `JunctionSegmentSelector`, `LegacyBOccurrenceReconstruction`, `LegacyBAssessor` where their contracts match |
| UI | New `ui/RefinementControls.java`; adapt `actions/AlignWayAction.java`, `service/PreviewSessionController.java`, `ui/PreviewReviewState.java` and existing section panel/model |
| Export | New `diagnostics/replay/format15/RefinementLineageCodec.java`; adapt existing export/bundle/replay entry points after inventorying their RC15 signatures |
| Menu/preferences | `WayHeatmapTracerPlugin.java`, `model/TrackerMode.java`, `config/PluginPreferences.java`, `ui/HeatmapSettingsDialog.java` |

Define these nested immutable public records/enums in `RefinementModels` before dependent work:

- `SessionId(UUID value)`, `StageId(UUID value)`, `CandidateId(String value)`.
- `ParentArc(int originalOccurrence, int parentEdge, double fraction)` with finite fraction in[0,1], canonical vertex ownership and monotonic traversal validation.
- `Stage(StageId id, Optional<StageId> parent, Optional<CandidateId> parentChoice, TrackerMode engine, List<Candidate> candidates, CandidateId selected)`; lists/maps copied, unique IDs, selected membership required.
- `Candidate(CandidateId id, AlignmentResult result, List<ParentArc> correspondence, String immutableIdentity)` retains existing final geometry/assignment and evidence contracts; do not fabricate a modern edit plan for Legacy.
- `OperationTicket(SessionId session, long sequence, StageId parent, CandidateId choice, TrackerMode engine, AlignmentMode mode)`.
- `Outcome { COMPLETE, PARTIAL, NO_USABLE_RESULT, CANCELLED, STALE, RESOURCE_LIMIT, FAILED }`.

`RefinementSession` owns immutable root selection/config/source identity and existing authority handle, mutable EDT-selected history cursor, operation sequence and terminal state. It exposes `select(CandidateId)`, `Optional<Stage> back()`, `OperationTicket begin(TrackerMode)`, `boolean publish(OperationTicket, Stage)`, `cancel(OperationTicket)`, `close()` and `terminalizeForApply()`. Publication checks root eligibility, sequence, current parent/choice and nonterminal owner atomically. Engine adapters never modify history or OSM.

## Task1 — Session ownership and linear history

**User amendment S08 (2026-11-20):** expose explicit preview Undo/Redo, keeping
Back as the existing Undo API/alias. Add behavioral RED then GREEN for cached
multi-stage round trips restoring candidate/palette/stage settings/findings,
root/end boundaries, confirmation reset, no inference/acquisition/OSM changes,
and no navigation during conflicting work or after terminal closure. Selection
alone and failed/cancelled replacement retain forward history; successful new
publication replaces it. Charge forward/pinned stages under the existing limits.
Redo restores factual stored child lineage even if an earlier stage's selection
was subsequently changed. Old Back-only tests do not establish S08 completion.

**Files:** new session/model/limits classes; `T/service/refinement/RefinementSessionTest.java`, `RefinementHistoryOwnershipTest.java`. Extend `PreviewSessionController` only for an explicit long-lived root owner separate from per-operation sequencing; keep old APIs compatible until callers migrate.

- [ ] RED: every normal alignment starts Legacy, regardless of old saved engine choices; root permits A/B and descendants permit A→B/B→A. Constructed invalid/historical non-Legacy roots refuse new refinement with a typed reason. Settings mode default plus each explicit Precise/Move override is preserved. Back at root is empty; child Back restores parent selection and clears confirmation. New refinement after Back replaces forward history only on successful child publication, not merely on starting/cancelling a job.
- [ ] RED: latch/controlled-queue tests for cancel/restart on the same parent, changed choice, Close, Apply and late publication. Cancel retains parent, Back while running refuses, terminal Apply admits exactly one command request. No sleeps.
- [ ] RED: stage/candidate/payload limits, shared immutable-buffer accounting, failed reservation rollback and no earlier-stage eviction. Neither refusal nor Cancel loses root Apply.
- [ ] Implement immutable snapshots and monotonic sequence ownership; release discarded-forward payload after workers cannot publish it. Clear stage-local confirmations on candidate/history changes. Keep failed-operation identity separately from successful stage identity.
- [ ] Run focused tests GREEN, then existing `*V022PreviewStateTest` and `*ManagedModernPreviewLifecycleTest`; record counts/commands, inspect no leaked owners, `git diff --check`, commit this coherent model.

## Task2 — Parent authority and real A/B adapters in both modes

**Files:** binder/adapters plus `T/service/refinement/ParentReferenceBinderTest.java`, `RefinementEngineAdapterTest.java`; extend existing `LegacyBOccurrenceReconstructionTest`, `LegacyBReferenceFactoryTest`, `LegacyBParentAdmissionTest` without weakening old checks.

**Interfaces:** `ParentReferenceBinder.bind(originalSelection, frozenRootConfig, Candidate parent, originalAuthority)` returns an immutable `BoundParent` with effective mode, exact parent polyline, complete original-occurrence correspondence and source/authority identities. Use existing concrete RC15 types for selection/config/authority; record their selected signatures in the first execution checkpoint before editing. `RefinementEngineAdapter.engine()` identifies A/B; `run(BoundParent, acquiredEvidence, remainingBudgets, cancellation)` returns the actual native result, actual work, proposed mode-representable candidates and typed failure. No stub result or test-only approximation.

- [ ] RED: a non-top Legacy palette candidate is the exact reference; selecting a different parent changes identity. Root permissions remain original, while previously authorized moved parent junctions stay frozen. Repeated descendant offsets cannot walk outside original authority.
- [ ] RED: Precise removed interior occurrence at a trimmed cut maps to an exact parent edge subdivision; ambiguous/missing mapping freezes only that island. Tagged/shared/relations, repeated source occurrences and stale geometry fail closed. Stage IDs prevent inserted-point aliasing across stages.
- [ ] RED: Move preserves exact ordered original IDs/count, endpoints/protections, and produces actual node assignments. Evaluate reconstructed existing-node geometry; an unsafe/unrepresentable result remains blocked. Precise supports permitted insertion/removal and cleanup semantics independently.
- [ ] Add adapters around actual production Corridor A and B. Do not generalize the B-only `LegacyBReferenceFactory` by removing its safety conditions. Explicit whole-range eligibility refusal records nativeCalls0 and permits only contained interval fallback. Require source-specific capabilities for mode/intensity before inference.
- [ ] GREEN adapter tests must include real small production-engine cases in each A/B×Precise/Move cell, not only mocks. Neighbor assignment/reconstruction/parent-admission tests pass; Legacy source hashes unchanged. Commit binder/adapters separately from UI.

## Task3 — Explicit sources and parent-centered acquisition

**Files:** source planner and bounded sampler/capture adaptations; `T/service/refinement/RefinementSourcePlannerTest.java`, `RefinementAcquisitionTest.java`.

**Interfaces:** `RefinementSourcePlanner.plan(OperationTicket, rootSourceSettings, Candidate)` produces immutable ordered source requests and explicit unavailable combinations. Each acquired frame binds session/operation, source generation, palette/intensity mapping, parent transform, physical sampling scale and original authority. Reuse plugin-owned coordinator transport; do not make UI paint acquire evidence.

- [ ] RED: five separate managed base schemes requested even when the selected parent uses one; configured alternatives remain detector variants and configured aggregate requires all bases in one frame. Selected parent palette affects order, not permission to silently omit others.
- [ ] RED: visible-only cannot pretend other base schemes were acquired; existing alternative mappings remain distinct. Visible direct intensity reports its unsupported limitation modelessly, without replacing the requested intensity source or disabling parent Apply.
- [ ] RED: a bent/moved parent requires fresh profiles around its geometry; original-centered rows cannot be reused. Off-raster corridor/halo and incompatible metric transform refuse. Source-generation invalidation/auth failure is attempt-wide; ordinary unavailable palette keeps truthful per-source status.
- [ ] Implement required imagery acquisition within unchanged physical width/generation/collision bounds. Validate transform and factual metres independently of projection pixels. Deterministic work preflight and reserved finalization capacity precede engine entry; persist unavailable and unattempted requests.
- [ ] GREEN source and neighboring managed-source/coordinator tests, including cancellation before cache publication. Preserve private/redaction contracts. Commit capture changes with exact palette/counter evidence.

## Task4 — Whole-range attempt and parent-backed interval fallback

**Source-contract correction to qualify in independent Task4 review:** actual
`freezeFixedIslands` preserves editable-way ranges/edit-region but protects each
island's original nodes. Carry a validated captured partition/restriction envelope
in root authority; bind original way/range, dataset/generation and production
primitive/referrer parity, account its retained bytes and reference identity.
No proof is not proof of no islands. A closed-original-snapshot no-island proof
may cover ordinary junctionless and exact safe two-way continuation cases, never
unknown/tagged/relation branching by assumption. Test ordinary fixed two-node
ends remain whole-eligible, a true full two-node frozen island cannot acquire an
inserted curve, foreign/parity-mismatched proof refuses, and unknown complex
capture makes zero native calls while preserving Legacy Apply. Captured partition
always takes precedence. Do not redesign raw snapshot codecs or widen authority.

**Shared-slot allocation:** freeze/export a named deterministic policy for each
native call's offered/issued raw/distinct caps within the ONE operation budget.
Default production B must not consume all32raw slots on the first cheap source
merely because tests override native calls to1/1. Test actual default multi-base
work/provenance and compare strong/competing-ridge best geometry/status against
the unsplit native baseline. Declare extra-slot/remainder priority rather than
silently favoring the last palette. Pair/transition/state limits, genuine native
resource stops and canonical whole/fallback order remain unchanged; no finite
budget promises all requested work completes. Native kernels are unchanged.

**Files:** interval planner/pipeline/composer; `T/service/refinement/RefinementIntervalPlannerTest.java`, `RefinementPipelineTest.java`, `ParentBackedComposerTest.java`; neighboring `JunctionSegmentSelectorTest`, `LegacyBPipelineTest`, `V022FixedIntervalEditPlanComposerTest`.

**Interfaces:** `RefinementIntervalPlanner.plan(BoundParent)` returns ordered original-occurrence ranges, parent arc cuts and eligibility/frozen reasons. It uses the selector's shared pure topology/trim rules, not calls to the UI action. `RefinementPipeline.run(ticket, boundParent, sourcePlan, adapters, operationBudget, cancellation)` returns immutable per-source operation outcomes and assessed candidates. `ParentBackedComposer.compose(parent, intervalChoices)` retains parent geometry outside replaced ranges and emits one full selected-range preview with assignments/provenance.

- [ ] RED: whole-range success performs no fallback. Eligibility/no-route/coverage/unsafe-final-output refusal triggers fallback where contained, without faking an executed whole run. REVIEW_REQUIRED geometry is inspectable and does not by itself trigger fallback.
- [ ] RED: multiple T/X/shared boundaries produce every eligible interval, each more than two original occurrences **after** existing trimming; no physical buffer, lost shared boundary, selection mutation or inserted-point eligibility. Whole selected subrange cannot expand to unselected way parts.
- [ ] RED: successful A/B intervals survive another local failure, frozen/tagged/relation intervals preserve parent positions, and A→B failure retains A rather than root Legacy/original OSM. Removed Precise cut and Move assignments work in actual assembly.
- [ ] RED: native RESOURCE_LIMIT/deadline stops all further inference; whole/fallback/palettes share actual work. Unknown work, cancellation/stale/source invalidation abort success. Completed results are diagnostic only unless final full assessment finishes under reserved capacity.
- [ ] Implement bounded assembly (no Cartesian interval product), canonical alternatives/provenance and full joins/topology/protection assessment. Parent fallback lengths stay in common quality denominators exactly once; native raw objective is not a common ranking score. Refuse unsafe children; keep rejected native output inspectable.
- [ ] GREEN all new and existing neighbors; actual final previews, not just ridge coordinates, are asserted. Record native invocations/work for whole/fallback distinctly; commit pipeline/composer.

## Task5 — Modeless controls, candidate choices and Back

**User amendment S08:** label preview navigation Undo/Redo; restore the stage's
geometry, candidate/palette selector, smoothing/refinement choices and findings.
Test different-palette selection after Undo followed by successful/failed/cancelled
refinement and cached Redo. Disable unavailable boundaries/conflicting work and
clear confirmation in both directions. Keep global preferences and JOSM dataset
Undo/Redo separate, with no computation or command on preview navigation.

**Files:** `ui/RefinementControls.java`, `AlignWayAction`, root-owner plumbing, existing review/section panels; `T/ui/RefinementControlsTest.java`, `T/actions/StepwiseRefinementActionTest.java`, GUI host scenario.

**Legacy fast-path constraint:** Legacy→Apply must not acquire extra palettes,
run another engine, or eagerly convert whole captures to modern scalar fields
merely to enable optional buttons. Capture the necessary immutable original
source/selection facts and owning-thread change epoch without changing Legacy
math. Expensive native-authority bootstrap may run on the first explicit refine
only if it can prove those original facts/epoch unchanged; otherwise refuse
refinement while retaining the existing appropriately revalidated Legacy path.
Never mint fresh edit authority from a changed dataset as a hidden refresh.
Instrument the Legacy-only workflow tests for zero additional acquisition/native
conversion/engine calls, in addition to the existing tracker performance gates.

- [ ] RED: A/B refinement and optional smoothing are available in every eligible normal Legacy-root session, including A/B descendants; stale/source/mode/authority failures have specific explanations. No tooltip tells users to change a removed method selector. Parent chosen from dropdown, not automatically top-ranked, is pinned on click.
- [ ] RED: Back restores stage/candidate and clears confirmation; successful different refinement drops forward history; cancelled/failed replacement retains it. While running disable Back/selection/Apply/refine, retain Cancel/Close. Stale source disables Apply across history; going Back does not refresh authority.
- [ ] RED actual-dialog tests: keyboard activation, tooltip accessibility, wrapped long interval reasons, scroll/resize, pan/zoom modeless behavior and buttons visible on normal/small windows. Candidate labels include engine/palette/stage without raw internal scores as primary UI.
- [ ] Wire session operations through real adapters/pipeline off EDT; publish snapshots on EDT with exact operation ticket. Do not duplicate root Legacy computation on each click. Status text distinguishes whole versus interval work, retained parent sections, truncation and unavailable palettes.
- [ ] GREEN pure UI/action tests then the graphical host tests. Preserve unchanged ordinary-mode shortcuts and source-time overlay geometry after pan/zoom. Commit controls/wiring; no menu removal until this parity passes.

## Task6 — Lineage, failure exports and final transaction

**Files:** lineage codec plus exact existing diagnostics/export and Apply entry points selected from RC15; `T/diagnostics/replay/format15/RefinementLineageCodecTest.java`, `T/actions/StepwiseRefinementApplyTest.java`, deterministic cancellation regressions.

- [ ] RED: root original, selected parent, current child, stage chain, palette/interval/source attempts and actual work are distinct immutable identities. Back then a failed operation leaves its diagnostics available even without a new child. Discarded or superseded operations cannot overwrite latest diagnostics.
- [ ] Add a versioned additive `refinement-lineage.json` artifact with schema1, root/stage/parent/candidate/operation/mode/source/settings identity, interval outcomes, actual-work availability and current selection. Reference existing checksummed geometry rather than duplicate private coordinates. Bind artifact hash and selection to the bundle; missing older fields are unavailable. Keep old Format-15 signatures/readers; do not claim old Legacy-B replay can reproduce A→B sessions without an implemented production-chain replay path.
- [ ] RED: changing parent chain, selected child, source identity or assignments invalidates confirmation/export binding. Failed/cancelled/no-route/unsupported source exports remain accessible through current-owned modeless UI with genuine available evidence only.
- [ ] RED actual-host transaction: Legacy→A→B and Legacy→B→A in both modes mutate nothing before Apply; exactly one command modifies the intended range. Undo restores original geometry/modified flags/membership; Redo stores final coordinates. Off-EDT calls, stale dataset/source, failed execute/Redo, uploaded-node tombstones and unrelated history follow existing guards. An unchanged unsafe parent section keeps the child blocked.
- [ ] Implement final selection-to-original-authority command planning and terminal session ownership. Reuse stored immutable assignments; no individual interval/engine command enters Undo. GREEN meaningful transaction/export neighbors and GUI scenarios, then commit.

## Task7 — Menu, preferences and compatibility

**Files:** plugin entry point, tracker/preferences/settings, lifecycle/shortcut tests; existing `WayHeatmapTracerPluginTest`, `PluginPreferencesTest`, `HeatmapSettingsDialogTest` plus new migration/menu assertions in their packages.

- [ ] RED: exactly five plugin top-level entries: ordinary Align, Select Longest Junctionless Segment, Heatmap Settings, Geometry Cleanup Settings, Diagnostics submenu containing Last Slide Debug/Calibration Tiles. Precise/Move menu launchers disappear but their live shortcut actions and effective invocation modes remain. Ctrl+Shift+J survives; all live starts use Legacy. No standalone A/B/Image/Hybrid launcher or primary-method selector remains.
- [ ] RED: fresh/malformed preferences and saved A/B/Image/`LEGACY_THEN_B`/`HYBRID` starting choices yield live Legacy, never automatic refinement. Preserve mode, palette/intensity/source/junction settings and other unrelated preferences. Historical captures retain their actual engine identities and versioned defaults. Settings acceptance is not followed by a modal explanation. Test migration separately from historical parsing; do not globally reinterpret old evidence as Legacy.
- [ ] Implement UI-only selection removal with compatibility enums/codecs retained where required. Unload destroys submenu, children and shortcut bindings exactly once; reload cannot duplicate entries. Do not delete old replay services merely because their automatic UI route is gone.
- [ ] GREEN menu/preferences/shortcut/lifecycle and ordinary action GUI tests after Tasks5/6 parity. Commit migration/removal with release-note text describing Back and both refinement buttons.

## Task8 — Full-delta verification, review and testable delivery

- [ ] Reconcile every approved specification section and all eight Astra findings with exact commits/tests/artifacts; record remaining findings honestly. Compare full base→head, including relevant untracked files, not just the last commit.
- [ ] Run appropriate complete public Java/Python/GUI suites through existing CI, with no private uploads. Add the new actual-production workflow scenarios to the existing GUI harness rather than a parallel test-only substitute. Include Legacy-root exact geometry/work/performance guards, real A/B refinement behavior, Legacy-only settings migration and historical engine compatibility. Existing timing exceptions are historical, not automatic permission for a new regression.
- [ ] Review final source independently with Sol (high where transaction/ownership risk warrants). Fix blocking findings with deterministic tests and scoped affected review. No automatic second Astra review.
- [ ] Update G004, user documentation/menu instructions, scoped specification/plan evidence and delivery notes. Keep G001/G002 scope separate. Preserve private fixtures/intermediate results/worktrees unless separately authorized cleanup proves safe.
- [ ] Build a versioned testable RC only under execution/release authorization; verify manifest/source/tag/CI/artifact binding, publish primary `wayheatmaptracer.jar`, download it and verify identity. A documentation task does not grant publication. User testing verifies the new workflow after CI/review; do not infer subjective geometry preference from safety tests.

## Commands and evidence

For focused Java verification, run in the isolated execution checkout, substituting exact test classes named above and repeating `--tests` for neighbors:

```sh
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk flock /data/data/com.termux/files/usr/tmp/josm-drasov-gradle.lock sh ./gradlew --no-daemon --max-workers=1 -Pjosm19555HostJar=/data/data/com.termux/files/usr/tmp/v022-official-josm19555-host/JOSM-Linux-java11-19555.jar test --tests '*RefinementSessionTest'
```

Expected RED: the new behavioral assertion/API fails for the intended missing behavior, not infrastructure/fixture construction. Expected GREEN: zero failures/errors/skips. Keep exact command/source/count/output. Native solver calls in deterministic integration tests must be small synthetic cases with actual engine/work evidence; no private capture or long benchmark is implicit in a unit-test command.

Before commits use `git diff --check` and stage only the named task delta. Do not broad-stage the root tree. Root plan/goal/spec documents must be carried into the isolated checkout explicitly without importing unrelated dirty production changes.

At execution start create one compact G004 checkpoint recording base/head, requirement/task, tests/review, actual model/effort where surfaced, retained-history/engine allocations, source/worker identities, failures and next safe action. Preserve closure capacity; optional polish and speculative optimization are first to cut. Planning has no tests/solver executions. A tool or allowance blocker yields a resumable checkpoint, never a completed goal.

## Self-review and current disposition

Coverage: confirmed workflow decisions appear in Tasks1/5; parent/Mode adapters and cuts in2; palette/halo/source in3; all-interval fallback/budget/assessment in4; ownership/Apply/export in1/6; menu/migration in7; reviewed delivery in8. All five review-focus cases and all eight Astra findings have named tests. No change to Legacy mathematics or G002 is authorized. Plan-defined retention/scheduling choices must be reported explicitly in implementation/review; they do not waive source or native caps.

Status: PLAN PREPARED. Specification is approved; implementation has not started.
