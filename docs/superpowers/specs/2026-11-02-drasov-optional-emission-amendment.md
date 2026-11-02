# Proposed amendment: optional emission of sampling rows

Status: **PROPOSED — user approval required before production implementation.**
Parent: `2026-11-02-drasov-legacy-corridor-quality-design.md` and its execution plan.
This changes one correspondence constraint; it does not change the goal or approve a release.
Independent Sol/high review found no blocking inconsistency; the clarifications
below preserve the approval boundary and distinguish observations from emissions.

## Demonstrated defect

An independently painted straight raster ridge is sampled from a bent source way.
Both production engines stay within one native pixel of the ridge but traverse it
backward: Legacy accumulates 7.472 m of backward image travel and A accumulates
6.600 m. Their existing opposite-arc and other preservation controls still pass.

For this fixture, any point on original normal row j has
`x - 64 = -1.425*j + j^3/32 - (j/4)*e`, where `e = y - 64`.
Rows -4 and +4 therefore force approximately 5.4 m or more of backward image
travel whenever both points remain inside the independently known one-pixel
image strip. This is a continuous-coordinate limitation, not a bad coefficient
or an insufficient discrete search. The proof was independently checked by Sol.
It does not prove that every Drasov defect has this cause.

The desired-behavior regressions are retained on isolated branch
`codex/drasov-chart-engine-red`; they must not be merged as passing tests.
The execution ledger records their revision and evidence.

## One proposed change

Allow inference to consume a bounded run of **sampling rows** without requiring
each row to emit a successive geometry vertex. Select those transitions inside
the disclosed inference process. Do not delete loops or smooth geometry afterward.

A representability witness retains exact-ridge stations -8, -7, +7, +8. Their
image x coordinates increase. Its central transition consumes 13 interior rows
over approximately 19.39445 m of original source chainage, inside the existing
16-profile/20-metre numerical limits. Adopt those numerical limits for the new
transition type; the existing gap rules do not authorize consuming observed rows.
This is a witness only, not a validated optimizer.

## Required contracts

- Preserve acquisition, configured ±25 m search authority, palettes and frozen
  raster inputs. Do not acquire extra imagery, increase work/heap ceilings,
  introduce another tracing pass or add an engine/menu option.
- Retain all existing candidate states. Optional transitions need an explicit,
  bounded admission rule independent of the eventual winning path; no hidden
  beam, global topology history or pruning of inconvenient observed states.
- Keep A as one exact bounded second-order pair-state inference process. Its
  state must identify the preceding emitting occurrences when rows are consumed.
  Count the actual extra states, transitions and allocations against existing
  limits. Specify Legacy's corresponding recurrence separately.
- Admit an emitted connector only using its actual native-raster geometry,
  valid scalar support, branch evidence and original decision-region authority.
  Support on an un-emitted curved route cannot authorize a straight shortcut.
  Absence, censoring and ambiguous evidence remain explicitly unavailable.
- Sampling rows are not OSM nodes. Move mode keeps every original node identity
  and count. Protected occurrences and junction boundaries remain mandatory and
  exact; optional emission cannot bypass them. Precise mode retains its existing
  command and node-deletion protections. Revalidate the actual final preview.
- Consumed observations remain in diagnostics with explicit ownership. They are
  not automatically direct coverage, position, turn or movement evidence for an
  emitted connector. Keep original source chainage, emitted-path length and
  their respective coverage denominators distinct. Preserve every original
  observed/gap/censored classification separately from emitted/consumed status.
- Define physical data/continuity costs before implementation. Do not reward
  fewer vertices or fewer evaluated observations merely for being fewer. Include
  connector evidence and consumed-row accounting in the complete objective.
- Version the numerical policy, observation-to-emission mapping, cost rows and
  native work identities. Keep old readers and strict historical gates intact;
  any new-policy comparison must be explicitly named and approved.
- Update every consumer that assumes one candidate point per sampling row.
  Distinguish full observation-indexed evidence from emitted geometry through an
  explicit versioned mapping. Do not shorten parallel arrays or manufacture
  interpolated evidence rows to make the old indexing appear valid.

## Execution and acceptance

Approval of this amendment authorizes completing the precise recurrence,
admission/cost/provenance specification, independent review, and a bounded
implementation experiment under these contracts. Freeze that patch packet in
the existing execution ledger before production edits; no further general
milestone approval is needed unless another material contradiction is found.

First establish GREEN on both retained straight-ridge regressions without
weakening their image oracle. Preserve the opposite arc, supported switchbacks,
strong/weak thin strands, dark-gap separation and collinear-insertion controls.
Then replay original Drasov and the successful Omice A case, including final
preview, both geometry modes, Apply/Undo and actual work/time measurements.
An extra refusal or a skipped bend is not success. Reject the experiment if
usable geometry and practical existing resource limits cannot both be achieved.

Private accepted-reference annotation, full CI, independent completion audit
and the original plan's remaining gates are still required. No RC publication
is authorized by this proposal or by approval of the amendment.
