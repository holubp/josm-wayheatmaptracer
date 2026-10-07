# Engine B Normal-User Apply and v0.22.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make valid ordinary Engine B slides reliably applicable, restore practical performance, finish managed color support, and close the approved v0.22.0 release gates.

**Architecture:** Preserve detached capture, one production inference pipeline, common final validation, immutable edit plans, and command-owned dataset mutation. Clip read-only topology context before bounded projection, protect interior selection boundaries, and optimize exact bounded path selection without changing numerical or safety contracts. Final release remains conditional on independently verified completeness and corpus gates.

**Tech Stack:** Java 17 target, JOSM 19555, Gradle 9.4.1, JUnit 5, Python archive/report tools; existing plugin-owned tile coordinator.

**Spec:** `superpowers/docs/plans/2026-10-07-v022-b-usable-release-design.md`; investigation `2026-10-06-rc6-frame-performance-investigation.md`; original scope `josm-wayheatmaptracer-v0.22.0-implementation-plan.md`; approved release sequence `2026-09-22-v022-release-hardening.md`.

## Global Constraints

- Approved by maintainer on 2026-10-07 after Astra review; execute with the previously chosen subagent-driven method.
- Baseline `6288aaf`; reconcile staged, unstaged, committed and relevant untracked work before execution. Do not reset or clean user data.
- Java target 17; JOSM dependency 19555; preserve formats 1–14 and truthful Format-15 capability declarations.
- Dataset mutation occurs only inside the existing Apply/Undo transaction. Failed/cancelled attempts never mutate later.
- Junction and endpoint movement remain opt-in; tagged/relation-dependent and unsafe junction islands stay fixed; disjoint safe intervals remain usable.
- Review-only findings permit session-local explicit confirmation; structural/topology/stale-source failures remain blocked.
- User acceptance requires successful ordinary managed B Apply in BOTH Precise Shape and Move Existing Nodes. Move preserves node identities/order/count; genuinely undersampled curves offer an explicit one-shot Precise rerun. An already-aligned candidate is a successful no-op without an Undo entry.
- Managed all-color detection requires hot, blue, bluered, purple, gray in the same sampling frame; no subset and no post-hoc geometry consensus.
- Private coordinates/archives/credentials stay local; preserve bounded privacy scanning and existing archive admission limits.
- Shared 256 MiB whole-attempt gate and METHODS.md are deferred to 0.22.1; no other criterion is silently waived.
- Keep one writer per worktree. Route implementation per AGENTS.md; combine ordinary review where appropriate; require independent Sol-high review for consequential integrated changes.
- Record actual surfaced allowance or unknown; reserve capacity for verification and review. No unapproved resource-pool switch.

## Review Focus

1. A contextual segment crosses the edit corridor although both endpoints lie outside the frame: collision is still detected (Task 2).
2. A partial selection moves its boundary and changes unselected continuation: preserve the continuation exactly (Task 3).
3. Adding a common energy increment collapses two distinct doubles into a tie: lexical ordering stays exact (Task 4).
4. One requested palette is missing or belongs to a stale generation: no partial aggregate becomes valid evidence (Task 7).
5. Dataset/source changes after confirmation or Undo: Apply/Redo refuse visibly without changing unrelated history or primitives (Task 8).

## File Structure and Ownership

Paths below are repository-relative. `P` means `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`; `T` means `src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`. These prefixes are literal path expansions, not package aliases.

| Unit | Files | Responsibility |
| --- | --- | --- |
| Read-only clipping | new `P/service/tracing/CertifiedContextSegmentClipper.java`; existing `P/service/tracing/ModernSingleWayEditPlanAdapter.java`, `P/model/LocalMetricFrame.java` | Certify contextual segment projection without changing edit authority |
| Boundary authority | `P/service/LiveBPreviewService.java`; `P/service/snapshot/SelectedSegmentNodeAuthority.java` | Grant movement only to authorized occurrences |
| Exact bounded alternatives | `P/service/tracing/probabilistic/ProbabilisticInference.java`; `ProbabilisticInferenceResult.java` in same directory | Selection, ancestry ownership, work accounting and honest completeness |
| Quality display | `P/actions/AlignWayAction.java`; new `P/service/quality/FindingSummary.java` | Compact copy without modifying evidence or dispositions |
| Managed sources | `P/service/ManagedModernPreviewSource.java`, `LiveBPreviewService.java`, `AlignmentTileSourcePlan.java`, `TileHeatmapSampler.java`; `P/actions/AlignWayAction.java` | Source acquisition, scalar derivation, lineage and routing |
| Host/replay evidence | existing command, preview, Format-15 and replay classes; `src/tools/java/.../v022/V022ReplayTool.java` | Integration verification and honest replay capabilities |
| Delivery | README, release notes, gradle.properties, existing release/completion ledgers | Testable RC and gated stable release |

Do not split large existing classes solely for style. Extract only the clipping and
finding-summary units with the focused responsibilities above.

---

### Task 1: Reconcile scope, existing work and reusable capture evidence

**Files:** Update `superpowers/docs/plans/v0.22.0-completion-matrix.md`, `v0.22.0-release-gate-status.md`, `v0.22.0-execution-ledger.md`; private reports under ignored `build/v022/rc6/`.

**Interfaces:** Consumes original requirement IDs, approved changes and actual Git state. Produces a current requirement-to-task/gate table and checksum-bound private capture inventory.

- [ ] Record `git branch --show-current`, `git rev-parse HEAD`, `git status --short`, and `git worktree list`; inspect unique changes, including `/data/data/com.termux/files/usr/tmp/v022-managed-colors`. Preserve useful existing implementation; do not redo or overwrite it.
- [ ] Map every original requirement to VERIFIED, this plan's task, approved DEFERRED, or EXTERNAL BLOCKER, with evidence. Old pending rows are not current proof. Record the B-first scope without treating it as blanket waiver of other requirements.
- [ ] Validate the two evening and four morning RC6 bundles through production readers. Inventory checksum, engine, frozen-input identity, replay capability, captured policy, findings and timing counters; retain no private data in public documents.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*V022LiveBPreviewServiceTest' --tests '*V022ModernSingleWayEditPlanAdapterTest' --tests '*V022BCompletenessContractTest' --console=plain`; record completed exit/XML results as baseline. Baseline failures are classified before editing.
- [ ] Commit only sanitized ledger updates: `git add superpowers/docs/plans/v0.22.0-completion-matrix.md superpowers/docs/plans/v0.22.0-release-gate-status.md superpowers/docs/plans/v0.22.0-execution-ledger.md`; `git commit -m 'docs: reconcile Engine B RC6 release gates'`.

### Task 2: Make contextual topology projection certified and complete

**Files:** Create `P/service/tracing/CertifiedContextSegmentClipper.java`; modify `P/service/tracing/ModernSingleWayEditPlanAdapter.java`; test new `T/service/tracing/V022CertifiedContextSegmentClipperTest.java`, existing `V022ModernSingleWayEditPlanAdapterTest.java` and `T/service/V022EndToEndTest.java`.

**Interfaces:** `Optional<ClippedSegment> clip(GeographicPoint start, GeographicPoint end, LocalMetricFrame frame)`; nested `ClippedSegment(MetricPoint start, MetricPoint end, boolean originalStart, boolean originalEnd)` and `InvalidContextGeometryException extends IllegalArgumentException`. `Optional.empty()` means proved disjoint from the certificate rectangle; malformed/ambiguous geometry throws that exception. Only unchanged contextual segments consume clipping. Changed segments retain strict full-domain projection.

**Reviewed correction during execution (2026-10-07):** Sol review and focused
Astra consultation rejected nominal clipped metric endpoints as topology decision
authority. A valid narrow-frame crossing and ordinary-frame vertex touch both
returned NONE in local commit `c9e03f2`, despite the inset guard. Use original
unwrapped geographic chords with robust/exact incidence and conservative physical
separation/contact bounds instead. Preserve coordinate `1e-8 m` and orientation
`1e-8 m²` units independently, bound numerical uncertainty, and fail closed when
separation is unproved. No off-domain edited projection or artificial OSM identity.
Clipped metric endpoints may support diagnostics, not sole safety decisions.
The durable counterexamples and correction contract are in
`2026-10-07-v022-context-safety-correction.md`; the detailed bounded packet is in
this plan's SDD `task-2-astra-proof.md`. Retain both as actual adapter regressions.
This supersedes the defective decision mechanism only, not Task 2's scope or gates.

- [ ] Add RED tests named `farContextDoesNotPreventFixedApply`, `outsideEndpointsStillDetectCrossing`, `clippedEndpointDoesNotBecomeSharedOsmTouch`, `antimeridianBranchIsConsistent`, and `outsideChangedGeometryStillFailsClosed`. Assert safe plan availability, unsafe crossing refusal, and unchanged context—not merely absence of exceptions.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*V022CertifiedContextSegmentClipperTest' --tests '*V022ModernSingleWayEditPlanAdapterTest' --console=plain`; establish RED for the production refusal.
- [ ] Implement conservative parametric clipping in unwrapped geographic coordinates on the frame's longitude branch, then project only admitted points. Preserve original segment identity/index and endpoint provenance. Prove boundary/tangency cases conservatively; uncertain clipping cannot mean disjoint. Do not extrapolate `toMetric` or enlarge its epsilon. Keep real shared-node exemptions limited to real endpoints.
- [ ] Prove exclusion against the ACTUAL collision classifier, including its `1e-8` orientation and coordinate tolerances. Exact rectangle disjointness alone is insufficient if a near-boundary touch could still classify as collision. Cover corners, tangency, boundary overlap, zero-length clipped contact, reversed segments, antimeridian crossing and unselected continuation. Uncertain exclusion fails closed; artificial endpoints never gain shared-node exemption or edit identity. Record the proof and classifier-unit assumptions for independent review.
- [ ] Integrate into `finalTopologyFindings`; check every pair involving changed geometry against relevant clipped context. Audit `TopologyConversion` separately: it cannot substitute artificial clipping nodes into the editable graph. Unsupported explicit reattachment components must use the existing manual/fixed-interval path rather than throwing an internal coordinate exception. Run focused tests plus `*V022MetricRegionIntersectionTest` and `*V022EndToEndTest`; require independent geometry review before integration.
- [ ] Commit the exact task files with `fix: certify contextual topology before projection`; record RED/GREEN and crossing evidence in the ledger.

### Task 3: Preserve unselected continuations at partial-range boundaries

**Files:** Modify `P/service/LiveBPreviewService.java`; tests `T/service/V022LiveBPreviewServiceTest.java`, `T/service/V022EndToEndTest.java`, `T/model/V022EditAuthorityAdversarialTest.java`.

**Interfaces:** Existing `captureAuthority(...) -> CaptureAuthority`; existing `captureManagedSeed(...) -> ManagedCaptureSeed` and visible `capture(...) -> Captured`. No new settings or persisted schema.

- [ ] Add `partialLegacyBoundariesStayFixedInManagedAndVisibleCapture`: for an interior subrange under LEGACY_BOUNDED_MOVE, assert both boundary keys protected, absent from movable keys, and interior ordinary keys movable. Add `fullWayEndpointOptInStillWorks` and retain tagged/shared protections.
- [ ] Establish RED with `sh ./gradlew --no-daemon test --tests '*V022LiveBPreviewServiceTest' --console=plain`.
- [ ] Before upgrading boundary authority, reject LEGACY_BOUNDED_MOVE movement for an occurrence other than index 0 or the selected way's final index. Preserve explicit REATTACH semantics. Do not enlarge editRegion to cover unintended continuation movement.
- [ ] Decide eligibility BEFORE `captureAuthority`'s incomplete-arm/relation movement checks: an ordinary protected interior boundary must not fail while requesting authority it never uses. Shared fixed boundaries still need read-only collision/closure evidence; do not bypass genuinely incomplete relevant topology. Test one/both interior boundaries, one actual full-way endpoint, tagged/shared/relation boundaries, incomplete incident geometry with provable exclusion, and both capture sources.
- [ ] Add a full capture→compute→plan→Apply test with partial selection: exact unselected node sequences/coordinates remain unchanged, review confirmation works, and Undo/Redo match the preview. Move the old moved-subrange-continuation adversary to explicit detached malicious-plan validation so its safety oracle survives the newly fixed capture policy. Run `*V022EndToEndTest`, `*V022EditAuthorityAdversarialTest`, and `*V022ModernSingleWayEditPlanAdapterTest`.
- [ ] Commit with `fix: keep partial selection boundaries inside edit authority`; independently review the boundary contract with Task 2.

### Task 3A: Resolve B settings before expensive acquisition

**Files:** `P/actions/AlignWayAction.java`, `P/service/LiveBPreviewService.java`, `P/model/AlignmentConfig.java`, `P/service/tracing/ModernTracePipeline.java`, `ModernSingleWayEditPlanAdapter.java`; tests action/live-service/pipeline/adapter suites.

**Contract:** One effective invocation is used by capture, compute, assessment, confirmation and Apply. Keep requested settings separately for disclosure/export and saved preference identity. Suppress unsupported B legacy cleanup and simplification per attempt with visible information; never mutate preferences or pretend this completes the original cleanup requirement. Freeze effective settings before capture. Capabilities that cannot safely be normalized fail before acquisition with accessible recovery.

- [ ] Establish RED through the ordinary action for fresh/migrated legacy simplification, every stored cleanup preset, A-to-B switching, Move-to-Precise switching and both one-shot actions. Reproduce contradictory pipeline suppression versus adapter/preflight refusal.
- [ ] Implement one normalization boundary; remove contradictory downstream guards only when consumers use its frozen effective invocation. Export requested/effective values and identities in redacted Format-15 diagnostics. Settings changes invalidate/recompute under the session/source contract.
- [ ] Test preference preservation, visible disclosure, successful managed confirmation/Apply in both modes with incompatible saved settings, and unsupported options failing before tile work. Color-only/direct-scalar policy agrees with Task 7. Run focused and neighboring suites; independently review with Tasks 3/8. Commit `fix: resolve effective B settings before capture`.

### Task 4: Accelerate exact bounded K-best selection

**Files:** Modify `P/service/tracing/probabilistic/ProbabilisticInference.java`; tests `T/service/tracing/probabilistic/V022ProbabilisticInferenceTest.java`, new `V022KBestExtensionOrderingTest.java`; private benchmark report under `build/v022/rc6/`.

**Interfaces:** Retain `solve(...) -> ProbabilisticInferenceResult`, PATH_ORDER and numerical identity. A package-private testable descriptor order computes exactly `parent.energy() + increment`, ordered by that rounded energy, parent lexical rank, then appended state. Logical transitions remain the budget contract; new diagnostic counters `inference.extensionDescriptors` and `inference.ancestryRecordsAllocated` measure physical work.

- [ ] Add RED equivalence tests using a small independently exhaustive lattice: raw path states, energy/log-measure bits, lexical ties, terminal saturation count, marginals, gap/disposition/completeness and budget/cancellation outcomes. Include `roundedExtensionTieUsesLexicalOrder` where distinct prefix energies collapse after addition, cap values 1/8/32, absent transitions and equal-energy paths.
- [ ] Preserve the existing bounded per-pair algorithm bit-for-bit, including prefix pruning. A fully exhaustive global oracle applies only when no intermediate pruning occurs; otherwise exhaustively enumerate extensions of the SAME retained prefixes with the SAME cap/tie contract. Binary64 rounding can make a pruned prefix globally competitive, so global exhaustive equivalence is not a blanket requirement for this performance-only change. Do not change numerical policy unnoticed.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*V022KBestExtensionOrderingTest' --tests '*V022ProbabilisticInferenceTest' --console=plain`; preserve baseline fingerprints before optimization.
- [ ] Replace allocate-every-extension insertion with reusable accounted descriptors. Compute exact increments once per predecessor triple. Order each small extension stream by rounded energy and lexical identity, then merge streams for the global top cap; allocate PathArena ancestry only for retained extensions. Preserve saturated path counts independently. Do not assume pre-addition energy order survives floating-point ties.
- [ ] Charge logical work in the historical enumeration order before publishing a frontier; preserve RESOURCE_LIMIT and cancellation safety, account scratch arrays/heaps, release all owners on failure. Run probabilistic, memory-owner and completeness neighboring suites. Replay the supplied scalar/final frozen inputs: same output fingerprints and findings are required. Benchmark two warmed runs per evening case; initial objective is at least 2× lower K-best median and at least 5× fewer ancestry allocations, with no time threshold in unit tests. If objective is missed, record evidence and revise implementation rather than reduce search scope.
- [ ] Commit with `perf: select exact B alternatives before allocating ancestry`; obtain independent numerical/accounting review before integration.
- [ ] Record whole ordinary-action start-to-preview-ready latency, acquisition/inference/finalization and cancellation responsiveness on a declared reference environment. After warm-up use at least three measured repetitions. Practical RC acceptance requires at least 2× lower median TOTAL cached-source preview latency than RC6 on each supplied long evening case, identical geometry and no cancellation regression, using the same selected-palette configuration supported by RC6. Record five-source latency separately because RC6 rejects that configuration. A K-best-only improvement cannot pass. Record cold/network latency separately; unrepresentative hardware cannot establish user-device runtime. No flaky unit-test timing thresholds.

### Task 5: Close the strict alternative-completeness design gate

**Files:** Inspect `P/service/tracing/probabilistic/ProbabilisticInferenceResult.java`, `PathAlternativeSelector.java`, `ProbabilisticInference.java`, `P/diagnostics/replay/format15/ProductionReplayValidator.java`; tests `T/service/tracing/probabilistic/V022BCompletenessContractTest.java`; create `superpowers/docs/plans/2026-10-07-v022-b-alternative-completeness-contract.md`.

**Interfaces:** Consumes Task 4's exact raw path enumeration and existing Completion receipt. Produces a reviewed proof-backed contract and a bounded follow-on implementation packet, or an explicit final-release blocker. This task does not grant permission to change the strict release predicate.

- [ ] Add/retain adversarial lattices with more than 32 near-identical best paths followed by a distinct relevant route. Assert the capped receipt remains incomplete; a rank limit or desired diversity count alone cannot certify exhaustive coverage. Preserve the existing strict validator rejection.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*V022BCompletenessContractTest' --tests '*V022ProductionReplayTest' --console=plain`; record the current honest refusal separately from fingerprint fidelity.
- [ ] Write the finite-DAG argument for current raw count saturation and geometric selection. Specify exactly which statement a certificate would prove: exhaustion, completion of an approved bounded alternative request, or exclusion of relevant unseen rivals. Analyse nontransitive geometric diversity and greedy selection; no probability cutoff or equivalence-class assumption is introduced without a reviewed contract change.
- [ ] Have Sol-high review the argument and complexity bounds; escalate only unresolved consequential reasoning. If a safe bounded certificate is established, write its exact receipt fields, algorithm, RED oracles, codec/version changes and commands as a reviewed dependent packet before implementing it. If no certificate is established, mark this gate BLOCKED and continue independent tasks/test RC; stable 0.22.0 remains blocked. A maintainer change to the release criterion requires explicit approval and ledger updates.
- [ ] Commit proof/evidence with `docs: define B alternative completeness release contract`. Do not claim this design task alone fixes completeness.

### Task 6: Make review findings understandable and distinguish Apply failures

**Files:** Create `P/service/quality/FindingSummary.java`; modify `P/actions/AlignWayAction.java`; tests new `T/service/quality/FindingSummaryTest.java`, existing `T/actions/AlignWayActionTest.java`.

**Interfaces:** `static String summarize(List<String> labels)` returns stable first-occurrence ordering and counts, e.g. `LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW) × 93`. Both quality and plan reason displays consume it; detailed diagnostic arrays remain unchanged.

- [ ] Add `repeatedFindingsAreCountedWithoutLosingDistinctReasons`, `hundredsOfReasonsKeepButtonsReachable`, and `reviewConfirmationEnablesOnlyAvailableSafePlan`. Assert separate quality, confirmation and plan-availability states; no-plan does not report a successful affected-way count.
- [ ] Establish RED with `sh ./gradlew --no-daemon test --tests '*FindingSummaryTest' --tests '*AlignWayActionTest' --console=plain`.
- [ ] Implement grouping in both summaries; preserve existing wrapping/scroll bounds. Internal plan failure copy must state Apply unavailable and the actual recovery reason. Do not instruct users to manually change junctions for generic image uncertainty.
- [ ] Add explicit already-aligned/no-change and `PRECISE_SHAPE_REQUIRED` outcomes: no-change creates no command/history entry; undersampling offers an explicit one-shot Precise rerun preserving the saved mode. Classify no-change before generic failure handling; never relabel a rejected plan as no-change. Disclose unsupported source capability before confirmation. Test switching, invalidation and reachable buttons.
- [ ] Run the supplied routes through unchanged quality evaluation and compare code/severity/location/support fields. Any proposed false-positive correction needs its own RED synthetic geometric oracle; do not suppress UNSUPPORTED_ISOLATED_EXCURSION, unavailable quality or SEARCH_TRUNCATED for usability. Run preview-state neighboring suites.
- [ ] Commit with `ui: group review findings and clarify Apply availability`.

### Task 7: Finish managed alternative mappings and complete all-color evidence

**Files:** Modify `P/service/ManagedModernPreviewSource.java`, `AlignmentTileSourcePlan.java`, `TileHeatmapSampler.java`, `LiveBPreviewService.java`, `P/actions/AlignWayAction.java`; test `T/service/ManagedModernPreviewSourceTest.java`, `V022LiveBPreviewServiceTest.java`, `T/actions/AlignWayActionTest.java` and existing tile coordinator tests. Reuse reviewed work from the managed-colors worktree where it satisfies the contract.

**Interfaces:** Preserve `selectedOnly(...) -> Request` compatibility. Add `acquireSources(List<GeographicPoint> source, ManagedHeatmapConfig config, String sourceIdentity, CredentialSnapshot credentials, CancellationProbe cancellation) -> SourceRasters`, with `SourceRasters(Map<String,Raster> palettes, AlignmentTileSourcePlan plan)`. Every Raster shares transform dimensions, zoom, generation and source lineage. The legacy `managedRaster()` selected Raster accessor remains valid; Captured additionally owns the immutable source set required by requested scalar fields.

- [ ] Add RED cases for alternative-only acquisition (one palette), aggregation acquisition (exactly five), missing/auth-failed palette, stale generation, mismatched grid, cancellation, and direct scalar modes ignoring color-only flags. Assert all requested detector attempts are present and no missing source becomes direct support.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*ManagedModernPreviewSourceTest' --tests '*V022LiveBPreviewServiceTest' --console=plain`.
- [ ] Acquire through TileFetchCoordinator in one frozen generation/frame. Derive each palette through native semantic intensity; build `all-colors-combined` using the existing calibrated power-mean scalar conversion, p=1.25. Alternative mappings operate on the selected raster only. Remove unsupported-option guards only after ordinary action acquisition, evidence and lineage tests pass. Preserve bounded admission limits and no I/O in layer paint.
- [ ] Exercise each source choice through ordinary B capture→preview→confirmation→Apply and Format-15 frozen replay. Selected-palette behavior and direct-luminance/max/alpha outputs must retain baseline results. Run palette fixture, source-plan, coordinator/lifecycle and action regressions; independently review acquisition/lineage before integration.
- [ ] Commit with `feat: support managed modern alternative and all-color sources`; update README settings instructions in the same commit.

### Task 8: Prove normal-user Apply, safe intervals and host history behavior

**Files:** Tests `T/service/V022EndToEndTest.java`, `T/util/V022ProductionNetworkTransactionTest.java`, `T/actions/AlignWayActionTest.java`, `T/service/tracing/V022FixedIntervalEditPlanComposerTest.java`; production fixes only in the responsible existing command/preview/adapter files when a retained RED demonstrates failure.

**Interfaces:** Consumes validated immutable `AlignmentEditPlan`, current-source/review token validators, and `ApplyAlignmentEditPlanCommand`; no new transaction mechanism.

- [ ] Add `fixedBReviewCanConfirmAndApplyExactPreview`, `safeIntervalsSurviveMultipleManualJunctions`, and `staleRedoPreservesDatasetAndUnrelatedHistory`. Include tagged nodes and participating relations, two frozen islands, a genuine crossing, cancellation during worker phase changes, and source edits after confirmation.
- [ ] Parameterize ordinary-action capture→compute→preview→confirmation→Apply→host Undo/Redo over Precise/Move × full-way/subrange × single/composed safe intervals. A dense curved Move fixture must ACTUALLY move nodes, preserve complete identities/order/count, and Apply its exact sparse final preview. A separate undersampled Move fixture refuses safely and succeeds after explicit Precise rerun. Refusal-only tests do not prove Move functionality. Include current-user settings, no-change and source matrices from Tasks 3A/7/8A.
- [ ] Establish RED for missing behavior using deterministic EDT queues/latches. Run `sh ./gradlew --no-daemon test --tests '*V022EndToEndTest' --tests '*V022ProductionNetworkTransactionTest' --tests '*V022FixedIntervalEditPlanComposerTest' --tests '*AlignWayActionTest' --console=plain`.
- [ ] Fix only failures reproduced above. Safe review-only candidate confirmation must enable Apply; confirmation cannot fix an unavailable plan or bypass a hard block. Apply matches every affected-way preview exactly. Frozen surroundings and contextual ways remain byte-equivalent in snapshots.
- [ ] Verify twenty Undo/Redo cycles, modified flags and uploaded deletion semantics. Failed stale Redo leaves dataset unchanged, adds no Undo entry, preserves unrelated history and shows visible failure; JOSM 19555 may consume only the attempted Redo entry and requires recomputation. Verify real host handler, not only direct command calls. Run neighboring cancellation/session/command suites.
- [ ] Commit with `test: verify ordinary B Apply and protected interval workflow`; require independent transaction review of consequential source fixes.

### Task 8A: Close visible-source Apply/Redo freshness parity

**Files:** `P/actions/AlignWayAction.java`, `P/imagery/VisibleSourceEpoch.java`, `P/util/VisibleSourceLockedApplyValidator.java`, visible capture/immutable receipts; tests action/live-service/host transaction suites. Write `superpowers/docs/plans/2026-10-07-v022-visible-source-freshness-contract.md` and its reviewed dependent implementation packet.

**Gate:** Generic visible layers lack a trustworthy epoch and reject Redo; rendered managed captures reject first Apply; partitioned visible previews reject Apply. This is unresolved functionality. The original visible requirement remains a stable-release blocker unless explicitly changed by the maintainer. An honestly scoped managed-only usable RC may precede completion.

- [ ] Reproduce all three refusals and visible color-option defaults through ordinary action. Specify owner/layer/filter/source/pixel-generation receipt validation without network, rendering or blocking acquisition inside the locked command. Pan/zoom alone must not invalidate slide-time geometry; actual source changes invalidate confirmation/Apply/Redo. A frozen raster hash cannot alone prove the current layer unchanged.
- [ ] Review the receipt and locked-validator design before production edits. If host APIs cannot prove generic-layer freshness, define supported source classes and fail early/actionably for others; narrowing original scope requires maintainer approval. Do not simply permit Redo or reuse managed credential validation for rendered imagery.
- [ ] Produce and execute the dependent packet with deterministic RED/GREEN tests: generic visible/rendered managed × both modes × single/composed intervals, source/filter/owner changes, pan/zoom, cancellation and twenty host Undo/Redo cycles. First Apply, confirmation and Redo share the supported freshness contract. Preserve managed tests. If unresolved, checkpoint the exact blocker and continue managed RC work without claiming visible parity or stable completion.

### Task 9: Make diagnostic and replay evidence usable without Apply

**Files:** `P/diagnostics/replay/format15/Format15ProductionBundleFactory.java`, `Format15ReplayRunner.java`, `ProductionReplayCommand.java`, `P/actions/AlignWayAction.java`; tests `T/diagnostics/LiveFormat15RegistryTest.java`, `T/diagnostics/replay/format15/V022ProductionReplayTest.java`, `V022FinalOutputCompanionTest.java`; private corpus reports under build.

**Interfaces:** Existing frozen request/evidence/network/options and final-output companions; preserve schema/versioned receipts. Add only coordinate-free plan-availability status/reason and actual completeness/work counters. FULL_EDIT_PLAN remains unavailable until its declared inputs and actual production reconstruction are implemented and verified under original scope.

- [ ] Add tests exporting applicable, unconfirmed-review, unavailable-plan, cancelled and failed attempts. Assert the newest attempt wins, no Apply is required, detailed findings survive UI grouping, and no private credentials/raw server strings enter artifacts.
- [ ] Run `sh ./gradlew --no-daemon test --tests '*LiveFormat15RegistryTest' --tests '*V022ProductionReplayTest' --tests '*V022FinalOutputCompanionTest' --console=plain`; reproduce any missing terminal evidence before fixes.
- [ ] Serialize truthful plan availability and logical/physical performance counters. Reuse current frozen codecs and reject mixed lineage. Test strict gate integrity by breaking/bypassing the production engine in a controlled test: strict validation must fail.
- [ ] Create checksum-bound local corpus manifests for the supplied cases. Run `sh ./gradlew --no-daemon v022Replay --args='--manifest build/v022/rc6/manifest.json --output build/v022/rc6/replay.json --engines B --offline --strict' --console=plain`. Record fidelity, completeness, image quality and replay level separately; a truncated faithful replay is not a passed release gate. Complete remaining approved corpus/ablation/calibration work with the actual required reference manifest. Do not invent annotations or claim 14/14 from an arbitrary capture count.
- [ ] Commit public diagnostic/regression changes with `test: preserve B replay and Apply availability evidence`; keep archives/results private.

### Task 10: Integrate and deliver the next testable RC

**Files:** Existing menu shortcut delta in `P/actions/SelectLongestSegmentAction.java`, `T/WayHeatmapTracerPluginTest.java`, README; `gradle.properties`; new next-version release notes; existing release ledger.

**Interfaces:** Tasks 2–4 (including 3A) and 6–9 produce an applicable managed B workflow in BOTH modes. Tasks 5 and 8A may remain explicit stable-release blockers. RC publication discloses gates and supported sources; it does not claim stable parity.

- [ ] Reconcile all task deltas and reviews, including pre-existing rename/shortcut work. `Select Longest Junctionless Segment` uses Ctrl+Shift+J with shortcut ID `wayheatmaptracer:select-longest-segment`; custom user bindings survive. Verify no experimental Engine launchers return.
- [ ] Run `sh ./gradlew --no-daemon clean test build javadoc compileToolsJava --console=plain`, `python -m pytest -q scripts/tests`, `python scripts/validate-sampling-scale.py`, `python -m compileall -q scripts`, and `git diff --check`. Pytest must collect both function-style and unittest-style cases; a unittest-only discovery run omits function-style cases and is insufficient. Record exits, collection/XML counts/skips and artifact checksums. If pytest is unavailable, diagnose that environment prerequisite before claiming verification; do not substitute a runner that omits cases.
- [ ] Conduct one independent Sol-high audit of scope, complete delta, clipping proof, boundary preservation, numeric ties, acquisition, normal-user Apply and mandatory gate evidence. Reuse qualifying task reviews; fix blockers and rerun affected checks.
- [ ] Require the both-mode ordinary-action success matrix, preference-safe normalization/recovery and whole-preview performance evidence before calling the RC usable. Precise-only success cannot prove Move. If visible parity is pending, explain managed-source requirements and visible limitations before expensive acquisition.
- [ ] Choose the next unused RC version by inspecting tags/releases and current version; do not overwrite RC6. Build with Java 17 compatibility and Plugin-Mainversion 19555, manifest version matching the chosen tag. Publish only under existing applicable user authorization, then download the primary `wayheatmaptracer.jar` and verify its checksum/manifest. Deliver installation and confirmation instructions, including the new shortcut.
- [ ] Commit release metadata with the chosen RC version and update checkpoint. State clearly which final-release gates remain open. Missing stable completeness/corpus gates do not prevent delivery of an honestly labeled test RC.

### Task 11: Close final v0.22.0 gates and publish stable

**Files:** Original completion matrix/release ledger, approved completeness packet, required private corpus manifest/reports, release notes, gradle.properties.

**Interfaces:** Consumes Task 10's reviewed RC and Task 1's exhaustive requirement dispositions. Produces stable only when every applicable gate is satisfied.

- [ ] Complete the approved dependent completeness implementation from Task 5 and its proof/tests; require the strict validator to fail deliberately incomplete/bypassed production output. A design blocker must not be marked implemented.
- [ ] Close Task 8A's visible contract/implementation and both-mode host evidence, or obtain an explicit scope change. Finish original cleanup and other requirements left open after Task 3A; an effective Off invocation is not evidence of implemented cleanup.
- [ ] Verify the required fourteen reference cases and production replay, declared ablations/calibration, and host Apply evidence at their honest capabilities. Reuse RC6 frozen data where inputs remain sufficient; request recapture only for a documented missing input/changed acquisition requirement. Full edit-plan requirements need implementation/host evidence, not fabricated replay support.
- [ ] Audit every original requirement plus approved change against final source, tests, corpus and reviews. Remaining original-scope work discovered by Task 1 requires its own reviewed bounded packet. Defer only the explicitly approved 0.22.1 items. Any unsatisfied mandatory external gate blocks stable publication, not independent engineering work.
- [ ] Run exact-final-tree full checks and applicable independent final review; inspect the entire delta and ensure no critical uncommitted work is omitted. Record a complete release evidence table before claiming readiness.
- [ ] Publish a new `v0.22.0` release only when permitted and all gates pass; asset `wayheatmaptracer.jar`, manifest Plugin-Version `0.22.0`. Download and compare hashes. Update durable handoff with verified release identity and remaining approved 0.22.x work.

## Self-review and Execution Handoff

Coverage: ten discussed topics map respectively to Tasks 2, 3, 4, 5, 6, 7,
8–9, 10, 1/9/11, and 10–11. Normal-user applicability is an explicit Task 8
and release acceptance criterion. The five Review Focus inputs each have named
test ownership. Source-frame clipping and rounded-energy ordering have dedicated
proof obligations; neither is delegated as an unexplained implementation choice.

This plan does not pretend the original release matrix is current or completely
closed. Task 1 reconciles it, and Task 11 prevents omissions. Task 5 is an explicit
remaining design gate, not a promise that bounded raw search proves completeness.
Task 3A resolves invocation settings; Task 8A owns previously missing visible
freshness. These follow `2026-10-07-v022-b-usable-release-astra-review.md`.
Design gates are not solved implementation packets; their dependent packets
must be reviewed before closure. No production code changed while writing this plan.

Recommended execution uses the preserved subagent-driven method with bounded
packets, one writer and coherent independent review batches, as constrained by
AGENTS.md. Maintainer approval was received on 2026-10-07; execution is active.
