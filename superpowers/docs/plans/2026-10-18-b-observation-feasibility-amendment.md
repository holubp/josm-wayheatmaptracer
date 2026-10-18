# B observation TRAIN-feasibility amendment implementation plan

> **For agentic workers:** Use `superpowers:executing-plans` for controller integration and bounded existing workers where useful. This amends Task 4 of the approved 2026-10-16 plan; Tasks 5–6 and OBS-01–20 remain required.

**Goal:** Test whether the existing finite observation family contains a geometrically feasible configuration, then evaluate a single frozen candidate through the production engine. A second rejection does not complete the active goal.

**Architecture:** Preserve the equations, all 32 parameter configurations, V3 admission, exact inference, weights, thresholds and attempt caps. Reject geometrically failing configurations on TRAIN before ranking their unchanged proper scores. Use genuinely new grouped SELECT geometry and a new sealed concrete HOLDOUT payload physically absent from fitting checkouts.

**Tech stack:** Existing Java 17 identification runner, JUnit, Python workflow-boundary tests, synthetic-only GitHub Actions.

**Spec:** `2026-10-16-b-observation-contract-specification.md`, approved original plan, and unchanged equations in `2026-10-17-b-joint-observation-candidate.md`.

**Status:** GENERIC PROTOCOL REVIEWED; implementation and source freeze pending. Sol reviewed the TRAIN/fresh-SELECT protocol after two matched-control corrections, then the replacement sealed-HOLDOUT producer law after alias-rejection/single-scope corrections. Actual generic source/tests still need independent review before concrete generation; actual fitting source freeze precedes fresh SELECT reads. Existing approval to diagnose/amend/review narrowly supplies scope authority; no stronger coefficient or new model equation is introduced.

**2026-10-18 integrity incident:** The implementation worker accidentally viewed the old split-shape producer switch. No HOLDOUT raster, label, facts, score or empirical outcome was read, and no subsequent implementation decision used the viewed expression. Nevertheless its recipe-access boundary was breached. Independent Sol ruling: retire original HOLDOUT for this candidate; preserve all artifacts and do not call this an empirical HOLDOUT failure. Old SELECT remains diagnostic-only; active experiment=112 original TRAIN+20 REGRESSION+112 fresh SELECT+112 genuinely new sealed HOLDOUT=356. New HOLDOUT needs disjoint geometry/source groups and preregistration before selection, not fresh phases/seeds of exposed recipes. Generic producer/sealed concrete-recipe separation is now reviewed; Task0 actual source review/generation precedes TaskA. TaskA currently has only a frozen metadata test scaffold, no source implementation or new evaluation materialization.

## Evidence and preserved rejection

Original run `39083054992` rejected all four shortlisted configurations. Its archive, source identity, native inputs and outcomes remain immutable. Diagnostic run `39089515155`, commit `56936cc`, reproduces the saved MAP energy exactly: 69.90591040773661. Two feasible nearest-truth branch witnesses cost 225.48813055410196 and 145.06449912005954. Unary savings dominate their reduced turn costs. This establishes preference over those witnesses only; they are not optimized branch-restricted paths and do not prove global optimality.

Do not reuse the old SELECT for new selection, change the tolerance-union oracle, or treat baseline failure as a waiver. Fresh SELECT geometry is preregistered below before any of its raster/labels/facts/scores are materialized. Original HOLDOUT has zero empirical reads but is retired after producer-source exposure. New concrete HOLDOUT recipes must remain outside fitting checkouts and unseen until qualified Task5.

## Global constraints

- Kernel, adapter, mixture, facts and extractor mathematics stay unchanged. Grid: `G={1,4,16,64}`, `lambda={1,2}`, `B={0,1,3,7}`.
- No changes to production defaults, A/Image/Hybrid/legacy, Apply, authority, geometry thresholds, private targets or existing fixed regressions.
- Keep every V3-admitted state, the same decision region, one exact DP, temperature and turn/orientation/guide weights.
- Existing per-attempt budgets, 64 MiB resident control, 2 GiB output, 512 catalog/scene ceiling and 3600-second fitting watchdog remain. No watchdog reset between TRAIN and SELECT or implicit second run.
- Fitting runs on public synthetic CI only. Sealed recipe generation is local and outside FIT; no held-out rasters, labels, facts or scores before qualified Task5. No release, JAR or private capture upload.
- CATALOG reads metadata only. Failed/incomplete controls never become PASS. Exhausted work/time/memory rejects the run and preserves evidence.
- All edits through `apply_patch`; preserve unrelated work and the original rejected artifacts.

## Review focus

1. A finite good forecast score cannot shortlist a geometrically failed TRAIN configuration.
2. Early rejection skips expensive later geometry honestly without skipping its score or inventing successful controls.
3. New SELECT must be a new geometry/source group, not a phase/seed alias of old SELECT or TRAIN.
4. Cross-platform synthetic inputs must be reproducible; mathematical renderer correction cannot relabel the old rejection.
5. The sealed new HOLDOUT payload is absent from FIT and inaccessible under old/string-only authorization; later production qualification, exact package verification and one-shot claim remain mandatory.

## Fresh SELECT recipe, frozen before evaluation

Register112 fresh SELECT cases: seven non-null contrasts × eight existing physical sampling variants × two sides. Active experiment=112 original TRAIN+20 REGRESSION+112 fresh SELECT+112 sealed new HOLDOUT=356. Retain112 old SELECT metadata rows as explicitly `INELIGIBLE_DIAGNOSTIC` for468 catalog rows below512; remove exposed original HOLDOUT from qualification. New SELECT IDs contain `/select-polynomial-v1/`; generator, geometry and source groups use that cohort identifier. Old and fresh SELECT must never be pooled. Reuse the eight existing phase/pitch/oversampling variants only as correlated controls, not independent groups.

For `t=x/120`, define the new source skeleton

`F(x)=30+16*t*t*(1-t)*(1-t)*(5+7*(2*t-1)-6*(2*t-1)*(2*t-1))`.

This polynomial skeleton is independently specified, not obtained by resampling, translating, reversing or rephasing any old scene. Every fresh contrast uses this skeleton with its own explicit recipe lineage. Share lineage across its correlated variants and paired sides where they sample the same field. No fresh geometry may be materialized before the reviewed manifest is frozen.

Let `K(y,c,s)=StrictMath.exp(-0.5*((y-c)/s)^2)`. Clamp scalar output to `[0,1]`; retain the original native-grid/capture producer, decision width, profile spacing, masks and factual pitch metadata. The source OSM segment stays `(0,30)`–`(120,30)`, reversed only for the registered reversal control. Truth polylines sample their declared continuous curves at the existing 1 m truth spacing. Labels derive from these equations, never model output.

| Contrast | Left recipe | Right recipe | Association / registered geometry |
| --- | --- | --- | --- |
| weak coherent / disconnected flecks | `.02+.08*K(y,F(x),.9)` | Same transverse field multiplied by disconnected longitudinal windows: `.02+.08*K(y,F(x),.9)*max_j W_j(x)`, with centers j=13,41,74,106 m and `W_j=(1-((x-j)/1.6)^2)^3` for abs(x-j)<1.6 m, otherwise0 | Left UNIQUE F; right UNKNOWN structure, NOT_APPLICABLE location target. Amplitude, lateral width and center are matched; only longitudinal support changes. Flecks are not a connected acceptable path. |
| concentrated / diffuse | `.02+.16*K(y,F(x),.65)` | `.02+.032*K(y,F(x),3.25)` | UNIQUE F; equal continuous excess integral `.104*sqrt(2*pi)` per cross section. Report actual sampled/captured budgets separately, not as exact equal pixels. Width/uncertainty remains explicit. |
| sparse crossing strands / persistent parallel | `.02+.035*sum(K(y,F(x)+d(t),.28))`, `d={-2+4t,1.4-.7t,2-4t}` | `.02+.44*K(y,F(x)-4,.8)+.44*K(y,F(x)+4,.8)` | AMBIGUOUS union of the three declared left curves or two right curves; no fabricated probabilities. Existing right-hand segment-union gate over x40..80, factual pitch tolerance. |
| resolved short apex / stair alias | `.02+.30*K(y,F(x)+4.5*C((x-60)/6),.7)`, `C(u)=(1-u*u)^3` for abs(u)<1, otherwise0 | `.02+.14*K(y,pitch*floor(F(x)/pitch+.5),.7)` | Left UNIQUE continuous apex curve and existing x60 apex gates; right UNIQUE underlying F with declared source quantization. Keep original supported 6 m apex regression unchanged. |
| displaced strong / nearby faint | `.02+.70*K(y,F(x)+14,1.1)` | Left field plus `.035*K(y,30,.7)` | UNIQUE displaced strong curve `F+14`; existing displacement gate x40..80, factual pitch tolerance. No acquisition widening. |
| physical pitches / reversed order | `.02+.16*K(y,F(x),1.05)` | Identical native field, reversed OSM order | UNIQUE F; original affine-provenance reversal and equivalent-oversampling controls. |
| source capability | `.02+.16*K(y,F(x),1.05)` with existing known-native post-palette lineage | Same normalized scalar field with existing rendered-only capability | UNIQUE F; this tests the two capability paths after scalar mapping, not a new claim that palette decoding itself was exercised. Keep actual palette regression suites required separately. |

The null/invalid/censored controls and all fixed REGRESSION cases remain unchanged and outside ranking. New evaluation recipes do not select coefficients. If independence or label adequacy is rejected in review, correct this preregistration before any fresh case reads.

## Task 0 — Reviewed generic producer and sealed replacement HOLDOUT

**Contract:** [Sealed HOLDOUT producer](2026-10-18-b-sealed-holdout-generator-contract.md), including exact law, bounds, tuple/reversal rejection, fixed scope, hashes and access rules. This contract is authoritative for the integrity repair; no old HOLDOUT-retention wording is applicable.

**Files:** Create `scripts/generate-observation-holdout.py` and `scripts/tests/test_observation_holdout_generator.py`. No Java/model changes in this task.

- [ ] Write dummy-fixture REDs for HMAC stream/byte rejection, quintic endpoints/C2 joins, geometric validity and duplicate/reversed proposal rejection within original bounded draws/bytes.
- [ ] Write REDs for112 index entries/seven distinct registered groups/correlated variants, deterministic dummy byte hashes, public-index redaction and size bounds.
- [ ] Write REDs for exact fixed production scope derived from common Git directory, refusal before entropy on an existing claim, failed terminal invocation, no alternate output CLI/seed, and no accepted index on partial publication.
- [ ] Implement the generic producer under the exact reviewed contract; importing/testing it must not draw production entropy or generate evaluation recipes. Dummy outputs are explicitly nonqualifying.
- [ ] Run Python unit/compile checks and independent actual source/test audit. Freeze generator and generic contract hashes before production invocation.
- [ ] Invoke once locally, with no configurable seed/path. Keep concrete payload and seed/private metadata outside FIT worktree; return only safe status/hash/count metadata. Do not inspect concrete recipes or print seeds/coefficients.
- [ ] Copy ONLY the validated public index into FIT source control. Freeze its hash, payload hash and claim identifier for TaskC. A failed invocation remains terminal; no alternate path or automatic regeneration.

## Task A — Reproducible registered renderer and cohort

**Files:** `T/BIdentificationCatalog.java`, `T/BIdentificationScenes.java`, `T/V022BIdentificationCatalogTest.java`, existing scene/manifest tests. `T` is the existing test package `org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic`.

- [ ] Add metadata-only REDs for468 catalog/356 active rows,112 fresh SELECT,112 sealed new HOLDOUT entries, retired original HOLDOUT exclusion and disjoint group/alias identities. No concrete HOLDOUT recipe or fresh SELECT raster/label access.
- [ ] Register the explicit cohorts above. `definition(key,access)` resolves old diagnostic/fresh registered keys, but retired SELECT cannot enter scoring/coverage/pairs. Sealed HOLDOUT definitions carry only indexed markers/recipe hashes. Unknown/modified keys and private-payload access before qualified Task5 fail closed.
- [ ] Prospectively replace analytic transcendental calls with `StrictMath`, preserve operation grouping, and increment renderer identity. Preserve the original Math renderer/receipts through immutable commit56936cc; never rewrite their hashes as new results.
- [ ] Add cold/warm ARM64/AMD64 renderer verification using ONLY one original TRAIN case. Require identical native scalar/mask/frame hashes, then complete facts hashes, across hosts. A source-math fix is not a model pass.
- [ ] Validate recipe/label construction through code and metadata. Defer fresh SELECT raster/label assertions to the single frozen experiment so tests cannot consume selection data before the freeze.
- [ ] Run focused catalog/scene/source ownership suites and retain actual RED/GREEN evidence. Commit this coherent prerequisite after independent review.

## Task B — TRAIN geometry feasibility before ranking

**Files:** `T/BObservationIdentificationTool.java`, `T/V022BIdentificationRunnerTest.java`; reuse existing `BIdentificationAcceptance`, `BObservationGeometry` and adapter without equation/threshold changes.

**Interface:** Add explicit immutable per-configuration TRAIN state `UNTESTED`, `FAILED` or `FEASIBLE`, with tested/skipped scene/control counts and reason receipts. The runner's shortlist function consumes TRAIN scores plus these states, rejecting absent/incomplete state. Add per-scene geometry eligibility input to `scene(...)` so a previously failed parameter is still scored but is not solved again unnecessarily.

- [ ] RED: a lower-loss TRAIN failure loses to a higher-loss feasible configuration; all failed or incomplete configurations produce no shortlist. Existing score and `(G,lambda,B)` tie order remains.
- [ ] RED: early TRAIN failure skips later geometry only, retains all32 proper-score reports, records untested controls and never freezes a partial candidate. Cancellation/time/memory refusal preserves failure and cannot resume with a reset clock.
- [ ] TRAIN processing uses deterministic order: registered geometry-selector cases first, then descending native pitch, variant ID, contrast ID and side. Configurations retain lexicographic `(G,lambda,B)` order. This ordering manages bounded work; it changes no acceptance condition.
- [ ] For every TRAIN configuration, run existing applicable profile normalization/null/censor/component controls. While it has no failed control, execute actual existing raw geometry plus exact anchors and the applicable registered selector. Once failed, never evaluate its later expensive geometry; record skips, not PASS. Scores still cover every original TRAIN case.
- [ ] A configuration becomes FEASIBLE only after all original TRAIN cases and required applicable controls finish. Rank only FEASIBLE finite-score configurations; shortlist at most4. Zero feasible configurations is an explicit preserved family rejection before fresh SELECT is opened.
- [ ] Restrict new SELECT evaluation, `expectedSelect`, required-pair inventory and cache comparisons to the fresh cohort. All existing SELECT profile/source/reversal/oversampling/geometry checks remain required, with unchanged tolerances.
- [ ] Add deterministic small TRAIN-only behavioral fixtures for each runner branch. Do not use real fresh SELECT outcomes as unit-test expectations or tune the parameter grid.
- [ ] Produce a metadata/resource preflight under the original caps: native/coarsened policy, per-attempt bounds, at most32 configurations, bounded live owners/output and one continuous3600-second watchdog. Report worst-case work and any uncertainty; do not promise the experiment will finish or raise limits.
- [ ] Run focused runner, score, acceptance, cancellation and memory suites; independently review source and gate behavior before freeze.

## Task C — New manifest, source freeze and one bounded experiment

**Files:** new `2026-10-18-b-observation-feasibility-manifest.json`; `T/BIdentificationManifest.java`, manifest tests, CI workflow/source-boundary tests, experiment workflow and CI instructions. Preserve the original manifest file byte-for-byte.

- [ ] Add explicit schema2 fields for `selectionProtocol`, `selectCohort`, `rendererRevision`, `trainingControlOrder`, `holdoutPolicy` and retired SELECT scope. Exact field/value validation rejects unknown or missing policies. Grid/equations/capabilities/OBS ownership remain unchanged.
- [ ] Bind new catalog, actual generating source inventory and manifest hashes only after independently reviewed code/tests are frozen. Record original rejected and new prospective identities separately. A changed equation/grid/caller/renderer/catalog after freeze must fail before data reads.
- [ ] Bind the sealed public-index hash, expected payload hash/bytes, generic producer/contract hashes and generation claim identifier. Recheck exact public-index bytes before data reads. Test private payload/seed absence from FIT checkout and CI artifact paths; no self-consistent string hash can mint authority. The new manifest cannot reuse old candidate/authorization strings. No concrete HOLDOUT API is opened by this task.
- [ ] Keep CATALOG metadata-only. FIT_SELECT is one3600-second experiment, not separate TRAIN/SELECT jobs each with a fresh allowance. Shared caps, disk ceiling, cancellation and incomplete-run refusal stay unchanged. No automatic retry after rejection or timeout.
- [ ] Run the finite experiment on public CI only after reviewed preregistration. Read full terminal artifacts and verify source/catalog/manifest bindings. No frozen artifact on failed/incomplete TRAIN or SELECT.
- [ ] If frozen, continue original Tasks5–6: production-path capability, unchanged regressions, qualified one-shot HOLDOUT, private evaluation, actual production comparison, numerical-policy qualification and independent whole-goal audit. This amendment does not replace them.
- [ ] If rejected/exhausted, preserve results and diagnose under the active goal. No SELECT-based coefficient/threshold/grid modification or claim that the model is complete.

## Completion evidence

An executable harness or prospective manifest is partial progress only. Completion remains the active goal: a tested, integrated new observation model with honest reproducible existing-B comparison and all original production, regression, HOLDOUT/private and independent-review gates. No publication is authorized.
