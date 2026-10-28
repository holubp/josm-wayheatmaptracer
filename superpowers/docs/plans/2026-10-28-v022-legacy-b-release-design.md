# v0.22 Legacy-first release design

Status: approved by maintainer, including Precise-only Hybrid and preference migration.
Date: 2026-10-28.
Scope: release-focused continuation, not a restart of the unsuccessful shared-position research experiment.

Builder check: independent gpt-6.1-sol / medium review found no additional
important planning gaps. Actual runtime model/effort verified from turn metadata.
Maintainer approved Precise-only Hybrid and old-Hybrid preference migration.
Maintainer additionally confirmed removal of the old A+B implementation, not
merely its selectable entry. FinalGeometryEvaluator already supplies a
common final-geometry support integration to reuse; no new quality-model research
is required. This check is not an implementation or release correctness audit.

## Intent and authority

Ship a practical 0.22 with Legacy v0.2 as the default, Corridor and existing
experimental B as options, and a replacement Hybrid which runs Legacy then B.
Remove the informational modal after saving Heatmap Settings (Ctrl+Shift+U).
Aim for at most one additional public RC, followed by final release after its
acceptance and mandatory checks. CI builds and commits are not public releases.
An RC-count target never authorizes shipping known safety defects.

The original implementation plan and approved corrections remain the baseline.
This document specifically replaces the old A+B Hybrid behavior and changes the
default engine. Reconcile the completion matrix before execution; unrelated open
requirements need evidence or an explicit approved disposition, not silent deletion.
Keep the previously approved METHODS.md and 256 MiB proof deferrals to 0.22.1.
Do not revive the failed shared-position model, tune standalone B, or add new
research as part of this release cleanup.

## Approaches and decisions

1. Independent Legacy and B runs: cheap to integrate but does not give B the
   better starting course requested by the maintainer. Rejected.
2. One Legacy run followed by one whole-selection B run: provides refinement but
   lets a junction prevent useful refinement elsewhere. Superseded.
3. Legacy followed by interval-local B refinement: chosen. Preserve useful Legacy
   alternatives, refine eligible sections independently, assemble and assess the
   actual result. A local B failure does not suppress other successful sections.

No geometry averaging, blend of completed engines, or old A+B recovery remains
behind the replacement Hybrid setting. Standalone Corridor remains available.

## Engine choices and preferences

Approved: Legacy is the default; Corridor and existing experimental B remain
selectable; Hybrid means Legacy followed by the existing B engine. Existing B
means the production experimental implementation, not the failed new research model.

Approved release details:

- Default applies to fresh, reset, blank, and invalid engine preferences. Preserve
  explicitly stored Legacy, Corridor, and B choices.
- Give the replacement Hybrid a distinct persisted identity. An old A+B Hybrid
  preference migrates to Legacy with a visible nonmodal explanation; it must not
  silently activate a different algorithm.
- Ship the new Hybrid for Precise Shape first. In Move Nodes, explain before any
  computation that Hybrid requires Precise Shape; do not silently change mode.
  Standalone engine Move Nodes support is retained where already functional.
- Directional Image removal has not been approved. Preserve its existing choice
  and preference until an explicit scope decision changes that; do not broaden
  this cleanup into Image work.

## One attempt, original authority, temporary geometry

Capture the original selected way, selected occurrence range, protected anchors,
incident topology, settings, source evidence, and edit authority. Accept whole-way
selection or the existing way-plus-two-nodes range contract. Preserve existing
repeated-occurrence and incomplete-source safeguards.

Run Legacy against this range without touching dataset primitives. Carry its
temporary geometry and a monotonic mapping of original anchor occurrences into
each retained candidate. Legacy's simplified or inserted points must not lose
the identity of a junction, endpoint, tagged node, or frozen interval.

The second-stage reference curve is Legacy's candidate, but the original frozen
OSM snapshot remains the sole authority for permissions, stale validation, node
identity, and the eventual command. Never synthesize a modified source dataset
or claim that Legacy's temporary coordinates were the captured original.

Respect opt-in junction movement and all existing relation/tagged-node safety
restrictions. With movement off, original protected coordinates remain exact.
With movement on, only independently authorized safe Legacy junction placements
may be used; B cannot move them again or enlarge their authority. Complex junction
vicinities already frozen by policy remain frozen, rather than treating every
between-junction interval as automatically eligible.

## Interval-local refinement and evidence

For each retained Legacy candidate, partition the selected occurrence range at
junctions and protected anchors, honoring frozen safe intervals. Use actual
Legacy geometry as B's reference in each eligible interval, preserving exact
boundary coordinates and monotonic source provenance. Degenerate/too-short or
frozen intervals retain Legacy geometry and an explicit reason.

B receives fresh profiles sampled around that reference from factual imagery.
Do not reuse profiles centered on the original OSM way as though they followed
Legacy. Verify metric-frame and raster coverage for the reference, search
corridor and required evidence halo. Reuse frozen evidence when sufficient;
otherwise acquire the necessary bounded coverage under the same attempt and
source generation, verifying source/settings authority. Unsupported acquisition
must produce a truthful interval fallback, not fabricated support or extrapolated
coordinates. Keep physical width explicit; no invisible search widening.

If an interval has a usable B result, retain it for assembly. If it has no route,
unsafe geometry, insufficient coverage, or another safely contained failure,
retain that interval's Legacy geometry and continue subsequent intervals.
Distinguish local contained failure from attempt-wide cancellation, stale source,
invalid authority, authentication/source invalidation, and resource exhaustion:
those terminate or stop further work appropriately and never enable stale Apply.
An exhausted attempt budget is not reset for the next interval; report unattempted
sections distinctly from attempted B failures.

Local inference results are provisional. Reconstruct boundaries and protected
anchors, then validate the actual assembled geometry. A local failure must never
discard unrelated successful intervals or silently hide their usable results.

## Alternatives and assembly

Keep retained Legacy candidates available. For each, construct a Hybrid candidate
using usable B interval results and Legacy fallbacks elsewhere. If B succeeds
nowhere, do not manufacture a separate duplicate Hybrid candidate.

Bound retained Legacy parents, B alternatives, total alternatives, work, memory,
and wall time with explicit tested limits selected in the execution plan. Avoid
the Cartesian product of every interval alternative. Use deterministic bounded
selection, preserve useful distinct variants, and report truncation. Interval
and parent traversal must be deterministic and the attempt budget shared.
Do not merge near-but-different geometry without a documented tested equivalence.
Deduplication must retain provenance and important warnings.

Every assembled candidate has one immutable final geometry, complete proposed
node assignments, anchor mapping, interval provenance, and eventual edit plan.
Use one modeless preview and one atomic Apply/Undo command, never an intermediate
Legacy command followed by B commands. Nothing outside the selected edit authority
is changed merely because a new reference curve extends beyond it.

## Comparable quality and presentation

Assess Legacy-only and assembled Hybrid candidates using the same final-geometry
assessment and factual image support definitions. Engine objectives are not
comparable quality percentages and must not be averaged. A refinement label
confers no ranking bonus.

For each nonoverlapping interval record: source (Legacy or B), attempted/skipped/
failed status, safe reason, final supported length and assessed length, findings,
and parent/result identities. Shared boundary points contribute no duplicated
length. Frozen sections and Legacy fallbacks remain in the denominator. Measure
length on the displayed final candidate, not the original way or raw B curve.
Unknown image quality is unavailable, not zero or invented support.

Report overall supported length as the sum of supported final lengths over total
selected final length. Run whole-result geometry/topology checks, including joins,
nonadjacent crossings, contacts, overlaps, abrupt turns and protected assignments.
A good average never suppresses a blocking section or review warning.

Show a short summary such as:

> Legacy + B refinement: B refined 3 of 4 sections; 1 retained Legacy.
> Heatmap support: 920 m of 1,000 m. Review needed: weak support in section 2.

Provide a section breakdown; selecting a row highlights that section on the map
and explains fallback/warnings. Keep text wrapped and the buttons accessible.
Review confirmation is session-local and bound to the exact final candidate.
Unsafe candidates cannot become applicable through confirmation. Diagnostics
remain exportable for failures and contain actual interval inputs, work, results,
fallbacks, settings and identities, without inventing full-way B inference.

## Settings save behavior

Remove the two unconditional informational success dialogs in
HeatmapLayerSettingsAction, both with and without managed access values. Keep
refresh, persistence, asynchronous source probe, silent-success logging and genuine
failure/authentication/rate-limit warnings. Preserve Settings Cancel behavior and
cookie focus regressions. This does not remove the Settings dialog itself.

## Verification and release sequence

1. Reconcile the dirty primary tree and original completion matrix. Inventory and
   preserve unrelated/untracked/private files. Separate production B fixes from
   isolated failed research; no blanket reset, copying or staging.
2. Implement preference/default and settings changes with regression tests.
3. Implement Legacy-to-B interval preparation, bounded alternatives, truthful local
   fallback, composition, comparable assessment, preview and diagnostics under TDD.
4. Verify deterministic cases: multiple junctions; selected subrange; protected and
   tagged anchors; relation restrictions; B success/failure/success; all B failures;
   coverage outside original raster; whole-result crossing despite safe intervals;
   no-signal support; alternative deduplication; budget exhaustion; cancellation;
   stale publication; exact Apply/Undo/Redo; Move Nodes rejection before work.
5. Run focused and required broader tests, existing production replay and available
   high-intensity/sparse/large-offset/junction captures in CI. Verify actual Legacy
   and B production paths, diagnostics export and settings UI. Use one independent
   Sol audit of consequential implementation and original-scope reconciliation.
6. Publish at most one additional public RC when functional and verified; obtain
   user acceptance and remaining factual evidence. Fix release blockers without
   unrelated features, complete mandatory gates, then publish and verify the final
   downloaded JAR. No release-readiness claim while a required gate is unresolved.

## Code anchors and known integration risk

TrackerMode currently defaults to CORRIDOR_AWARE and HYBRID means A+B.
LiveBPreviewService and AlignWayAction contain explicit old Hybrid routing.
IntervalTraceRequestFactory validates original frozen anchors and request identity;
it cannot simply be passed Legacy coordinates in place of its original source.
SelectedWayIntervalPartitioner and FixedIntervalEditPlanComposer provide relevant
existing contracts, but require a deliberate refinement-reference adapter rather
than weakening their authority checks. The new common assessment must cover Legacy
fallback sections too, without interpreting missing modern evidence as measured.

This is a cross-module integration change, not merely renaming an enum. No new
model research, performance target, mandatory new corpus recapture, or quantitative
weekly-allowance prediction is introduced by this design.
