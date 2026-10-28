# Legacy-first v0.22 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline, or superpowers:subagent-driven-development when expressly selected. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved Legacy-default release with sequential interval-local Legacy → B Hybrid, silent successful Settings saves, one testable RC at most, and verified final 0.22.

**Architecture:** Preserve original capture/edit authority independently of temporary Legacy reference curves. Run the existing B engine separately on eligible sections, assemble bounded alternatives with local Legacy fallback, and assess the complete final candidates before one immutable Apply command. Remove the old A+B Hybrid implementation, not standalone Corridor or existing B.

**Tech Stack:** Java 17, Gradle, JUnit, JOSM/Swing, frozen evidence/network snapshots, existing Format-15 production replay, GitHub CI.

**Spec:** `superpowers/docs/plans/2026-10-28-v022-legacy-b-release-design.md` (approved), plus the original implementation-plan scope and approved corrections reconciled in `v0.22.0-completion-matrix.md`.

## Global Constraints

- Planning baseline: primary HEAD `117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a` with substantial pre-existing tracked edits. Reconcile the actual delta before execution. Preserve all unrelated/private/untracked files and failed-research worktrees.
- Legacy default; preserve explicit Legacy/Corridor/B preferences. New persisted identity `LEGACY_THEN_B`; migrate old `HYBRID` to Legacy with a nonmodal explanation. Existing Directional Image choice remains; removal was not requested.
- New Hybrid supports Precise Shape only. Reject Move Nodes before capture/computation with an actionable explanation; never silently change modes.
- One attempt, original occurrence/permission authority, final preview, review token and atomic command. No intermediate dataset mutation. Respect tagged/relation/frozen-junction restrictions and opt-in movement.
- B failure is local; cancellation, stale authority and invalid source are attempt-wide. Resource exhaustion stops further inference without discarding safely completed work or inventing attempted failures.
- No new research, automatic search widening, geometry consensus, blanket gate waiver, or standalone B algorithm change. METHODS.md and total 256 MiB proof remain approved 0.22.1 deferrals; keep existing enforced local memory limits.
- Use deterministic RED/GREEN tests, then neighboring regressions. Heavy replay/benchmarks run as code in CI; private evidence must not enter public Git history, artifacts or logs.
- At most one additional public RC is the target, not permission to release an unsafe artifact. No public test releases for CI-only experiments.
- Default execution is inline to avoid context churn; one independent Sol implementation audit before release. No Astra without asking. Record actual weekly usage only if telemetry is available.

## Review Focus

1. Selected subrange has an off-range shared/tagged node: outside geometry stays exact (Tasks 3, 6).
2. Legacy moves the reference outside the original raster: resample factual imagery or fall back locally, never fabricate support (Task 3).
3. One failed B section among successful sections: keep successes and run later sections (Task 4).
4. Individually safe intervals create a crossing or sharp join together: whole-candidate validation blocks unsafe Apply (Task 5).
5. Cancel/source change while another section is running: no later preview publication or dataset mutation (Task 6).

## File and interface map

Existing model/config: `model/TrackerMode.java`, `model/EngineCapabilities.java`, `model/TraceBudgets.java`, `config/PluginPreferences.java`, tracing settings and enum switches discovered through compilation.

Existing routing/source: `actions/AlignWayAction.java`, `actions/HeatmapLayerSettingsAction.java`, `service/AlignmentService.java`, `service/LiveBPreviewService.java`, `service/RidgeTracker.java`, `service/RenderedHeatmapSampler.java`.

Existing authority/finalization: `service/snapshot/SelectedWayIntervalPartitioner.java`, `service/tracing/IntervalTraceRequestFactory.java`, `service/tracing/FixedIntervalEditPlanComposer.java`, `service/tracing/ModernSingleWayEditPlanAdapter.java`, `service/quality/FinalGeometryEvaluator.java`.

Create focused orchestration in `service/tracing/hybrid/`: `LegacyBModels.java` (immutable records), `LegacySeedAdapter.java` (existing Legacy production tracking/reconstruction), `LegacyBReferenceFactory.java` (reference sampling), `LegacyBPipeline.java` (bounded sequencing/composition), `LegacyBAssessor.java` (common assessment).

Create `ui/HybridSectionTableModel.java` for user-readable section rows and highlighting bindings. Keep ordinary preview integration in AlignWayAction rather than adding another launcher.

`src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/` is the production prefix below; the corresponding `src/test/java/.../` prefix applies to all named tests. New Hybrid tests live under `service/tracing/hybrid/`.

## Task 1: Establish scoped baseline and release obligations

**Files:** approved design, this plan, existing completion matrix and current supervisor checkpoint. No production edits.

**Interfaces:** consumes original plan, approved decisions, source and all relevant committed/dirty state; produces a criterion-to-task/evidence mapping and scoped baseline inventory in this plan's Execution ledger.

- [ ] Record branch, HEAD, `git status --short`, `git worktree list`, tracked diff statistics and relevant untracked source. Identify existing B production fixes separately from test-only failed research. Do not reset or stage either wholesale.
- [ ] Compare original requirements with the current matrix; record every unresolved release requirement with its owning task below, existing qualifying evidence, approved deferral, or genuine external gate. A newly discovered material contradiction requires a decision rather than scope reduction.
- [ ] Run a compile/test-discovery baseline and focused existing suites from Tasks 2–6. Record failures by exact test name and intended versus pre-existing cause. Existing failures cannot silently disappear from the final gate.
- [ ] Verify CI branch/workflow and private-input handling before dispatching heavy work. Record usable local JDK17 and CI commands; do not install or change accounts/pools.
- [ ] Commit scoped planning evidence when reconciled; include no private file names/content beyond already approved public identifiers.

## Task 2: Engine defaults, new identity, and successful Settings saves

**Files:** TrackerMode, EngineCapabilities, PluginPreferences, HeatmapLayerSettingsAction, HeatmapSettingsDialog; tests `model/AlignmentConfigTest.java`, `config/PluginPreferencesTest.java`, `actions/HeatmapLayerSettingsActionTest.java`, `actions/AlignWayActionTest.java`.

**Interfaces:** `TrackerMode.defaultMode()` returns LEGACY_V02; `TrackerMode.fromPreference(String)` recognizes LEGACY_THEN_B, migrates old HYBRID to Legacy through a persisted one-time migration notice. New capability is explicit, not inferred from enum inequality.

- [ ] Add RED tests: fresh/reset/blank/invalid preferences choose Legacy; explicit Legacy/Corridor/B/Image round-trip; old HYBRID chooses Legacy, never runs old code, and migration notice appears once without a modal; new Hybrid round-trips.
- [ ] Add RED Settings action tests for both managed-access branches: successful save emits zero informational modals, persists/refreshed settings and still starts the probe; probe errors and genuine refresh failures remain visible; Cancel does not save/refresh.
- [ ] Add RED action test that LEGACY_THEN_B + Move Nodes is rejected before any capture/inference with “Hybrid requires Precise Shape. Select Precise Shape or another tracing method.”
- [ ] Implement these changes, retaining current standalone modes and cookie-pane focus contract. Do not expose the new Hybrid as functional before Task 6 wires its production route.
- [ ] GREEN: `sh ./gradlew test --tests '*PluginPreferencesTest' --tests '*AlignmentConfigTest' --tests '*HeatmapLayerSettingsActionTest' --tests '*AlignWayActionTest' --no-daemon --max-workers=1` with JDK17. Record counts, then commit only scoped hunks.

## Task 3: Detached Legacy seeds, occurrence provenance, and B reference sampling

**Files:** new LegacyBModels, LegacySeedAdapter, LegacyBReferenceFactory; smallest necessary extraction in AlignmentService/LiveBPreviewService; reuse RidgeTracker and RenderedHeatmapSampler; tests `LegacySeedAdapterTest`, `LegacyBReferenceFactoryTest` and existing interval-factory/partitioner tests.

**Interfaces:** define nested records in `LegacyBModels`:

- `Seed(String id, List<MetricPoint> points, Map<Integer,Integer> originalToVertex)`; mapping keys are original way occurrences, values final Legacy vertices. Constructor copies containers and validates order, range and protected-anchor completeness.
- `Section(String id, OccurrenceRange originalRange, List<MetricPoint> reference, Map<Integer,Integer> originalToVertex, boolean frozen)`; each section includes exact boundary anchors. Physical chainage belongs to this reference, never inferred from original occurrence count.
- `Reference(Section section, TraceRequest request, EvidenceSnapshot evidence)`; request retains original NetworkSnapshot authority and stores the distinct reference through an explicit new refinement-reference field/adapter, never overwriting captured original geometry.
- `LegacySeedAdapter.track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles, double sourcePixelSizeRasterPx)` returns existing `List<CenterlineCandidate>` through the production Legacy tracker. A side-effect-free reconstruction adapter produces Seed using the existing Precise Shape anchor/reconstruction rules.
- `LegacyBReferenceFactory.prepare(Section section, TraceRequest originalRequest, EvidenceSnapshot evidence, NetworkSnapshot originalNetwork, TraceBudgets remaining, CancellationProbe cancellation)` returns Reference or a typed contained coverage failure. Acquisition remains in the attempt/source service, outside pure inference.

- [ ] RED `legacyAdapterUsesProductionLegacyNumerics`: identical captured profiles produce identical Legacy offsets/order before and after extraction; no Corridor call, no dataset edits, exact anchors retained through simplification.
- [ ] RED `partitionsWholeWayAndSelectedRangeByOriginalOccurrences`: two interior junctions produce three eligible sections, fixed vicinities remain frozen, tagged and relation-protected anchors survive, and no outside-range node is included in edit authority.
- [ ] RED `referenceDoesNotReplaceOriginalAuthority`: Legacy lateral movement changes B profile locations/chainage but not original snapshot/hash/permissions; stale source remains detectable.
- [ ] RED `offRasterReferenceCannotInventSupport`: test Legacy course outside original evidence at a point away from calibration samples; capture necessary bounded coverage under the same source generation or mark just that section unavailable. Test configured width/halo and projection-domain validation.
- [ ] Extract/reuse Legacy numerical code without changing standalone behavior; implement immutable provenance and factual reference sampling. Extend request validation/serialization explicitly where needed; do not weaken IntervalTraceRequestFactory's original-anchor checks.
- [ ] GREEN focused new tests plus `*V022IntervalTraceRequestFactoryTest`, `*V022SelectedWayIntervalPartitionerTest` and Legacy fixture suites. Commit the coherent extraction/adapter change.

## Task 4: B section execution, local fallback, bounded alternatives

**Files:** new LegacyBPipeline, LegacyBModels; existing TraceWorkUsage/TraceBudgets only for deliberate integration; test `LegacyBPipelineTest`.

**Interfaces:** `LegacyBPipeline.refine(List<Seed> seeds, List<Section> sections, SectionRunner runner, TraceBudgets budgets, CancellationProbe cancellation)` returns `Batch` (immutable candidates, section outcomes, cumulative actual work and terminal status). SectionRunner is injected for deterministic tests and calls existing production B in live/replay wiring. Sections must be derived for each parent Seed; factory validates parent ownership.

Define outcomes B_REFINED, LEGACY_RETAINED, FROZEN, UNATTEMPTED_BUDGET with fixed safe reason codes, actual work, parent/refined identities and exact final section geometry. Cancellation/staleness are terminal states, not local failures. Contain only explicitly classified inference/coverage failures; programming/integrity failures stop the attempt with exportable evidence.

Bounded implementation choices: at most two retained Legacy parents; at most two B alternatives per section; preserve each Legacy parent plus at most two assembled refinements per parent (six final candidates maximum). Assemble rank-0 B results and, if distinct, rank-1 results sectionwise, falling back to rank-0 where rank-1 is absent. Do not enumerate all combinations. Traverse first-parent/first-section order deterministically. Record omissions/truncation, retaining Legacy candidates even on budget exhaustion.

Use ordinary B default 96 states/profile, 8M pair visits and 128M transitions as shared attempt-wide B limits, not per-section resets; raw/distinct alternative caps remain no larger than the frozen request allows. New Hybrid must not call old fundedHybrid() 3x limits. Serial workers share one monotonic 300-second inference deadline and existing enforced memory ledger; stop starting work when exhausted and release section scratch between runs. These are resource ceilings, not a performance promise. Final assessment has separately bounded cancellation-aware work and cannot be omitted when inference stops early.

- [ ] RED `failureInMiddleKeepsBothNeighborRefinements`: scripted success/failure/success yields B/Legacy/B; verify third section called, all anchors exact, one failed section reported, and retained Legacy parent unchanged.
- [ ] RED all-B-failure, frozen/too-short, missing coverage, multiple Legacy parents, single usable B variant, duplicate all-Legacy result, and rank-1-missing cases. Assert deterministic candidate coordinates/provenance, no Cartesian explosion and caps.
- [ ] RED work/deadline/memory exhaustion: accumulated actual work never resets; later sections are unattempted, not failed; partial successes survive for assessment. Cancel/auth/source invalidation never masquerades as Legacy fallback.
- [ ] Implement sequencing and immutable candidate assembly. Test injected counters/clock; no sleeps. Deduplicate exact canonical geometry only, preserving merged provenance and union of applicable warnings.
- [ ] GREEN new pipeline tests plus existing B work/cancellation regressions. Commit without standalone B numerical changes.

## Task 5: Comparable final quality and truthful section presentation

**Files:** new LegacyBAssessor, HybridSectionTableModel; existing FinalGeometryEvaluator, AlignWayAction preview/PreviewOverlay; tests `LegacyBAssessorTest`, `ui/HybridSectionTableModelTest`, `service/quality/V022FinalGeometryTest`.

**Interfaces:** `LegacyBAssessor.assess(Batch batch, AssessmentContext context, CancellationProbe cancellation)` returns `List<AssessedCandidate>`. Define immutable AssessmentContext using existing image field, factual pitch, protected assignments, incident geometry and original authority; AssessedCandidate carries final geometry, FinalGeometryEvaluator.Result and section support rows. No opaque live callbacks in evidence.

`HybridSectionTableModel.setCandidate(AssessedCandidate candidate)` exposes source/status, supported/total final metres, warnings and fallback reason; selected row supplies the exact final vertex range to PreviewOverlay. Keep original occurrence range separate from final vertex range.

- [ ] RED `supportIncludesFallbackAndFrozenSections`: three sections of known final lengths/support aggregate without duplicating boundary length; all selected final geometry remains in denominator. Legacy and refined identical geometry yield identical common image/geometry metrics.
- [ ] RED `unknownEvidenceIsUnavailable`: no B posterior/profile evidence for a Legacy section remains unavailable, not fabricated zero/confidence or inferred B failure. Show unavailable summary when complete support cannot be measured; never imply full support.
- [ ] RED `safeSectionsCanFormUnsafeWhole`: a cross-section interaction and an abrupt fixed-boundary join each block Apply even when local runs succeeded; high average support cannot override a hard finding or local review warning.
- [ ] RED preview summary “B refined 2 of 3 sections; 1 retained Legacy”, wrapped long reasons, fallback explanation and row highlighting after pan/zoom. B refinement gets no ranking bonus. No fabricated geometry change if all fallbacks equal parent.
- [ ] Reuse FinalGeometryEvaluator for all new-Hybrid alternatives including Legacy-only parents; keep standalone Legacy ranking/behavior unchanged. Bind each assessment to final candidate identity and rebuild assignments after assembly.
- [ ] GREEN new tests, final-geometry suites and preview state tests; commit assessed model/presentation as one coherent batch.

## Task 6: Production source routing, Apply transactions, diagnostics and replay

**Files:** AlignWayAction, LiveBPreviewService, ordinary source capture, ModernSingleWayEditPlanAdapter/FixedIntervalEditPlanComposer as required; Format15ProductionBundleFactory/Format15ReplayRunner and additive codecs; tests `V022LegacyBIntegrationTest`, existing action/live-preview/composer/adapter/replay/transaction tests.

**Interfaces:** ordinary LEGACY_THEN_B action invokes Tasks 3–5 through the existing attempt controller. Format-15 adds explicit algorithm identity `legacy-then-b-v1`, original selection/snapshot identities, each Legacy seed/reference/section, factual evidence bindings, work and fallback/quality/plan identities. Live and replay call the same LegacySeedAdapter, LegacyBPipeline, B engine and assessor. Older bundles remain readable; absent fields are unavailable.

- [ ] RED full-way and two-node subrange tests with synthetic production imagery: actual Legacy runs before actual B; no route uses A/guided B; visible and managed sources use truthful capture frames; alternatives/all-color options retain their declared strict semantics.
- [ ] RED modeless export for partial/all-B failure, acquisition failure and no route; no fake whole-way B request or invented frozen image. Public diagnostics redact credentials and local private paths.
- [ ] RED controlled-queue cancellation/new-attempt tests between Legacy and B, between B intervals, during acquisition and before publication. No stale publication or later command. Source changes at confirmation/Apply invalidate exact plan.
- [ ] RED selected-range Apply/Undo/Redo: exact preview geometry, original modified flags and deletion/upload semantics; off-range/non-highway incident geometry unchanged; tagged/relation/frozen anchors untouched; stale Redo leaves dataset/history unchanged except the permitted single attempted Redo consumption and shows visible failure.
- [ ] RED production replay counterfactual: breaking/bypassing Legacy or B makes the relevant positive production-invocation/equality test fail; an expected local B fallback cannot make a bypassed positive case pass. Replay a mixed success/failure fixture and assert exact interval/assembled/plan identities.
- [ ] Wire production adapters under the existing attempt owner; perform capture on EDT only where required, inference and tile work off EDT. Use one immutable combined plan and exact shared-node assignments; the temporary Legacy geometry never reaches live primitives before Apply.
- [ ] GREEN focused integration/transaction/replay suites and neighbors. Record commands and native work receipts; commit scoped production integration.

## Task 7: Delete old A+B Hybrid and reconcile compatibility

**Files:** delete `service/tracing/HybridTraceEngine.java` and old-exclusive `probabilistic/GuidedProbabilisticTraceEngine.java` / `ProbabilisticStructuralGuide.java` if confirmed no other approved production consumer; remove their exclusive methods in ProbabilisticTraceEngine; TrackerMode/EngineCapabilities, TraceBudgets, LiveBPreviewService, AlignWayAction, Format15ReplayRunner and obsolete `V022HybridRecoveryTest` coverage.

**Interfaces:** no production constructor or selector can execute old A+B. New route identity differs in config, diagnostics and replay. Historical A+B archives can be inspected, but execution reports explicit unsupported historical algorithm instead of rerunning as the new Hybrid or returning a false replay pass.

- [ ] RED tests assert old saved HYBRID migrates, no selectable A+B entry/action/capability remains, historical A+B archive read remains possible, and strict replay refuses identity substitution. Convert relevant generic cancellation/budget tests to the new pipeline; delete only old-algorithm-specific expectations.
- [ ] Remove old orchestration, old-exclusive guide paths, fundedHybrid limits and labels. Update all enum switches through compiler evidence; retain Corridor and independent B behavior. Preserve the failed-research worktree without adding it to the release.
- [ ] GREEN compile and new/historical replay/config suites; search `HybridTraceEngine`, `A+B`, `fundedHybrid`, structural-guide production callers. Every remaining hit must be an explicit compatibility message/document or justified separate consumer, never an active old route.
- [ ] Update README engine/mode/source guidance and migration/partial-refinement explanations. Reconcile original matrix entries affected by approved replacement; commit scoped removal and docs.

## Task 8: Integration audit, one RC, acceptance and final release

**Files:** completion matrix, current supervisor checkpoint, README/changelog/version metadata, existing CI workflows only where a verified gate needs wiring. This plan's Execution ledger is the compact current task tracker.

- [ ] Run focused checks invalidated by the final delta, then full required project suites once on the stable snapshot. Run production replay against available original/captured high-intensity, weak/sparse, large-offset and multi-junction cases; compare Legacy, B and new Hybrid on the same factual inputs. Report geometry/support and timing separately; no arbitrary mandatory 2x/10x speedup target.
- [ ] Verify CI jobs actually execute (no hidden skips/disabled gates). Keep private captures in approved private execution/storage and report only permitted aggregate evidence. Reuse existing valid gate results; do not demand recapture of unchanged facts. Any genuinely missing mandatory input is an explicit blocker.
- [ ] One independent Sol audit of the whole release delta, original requirements/matrix, authority/transactions, local fallback, numerical/source frames, budget/diagnostic/replay integrity. Correct blocking findings and re-review only affected areas.
- [ ] `git diff --check`, stable scoped status/diff and matrix line-by-line reconciliation: every original requirement has tested evidence, approved replacement/deferral, or unresolved blocker. No unresolved blocker is labelled complete.
- [ ] Choose the next unused RC version from current tags/releases (do not reuse an existing version). Build through established release CI; verify manifest Plugin-Version, source commit and JAR SHA-256. Publish `v<version>` with `wayheatmaptracer.jar` only when release authorization and checks are satisfied. Keep all other CI runs commit-only.
- [ ] User acceptance checklist for that RC: default Legacy; standalone Corridor/B; Hybrid on whole way and selected range with multiple junctions; mixed fallback and comparison; section highlighting/support; exact Apply/Undo/Redo; modeless export; silent Settings save. Capture further evidence only where needed for remaining mandatory gates.
- [ ] After acceptance and all release gates pass, build final 0.22.0 from the reviewed revision; publish and download the public JAR, verify bytes/manifest against CI, record tag/commit/download identity and release limitations. Do not publish another RC automatically or treat the one-RC target as approval to skip a blocking repair.

## Commands and evidence convention

Local: prefix Gradle with `JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk`; run one Gradle process at a time with `--no-daemon --max-workers=1`, bounded console output redirected under `build/`. Add each new test class to explicit CI selections if the workflow filters classes. Before dispatching replay, read the current `v022Replay` tool help and pass a factual approved manifest; do not invent CLI flags or use fixture-description generation as production replay.

Each task ends with targeted `git diff --check`, scoped commit, and ledger update: files, revision, RED reason, GREEN commands/counts, neighboring evidence, CI run/commit identity, remaining findings. Never commit pre-existing source hunks merely because they share a filename.

## Execution ledger

Baseline execution workspace: `.worktrees/v022-legacy-b-release`, branch
`codex/v022-legacy-b-release`. Local snapshot `2fdab87` preserves the primary
`117baaf` tracked delta without changing its index/files. Current task evidence
and rulings: `.superpowers/sdd/2026-10-28-v022-legacy-b-release-execution/progress.md`
in that worktree. No weekly telemetry available.

Original-scope reconciliation (these are ownership assignments, not completion):

| Original requirement | Disposition in this execution |
|---|---|
| R01 Legacy/A compatibility | Tasks2,3,7,8; preserve standalone modes and compare regressions |
| R02 independent B | Tasks3,6,8; existing engine remains independent; no failed research replacement |
| R03 A+B | Approved replacement by Legacy→B; Tasks3–7 remove old implementation |
| R04 Image engine | Existing option retained; Task8 records unresolved original evidence, no silent waiver |
| R05 supported refitter | Task8 reconciles existing implementation/evidence; not replaced by naming new Hybrid |
| R06 cleanup | Task5/8 final-assessment and existing interval cleanup regressions |
| R07 defect detection | Task5 and existing final-evaluator tests/replay |
| R08 ranking | Task5 common final geometry, no refined-candidate bonus |
| R09 junctions | Tasks3/6 approved fixed/manual complex-junction policy |
| R10 incident reconstruction | Tasks3/6 preserve authorized simple cases and frozen surroundings |
| R11 integrity | Task6 protected anchors, off-range/incident geometry and upload semantics |
| R12 transactions | Task6 exact plan, cancellation, stale Apply/Undo/Redo |
| R13 settings | Task2 default/migration/silent save; Task6 effective ordinary routing |
| R14 diagnostics/replay | Task6 factual interval/source/work/plan identities and modeless failure export |
| R15 tests | Each task focused evidence; Task8 integrated suites and original acceptance mapping |
| R16 calibration | Task8 available corpus/annotations/ablations; unresolved mandatory facts block final release |
| R17 execution budgets | Tasks4/6 bounded shared work, off-EDT/cancel; total256MiB proof deferred0.22.1 |
| R18 release | Task8 audit, RC acceptance, mandatory gates and downloaded artifact verification |

Ruling: the active goal ends at the next functional RC, while final0.22 remains
conditional on user RC acceptance and mandatory final gates. Open R04/R05/R16
evidence is not implicitly waived by a shorter practical-engine release request.

| Task | Status | Evidence / next action |
|---|---|---|
| 1 baseline and obligations | TESTED | isolated2fdab87 snapshot, scoped23-file inventory;90 focused tests green; original obligations mapped above |
| 2 defaults/settings | TESTED | intended defaults/migration RED6→GREEN28; settings/guard RED2; final41PASS with corrected synthetic-session fixture; GUI smoke pending Task8 |
| 3 Legacy seeds/reference | PARTIAL |4027f47,d46ffe6,9e6eacb: detached reference, native B entry, captured-projection seed conversion, exact anchors and cancellation tested;101bridge/Legacy/interval neighbors green; frozen source assembly and simplification provenance still need Task6 integration |
| 4 interval orchestration | TESTED CORE |7dc2b57:27focused then58native B/pipeline tests green; explicit completion/resource reasons added RED→GREEN67 with assessor/native neighbors; live memory/source runner remains Task6 |
| 5 assessment/preview | TESTED COMPONENTS |75tests PASS: shared evaluator, complete support denominator, actual native section identity, wrapped table, immutable projected highlight and existing preview state; ordinary action hookup remains Task6 |
| 6 live/apply/export/replay | NOT STARTED | same production path required in live and replay |
| 7 old Hybrid removal | NOT STARTED | implementation removal explicitly approved |
| 8 RC/final release | NOT STARTED | no release readiness or new RC claimed |

Plan self-review: approved design sections mapped to Tasks 1–8; all five Review
Focus cases have named RED tests. New immutable models and helper types are defined
in their owning task; existing JOSM/request types are reused. Concrete resource and
alternative limits are implementation-plan choices for review, not measured timing
claims. No code, tests, commits, or public releases were performed while writing it.

Execution note2026-10-28: prototype fd67b52 numerical changes excluded from release
worktree after byte-level provenance check; retained pre-experiment117 B plus
explicit reference entry. Primary originals and35secondary research files remain
preserved. Focused Sol/high diagnosed invalid short/censored positive fixture;
corrected positive test uses on-ridge ends and a displaced original midpoint.
No B tuning. Current210focused/neighbor tests pass,0fail/error/skip. Latest public
release verified through GitHub is v0.22.0-rc.12; its crossing/disconnection branch
has approved commits beyond117. Integration of those is a pending baseline gate,
not an optional omission. No newer RC published.
