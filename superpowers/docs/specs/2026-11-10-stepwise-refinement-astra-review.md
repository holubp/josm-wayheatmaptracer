# Stepwise refinement design review

Reviewed: 2026-11-10. Planning only; no implementation, tests, JVM, private captures,
source acquisition, or native execution. Basis: the draft specification, G004,
the approved 2026-10-28 Legacy/B design, and targeted source reads at published
RC15 `111e5027c4d09e8a8ef672c2c58045edc0ce2663`. The dirty checkout's production
sources were not treated as RC15. This is a design/buildability review, not
implementation or release verification.

**Verdict: revise before approval.** The workflow is feasible without changing
Legacy mathematics or A/B objectives, but the following contracts need correction
before an implementation plan can reliably deliver it.

## Consequential findings and minimal corrections

1. **A refinement adapter is a required deliverable, not straightforward reuse.**
   RC15 `LegacyBReferenceFactory.prepare` accepts only `LEGACY_THEN_B`;
   `validateReferenceAuthority` requires Precise and rejects interior shared or
   protected occurrences. Its sampler constructs a B request. Consequently this
   adapter cannot execute the proposed whole-range attempt across junctions, A,
   or Move. Specify engine-independent parent-reference binding with separate
   original authority and immutable parent geometry, followed by mode-specific
   production A/B adapters. A whole-range eligibility refusal may trigger contained
   fallback; do not manufacture a native run or weaken the existing interval
   checks merely to satisfy “whole first.” Record whether inference actually ran.

2. **Protected-anchor mapping alone cannot implement trimmed fallback after
   simplification.** Ctrl+Shift+J trims to an adjacent original occurrence that a
   Precise parent may have removed. `LegacyBReferenceFactory.partition` requires
   mapped vertices; `LegacyBOccurrenceReconstruction` demonstrates that additional
   reconstruction is already necessary. Require monotonic original-occurrence
   correspondence to parent vertices or explicit parent-edge positions, including
   removed occurrences needed as fallback cuts. Materializing a cut must subdivide
   the exact parent edge without changing its shape or inventing observed evidence;
   ambiguous mappings freeze only the affected interval. Keep generated-point IDs
   stage-scoped. In Move, require exactly the original ordered occurrences and
   assess their resulting polyline, not an unrepresentable dense solver curve;
   retain the existing `PRECISE_SHAPE_REQUIRED` block without switching modes.

3. **Parent preservation and original protection must not conflict.** The phrase
   “reconstruct original protected anchors” could reset an independently authorized
   Legacy junction placement. The approved prior design allowed such placements
   but prohibited B from moving them again. State that refiners preserve the pinned
   parent's authorized protected/boundary coordinates; originally immovable anchors
   remain at their original coordinates. Root authority and cumulative displacement
   limits still govern every descendant, so repeated clicks cannot walk outside
   the original permission envelope. Also, `FixedIntervalEditPlanComposer` currently
   fills frozen gaps from original OSM. It requires an explicit parent-backed
   composition path; direct reuse would erase earlier refinement successes.

4. **Palette and source scope is underspecified at the settings boundary.** Five
   acquired base renderings, alternate detectors on one rendering, and the scalar
   all-color aggregate are different options. RC15's source plan does not acquire
   all five merely because diagnostics are visible. Specify that a refinement
   click explicitly requests its advertised source set and identify what existing
   alternative/aggregate settings do. Preserve unavailable/failed/unattempted
   distinctions, native semantics and strict complete-aggregate behavior. Do not
   silently drop configured variants or represent them as additional acquisitions.
   RC15 `LiveBPreviewService.requireSupported` rejects direct intensity for the
   visible modern route; promising it for refinement requires a supported evidence
   adapter, or an explicit visible-source limitation pending approval. Managed
   support does not establish visible support. Fresh imagery must remain bound to
   the root source generation and a sufficient root collision/authority snapshot;
   additional pixels alone cannot authorize an expanded edit region.

5. **Shared limits need scheduling and terminal-result semantics.** “All palettes”
   and “all eligible intervals” otherwise become promises the first expensive
   whole attempt can consume. Require deterministic scheduling, preflight limits,
   explicit truncation, and reserved assembly/final-assessment/export capacity.
   Exhaustion must stop new inference while retaining completed work as diagnostic
   evidence; a partial candidate may be published only if its final assessment
   actually completes. Clarify native resource refusals separately from contained
   no-route/coverage failures. RC15 `LegacyBPipeline` stops further inference on
   native `RESOURCE_LIMIT`; preserve that policy unless explicitly approved to
   change it. Never reinterpret that refusal as a fresh fallback allocation.
   Numerical limits may follow in the plan, but these semantics belong here.

6. **Root ownership must cover Legacy as well as modern descendants.** RC15's
   `PreviewSessionController.open` supersedes a window; it is not a stage-history
   operation. Specify one session owner from the initial Legacy invocation and a
   separate monotonic operation sequence per refinement. Publication must match
   session, operation, parent-stage and parent-candidate identities, including
   after cancel/restart on the same parent. Cancel should return to the retained
   parent; Close should close the session. Back during work must have one defined
   behavior (the proposed disabled control is sufficient). Apply terminalizes the
   session before queuing its single command. Failed/cancelled work needs its own
   current-owned diagnostic record even when no child stage is published; otherwise
   “only current-owned stages publish latest diagnostics” loses failure evidence.

7. **Quality and safety need explicit inheritance rules.** The approved prior
   design required common final-geometry assessment, with retained sections in the
   denominator and no ranking bonus for refinement. Restore those requirements:
   evaluate the exact assembled candidate, include parent fallback lengths once,
   identify the factual palette/evidence for each reported metric, and show unknown
   support as unavailable. Do not average engine objectives or transfer a parent's
   review confirmation. State whether a structurally valid but non-applicable
   parent may be used as computational input; regardless, unchanged unsafe sections
   must keep the child blocked. Keep rejected outputs inspectable/exportable without
   treating them as successful refinements or permitting Apply.

8. **Menu migration is feasible but the legacy migration path must also change.**
   RC15 registers all actions in a flat menu and retains separate shortcut action
   objects. Keep those objects alive when hiding their menu items, and adapt
   submenu destruction to remove the Diagnostics container and its children.
   The existing old-`HYBRID` notification explicitly advertises “Legacy then B
   Hybrid”; replace that notice as well as migrating `LEGACY_THEN_B`, so neither
   migration directs users to a removed method. The five-entry layout, hidden
   mode launchers, and exact migration explanation remain proposals for approval.

## Decisions and questions

Already settled: standalone methods remain; only Legacy-root sessions refine;
A→B/B→A chaining; effective invocation mode; user-selected parent; linear Back;
one final Apply/Undo; whole-first with contained interval fallback. Do not reopen
these or revive unrelated B-model research.

Only these scope questions need approval if the conversation has not settled them:

1. Does “all color schemes” mean all five separately acquired base palettes while
   preserving configured alternate detectors/aggregate, or a different explicit
   set? Approve the advertised behavior when only visible imagery is available.
2. Approve the proposed concrete five-entry menu and removal of visible Precise/
   Move launchers while retaining their shortcuts.

The remaining findings are engineering corrections, not reasons for additional
permission rounds. Incorporate them, obtain design approval, then write the plan.
