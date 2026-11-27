# Stepwise Legacy refinement — specification

Status: APPROVED by the user on 2026-11-10, including Astra high corrections and the concrete menu/source proposals, with subsequent user amendments below. Implementation is partial; component evidence and unfinished integration are recorded in the G004 execution ledger.

## Binding cumulative auxiliary policy amendment — approved 2026-11-27

The user explicitly approved “Approve combined64M and resumeG004”. One optional
click has ONE cumulative64,000,000-visit allowance including lazy bootstrap,
source preparation and Smoothen or A/B preparation. All transitions share the
same private counters, owner, cancellation token/probe and original absolute
five-minute deadline; no phase receives a fresh pool. Native/memory/history,
source/geometry/full-quality and transaction limits remain unchanged. Existing
three-argument tightening at or below 8M and actual 8M negative controls remain.
Rootless bootstrap status schema 2 records the actual context ceiling as
`auxiliaryMaximumVisits` and uses build identity
`runtime-stepwise-preparation-v2` in the format-15 archive. A context that was
never created leaves this value unavailable. Historical schema 1 and lineage
work without an explicit ceiling retain their original 8M validation bound.
This supersedes only the earlier bootstrap/Smoothen8M allowance below. Sufficiency
requires actual unchanged GUI/full/performance qualification, final audit and RC.

## Historical preparation policy amendment — approved 2026-11-26, superseded above

The user explicitly approved64,000,000 auxiliary preparation visits for an
actual A/B refinement ticket, across source palettes and fallback. Bootstrap
and smoothing retain8,000,000 visits. Native pair/score/state limits,256MiB
physical memory, retained history, cancellation and absolute deadlines remain
unchanged. This is no solver, conversion, source-grid or numerical-method change.

An inherited bootstrap phase and its refinement share one private counter state,
owner, byte ceiling, cancellation probe and absolute deadline. Meter/admit the
small phase view before allocation; no reset or per-palette pool is allowed.
Diagnostics carry the actual effective ceiling and validate its ticket kind;
older missing values stay unavailable under their original8M bound. Existing
three-argument test tightening sets both phase ceilings together at≤8M, and
actual GUI negative coverage uses only an EDT-owned tightening scope. Preserve
the original400m positive assertions and all source/geometry/transaction guards.

## Binding workflow correction — approved 2026-11-22

This amendment supersedes conflicting Legacy-only roots, method-selector removal,
refinement eligibility and dialog/export instructions below and in subordinate
plans. Historical decisions remain for provenance, not as current scope.

- Settings retain the starting algorithm choice: Legacy, corridor-based A or
  experimental B. Preserve configured/one-shot Precise Shape or Move Existing
  Nodes mode. A/B roots are valid; Legacy ancestry is not required.
- Every result is reviewed in a modeless dialog. Select a computed result and
  Apply immediately if desired, under unchanged safety/confirmation checks.
  Refinement and smoothing are optional.
- Provide three buttons: **Refine using A**, **Refine using B**, **Smoothen**.
  Each uses the exact selected geometry. Operations may repeat in either order,
  including A/B after smoothing.
- Hide the current review dialog during computation; show the new review dialog
  with the results when finished. A/B runs configured available detector
  alternatives; the user selects the best result afterwards. The parent's
  detector is not a preassignment restricting child detector exploration.
  Existing managed/rendered-source and unavailable-palette rules still apply.
- Preview Undo/Redo restores complete retained review stages, selections,
  controls and findings without recomputation. Only successful new publication
  after Undo replaces the forward branch; failure/cancellation preserves it.
- Medium/Strong remain alternatives from the same pinned unsmoothed parent,
  not silently compounded smoothing. Subsequent explicit A/B refinement may
  use the selected smoothed geometry.
- **Export last slide debug** stays exclusively in the menu/keyboard shortcut,
  usable with every modeless dialog and after failed attempts. Do not put an
  export button in review dialogs.
- Failure/cancellation restores the previous review and selection with a
  readable explanation. Handle expected failures and unexpected operation
  exceptions at UI/worker boundaries, settle resources and preserve truthful
  redacted diagnostics. Cancellation remains accessible while review is hidden.
  Only the current attempt may publish; no late worker may mutate OSM.
- Source/data changes invalidate Apply and require recomputation. Failed Apply
  remains atomic, adds no Undo entry, preserves unrelated history and closes
  the stale preview with visible failure and accessible diagnostics. History
  and confirmation never authorize unsafe or stale output.
- Only final Apply changes OSM through one stored undoable command. Preserve
  junction protection, original edit authority, interval fallback, resource
  bounds and whole-output safety. No tracing algorithm redesign is authorized.

Status: approved requirements; G004 execution resumed explicitly on2026-11-22
and its runtime goal is ACTIVE. Implementation is partial, with current
evidence and remaining work in the canonical execution ledger and the approved
[completion plan](../plans/2026-11-22-g004-workflow-completion.md).
Approximately 20% weekly allowance is a planning target,
not a measured estimate or permission to omit checks. Reuse valid evidence
and a coherent final independent audit rather than restart component reviews.

## Historical primary-method simplification — 2026-11-19 (superseded above)

The user supersedes the earlier standalone-method selector: **Legacy is the only
first-pass method for new live sessions; A and B are refinement methods only**.
Ordinary Align and the Precise/Move invocation shortcuts start Legacy. After
selecting a Legacy result, the user may explicitly refine using A/B, optionally
smooth any selected Legacy/A/B stage, and navigate Back. No refinement runs
automatically. All new sessions therefore satisfy the Legacy-root requirement.

### Ordinary user workflow and acceptance examples

Run Legacy once and review its color-scheme candidates. If the selected Legacy
result is good enough, Apply it immediately under the normal safety/confirmation
rules: neither A/B nor smoothing is a prerequisite. Nothing else runs unless the
user explicitly requests it.

Otherwise refine the exact selected result with A or B, or smooth it directly.
Refinement may be repeated in either order; smoothing is optional after Legacy
or a subsequent refinement. Required supported paths include:

- `Legacy → Apply`
- `Legacy → Smoothing → Apply`
- `Legacy → A → B → Apply`
- `Legacy → B → A → Apply`
- `Legacy → A → B → Smoothing → Apply`, with repeated refinement as needed

Before final Apply, Back may be used repeatedly through the entire retained
active ancestry, restoring each previous stage's exact geometry, selected
candidate and color scheme. It is not a one-level-only undo. Each stage keeps
its color-scheme alternatives available for review. For example, Back from
`Legacy blue → A gray → B hot` restores `A gray`, then `Legacy blue`; the user
can choose a different Legacy palette and refine that selection instead.
Changing a palette selects that stage's factual result, never a blended geometry
or a hidden rerun. A new successful refinement after Back replaces the forward
branch, consistently with linear history. History limits refuse additional work
rather than deleting earlier versions needed by Back. All previews remain
dataset-side-effect-free; only the final selected version enters Apply/Undo.

### Preview Undo/Redo clarification — 2026-11-20

The user's clarification adds explicit **Undo** and **Redo** controls to the
review dialog. Existing Back is the preview Undo operation; this is not JOSM
dataset Undo/Redo. Undo restores the preceding retained stage; Redo restores
the next retained stage. Each restores that stage's actual geometry, candidate
list, selected candidate/palette, stage-local refinement/smoothing choices and
quality findings. Navigation performs no inference, acquisition or OSM command.
It never rolls back global plugin preferences or manufactures fresh authority.

At an earlier stage the user may choose a different color-scheme candidate and
refine it. Candidate selection alone does not discard the saved forward branch;
Redo restores the saved next stage with its factual input lineage, without
pretending that it was computed from a newly selected earlier candidate. Only
successful publication of a new operation replaces the forward branch. Failed
or cancelled replacement preserves it. End-of-history Redo and root Undo are
disabled; both are disabled while an operation owns conflicting state. All
retained forward stages continue to count toward history limits and ownership.

Both directions clear Apply confirmation. They retain original source/selection
authority and stale checks; navigation cannot make a stale output applicable.
All changes remain previews until one final Apply, whose JOSM Undo/Redo is a
separate transaction contract. This amendment is required but not yet verified
by the previously passing Back-only component tests.

| ID | Requirement | Verification |
| --- | --- | --- |
| S08 | Preview Undo/Redo restores retained stage geometry and review state without computation, preserves forward history on selection/failure/cancellation, replaces it only on successful new publication, and obeys confirmation, ownership, limits and stale-authority rules. | History round-trip/branch/cancel/limit tests; actual dialog controls and final transaction tests. |

Remove the starting-method selector and obsolete standalone/Hybrid launchers.
The existing Image engine is not an alternative live starting choice under this
Legacy-only rule; retain its historical IDs/readers and internal services where
needed for old evidence. This does not remove image/intensity-source settings,
palette options, authentication, alignment mode or relevant protection controls.
Show refinement-specific settings only where they are useful, separately from
the ordinary Legacy settings. Settings acceptance must not add a modal notice.

Normalize old saved A/B/Hybrid/Image starting choices to Legacy for future live
sessions, preserve unrelated settings and effective mode, and export the factual
effective method. Keep historical capture engine identities/versioned readers
unchanged; migration must not relabel old evidence or rewrite replay defaults.

Medium/Strong are sibling alternatives from one pinned unsmoothed parent.
Changing strength must not silently run Strong on Medium or compound repeated
smoothing. Back restores that parent; a later explicit A/B stage can have its
own optional smoothing step. This preserves the requested linear workflow and
the advertised displacement limits without another configuration choice.

## User-requested optional smoothing extension — 2026-11-19

### Superseding release-scope decision — 2026-11-21

The user approves using the existing visually reviewed Gaussian smoothing for
the first G004 workflow release, with later quality improvements owned by
[G008](../goals/2026-11-21-g008-smoothing-shape-improvements.md). This supersedes
the requirement below to select/qualify a new turn-preserving numerical method
before integration. It does not claim that the existing proposal is already a
production adapter.

For this baseline, preserve the gallery method's actual recorded capture-raster
transform/dimensions and Gaussian policy. Identify that versioned reference
frame truthfully; do not relabel an evidence raster as original ARGB pixels or
fit a fictitious original affine. Browser zoom/pan must not change a stored
candidate's smoothing scale. New raw-original nonlinear/ribbon certification
research from the later method proposal belongs to G008, while ordinary exact
coordinate conversion, stored-output safety and edit authority remain required.

S01–S06 remain required: explicit Medium/Strong, factual capture scale, exact
selected parent/palette, noncompounding strengths, cached preview history,
ownership/diagnostics and one final transaction. S07's improved preservation of
turn/apex shape and switchback width is deferred to G008; its hard whole-output
topology, protected-anchor, source-fit and original-authority checks remain
mandatory. Unsafe smoothing is refused/backtracked while retaining a usable
parent. No fabricated success, support or Apply authority follows from accepting
the current quality. Legacy/A/B numerical kernels remain unchanged.

The user requests optional smoothing of the selected Legacy result, or of a
selected subsequent A/B result, within this stepwise workflow. This adds a
stage operation; it does not approve the current G006 geometric prototype for
Apply or waive the turn-shape/topology failures found during visual review.
The algorithm remains owned by G006 and must be qualified before integration.

| ID | Required behavior | Verification owner |
| --- | --- | --- |
| S01 | Smoothing is explicit and optional in the modeless review dialog, after the selected Legacy or A/B result. No automatic smoothing or extra top-level engine launcher. | G004 stage/UI tests |
| S02 | Offer Medium (1 reference-image pixel) and Strong (2 reference-image pixels), with the qualified approximately ±20 reference-pixel longitudinal scale. Bind physical units to the factual parent capture, independent of browser zoom and raster resampling. | G006 numerical tests; G004 parent/source integration |
| S03 | Operate on the exact selected candidate and its palette/evidence, retaining unsmoothed geometry and candidate identity. Do not combine completed palette geometries, smooth an implicitly better candidate, or rerun Legacy/A/B. | G004 selected-parent and provenance tests |
| S04 | Back restores the exact preceding stage and selection. The full chosen chain, including smoothing, remains preview-only until one final Apply/Undo/Redo against original authority. | G004 history/transaction/stale tests |
| S05 | Preserve effective Precise/Move mode, original protected occurrences, fixed junction vicinities and original edit bounds. In Move mode assess the actual existing-node output, not an unrepresentable dense curve. | G004 mode/authority tests |
| S06 | Keep Medium/Strong choice, exact smoothing parent, actual work, source identity and final geometry exportable. Cancellation or refusal retains the valid parent; it never fabricates support or publishes a late child. | G004 ownership/diagnostics tests |
| S07 | Preserve heatmap-supported turn placement, apex/turn shape and switchback width, while suppressing small wrinkles. Newly introduced self-crossings/touches/overlaps are not applicable. Assess the exact final assembled geometry, with source fit and original protections. | G006 marked real cases and topology regressions; G004 whole-output safety |

**Eligibility resolved:** all new live sessions start with Legacy, so optional
smoothing is available after Legacy or any eligible A/B descendant. There is no
standalone-A/B-first case to expose or configure. Strength changes use the same
unsmoothed parent as specified above, preserving exact Back and no compounding.

**Evidence/algorithm status:** overall smoothing was visually useful, but local
feedback identifies inside-turn shortcuts, narrowed switchbacks, retained or
amplified beaks, and a newly introduced Strong self-intersection. The current
Gaussian request is not an approved production adapter. See
[G006 Astra analysis](2026-11-19-g006-turn-preserving-smoothing-astra-analysis.md)
when finalized; its replacement algorithm is a proposal, not adopted code.

## Intent and authority

Replace automatic Legacy-then-B Hybrid execution with deliberate refinement from
the modeless review dialog. Users choose the starting Legacy candidate and its
color scheme, inspect A or B results, go Back when they dislike a result, and
optionally continue A→B or B→A. Subjective acceptance belongs to the user; safety
checks remain mandatory. This specification changes orchestration/UI, not solver
mathematics or the separate bounded-coarsened feasibility research.

Confirmed decisions:

- Legacy is the sole live starting method; remove the method selector. A/B are
  explicit refinement operations in the review dialog, not primary modes.
- All ordinary sessions start with Legacy. Descendants retain refinement and
  optional smoothing even when their current method is A or B. Missing, stale
  or incompatible parent/source authority still disables the affected action.
  Reject non-Legacy roots at service boundaries for historical/invalid sessions;
  do not instruct users to change a selector that no longer exists.
- Preserve the effective alignment mode: settings default plus the user's
  invocation override. Both Precise Shape and Move Existing Nodes must work;
  never silently change modes or inherit the old Hybrid's Precise-only limit.
- Each refinement operates on the selected candidate and offers color-scheme
  alternatives for review. It does not refine every parent candidate automatically.
- Nothing changes OSM data before final Apply; the complete chosen chain is one
  atomic command and one Undo entry.
- Use linear history. Back restores the preceding stage and its selected
  candidate. Starting a different refinement replaces the forward branch.
- Try the complete selected range first. On a contained refinement failure,
  refine all eligible continuous junctionless intervals with more than two nodes,
  retaining the parent geometry where an interval cannot be refined.

The original range can be a whole way or a way-plus-two-endpoints selection.
Refinement never expands that original edit authority.

## Existing implementation and base

Planning baseline is released RC15, source
`111e5027c4d09e8a8ef672c2c58045edc0ce2663`, not the dirty historical root checkout.
Existing `service/tracing/hybrid/LegacyB*` services provide reference geometry,
protected-occurrence reconstruction, interval assembly, work accounting and final
assessment. `AlignWayAction`, `PreviewSessionController`, `PreviewReviewState`
and `HybridSectionReviewPanel` own current preview behavior.
`JunctionSegmentSelector` and `SelectLongestSegmentAction` define Ctrl+Shift+J's
endpoint rules. Reuse those rules for **all** returned intervals rather than
repeatedly invoking the selection-only action or changing the user's selection.

Existing Hybrid is B-only and Precise-only; extending it is substantive work.
In particular A's final reconstruction and Move-mode assignments require real
integration tests, not merely enabling buttons or relabeling B results.

## Approach selection

Chosen by user: **linear stage history**. Each stage retains its candidates and
selected candidate. Back walks the active ancestry; starting a new refinement
after Back removes the discarded forward branch.

Rejected for this implementation: branching history retaining every exploratory
path. It adds navigation, ownership and memory complexity beyond the requested
Back behavior. Automatic Legacy+A/B is also rejected: it prevents choosing the
starting palette result and performs work the user did not request.

## Stage and session contract

Each immutable stage records its ID, parent stage ID, exact parent candidate ID,
root method, effective alignment mode, selected candidate ID, engine, source and
settings identities, candidate geometry/assignments, original-occurrence mapping,
quality, interval provenance, actual work and diagnostic/export availability.

The original OSM snapshot remains the only authority for permissions, original
node identities, stale validation and final command planning. Derived geometry is
only an engine reference. Do not synthesize a changed DataSet or pretend a parent
preview was captured OSM geometry. Precise stages may insert/drop permissible
interior points while maintaining monotonic original-anchor mapping. Move stages
preserve the original node sequence and identities, with no insertion/deletion.

Selecting a candidate updates only that stage's selection. Refine pins that exact
candidate, even if other candidates have higher scores. No implicit best-candidate
selection or automatic follow-up refinement is permitted. A child becomes the
current stage only after its final candidates and provenance are validly assembled.
If no usable distinct result exists, retain the parent stage and explain why.
Changing to an earlier stage does not reuse another stage's review confirmation.

Back is preview navigation, not JOSM Undo. Switching candidates or stages, starting
refinement, or returning Back clears confirmation for the newly selected output.
Cancellation of refinement leaves the valid parent available; a stale-source or
authority failure instead disables Apply throughout the stale session. Back may
not renew authority or make a stale candidate applicable.

Proposed UI: existing candidate selector and quality display, stage summary,
**Undo**, **Redo**, **Refine using corridor-based A**, **Refine using B**, optional smoothing
with **Medium/Strong** alternatives, existing review confirmation, Apply and
Close. Root Undo and end-of-history Redo are disabled. Explain actual source/mode/stale/authority
reasons when an action is unavailable; normal sessions already start with Legacy.
Running a job disables conflicting controls; Close/Cancel cancels the owned job.
Keep the dialog modeless, scroll/wrap bounded text, and keep buttons accessible.

## Color schemes and imagery

Refinement considers the five managed base schemes (`hot`, `blue`, `bluered`,
`purple`, `gray`) separately, exposing their distinct results in the stage's
candidate list. Choosing the Legacy parent determines geometry, not a palette
lock. Preserve native palette semantics and configured direct-intensity modes.
Do not average completed geometries or call five scalar remappings of one visible
palette five acquired source schemes.

All palette acquisition and processing is explicit and bounded through the
plugin-owned coordinator. Preserve per-palette attempted/unavailable/failed states;
never imply a scheme was evaluated when it was not. For visible-only capture,
only actually available imagery may be used; report the unavailable scheme
coverage and retain the parent if no supported refinement can run. Authentication,
source-generation and integrity failures retain their existing attempt-wide
semantics, rather than being hidden as an ordinary missing palette.

Resample profiles around the pinned parent geometry, from factual imagery covering
that reference, its configured physical search corridor and evidence halo. Reuse
frozen imagery only when it genuinely covers those requirements; otherwise use
bounded generation-consistent acquisition. Never silently widen search, project
off-raster points or borrow original-centered profiles as parent-centered evidence.

## Whole-range attempt and interval fallback

For each requested engine/source combination, first attempt the selected parent
range as a whole. A returned review-required candidate is not a failed execution:
show it if it passes applicable structural/authority checks. Never use a score
threshold to decide whether the user would like it.

If whole-range refinement has no usable route, unsupported local geometry or
bounded coverage/eligibility failure, partition using original occurrence topology
and the parent's protected-anchor mapping. Enumerate every maximal eligible
junctionless interval in original selection order. Apply Ctrl+Shift+J's existing
unsafe-boundary trimming; require more than two existing original occurrences
after trimming, so inserted Precise preview points cannot create eligibility.
Respect fixed/tagged/shared nodes, relation restrictions and frozen vicinities.
Do not move a protected boundary just to make a refinement join.

**Frozen-geometry source contract (implementation finding, 2026-11-20):**
protecting existing node coordinates alone does not preserve a frozen vicinity:
Precise tracing could insert a different curve between those nodes. Retain a
validated captured partition/restriction envelope with the original root, bound
to its exact selected way/range, dataset/generation and primitive/referrer parity.
Frozen spans retain the selected parent's complete geometry. A captured partition
takes precedence over inferred simplifications. An absent proof is not an empty
partition. A no-island proof may instead be derived from sufficiently complete
original snapshot facts, including the exact safe two-way endpoint-continuation
rule; never infer it merely because endpoints are protected or hashes match.
Normal fixed ends on a two-node way must not imply a full-way frozen island.
Ambiguous complex captures refuse qualified refinement without losing the
otherwise valid Legacy Apply. This preserves the existing frozen-vicinity
requirement; it grants no new edit authority or physical buffer.

Each interval references the actual parent geometry and keeps endpoints exact.
Successful intervals use the requested A/B geometry; failed, frozen, degenerate
or ineligible intervals keep the **selected parent** geometry, which can already
contain prior A/B work. Do not revert these to the root Legacy or original OSM.
One local failure does not discard successful siblings. Produce coherent bounded
whole-range alternatives per palette, not a Cartesian product of all interval
alternatives. Expose attempted/used/retained/unattempted states and reasons.

Cancellation, stale source, invalid authority, unknown work, exhausted shared
budgets/deadline or source invalidation do not authorize a fallback with a new
pool. The whole-range attempt's actual work counts against subsequent interval
work. A fallback is not an automatic second full-range retry.

Reconstruct original protected anchors and reassess the exact assembled output:
joins, unsupported jumps, sharp backtracks, crossings/touches/overlaps, assignments,
downloaded area and original edit authority. Local engine success never bypasses
whole-output safety. Subjective user preference can reject any result, but cannot
override a hard safety block.

Native review/hard-block reasons survive composition and source deduplication
with their factual provenance. A new common assessment cannot upgrade native
unsafe output by dropping its findings; a single deduplicated geometry must not
launder one palette's review status through another palette's assessment.

## Mode, ownership, budgets and Apply

Use the production A/B implementations selected by current supported policy;
do not adopt unreleased coarsened-model helpers as part of this feature.
Keep solver objectives, state limits, K policy and Legacy algorithms unchanged.

Each explicit refinement click is a new owned operation against a pinned stage
within the same immutable-root preview session. Allocate its finite acquisition,
work, memory and deadline before execution; share them across palettes, whole
attempt and fallback intervals. No hidden per-interval reset or recursive retries.
Bound retained history memory/stage depth; at a limit, explain and refuse another
stage without losing existing Back history. Record cumulative session work as
well as operation work. Exact numerical bounds belong in the implementation plan.

Use deterministic attempt/sequence ownership so cancelled, replaced, closed or
discarded-forward work cannot publish a child or later mutate OSM state. Stage
publication and selection are EDT-owned; expensive work stays off EDT. Source,
settings and dataset changes are revalidated before refinement, candidate switching,
Back confirmation and Apply. Incompatible settings changes require a new session,
not a chain with silently mixed mode or provenance.

Final Apply rebuilds an immutable command against the original snapshot with the
current candidate's complete assignments and protections. It rechecks current
dataset membership, original coordinates/sequences, authority and confirmation.
Undo restores the exact pre-session state; Redo uses stored final coordinates and
retains the existing stale-Redo, deletion/upload and history-integrity contracts.
No intermediate Legacy, A, B or interval command is added to Undo.

## Binding corrections from Astra high review

The following precise contracts supersede any broader reuse or protection wording
above. The [review report](2026-11-10-stepwise-refinement-astra-review.md) records
the RC15 source findings; these are required deliverables, not optional polish.

1. **Real adapters:** implement engine-independent parent-reference binding plus
   production A/B and Precise/Move adapters. RC15's `LegacyBReferenceFactory` is
   B/Precise-only and rejects interior protected/shared anchors. Do not weaken its
   old checks or manufacture a native run to satisfy whole-first. Whole-range
   eligibility refusal may trigger contained interval fallback; export whether
   inference actually ran.
2. **Complete correspondence:** map every original occurrence needed as a fallback
   cut to a parent vertex or explicit edge position, including removed Precise
   occurrences. Materialize a cut by subdividing the exact parent edge without
   changing its shape or inventing evidence. Ambiguous mapping freezes only the
   affected interval. Generated identities are stage-scoped. Move's final quality
   and Apply assess the representable existing-node polyline, not a dense solver
   curve; unrepresentable results remain inspectable/blocked, never silently Precise.
3. **Parent-backed composition:** preserve authorized protected/boundary positions
   from the pinned parent. Originally immovable anchors stay at their original
   positions; independently authorized Legacy junction placements must not be
   reset or moved again. Root authority and cumulative displacement constraints
   apply across all stages. `FixedIntervalEditPlanComposer` needs a new explicit
   parent-backed path; its original-OSM fallback cannot be reused unchanged.
4. **Explicit source set (proposal):** refinement requests the five managed base
   sources separately; configured alternate detectors remain additional variants,
   and a configured all-color aggregate remains a separate strict-complete scalar
   candidate. Variants are not extra acquisitions. Advertise requested versus
   attempted/unavailable/unattempted coverage. The visible modern route currently
   rejects direct intensity; preserve this clearly reported limitation for the
   first implementation rather than silently replacing intensity mode. Managed
   support does not prove visible support. New pixels never expand root collision
   or edit authority. Visible-only five-source unavailability remains a proposed
   behavior for user approval, not a claim all palettes ran.
5. **Bounded scheduling:** deterministic palette/interval order, preflight and
   reserved assembly/final-assessment/export capacity are mandatory. Retain
   RC15's stop-further-inference policy on native RESOURCE_LIMIT; it must not
   trigger a new fallback pool. Export completed work and unattempted coverage.
   Publish a partial candidate only if its complete assembled safety assessment
   actually finishes; otherwise retain the parent and failure diagnostics.
6. **Session plus operation ownership:** establish one root owner before Legacy
   starts and monotonic operation sequences thereafter. Publication must match
   session, operation, parent stage and parent candidate, including cancel/restart
   on the same parent. Back is disabled while running; Cancel returns to the
   retained valid parent; Close terminates the session. Apply terminalizes the
   session before queuing its single command. Failed/cancelled operations publish
   their current-owned diagnostic record even when no child stage is created.
7. **Common final assessment:** report factual palette/evidence for each metric;
   include parent/frozen fallback lengths once in supported/assessed length.
   Unknown quality remains unavailable. Refinement earns no ranking bonus and
   engine objectives are not averaged. Structurally valid but review-unconfirmed
   parents may be refined; missing source/assignment authority or hard structural
   safety blocks refuse refinement. Rejected outputs remain inspectable/exportable
   but cannot bypass blocking findings or acquire Apply by refinement alone.
8. **Migration and lifecycle:** hidden mode-launcher action objects stay alive for
   shortcuts. Destroy Diagnostics submenu and children on unload. Replace the
   old `HYBRID` notification that advertises Legacy-then-B, as well as migrating
   `LEGACY_THEN_B`; neither notice may direct users to a removed method.

Acceptance tests must pin each correction, especially trimmed removed occurrences,
authorized parent junctions, native resource refusal, failure-only exports and
current-owned publication after Cancel/Back/Close. Numerical scheduling/history
limits and exact adapter interfaces belong in the subsequent implementation plan.

## Menu and preference cleanup (proposed concrete layout)

Keep five top-level plugin entries: Align Way to Heatmap; Select Longest Junctionless
Segment; Heatmap Settings; Geometry Cleanup Settings; Diagnostics submenu. The
Diagnostics submenu retains Last Slide Debug and Calibration Tiles export.
Remove the duplicate Precise/Move visible launchers but retain their existing
shortcuts and invocation overrides. Keep the ordinary alignment shortcut and
Ctrl+Shift+J unchanged. No shortcut is reassigned without conflict verification.

Remove all standalone A/B/Image and automatic Hybrid choices/launchers and the
starting-method selector. Legacy alone starts alignment; A/B remain review-only
refinements. Migrate old non-Legacy starting preferences, including
`LEGACY_THEN_B`, to Legacy for future live sessions without automatic refinement.
Preserve effective Precise/Move mode and unrelated preferences. Retain historical
engine IDs, readers and required internal implementations for replay/old captures.
A fixed explanatory hint may describe refinement; no modal settings notice.

## Diagnostics and acceptance

Last-slide export remains accessible for success, partial results, cancellation
and failure through modeless UI. Export root geometry, selected parent, displayed
child, effective mode, palette attempts, stage ancestry and exact interval/work
provenance separately. Only current-owned stages publish latest diagnostics;
discarded forward jobs cannot replace the current record. Historical stage export
must identify the chosen stage rather than relabel it “latest.” Preserve old
Format-15 readers and state unavailable fields honestly. No credentials/private
fixture content enters public test artifacts.

Required deterministic tests cover: Legacy-only live starts and A/B refinements
through descendants; removed-method preference migration and historical-reader
compatibility; invocation override precedence; both modes; selecting a
non-top Legacy palette; all palette attempts; A→B and B→A; Back/forward replacement;
cancel/stale/late publication; whole success versus interval fallback; multiple
junctions and >2 trimmed occurrences; mixed successful/frozen/failed intervals;
protected tags/relations; parent rather than root fallback; shared budget exhaustion;
fresh capture/halo and assignments; one Apply/Undo/Redo; unavailable export paths;
menu/preference migration and real-dialog keyboard/layout usability.

Completion requires real production A and B integration, both modes, meaningful
failure tests, independent review, appropriate public/GUI/transaction regressions,
unchanged Legacy evidence and performance guard, and a user-testable verified RC.
The user approved this design for implementation planning. Execution and publication
remain separate actions; approval does not assert any implementation or test result.
