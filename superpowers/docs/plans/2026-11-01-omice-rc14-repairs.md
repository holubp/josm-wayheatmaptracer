# Omice RC14 Repairs Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans or explicitly delegated bounded tasks. Preserve deterministic RED/GREEN evidence and independent Sol review.

**Goal:** Repair at least one confirmed Omice failure and publish a verified RC14, with remaining limitations stated explicitly.

**Architecture:** Preserve the existing engines, native source inputs, immutable command boundary and resource caps. Narrow repairs to demonstrated defects; do not introduce a new tracing model or silently change search width.

**Tech Stack:** Java17, JOSM19555, Gradle/JUnit, private offline captures, existing public CI and real GUI checks.

**Spec:** User authorization2026-11-01 to fix confirmed problems and release another RC; prior approved safe-interior/fixed-junction contract; private Omice investigation and B-after-A report.

## Baseline and evidence

- Release worktree `.worktrees/v022-legacy-b-release`, branch `codex/v022-legacy-b-release`, HEAD `fc17721`; RC13 source/tag `2860147`.
- Primary tree remains dirty at117baaf and is not an implementation workspace.
- Private captures stay local. No private OSM, ZIPs, coordinates or tile images enter public commits/artifacts.
- Omice Legacy has rotating-normal physical foldbacks; A/bluered-corridor is the good recorded comparison. No blanket turn filter or extra smoother may erase supported switchbacks.
- Hybrid freezes0–13 because read-only receiver parent relations have unloaded members. Its semantic witness conflates relation-payload completeness and member-geometry completeness.
- Standalone B after A exhausts128M scored transitions on a25m half-width; this is a separate capacity failure, not a demonstrated B geometry defect.

## Release constraints

- Keep Legacy default and all current engine choices. Keep immutable Apply/Undo/Redo and source/stale/cancellation controls.
- Keep junction movement opt-in; participating relations/tagged or unknown geometry do not authorize automatic junction movement.
- Never reinterpret an old ambiguous incomplete witness as a new complete-membership proof.
- Keep unknown node/way referrers, missing receiver geometry, deleted/missing relation evidence and source changes fail-closed.
- Keep existing heap/work caps. No automatic narrowing/widening or hidden alternate engine.
- One local Gradle process at a time. Use CI for full public/GUI verification. Independent Sol source audit before publication.

## Review focus

1. Complete route member list with unrelated unloaded member geometry: selected interior may slide, receiver/junction/relations remain exact.
2. Truly incomplete relation payload: no new authority.
3. Missing receiver membership, incomplete receiver geometry, unknown way referrer: remain blocked.
4. Old witness/replay formats: retain conservative meanings; new factual field is serialized and participates in stale/review identity.
5. Changes to relation membership/completeness after capture: stale Apply/Redo remains rejected without mutation.

## Tasks

- [x] Reconcile source/worktree and prior private diagnosis; no production changes yet.
- [x] Independent focused Sol design audit selects the smallest safe repair and precise contract.
- [x] Add deterministic regression showing the selected repair missing; record intended RED.
- [x] Implement minimal repair, then focused GREEN and neighboring source/partition/replay/transaction regressions.
- [x] If reproduced, replace the generic B resource-limited no-preview message with the actual computation-limit cause and actionable manual narrower/shorter refinement guidance; retain modeless current-owner diagnostics and no automatic settings changes.
- [x] Re-capture/replay the private Omice case truthfully using factual original OSM; compare fixed islands/interior work and exact plan availability. Preserve good A control and report any remaining Legacy/B failures.
- [x] Independent Sol audit of actual full delta; repair findings and re-run invalidated checks.
- [x] Update version to next unused RC, document actual changed behavior/limitations, push scoped commit and run full public CI plus both real GUI engines.
- [x] Publish only validated CI `wayheatmaptracer.jar`; verify public tag/source, download bytes, SHA256 and manifest; update this ledger/checkpoint.

## Current execution status

Focused Sol design review selects the Hybrid read-only route proof repair.
The controller implementation choice is an explicit optional factual relation
payload-completeness value, separate from the unchanged aggregate completeness
bit. Fresh capture records `!relation.isIncomplete()`; old captures have no such
fact and retain conservative meaning. New proof is restricted to unchanged fixed
receivers on explicit hiking/bicycle routes. New facts require additive codec
and semantic identity support, with conservative old-version behavior.

Approaches considered: increasing B work caps does not fix the receiver proof and
violates current limits; adding a Legacy turn/progress filter risks supported
switchbacks and needs a broader geometry design; recording the missing factual
proof dimension addresses the demonstrated over-freeze without changing engines.
The last approach is selected for this RC. Legacy hairpins and standalone B's
25m capacity limit are explicitly not claimed repaired.

Luna owns the bounded implementation in isolated Termux temporary worktree
v022-omice-relation-20261101, basefc17721. Files: witness model, factual snapshot
capture, partitioner, versioned frozen codec and focused regression tests. Parent
owns private fresh-capture comparison, integration, CI and publication. Sol owns
independent actual-diff review. One local Gradle slot belongs to Luna until return.
No additional weekly telemetry is available. No Astra or pool/speed changes.

An independent Luna lane is checking the B resource-limit UI message in an
isolated worktree; it may prepare RED tests but must wait for the core worker to
return the sole local Gradle slot before running tests or changing production.
Only implement this message fix if the current UI loses the actual typed cause.
This is not a claim that B's25m computation limit is repaired.

Private baseline on compiled RC13 completed successfully: captured25m radius,
native calibration images, whole14-occurrence selection and all-source/alternative
flags. Both actual Legacy/Hybrid candidates are NO_CHANGE, native B work0; dataset
unchanged. Elapsed37.974s. Inputs/output/bindings are local under
`build/private-omice-rc14-20261101/`; input hash
`3c35525dacfa80d3cc362543a9931ecbd86a90e11bbac7f9e321f9a7a8f91f86`.
This is a fresh numerical capture, not a byte-identical historical replay.

Core worker's first test execution found a fixture-only missing-dataset-member
setup error, corrected before the intended regression run; it is not RED evidence.
The second capture attempt reached the wrong required-relation closure path and
stopped at incomplete members, also not intended RED. Per two-attempt policy,
further fixture retries paused for focused Sol diagnosis. Detached partition test
does fail at the expected empty-slide assertion, but its old ambiguous witness
must be replaced with an explicit new payload fact once the API exists; the old
ambiguous witness control must remain blocked. No production files changed yet.
UI lane temporarily owns the sole local Gradle slot for its independent RED/GREEN.

Corrected actual-capture regression now establishes intended RED: real capture
preconditions pass (receiver materialized, route/unloaded unrelated way outside
core, incoming relation watch present, aggregate-complete false, receiver-member
geometry true, unrelated-member geometry false); partition still freezes all.
Failure is the local-island assertion in
`V022NetworkSnapshotCaptureTest.capturedRouteWithUnloadedUnrelatedMemberRetainsOnlyLocalFixedReceiverIsland`.
Core worker is authorized to implement while UI uses the sole Gradle slot.
The UI fixture's EDT setup error was corrected before its intended RED; it is not
passing or intended-failure evidence. Original aggregate-positive test restored.

UI branch now passes intendedRED→GREEN: typed resource-limited result loses its
cause under the old generic message, then corrected formatter/caller pass5action
checks with0fail/errors/skips2m23s. Only AlignWayAction andAlignWayActionTest were
integrated into root; the old aggregate-positive test remains byte-identical.
Core worker owns sole Gradle slot for its first compile/focused GREEN attempt.

First codec regression run found a new v5 edit-plan explicit-operation flag
ambiguity (payload-only v5 was read as explicit v4). Worker corrected the v5
flag while preserving v1–v4 bytes; V022FrozenFrameTest now14PASS/0failures.
Combined capture/partition/frame run now63PASS/0fail/errors/skips. New envelopes: frozen payload8,
edit plan5, explicit-disconnection request2, authority4 when new facts require
them; old absent-fact snapshots retain prior layouts/identities. No cap changes.

Independent Sol audit of the actual whole task delta is active: core10files in
its isolated worktree, integrated UI2files in root release. Core retains the sole
Gradle slot for affected neighbors; no additional local builds allowed. Private
good-A frozen control helper is ready but uncompiled/unrun until root integration.

### Independent audit findings (must close before publication)

- C1 SOURCE CLOSED: old-known-fact projection is valid for recapture comparison, not for
  initial-source→effective authority derivation. Restore exact optional relation
  facts in SharedNodeDisconnectionPlanner and test unknown→known false/true.
- C2 SOURCE CLOSED: new partial-route proof must not ignore unknown/incomplete/restriction
  ancestors. Bound the new relaxation to empty parentWatches; retain historical
  fully complete proof behavior. Actual Omice routes have no captured parents.
- C3 SOURCE CLOSED: schema8 Legacy source without ParentPolicy did not round-trip because
  decoder assumed allocation present. Add schema8-only allocation presence flag;
  preserve v6/v7 bytes and test both valid variants.
- Additional coverage required: explicit-disconnection envelopes with new facts,
  optional-false roundtrip/hash, contradictory completeness rejection, and
  directional validation versus authority controls.

Sol found no material UI issue and no raised memory/work caps. Core worker owns
repairs/RED/GREEN; independent affected re-review remains required. The63-test
pass above precedes these findings and does not close the release gate.

Ancestry guard removal for a mutation/RED demonstration was rejected by automatic
approval review as an unsafe authority broadening. Worker keeps the guard and
does not bypass via shell. Its negative regression must be reported honestly as
GREEN-only if no preserved pre-fix artifact can prove the historical failure.
Other corrective findings retain ordinary observed RED/GREEN requirements.

Good-A helper baseline on unchanged RC13 compiled classes currently FAILS its
whole-output fingerprint assertion before checking geometry. This predates core
integration. Private helper author is diagnosing component/geometry differences;
do not claim an A-preservation PASS or attribute this to RC14 without evidence.
Log: build/private-omice-rc14-20261101/a-control-baseline-rc13.log.

Baseline A-control diagnosis is complete: all548ordered points, IDs, logical work
8257states/2137828transitions, support/objective/posterior/branch components match.
Only INFERENCE_SEMANTICS/ROUTE_RAW_SEMANTICS/ROUTE_FINAL_SEMANTICS differ, solely
retainedPairStateAllocations764644captured vs764642replayed (two fewer). Strict
capture fingerprint remains FAIL; do not waive it. This predates RC14. Compare
post-fix numerical output and replay components to this measured RC13 baseline;
report the original strict fingerprint discrepancy separately. Diagnostic log:
`a-control-baseline-rc13-component-diagnostic-3.log` in the private output root.

After corrective tests, core currently66PASS/0fail/errors/skips. This includes
allocation-policy absent/present, optionaltrue/false, constructor consistency,
FIXED scope/route ancestry/receiver geometry and source/effective authority parity.
Nonempty-disconnection/newfact envelope coverage and affected re-review still
must be explicitly confirmed before integration/publication.

Affected Sol source re-review closes C1/C2/C3 in inspected code, conditional on
remaining test receipts. Review confirms directional compatibility only at live
validation, exact authority derivation, no new ancestry relaxation, and symmetric
v8 allocation presence. Negative ancestry controls GREEN-only are explicitly
recorded; no historical failing-run claim.

Actual private Omice full-Hybrid comparison is running as direct Java session81341,
while the core's27compatibility neighbors use the sole Gradle slot. It uses the
worker's frozen audited compiled main classes + existing helper/tools/dependency
classpath; worker confirms no production/class changes during the run. Hash the
compiled class tree before/after; receipt must assert unchanged. Same original
OSM/native calibration and25m/all-source settings as baseline. New output identity
`fixed-core-rc14`; log/receipt under private output root. Do not run extra inference
or overwrite these outputs. Additional explicit-disconnection codec tests wait
until this private run ends; A-control post-fix is still pending.

Private fixed-core run81341 completed exit0 in55.959s; compiled tree unchanged
SHA256df1603766275ebdf9603f9db74b0ec436a9467386c88b681701015587dc1a1a8.
It removes whole-selection NO_CHANGE, but Omice still has NO USABLE HYBRID PLAN:
both actual Legacy fallbacks are FINAL_GEOMETRY_BLOCKED; the eligible section is
executed, but native B preparation reports RESOURCE_LIMIT / profile boundary
geometry work limit, with0DP states/transitions. This is the existing64M boundary
work gate, not the separate standalone-B128M failure or an8M bridge-work claim.
Dataset/source bindings unchanged. Release notes must distinguish the repaired
protection scope from unresolved Legacy geometry/B capacity; no quality success.
Additional explicit-disconnection codec tests may now run.

Core committed50513c57951b4dbc7ca24a059515eef090ab4a52 (10scopedfiles) and
cherry-picked into release as431ac2f. Worker's final receipts:66coreGREEN,
28affectedneighborsGREEN, explicit newfact+nonempty disconnection envelope1GREEN
(`payloadCompletenessWithExplicitDisconnectionRoundTripsEveryAuthorityEnvelope`).
Interrupted redundant combined rerun is not counted. Source unchanged since
private compiled-hash receipt. Core worktree clean; no worker push/publication.

Root release now runs sole local Gradle session96475, log
`/data/data/com.termux/files/usr/tmp/v022-rc14-integrated-focused.log`: action,
capture, partitioner, frozen-frame and disconnection-planner classes plus build,
Javadoc and tools. RC14version/README/UI/checkpoint/thisplan remain uncommitted.
Do not start another Gradle until terminal. After this run: post-fix A-control,
remaining source review confirmation, scoped commit/push, full public+GUI CI and
publish/download verification. Private Omice still blocked as recorded above.

To overlap required validation, commit/push the stable reviewed source while the
local integrated test/build task finishes; no source edits during that run. CI is
allowed to start before the local receipt, but publication remains gated on both
local checks and all full public/real GUI CI outcomes. No tag/release yet.

### Integrated verification at6f98691

Local integrated test/build/Javadoc/tools command completed exit0 in20m23s:
151tests,0failures/errors/skips (action62,capture19,partition30,frozen16,
disconnection24). Javadoc retains100warnings; build succeeds. Source was stable
throughout this run. Full public/GUI CI41264670843 targets the exact same source.

Post-integration good-A production replay diagnostic line is byte-identical to
the measured RC13 replay baseline:548ordered points and logical work
8257states/2137828transitions preserved. Its coordinate mutation negative control
is rejected. The original captured strict fingerprint still FAILS solely for the
pre-existing two-allocation diagnostic difference; no strict stable-release gate
is waived. Private receipt: a-control-rc13-rc14-comparison.txt. This is an explicitly
bounded RC13-versus-RC14 numerical comparison, not a claim of original strict
capture equality or stable0.22 readiness.

Fresh positive case03 production Hybrid control at the integrated revision exits0
in14.91s (inference9606ms). Both Legacy fallback and refined-0 have PLAN_AVAILABLE;
native B executes1699states/30365pair visits/1910572transitions. The dataset stays
unchanged. Native alternative search remains AMBIGUOUS/truncated, not fabricated
complete. Artifacts:private output root/positive-case03. This confirms an existing
applicable real refinement survives the protection change; it does not establish
Omice applicability or new quality/speed claims.

Full CI41264670843 PASS at exact6f98691ec4b9040642fb3c75e7eaefed3ee143a4:
2367Java tests,0failures/errors/skips, Python tests and every public stage PASS.
Both real JOSM19555 GUI engines PASS: geometry oracle, ordinary action, visible
preview, cancellation/no-late-preview, dataset/Undo unchanged, silent settings.
No private inputs uploaded. Validated CI JAR3011620bytes, Plugin-Version
0.22.0-rc.14/Mainversion19555, SHA256
af0fe6f4127cb44c6ce57e60070dd415361520d2010580bebf20ff9c2545514c.
Release published: https://github.com/holubp/josm-wayheatmaptracer/releases/tag/v0.22.0-rc.14
Public download verified byte-identical to CI, manifest correct, single JAR asset,
prerelease true/draft false; remote tag points exactly at tested6f98691 source.
Receipt:private output root/public-release-receipt.json. Documentation-only closure
follows the release source; it must not move the tag. Task complete for the bounded
RC14 request, not for final stable0.22. Remaining limitations above stay open.
