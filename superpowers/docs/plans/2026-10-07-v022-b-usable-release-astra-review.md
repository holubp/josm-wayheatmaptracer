# Astra review: Engine B functionality from the user's perspective

Baseline: `6288aafe6dc79e948a6981ae795d27ee56a653fe` (RC6).
Independent reviewer: `/root/astra_user_functionality_plan_review`, requested
`gpt-6-astra` / high through pinned collaboration fields; read-only, no delegation.
Scope: the usable-release design and implementation plan, actual action/capture,
pipeline/adapter, source validators, existing tests and RC6 investigation evidence.

Initial verdict: **CHANGES REQUIRED**. The initial plan did not establish usable
ordinary sliding in both geometry modes. Passing existing refusal tests would not
prove that a normal user could Apply a safe slide.

| ID | Finding and source evidence | Plan correction | Status |
| --- | --- | --- | --- |
| A1 | Move test only proved `PRECISE_SHAPE_REQUIRED` refusal (`V022LiveBPreviewServiceTest:1046`); sparse-route guards in `ModernTracePipeline:274`/`:317` remain meaningful | Tasks 6/8/10 require actual curved Move Apply, identity/count preservation, full/subrange and interval matrix; explicit one-shot Precise recovery for undersampling | Corrected in plan; implementation pending |
| A2 | Stored cleanup rejected by action preflight/adapter while B pipeline suppresses it; stored Precise simplification rejected by capture | New Task 3A freezes one disclosed effective invocation before acquisition, preserves requested preferences and diagnostics, tests mode migrations and all presets | Corrected in plan; original cleanup scope still open |
| A3 | Generic visible source lacks epoch/Redo; rendered managed first Apply rejected; visible intervals cannot Apply; default color flags also rejected | New Task 8A requires reviewed freshness design and dependent packet; stable gate explicit; first usable RC may be managed-only in both modes | Unresolved design gate explicitly owned |
| A4 | Boundary movement must be declined before incomplete-arm/relation authority checks, not only before granting movement | Task 3 checks eligibility first; preserves read-only shared closure and tests both sources/protected variants | Corrected in plan; implementation pending |
| A5 | Binary64 tie collapse can make global exhaustive results differ from historically prefix-pruned bounded search | Task 4 uses baseline bounded bitwise oracle; global exhaustive oracle only without intermediate pruning; retained-prefix extension oracle otherwise | Corrected in plan; implementation pending |
| A6 | Exact rectangle clipping must cover actual classifier tolerance (`ModernSingleWayEditPlanAdapter:1685`); artificial endpoints cannot acquire OSM identity | Task 2 proof obligation plus corner/tangent/overlap/zero-length/reversal/antimeridian tests; non-FIXED conversion audited separately | Proof required during implementation |
| A7 | K-best-only speedup could still leave 48–64-second slides; allocations alone do not establish practical latency | Task 4/10 require whole cached preview median improvement, declared reference environment, cancellation and multi-source evidence | Measurable proposed acceptance; user-device timing unverified |

The reviewer supported retaining Task 5's honest strict-completeness blocker,
separate from a safe test RC. Raw-32/diversity caps do not prove completeness.
Existing captures are not evidence of FULL_EDIT_PLAN replay. Original requirement
reconciliation and fourteen-case corpus gates remain; a review-fix batch cannot
replace original scope.

Controller response: amended both planning documents, preserving safety guards
and adding Tasks 3A/8A and explicit both-mode acceptance. No production source
was edited for this review.

## Focused re-review result

Astra re-read the amended documents and returned: **Planning review passes for
the managed B usable RC.** A1–A7 are closed as planning findings, with no remaining
blocking planning omission for that explicitly scoped RC. Implementation and
verification remain pending; this is not production correctness or stable-release
approval.

Two nonblocking clarifications were applied: each one-shot mode action is tested
from both persisted settings (its effective mode follows the action), and RC6
latency comparisons use a selected-palette configuration RC6 supports. Five-source
timings are reported separately because RC6 refuses that configuration.

Tasks 5 and 8A remain explicit stable-release design blockers. Original scope,
including cleanup, still needs reconciliation/evidence; effective cleanup Off is
not completion. Reviewed dependent implementation packets are required for those
design gates. No production tests were run for this documentation-only review.
Controller verification: `git diff --check` passes; unrelated root changes and
private/untracked evidence were preserved.
