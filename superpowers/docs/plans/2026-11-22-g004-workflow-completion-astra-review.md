# G004 completion plan — Astra high review

Date:2026-11-22. Reviewed [completion plan](2026-11-22-g004-workflow-completion.md)
against the approved Nov22 specification amendment, original Tasks1–8/S01–S08
and selectively inspected actual paused MAIN/adapter source.

Reviewer: independent `/root/g004_completion_plan_astra_review`, explicitly
dispatched `gpt-6-astra` / `high`, clean context, read-only. No implementation,
build, artifact extraction, private upload or recursive delegation occurred.

## Initial disposition: CHANGES REQUIRED

| ID | Severity | Finding and concrete correction | Final disposition |
| --- | --- | --- | --- |
| R1 | High | Native bootstrap returning only Legacy's Root omits settings/source providers, currentness, lookups and memory consumed by workflow. Add PreparedRefinementRoot/RefinementSourceContext, stable choices, actual host guard, original capture-time snapshot receipt and minimal detached command guard. Keep ordinary native Apply if optional admission fails. | ADDRESSED: Task2 contracts/files/tests and Task5 post-close guard checks |
| R2 | High | One native interval composition does not specify source/route control restoration or another selected parent; enumerating combinations exceeds retention bounds. Retain exact per-candidate RootReviewState, bounded materialization, old parent identities, confirmation clearing and refusal rollback. | ADDRESSED: Task2 partition policy and native partition test; Task4 cached review restoration |
| R3 | Medium | Lifecycle.begin(OperationTicket) cannot cover lazy Legacy preparation before a session/ticket exists. Use existing owner/PreviewWorkGate.Work from first click and attach ticket later. No parent reopen between successful bootstrap and native computation. | ADDRESSED: Task4 interface and first-click cancellation/rejection/close/presentation tests |

Other corrections: actual adapter test names are PostprocessedReferrerWorkTest
and PostprocessedWayReferenceWorkTest; service receives an immutable interval
selection DTO, not the action's mutable IntervalPreviewState. Final executable
version metadata precedes audited source freeze. Receipt tables distinguish
reused numerical evidence from freshly verified changed orchestration.

## Scoped repair confirmation: APPROVE

The same reviewer inspected the corrected Task2 root/source/partition contracts,
Task4 pre-bootstrap lifecycle and Task6 version freeze; no duplicate broad review.
Returned: **“APPROVE — R1–R3 are addressed at plan level.”** No remaining blocking
gap within that confirmation scope. The rest of the original review found the
workflow, accepted Gaussian baseline, Apply/failure distinction, evidence reuse
and single final Sol-high audit sound. No tracing redesign or new component
review campaign is required.

This approves planning only. Implementation/test/release correctness remains
unverified; G004 stays paused until the user's plan acceptance/execution instruction.
