# RC10: user clarification supersedes the proposed quality waiver

## 2026-10-14 approved disposition following exact browser inspection

The maintainer rejected the asymmetric painted-crossing example as a successful
fit: its unchanged branch is unsupported. The maintainer approved retaining
this intricate synthetic example only for crossing safety and using simpler,
non-crossing supported paths plus real captures for fitting validation.
This supersedes proposal 2 below; do not simply assert that the asymmetric
result is a successful alignment. No implicit way splitting is authorized.

The revised test checks that strong painted evidence cannot make the complete
crossing proposal safe. If composition freezes a failing interval and offers
a partial plan, its actual stored geometry must still pass topology checks.
A separate 1 m bend fixture requires BOTH supported non-crossing intervals
to change, the exact preview and immutable Apply geometry to agree, and fixed
island/endpoints/unselected nodes to remain exact. Unpainted refusal remains.
Production fitting/topology code is unchanged. Initial focused three tests
passed, with zero failures/errors/skips (focused-result.xml in
build/rc10-painted-visual). Sol independent review found no blocking defect;
its stored-after geometry strengthening is included and passed fresh tests.
Neighbor composer/adapter suites: 135/135, zero failures/errors/skips, 15m13s.
Final strengthened focused tests: 3/3, zero failures/errors/skips, 52s.
Exact XML/logs are retained under build/rc10-painted-visual; diff check passes.
Benchmark contract approval is a separate, still unanswered release gate.

The user requires the next test RC to substantially improve the sideways
zigzags reported in RC7. Publishing another RC with that issue merely disclosed
is not accepted. Proposal 1 below is therefore WITHDRAWN; do not weaken the
impulse assertion or use an RC9 non-regression bound as the release criterion.

The reported mechanism is multiple sparse real trace strands: separated lines
or weak local maxima formed by their changing overlaps. It is not adequately
represented by one bright stray pixel. Retain that example only as a secondary
stress test. Primary regression evidence must use multiple sparse/overlapping
strands and the existing Ricany/Maganik captures. Preserve genuinely supported
bends and distinct sustained parallel branches; do not average every branch
into a fabricated centerline or smooth the output after inference.

Proposal 2 remains a separate test-expectation question, not a fitting fix.
No disputed assertion has changed, and no RC10 has been published.

RC10 has not been released. Neither proposed test change below has been made.

## 1. Withdrawn proposal: accept unchanged weak-fit behavior for a test RC

A new artificial test puts one bright stray pixel beside a faint straight
heatmap ridge. I set a new target: the traced path must stay within 1 metre of
that ridge. RC9 misses it by 2 metres; the current build misses it by 1.87 metres.
The current build is slightly better on this test, but the problem is not fixed.

The proposed decision is to allow a **test RC** while this issue remains open:
keep the failing 1-metre result documented, and make the automated RC check
require that this exact example does not get worse than RC9's 2-metre result.
This is not a general 2-metre accuracy allowance, a safety exception, or proof
that your RC7 zigzags have been fixed. Stable-release quality remains unfinished.

## 2. One crossing test expects rejection even though its heatmap supports the path

This artificial test draws heatmap evidence along a proposed path. The current
build recognizes that evidence and allows that path on its own, but the old
test still expects rejection. The proposed decision is to correct that expected
result. A path without enough heatmap evidence must still be refused, and two
proposed paths that would cross must still be refused. Separate tests for those
two refusals already pass. No production safety check would be removed.

## How this relates to your RC7 reports

Your main fitting complaint was substantial sideways zigzag where individual
heatmap traces are sparse or spread out. The stray-pixel example probes a
related weakness, but does not reproduce or prove a fix for every RC7 case.
Current private replays complete within the default computation limits; that
does not prove their shapes are good or that live Apply works in every case.
The second test is a supported-path versus unsafe-crossing distinction, not a
solution to noisy-trace zigzags or incomplete-junction warnings.

The earlier request to proceed with that disclosed fitting limitation is
superseded by the user requirement above. RC7-quality improvement is required
before the next test release. Historical evidence below remains valid.

## Evidence

- Independent Sol reviewer supports this disposition and inspected actual
  source/tests. Baseline and RMS impulse runs both fail the exploratory target.
- Broad gate: 224 cases, 222 pass, two failures corresponding to these topics.
  XML: `build/rc10-stock-evidence/rms-replay-apply-failed-xml/`.
- New separate unpainted negative and existing combined-crossing topology
  controls: 2 passing cases, no errors/skips, session31498.
  XML: `build/rc10-stock-evidence/unsupported-crossing-control-xml/`.
- Seven current RMS stock captures pass default budget/production execution
  checks. These are not a claim of final geometric quality certification.
- Automatic approval review rejected the proposed combined patch, citing
  possible weakening of tests without an approved requirement. Explicit user
  approval is required for the above disposition; it must not be circumvented.

After approval: apply the precise disposition, verify targeted controls, obtain
final independent review, commit scoped changes, run full public CI and JOSM GUI
checks, and verify the release artifact before publication. Private evidence
stays local. Stable 0.22.0 and weak/dispersed fitting remain separate open gates.
