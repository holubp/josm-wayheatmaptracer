# Sealed replacement HOLDOUT producer contract

**Status:** GENERIC PROTOCOL REVIEWED. Independent Sol review accepted after deterministic alias rejection and a single fixed generation scope were required. Actual generic source/tests still require independent review and hash freeze before any concrete generation.

**Authority:** Narrow evaluation-integrity repair under the approved observation-identification goal. The original HOLDOUT producer-source exposure is recorded in the SDD ledger; its cases are retired for qualification, not reported as an empirical failure. This contract changes evaluation data handling, not the observation equations, grid, thresholds, inference or production permissions.

## Public law and unseen concrete recipes

The generic generation law is public and reviewed. Concrete random draws, seed, coefficients, curves, rasters, labels, facts and scores remain unseen by model fitting/amendment workers until qualified Task5. Knowing the law is not a claim that the algorithm is secret. The complete concrete payload must be physically absent from FIT checkouts and CI artifacts; an API guard alone is insufficient.

Use seven genuinely distinct registered base geometry/source groups, one for each non-null contrast already specified in the feasibility amendment. Each group has eight existing physical sampling variants and two controlled sides:112 HOLDOUT cases. Variants share one base recipe and remain correlated; they are not112 independent groups. Proposals follow the independent uniform law below; accepted groups are conditioned on validity/distinctness and are not claimed statistically independent after that conditioning. This distribution supports claims about these registered controls, not universal generalization.

The new base geometry is a piecewise quintic spline, not the old exposed curve or the fresh SELECT single polynomial. Knots are at x=`0,20,40,60,80,100,120` m. End offsets are exactly0. Five interior offsets are independent uniform draws from `{k/4 metre | k=-16,...,20}`. Within each interval let `s=(x-x_i)/20` and

`F(x)=30+a_i+(a_(i+1)-a_i)*(6*s^5-15*s^4+10*s^3)`.

For raster x outside `[0,120]`, extend F as30. This gives C2 joins with exact endpoint position and zero first/second derivatives. Directly observed native pixels still determine the model inputs; the spline is ground-truth generation, not a new optimizer prior.

A group draw is valid only if at least one interior offset is >=1 m, one is <=-1 m, and max absolute offset is >=3 m. Reject a tuple equal to any earlier accepted interior-offset tuple or its reversal; use the lexicographically smaller of forward/reversed tuples as the canonical distinctness key. These geometric requirements prevent trivial/duplicate/reversed draws and use no model outputs. Permit at most64 proposals per group, including rejected aliases. Any exhaustion fails the single generation invocation; no changed thresholds, second seed, model-dependent filtering or automatic regeneration.

The seven scalar fields, widths, amplitudes, compact fleck windows, matching integral rule, apex function, side labels and existing geometry selectors are exactly the reviewed fresh-SELECT template table, with this independently drawn F replacing its polynomial F. Quantization uses each factual native pitch. Use the same post-scalar capture producer and declared eight phase/pitch/oversampling variants. Reversal/oversampling aliases retain common source lineage. No phase/seed variation of an exposed old curve is registered as independent geometry.

Null/invalid/censored and fixed REGRESSION controls remain outside this new draw and outside ranking. They stay required independently.

## Reproducible bounded randomness

- Draw one32-byte master seed from the OS entropy source after producer/protocol review and freeze. Do not accept a user-provided evaluation seed or print the seed.
- Use a versioned deterministic HMAC-SHA256 byte stream: key=master seed; message=ASCII `b-observation-holdout-v2` followed by an unsigned64-bit big-endian block counter starting0. Consume digest bytes in order.
- For an unbiased integer draw, reject byte values>=222; otherwise return `(byte % 37)-16`. Consume at most8192 stream bytes for the whole invocation, failing if exhausted. No unbounded rejection loop.
- Generate groups in fixed contrast-ID lexical order. Within a group, draw five interior offsets in knot order; bounded geometric rejection consumes the next draw from the same stream.
- Deterministic reproducibility uses the privately retained seed plus the exact generator/protocol hashes. Replay is not permission to generate another evaluation package or inspect it before qualification.

## Outputs and placement

The reviewed generic producer runs locally once, outside the isolated FIT worktree. The exact generation identifier is `sealed-observation-v2-generation-v1`; its sole output scope is primary `build/sealed-observation-v2/generation-v1/`. Derive the primary repository from the non-bare common Git directory, not the isolated worktree root; refuse CI execution or an invalid/ambiguous repository. The production CLI takes no seed, output-path or generation-identifier arguments. Claim that exact directory exclusively before drawing entropy; existing directory refuses before any draw. A failed claim/invocation remains terminal. Never overwrite, delete or replace earlier attempts or try an alternate directory. Unit tests may inject dummy temporary roots/entropy through internal functions but cannot mint a production generation claim.

Private generation files:

- `payload.json`: canonical bounded concrete coefficients and per-case recipe/lineage records. No images, credentials or private mapper captures. The scalar/label template version is explicit.
- `generation-private.json`: seed, algorithm/protocol/generator hashes and bounded draw counters. Never publish or show its contents to model workers.
- On failure, safe `failure.json` records only fixed phase/cause/counters, without seed or coefficients. No success index may be minted.

Public metadata output `index.json` contains schema/version, generator/protocol hashes, exact payload SHA-256/byte count and112 bounded entries: ID, split, contrast/side/variant, generator/geometry/source group IDs, physical sampling metadata and each concrete recipe SHA-256. It contains no equation coefficients, coordinates, curve arrays, labels, feature values or seed. The index is the ONLY generated file copied into FIT source control or artifacts.

Normalize the index to stay within its receipt bound: split=`HOLDOUT` once at the top; a seven-element `groups` dictionary carries contrast and generator/geometry/source group IDs; an eight-element `variants` dictionary carries phase/pitch/oversampling metadata. Each of112 entries contains only `id` (short form `b-holdout-v2/g<0..6>/v<0..7>/<left|right>`), `group`, `variant`, `side` and `recipeHash`. Catalog reconstruction expands these validated references. Reject unknown dictionary/entry keys, duplicate or out-of-range references and inconsistent IDs. Do not repeat full group/variant descriptors in every entry or leak concrete geometry through the index.

Bound all outputs before publication: payload<=256 KiB, private metadata<=64 KiB, index<=64 KiB, exactly112 unique cases/seven base groups. Use canonical UTF-8 JSON with sorted keys, no NaN/Infinity, fixed compact separators and a terminal newline. Hash the actual stored bytes; define per-recipe hashes over their own canonical newline-terminated records. Publish success index only after payload/metadata have been atomically written, hashed and structurally validated. Preserve a claimed failed invocation; do not retry implicitly.

Freeze generic producer+protocol hashes BEFORE this invocation. Freeze the resulting public index/payload hashes and active catalog/manifest BEFORE candidate selection. The two freezes are distinct and recorded; creating the index cannot authorize HOLDOUT access.

## Access and catalog obligations

Active experiment:112 original TRAIN +20 fixed REGRESSION +112 fresh SELECT +112 new sealed HOLDOUT=356. Retain112 original SELECT metadata rows as explicitly `INELIGIBLE_DIAGNOSTIC`, for468 catalog rows below512; they cannot enter scoring, shortlist coverage or required SELECT-pair inventories. Exposed original HOLDOUT is removed from qualification and active catalog. All original data remain historical immutable evidence at commit56936cc and original receipts. No cap increases.

CATALOG reads only the public index and metadata. FIT_SELECT must not read or possess `payload.json` or private generation metadata. A sealed HOLDOUT `CaseDefinition` carries the indexed recipe hash/marker, never its concrete coefficients. Constructor/hash/group checks and unknown-key rejection remain strict.

Task5 only: validate a candidate-specific production/regression receipt, claim the one-shot HOLDOUT evaluation, verify exact payload bytes/count/schema/hash against the frozen index, then decode concrete recipes and render with the frozen generic law. A string hash alone cannot authorize reads. No new package, alternative draws, partial package, mismatched generator/source, second claim or post-result reselection.

## Required tests before concrete generation

- Deterministic HMAC byte stream with an explicit dummy seed, unbiased bounded-byte mapping, byte-budget refusal and geometric draw-bound refusal. Dummy inputs are never evaluation recipes.
- Quintic endpoint/C2 continuity and analytic bounds using explicit dummy knots; no hidden evaluation draw is used in tests.
- Exactly112 metadata rows, seven distinct groups with no duplicate/reversed tuples, correlated variants grouped, and no seed/coefficients/labels/coordinates in public index. Accepted draws are explicitly conditional, not independent samples.
- Existing output scope refuses before entropy access; malformed/double generation and exhausted bounds preserve failure without a success index.
- Byte/hash determinism for dummy output, payload/index size refusal, no partial accepted publication and no private files in FIT/CI upload paths.
- Independent actual generic-source/test review before the single unseen generation invocation. Report only hashes, counts and safe status; never cat or broadly inspect private outputs.

All existing production, numerical, safety, fixed-regression, private-comparison, one-shot HOLDOUT and final independent audit gates remain required. No release is authorized.
