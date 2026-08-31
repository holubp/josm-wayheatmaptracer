# WayHeatmapTracer Residual Lateral Stability Hardening and Calibration Plan

**Prepared for:** Codex CLI with GPT-5.6 and `superpowers-gpt-5.6`
**Prepared against:** public `main` of `holubp/josm-wayheatmaptracer`, inspected 2026-08-31
**Observed repository version at preparation time:** `0.19.2`
**Execution checkout:** `96e21297a90c5b75b07e3b4e21e04af04c8efd59` on `main`
**Execution status:** implementation and automated verification complete; manual JOSM beta pending
**Plan status:** ready for execution; all checkpoints initially `pending`
**Primary problem:** unnecessary short-wave lateral movement in `Experimental corridor-aware` + `Precise Shape` output
**Primary priority:** stability, determinism, topology safety, preservation of real bends, and fail-closed behavior
**Local private inputs:** `last-slide-debug*.zip` and `problems-*.zip`, present only in the user's original local clone

> This is an implementation-and-tuning plan, not permission to push, tag, publish, or alter private input archives. Codex may implement and test locally when the user supplies this plan as the task. It must not infer authorization to push, create a pull request, tag, or release.

---

## 0. Copy-paste kickoff prompt for Codex

Paste the following prompt into an up-to-date Codex session opened from the original repository clone, followed by this whole plan or a path to it:

```text
Use $using-superpowers for this conversation. Treat this as High-risk behavioral work because it changes geometry proposed for OSM editing and can affect topology, preview/apply consistency, and private debug data handling.

Implement the attached “WayHeatmapTracer Residual Lateral Stability Hardening and Calibration Plan”. The plan is authoritative for scope, invariants, checkpoints, acceptance criteria, and stop conditions. Adapt file names only where the current checkout differs; do not silently broaden the project.

Before editing:
1. Read the nearest AGENTS.md in full, then DEVELOPMENT.md, PLANS.md, README.md, the completed v0.19.0 geometry-cleanup plan, and all source/tests named by this plan.
2. Invoke $writing-plans to create a timestamped execution copy under superpowers/docs/plans/. Preserve this plan’s checkpoint ledger, add the current commit hash and repository version, and record any material checkout differences. Do not replace the plan with a shorter generic plan.
3. Invoke $systematic-debugging. Establish evidence from deterministic synthetic tests and the local private archives before selecting a root cause or changing production behavior.
4. Use $using-git-worktrees for an isolated implementation worktree. The private ZIP archives exist only in the original clone and may be untracked, so keep the original clone as a read-only calibration corpus and pass its absolute path through CALIBRATION_ARCHIVE_ROOT. Never move, rename, delete, rewrite, or commit those archives.
5. Use $test-driven-development for every confirmed behavioral regression: add or identify a focused red test, make the smallest production change, then refactor with the test green.
6. Use $executing-plans and execute one checkpoint at a time. Update the ledger and create a concise checkpoint handoff after each completed checkpoint.
7. Use Codex native subagents only for bounded, non-overlapping analysis or independent review. Do not let multiple agents edit the same production files concurrently.
8. Invoke $requesting-code-review before integration and $verification-before-completion before claiming success. Create a durable implementation log with $writing-implementation-logs because the work affects proposed OSM geometry and private calibration evidence.

Non-negotiable constraints:
- Preserve LEGACY_V02 behavior exactly.
- Preserve cleanup-disabled corridor-aware behavior exactly if the work is to ship as a 0.19.x patch.
- Keep one exact bounded second-order pair-state DP; do not introduce global beam pruning, a hidden second optimizer, completed-geometry consensus, free moving-average smoothing, or an unbounded state space.
- Keep the raw candidate available; create at most one cleaned sibling; give cleanup no unconditional ranking bonus.
- Preserve fixed/shared/tagged nodes, selected anchors, endpoint/junction constraints, stale-source checks, downloaded-area checks, final topology checks, preview/apply identity, and undo/redo behavior.
- Use cumulative ground metres for physical windows and native source-pixel units for raster-resolution-normalized quantities. Never treat projection units, view pixels, raster oversampling, or profile counts as metres.
- Never export or commit cookies, Authorization headers, CloudFront credentials, signed URLs, tokens, or private archive contents.
- Do not search outside the original repository root for the private ZIP files.
- Do not push, open a PR, tag, publish a release, or rewrite history without explicit authorization in a later user message.

Version decision:
- Target 0.19.3 only if behavior changes are confined to already explicitly enabled geometry cleanup, no new user-facing setting/default/preset is added, cleanup-disabled exact output remains unchanged, and ranking semantics remain unchanged.
- Reclassify to 0.20.0 before release if cleanup-disabled corridor-aware output changes, cleanup becomes enabled by default, a new user-visible preset/setting/workflow is added, or candidate ranking/selection semantics materially change.
- Do not edit gradle.properties until the final version gate.

Begin with Checkpoint 0. Report the current commit, version, worktree path, archive count/hash manifest location, baseline test result, and any conflict between this plan and the current checkout before making behavioral changes.
```

---

## 1. Executive design decision

The implementation should **not** start by replacing the corridor-aware tracker, increasing generic smoothing, reducing output points indiscriminately, or tuning constants from screenshots. The current architecture already contains the right safety-oriented building blocks:

- source-resolution-aware corridor profiles;
- raw/B3/B5 center evidence;
- robust longitudinal tube references;
- one exact bounded second-order dynamic program;
- optional unsupported-ripple regularization inside that DP;
- optional constrained normal-only smoothing and heatmap-constrained point reduction;
- raw and cleaned alternatives;
- final-preview topology and assignment revalidation.

The residual lateral movement most plausibly survives because one or more of these conditions holds:

1. the profile-derived center itself alternates because of source-pixel phase, broad plateaus, asymmetric shoulders, or raw/B3/B5 disagreement;
2. the local or stability tube inherits that alternation;
3. the current curvature term mainly penalizes deviation from the tube’s change of heading, so a wiggling reference can authorize a wiggling candidate;
4. the current unsupported-ripple detector counts reversals in the local tube but does not explicitly separate a robust physical-distance trend from short residual oscillation and does not use a calibrated amplitude term;
5. the optimized path is acceptably centered, but dense `Precise Shape` vertices expose sub-source-pixel movement that should be removed by the existing evidence-constrained cleanup stage;
6. the cleanup stage is disabled, rejected, or calibrated too weakly for the affected cases;
7. a smaller subset is not ripple at all, but a branch, endpoint, junction, gap, or palette-localization problem.

The plan therefore uses the following order:

1. **attribute the movement quantitatively;**
2. **freeze no-regression controls;**
3. **improve unsupported-ripple attribution with a robust trend and amplitude;**
4. **add a weak absolute short-wave turn cost inside the existing exact DP, gated only where motion is unsupported;**
5. **retune the existing constrained smoother and simplifier without weakening fit or topology gates;**
6. **change plateau targeting only when evidence proves it is a material source;**
7. **make no default or raw-output change until private holdout cases and synthetic controls pass.**

This is deliberately narrower and safer than the broader future improvements previously identified for palette inversion, sampling-parameter separation, joint global strand optimization, continuous centerline refinement, or rough-sketch 2-D minimal paths. Those remain valid research directions but must not be combined with this stability fix.

---

## 2. Release classification and decision gates

### 2.1 Patch-safe path: candidate `v0.19.3`

The change may remain a patch release only when all of the following are true:

- `LEGACY_V02` output is unchanged;
- corridor-aware output with `GeometryCleanupConfig.disabled()` is exactly unchanged, including allowed states, tie ordering, selected offsets, candidate IDs, ranking, warnings, and final preview;
- improvements occur only when the user has already explicitly enabled geometry cleanup;
- existing cleanup modes and preset names remain unchanged;
- no cleanup default changes;
- no new user-facing control, preset, or workflow is introduced;
- no candidate receives a ranking bonus merely because it is cleaned;
- diagnostics are additive and backward-readable;
- all safety and calibration gates pass.

This path is the default implementation target because it minimizes risk and preserves existing behavior.

### 2.2 Conditional minor path: candidate `v0.20.0`

Reclassify the work to a minor release before integration when any of these becomes necessary:

- the cleanup-disabled corridor-aware objective or selected geometry changes;
- a mild stabilizer is made intrinsic to `Precise Shape` rather than opt-in cleanup;
- geometry cleanup becomes enabled by default;
- a new `Precision` or similar preset is added;
- existing preset values are materially redefined;
- a new UI control or preference key is added;
- raw/cleaned ranking semantics change;
- profile sampling or lateral state construction changes;
- the debug workflow requires a new user-facing action.

A `v0.20.0` decision is not a failure. It simply requires broader migration, UI, documentation, and manual regression work. Codex must not hide a behavior change inside `0.19.3` merely to avoid the larger version.

### 2.3 Do not choose the version at the beginning

Record the version as `UNDECIDED (0.19.3 patch-safe target)` until the behavior and UI scope is known. Do not edit `gradle.properties` before the final version checkpoint.

---

## 3. Current architecture that must be preserved

Codex must verify these statements against the actual checkout before relying on them:

- `LEGACY_V02` is the stable/default tracker and must remain isolated.
- `CORRIDOR_AWARE` is opt-in and experimental.
- `PRECISE_SHAPE` reconstructs dense geometry from the traced centerline.
- geometry cleanup is a separate, future-slide-only, opt-in stage.
- `CorridorCenterlineOptimizer` performs one exact second-order pair-state dynamic program over a bounded lateral state set.
- the current lateral state bound is configured by `CorridorOptimizationParameters` and was 21 states at plan preparation time.
- mandatory states include band/core/shoulder centers or boundaries, robust tube centers, raw/B3/B5 centers, endpoint targets, and coarse-scale priors.
- `CorridorTubeBuilder` uses robust physical-distance references around approximately 5 m, 12 m, and a weak-signal 32 m scale.
- `UnsupportedRippleEvaluator` currently examines contiguous direct evidence in physical windows, counts local-center slope reversals with a source-pixel deadband, and reduces intervention when sustained motion is supported.
- cleanup-enabled optimization currently adds a stability-tube data cost and increases continuity/acceleration weights in unsupported windows.
- the existing geometric-curvature term compares candidate and tube/reference heading evolution; it is not a standalone absolute short-wave curvature penalty.
- `HeatmapConstrainedLaplacianSmoother` and `HeatmapConstrainedSimplifier` already operate under evidence and topology constraints.
- the raw candidate remains selectable and at most one cleaned sibling may be generated.
- final immutable assignments and all topology/safety checks are rebuilt for the stored cleaned preview.
- debug format 9 already carries cleanup-specific artifacts and older formats remain readable.

Any material discrepancy must be written into the execution plan before edits. The current checkout and nearest `AGENTS.md` take precedence over this document.

---

## 4. Goals

### 4.1 Functional goals

1. Reduce visibly unnecessary short-wave lateral movement in corridor-aware precise geometry.
2. Preserve true sustained bends, S-curves, apexes, switchbacks, broad-road centers, sparse-but-coherent corridors, endpoint approaches, and junction shapes.
3. Distinguish upstream center jitter, tube-reference jitter, optimizer-following jitter, postprocessor insufficiency, and output-density-only effects.
4. Keep correction strength continuous and evidence-gated rather than using brittle signal-class branches.
5. Keep physical behavior stable across source resolution, latitude, profile spacing, and raster oversampling.
6. Preserve raw output as an inspectable fallback.
7. Make every intervention diagnosable from an exported, redacted bundle.
8. Make tuning reproducible from local archives and deterministic synthetic fixtures.
9. Prevent repeated-slide drift or point growth.
10. Preserve JOSM edit safety and exact preview/apply/undo/redo correspondence.

### 4.2 Stability goals

1. Zero behavior change in `LEGACY_V02`.
2. Zero behavior change in cleanup-disabled corridor-aware mode for the patch-safe path.
3. One exact optimizer run; no global beam truncation or unbounded state growth.
4. No weakening of fit, topology, anchor, downloaded-area, stale-source, or command safety.
5. Bounded memory and near-current runtime.
6. Deterministic outputs for identical inputs and configuration.
7. Old debug bundles remain readable.
8. Private archives and credentials remain outside version control and exported reports.

---

## 5. Non-goals for this work

Unless a checkpoint’s evidence proves that one of these is indispensable, do **not** implement it in this workstream:

- replacing the corridor-aware tracker with skeletonization, vesselness, active contours, graph cuts, neural inference, or a general 2-D path finder;
- changing Strava palette-to-intensity mappings;
- changing detector source-tier ordering or candidate ranking priors;
- replacing `CorridorTracker` longitudinal association;
- increasing the lateral DP state bound;
- adding continuous subpixel optimization after the DP;
- changing inference/validation zooms;
- splitting `sampleStepMeters` into new public settings;
- reducing the 4 m profile-spacing floor;
- changing rough-sketch search behavior;
- adding a new automatic cleanup default;
- removing or hiding the raw candidate;
- changing topology tolerances to make a candidate pass;
- adding generic post-hoc moving-average, Savitzky-Golay, spline, or Gaussian smoothing that is not heatmap- and anchor-constrained;
- adding a second optimizer run or iterative completed-geometry consensus;
- adding machine-learning weight fitting from a small private corpus;
- publishing private fixtures or derived geometry that the user has not approved.

Record any tempting non-goal in `PLANS.md` or the implementation log rather than implementing it opportunistically.

---

## 6. Non-negotiable invariants

### 6.1 Coordinate and unit invariants

- Geographic checks use `LatLon`/`Bounds` and ground-distance methods.
- Projected coordinates remain `EastNorth`/projection space and are never assumed to be metres.
- raster-space offsets are named `*Px` and normalized by the native source-pixel pitch when the pitch is known.
- physical windows, wavelength, reversal spacing, and gap length use cumulative ground metres.
- profile-count windows may be used only as bounded implementation caps, never as the definition of a physical scale.
- managed raster origins remain pixel boundaries and decoded samples remain pixel centers with the existing half-pixel contract.
- no new value may be labelled “metres” or “source pixels” unless its transform is authoritative and tested.

### 6.2 Optimizer invariants

- retain one exact second-order pair-state DP;
- retain deterministic tie ordering;
- retain bounded lateral state count;
- do not add global beam pruning;
- do not change allowed states for a patch-safe implementation;
- do not invoke another optimizer during cleanup;
- a new short-wave term must be expressible as a per-transition second-order cost using existing pair-state information;
- when its effective strength is zero, it must produce exactly zero added cost and preserve exact baseline output.

### 6.3 Candidate and UI invariants

- raw candidate identity and geometry remain available;
- at most one cleaned sibling per eligible raw candidate;
- cleanup rejection or skip leaves the raw candidate usable;
- cleaned status alone gives no ranking bonus;
- settings affect only future slides;
- a modeless preview remains bound to slide-time settings and geometry;
- no hidden rerun occurs when a candidate is selected or Apply is pressed.

### 6.4 OSM safety invariants

- fixed, shared, tagged, selected-anchor, endpoint, and junction nodes retain their existing protection semantics;
- no simplification removes a tagged or referenced node;
- final cleaned geometry receives fresh immutable node assignments;
- self-intersection, foldback, terminal approach, connected-way crossing, vertex touch, collinear overlap, protected-anchor, stale-source, and downloaded-area checks remain fail-closed;
- previewed geometry is exactly the geometry passed to the command;
- execute, undo, and redo touch no unrelated OSM primitives.

### 6.5 Privacy and repository invariants

- never commit `last-slide-debug*.zip`, `problems-*.zip`, extracted contents, private labels, tile images, cookies, signed URLs, tokens, or generated private reports;
- do not add broad ignore rules that could hide legitimate source files; add precise local-result patterns only when necessary;
- never modify unrelated user files;
- never search outside the original repository root for private archives;
- all generated calibration work belongs under `build/` or another already ignored path;
- logs and handoffs contain hashes and redacted metadata, not private geometry or credentials.

---

## 7. Deliverables

The completed work must produce the following tracked deliverables, subject to checkpoint evidence:

1. a timestamped execution plan in `superpowers/docs/plans/` with an updated ledger;
2. deterministic synthetic regression fixtures for unsupported short-wave movement and supported-turn controls;
3. hardened local-archive analysis/orchestration with bounded nested-ZIP handling;
4. additive lateral-stability attribution metrics and reports;
5. an improved evidence-gated unsupported-ripple evaluator based on robust physical-distance trend residuals and amplitude;
6. a weak absolute short-wave turn cost integrated into the existing exact DP, active only when cleanup is enabled unless the work is explicitly reclassified to `0.20.0`;
7. calibrated constrained-smoothing and point-reduction behavior, without weakening evidence or topology gates;
8. a conditional plateau-target stabilization only if proven necessary;
9. backward-compatible debug/analyzer support;
10. focused and full Java/Python tests, performance and determinism evidence;
11. updated `README.md`, `DEVELOPMENT.md`, `PLANS.md`, relevant Javadocs, and durable invariants in `AGENTS.md` only when truly necessary;
12. a private, untracked calibration report and frozen holdout result;
13. an implementation log and independent review report;
14. a release classification recommendation, but no release without later authorization.

Expected untracked/private outputs under the implementation worktree:

```text
build/calibration-input/archive-manifest.json
build/calibration-input/archive-manifest.sha256
build/calibration-input/case-overrides.json
build/calibration-input/split-lock.json
build/calibration-results/bundle-candidates.csv
build/calibration-results/lateral-attribution.csv
build/calibration-results/lateral-attribution.json
build/calibration-results/replayability.json
build/calibration-results/parameter-runs.csv
build/calibration-results/pareto-frontier.csv
build/calibration-results/training-report.md
build/calibration-results/validation-report.md
build/calibration-results/holdout-report.md
build/calibration-results/holdout-report.json
build/calibration-results/privacy-scan.json
build/calibration-results/performance.json
build/calibration-results/determinism.json
```

Names may be adapted to existing repository conventions, but the information and privacy boundaries must be preserved.

---

## 8. Failure taxonomy and required attribution

Every target case must be classified into one primary cause and any relevant secondary causes. Do not tune one global weight before this attribution exists.

### 8.1 Primary classes

| Code | Meaning | Diagnostic signature | Likely remedy |
| --- | --- | --- | --- |
| `UPSTREAM_PROFILE_CENTER` | raw/B3/B5 or band/core centers alternate before tube construction | profile centers show high-frequency residual; tube and selected path follow | profile-center/plateau evidence stabilization, only if proven |
| `TUBE_REFERENCE` | local/stability/effective tube itself carries short oscillation | profile centers may vary; robust tube residual remains high | improved robust trend and unsupported-ripple attribution |
| `OPTIMIZER_RELATIVE_CURVATURE` | tube/reference wiggles and candidate follows because relative curvature is cheap | selected path nearly matches wiggling reference despite low motion support | add gated absolute short-wave turn cost |
| `POSTPROCESS_INEFFECTIVE` | raw optimizer wiggles and cleaned sibling retains too much of it | cleanup attempted and accepted but suppression is weak | tune constrained Laplacian/point reduction and gates |
| `POSTPROCESS_REJECTED` | cleanup correctly or incorrectly fails closed | raw wiggles; no cleaned sibling; typed rejection present | fix evidence mapping/gate only if rejection is erroneous |
| `OUTPUT_DENSITY_ONLY` | centerline residual is below resolution but dense vertices make tiny changes visible | low source-pixel residual, high point count, simplifier can remove without fit loss | tune constrained point reduction, not detector |
| `DETECTOR_OR_BRANCH` | path follows another parallel trace or changes corridor identity | persistent offset/valley crossing, not short residual oscillation | out of scope for ripple tuning; preserve as separate problem |
| `ENDPOINT_OR_JUNCTION` | movement concentrated near protected/adjustable boundary | endpoint guide or topology warnings dominate | endpoint/junction-specific diagnosis; do not smooth through anchors |
| `GAP_OR_SPARSE_PARENT` | movement occurs in interpolation-only or sparse parent regions | missing direct evidence, parent/child ambiguity | fail closed or preserve raw; do not let predicted evidence authorize smoothing |
| `REAL_SUPPORTED_GEOMETRY` | movement is supported by multiscale/direct evidence | coherent direction/apex/switchback, strong motion support | preserve; use as negative control |
| `INSUFFICIENT_EVIDENCE` | bundle lacks data or ground scale | metrics unavailable or non-authoritative | do not tune or claim replay |

### 8.2 Secondary flags

Record at least:

- `plateau-toggle`;
- `raw-b3-b5-disagreement`;
- `source-pixel-phase`;
- `scale-conflict`;
- `parent-merge`;
- `parallel-corridor`;
- `weak-signal`;
- `off-raster`;
- `interpolated-only`;
- `search-boundary-touch`;
- `endpoint-window`;
- `junction-window`;
- `repeated-slide-drift`;
- `cleanup-fit-gate`;
- `cleanup-topology-gate`;
- `cleanup-mapping-gate`;
- `unknown`.

### 8.3 Attribution rule

The analyzer may propose a deterministic cause, but human overrides are authoritative. Store overrides only in an untracked local file. Never infer ground truth from a filename alone.

---

## 9. Metrics and mathematical definitions

All metrics must state their units and availability. Missing or untrusted values are `null`/`unavailable`, never zero.

### 9.1 Basic profile quantities

For profile `i`:

- `s_i`: cumulative chainage in ground metres;
- `p_i`: native source-pixel pitch in sampled-raster pixels;
- `u_i`: selected optimized lateral offset in sampled-raster pixels;
- `u_i^sp = u_i / p_i`: selected offset in source pixels;
- `c_i^raw`, `c_i^B3`, `c_i^B5`: profile-center estimates;
- `t_i^local`, `t_i^stability`, `t_i^effective`: tube centers;
- `q_i`: robust short-window trend center;
- `sigma_i`: authoritative localization/tube uncertainty;
- `m_i`: sustained-motion support;
- `d_i`: direct-evidence indicator or fraction;
- `x_i`: scale conflict/parent merge/authorization flags.

### 9.2 Robust trend

Within a contiguous direct-evidence physical window around profile `i`, fit:

\[
q(s) = a + b(s-s_i)
\]

using a centered, confidence-weighted Huber regression. Initial weights should be based on:

\[
w_j = d_j \cdot \operatorname{clamp}(confidence_j) \cdot
\frac{1}{\max(0.5,\sigma_j/p_j)^2}.
\]

Use a small fixed number of deterministic IRLS iterations, preferably the same robust-regression conventions already used by `CorridorTubeBuilder`. Do not introduce a general-purpose dependency merely for this fit.

The first implementation should fit the local/tube center evidence already present in the slice. Do not change upstream extraction in the same checkpoint.

### 9.3 Trend residual and amplitude

Normalize the residual to source pixels:

\[
e_j = \frac{c_j-q(s_j)}{p_j}.
\]

Compute a robust local amplitude, for example:

\[
A_i = \frac{Q_{0.90}(e)-Q_{0.10}(e)}{2},
\]

or an equivalent deterministic weighted statistic. Record both signed residual and absolute amplitude. Do not use only reversal count: a tiny numerical zigzag and a visibly wrong 0.7-source-pixel oscillation must not receive the same weight.

### 9.4 Reversal and wavelength metrics

For consecutive residuals, define a slope in source pixels per metre:

\[
v_j = \frac{e_j-e_{j-1}}{s_j-s_{j-1}}.
\]

Treat `v_j` as zero when its implied residual movement is below a calibrated source-pixel deadband. Count a reversal when consecutive non-zero signs differ. Record:

- reversal count;
- median distance between reversal locations in metres;
- shortest and longest reversal spacing;
- physical window span;
- direct-evidence coverage;
- maximum absolute residual;
- robust amplitude.

The current `0.12` source-pixel deadband is a calibration seed, not a guaranteed final value.

### 9.5 Unsupported-ripple score

Use smooth, bounded components rather than hard signal classes. A starting formulation is:

\[
R_i = C_i \cdot S_i \cdot A_i^* \cdot U_i,
\]

where:

- `C_i` is direct-window coverage/contiguity authorization;
- `S_i` is short-scale exposure derived from reversal spacing relative to the configured ripple scale;
- `A_i^*` is a smooth amplitude score between onset and full-effect amplitudes;
- `U_i` is lack of support for coherent motion, apex, or multiscale turn evidence.

A concrete seed is:

\[
S_i = \operatorname{clamp}\left(\frac{R-\tilde{\lambda}_i}{R}\right),
\]

where `R` is the configured ripple scale in metres and `tilde(lambda)` is median reversal spacing. Use zero when fewer than two meaningful reversals exist.

Use a smoothstep amplitude gate:

\[
A_i^* = \operatorname{smoothstep}(A_{on},A_{full},A_i).
\]

Use support suppression such as:

\[
U_i=(1-m_i)\,(1-turnSupport_i)\,(1-apexSupport_i),
\]

with each factor clamped to `[0,1]`. Do not multiply in an unavailable value as zero support; unavailable required evidence should yield a typed no-intervention reason.

The final score is clamped to `[0,1]` and is **not** itself proof that movement may be pulled toward an arbitrary reference. Keep a distinct trend-authorization score.

### 9.6 Trend authorization

Define `T_i` separately from `R_i`. It must be low or zero when:

- the window is mostly interpolated/predicted;
- raw/B3/B5 centers disagree beyond uncertainty;
- the trend fit is ill-conditioned;
- scale conflict or parent merging makes a single center unreliable;
- the corridor touches the search boundary;
- the point is within a protected endpoint/junction transition where a different guide owns geometry;
- the trend lies outside the selected shoulder envelope;
- there are insufficient direct observations.

Use `R_i` to decide that short-wave turn should be discouraged; use `R_i*T_i` when adding a positional pull toward the robust trend/stability center.

### 9.7 High-frequency geometry metric

For the selected and final preview geometry, compute signed lateral residual to a robust physical-distance trend and report:

- RMS in source pixels;
- p50, p90, p95, and maximum absolute residual;
- reversal count and median spacing;
- residual amplitude;
- geometry turn-rate RMS/p95 in radians per source pixel and radians per metre where authoritative;
- the same metrics for profile, local tube, stability tube, effective tube, raw optimizer, and cleaned preview.

Do not use raw polyline vertex angle alone because unequal point spacing can exaggerate or hide roughness.

### 9.8 Shape retention

For synthetic supported sine, S-curve, apex, and switchback fixtures, compare the projected amplitude or signed offset envelope before and after intervention:

\[
retention = \frac{A_{after}}{A_{before}}.
\]

Also compare center bias and maximum local displacement. A retained amplitude alone is insufficient if the entire curve moved sideways.

### 9.9 Repeated-slide stability

When bundle `n+1` original geometry matches bundle `n` applied geometry, report:

- bidirectional mean/p95/max distance;
- point-count delta and growth ratio;
- length change;
- candidate identity/rank changes;
- warning/applicability changes.

The preferred result is idempotent or convergent, never a repeated lateral walk or point explosion.

---

## 10. Private archive discovery and handling

The archives are available only to Codex in the user's original clone. They are not available to the author of this plan and must not be expected in a fresh worktree.

### 10.1 Original-clone corpus root

Before creating a worktree:

```bash
set -euo pipefail
ORIGINAL_REPO_ROOT="$(git rev-parse --show-toplevel)"
export CALIBRATION_ARCHIVE_ROOT="$ORIGINAL_REPO_ROOT"
printf 'Original corpus root: %s\n' "$CALIBRATION_ARCHIVE_ROOT"
```

Discover only inside that root:

```bash
find "$CALIBRATION_ARCHIVE_ROOT" -maxdepth 2 -type f \
  \( -name 'last-slide-debug*.zip' -o -name 'problems-*.zip' \) \
  -print0 | sort -z
```

Do not use `/`, `$HOME`, parent-directory sweeps, shell history, browser profiles, or global file indexes.

### 10.2 Worktree isolation

Use an isolated worktree. The private archives remain in the original clone:

```bash
repo_name="$(basename "$ORIGINAL_REPO_ROOT")"
worktree_parent="$(dirname "$ORIGINAL_REPO_ROOT")"
worktree_path="$worktree_parent/${repo_name}-lateral-stability"

# Use a branch only when local branch/commit creation is authorized.
# Otherwise create a detached worktree and keep checkpoint evidence in files.
git worktree add --detach "$worktree_path" HEAD
cd "$worktree_path"
export CALIBRATION_ARCHIVE_ROOT="$ORIGINAL_REPO_ROOT"
```

If the path already exists, stop and inspect it; never delete an existing worktree automatically.

### 10.3 Archive immutability

- open archives read-only;
- hash before analysis;
- do not normalize ZIPs, rewrite timestamps, add comments, or repair them in place;
- do not extract into the source tree;
- do not preserve extracted copies after the analysis unless under ignored `build/calibration-work/`;
- never rename an archive based on inferred case labels.

### 10.4 Safe nested-ZIP reader

Existing scripts support nested ZIPs, but bulk calibration must use a bounded reader or wrapper with explicit limits. Initial limits may be adjusted only with a documented reason:

| Limit | Initial value |
| --- | ---: |
| maximum nested ZIP depth | 8 |
| maximum outer archives | 1,000 |
| maximum entries per ZIP | 20,000 |
| maximum total entries per outer archive | 100,000 |
| maximum compressed bytes per outer archive | 2 GiB |
| maximum uncompressed bytes per outer archive | 8 GiB |
| maximum single member uncompressed size | 1 GiB |
| maximum compression ratio | 200:1 |
| maximum filename length | 1,024 bytes |

Reject or quarantine with a typed reason:

- absolute paths;
- `..` traversal after normalization;
- NUL-containing names;
- Windows drive/UNC paths;
- symlink/device entries;
- encrypted entries;
- unsupported compression;
- malformed CRC;
- duplicate names with conflicting content;
- limit violations.

Prefer streaming or in-memory reads without filesystem extraction. When extraction is unavoidable, resolve every target path and prove it remains below `build/calibration-work/<outer-sha256>/`.

### 10.5 Archive manifest

For each outer archive record:

- relative path below `CALIBRATION_ARCHIVE_ROOT`;
- basename;
- byte size;
- modification time for local traceability only;
- SHA-256;
- nested archive/member counts;
- detected debug format versions;
- plugin versions/build identities where present;
- candidate count;
- presence of raw/B3/B5/profile/corridor/geometry/cleanup artifacts;
- selected/applied candidate metadata when present;
- rating/negative-tag availability;
- authoritative scale availability;
- replayability status;
- parser warnings;
- privacy scan status.

Generate a separate hash of the canonicalized manifest. Do not store full geometry or image content in the manifest.

### 10.6 Privacy scan

Scan member names and bounded text content for, at minimum:

```text
Cookie
Authorization
CloudFront-Key-Pair-Id
CloudFront-Policy
CloudFront-Signature
CloudFront-
_strava_idcf
X-Amz-Credential
X-Amz-Signature
access_token
refresh_token
Bearer
signedUrl
signed_url
```

Also detect URLs containing query signatures or policy blobs. Reports contain only:

- archive hash;
- member hash or redacted member name;
- rule identifier;
- count;
- severity.

Never echo the matched secret value. If a likely secret is found, quarantine that archive from automated report generation and notify the user in the implementation handoff without exposing the value.

---

## 11. Corpus labels, split, and replayability

### 11.1 Human label override file

Create an untracked file such as:

```text
build/calibration-input/case-overrides.json
```

Suggested schema:

```json
{
  "schemaVersion": 1,
  "cases": [
    {
      "outerArchiveSha256": "...",
      "nestedBundlePathHash": "...",
      "candidateId": "...",
      "role": "target|good-control|supported-turn|branch-problem|exclude",
      "primaryCause": "unnecessary-undulation|unknown|...",
      "secondaryFlags": ["plateau-toggle"],
      "expectedCandidateId": null,
      "notes": "Private local note; never commit"
    }
  ]
}
```

Do not put raw URLs, coordinates, screenshots, or secrets into tracked files.

### 11.2 Split by independent corridor/session

All variants that may contain the same geometry must remain in one split:

- all palettes of the same slide;
- repeated slides of the same corridor;
- nested bundles from the same outer `problems-*.zip` grouping when they share geography;
- raw and cleaned siblings;
- zoom/source variants of one case.

Preferred split:

- training: 60%;
- validation: 20%;
- holdout: 20%.

When there are too few independent groups, use leave-one-outer-archive-out validation and label the result **case-study calibration**, not a generalized benchmark.

Freeze `split-lock.json` from archive/group hashes before behavioral tuning. Never move a failing holdout case into training.

### 11.3 Replayability levels

Assign one of these levels to each bundle:

- `R0_OUTCOME_ONLY`: only exported geometry/metrics; can analyze but cannot replay the tracker;
- `R1_PROFILE_REPLAY`: complete profile, track, scale, settings, and constraints sufficient to replay tube/optimizer logic;
- `R2_FULL_DETECTOR_REPLAY`: scalar raster/profile acquisition inputs and transforms sufficient to replay extraction plus optimization;
- `R3_FULL_SLIDE_REPLAY`: complete redacted source imagery, transforms, OSM segment context, and settings sufficient to reproduce final-preview generation offline.

Do not claim a new algorithm was tested against an `R0` bundle. Use `R0` only for baseline attribution or to identify cases that must be manually recaptured with a new build.

### 11.4 No fabricated reconstruction

If a bundle lacks an authoritative ground scale, profile normals, band identity, selected-track association, or source transform, mark the relevant metric unavailable. Do not infer it from current viewport scale, filename, point count, or later screenshots.

---

## 12. Checkpoint ledger

Codex must copy this table to the timestamped repository plan and update it transactionally. At most one checkpoint may be `in_progress`.

| CP | Deliverable | Initial status | Completion evidence |
| ---: | --- | --- | --- |
| 0 | Preflight, worktree, baseline, version and archive inventory | complete | baseline at `96e2129`; private corpus untouched; ignored manifest under `build/calibration-results/` |
| 1 | Safe local archive orchestration and parser hardening | complete | shared bounded snapshot reader, privacy quarantine, 29 Python tests plus 5 subtests |
| 2 | Lateral-movement attribution metrics and frozen corpus split | complete | 14 outer archives split 9/3/2; prior analysis found 227 profile-center cases and only 1 optimizer-added case among 1,146 variants |
| 3 | Deterministic synthetic regression and preservation fixtures | complete | amplitude/span red tests plus existing scale/bend/sine/switchback/topology controls |
| 4 | Robust trend-residual unsupported-ripple evaluator | complete | Huber trend, physical span, source-pixel amplitude, typed reasons, authorization gate |
| 5 | Gated absolute short-wave turn cost in the exact DP | complete | one exact DP, weight `0.20`, zero disabled cost, unchanged state/transition counts |
| 6 | Conditional plateau-target stabilization | N/A | evidence gate not met; no plateau objective change |
| 7 | Constrained smoothing and simplification calibration | complete | existing presets unchanged; full cleanup calibration/performance suites green |
| 8 | Replayable private-corpus calibration and holdout | blocked-manual | old corpus has no accepted cleaned output; requires a fresh manual JOSM recapture, no fabricated replay |
| 9 | Preview/apply/undo/redo, ranking, anchors and topology integration | complete | integration matrix green; raw fallback/ranking contracts unchanged |
| 10 | Debug schema, analyzers, documentation and privacy compatibility | complete | additive format 10; formats 1-9 and legacy ZIP codecs tested; docs and bounded analyzer updated |
| 11 | Performance, determinism, full verification and independent review | blocked-manual | automated gate green: 317 Java tests, 29 Python tests plus 5 subtests, build/Javadoc, strict corpus scan; private replay/holdout unavailable under CP8 |
| 12 | Manual JOSM beta, version decision and release-ready handoff | maintainer-authorized | maintainer explicitly authorized `0.19.3` publication on 2026-08-31; unavailable private replay remains documented rather than fabricated |

### Execution recovery note

The initial detached Termux worktree was removed by temporary-directory cleanup between
resumes before its uncommitted files could be integrated. The original checkout remained
unchanged. The tested implementation was reconstructed in the persistent checkout from
the checkpoint evidence; all private archives and the user's pre-existing untracked files
remained untouched.

---

# 13. Checkpoint execution details

## CP0 — Preflight, worktree, baseline, and corpus inventory

### Purpose

Establish the exact starting state and prove that later changes—not a dirty checkout, version mismatch, private archive difference, or existing test failure—cause any observed delta.

### Work

1. Invoke `$using-superpowers`, `$writing-plans`, `$systematic-debugging`, and `$using-git-worktrees` as stated in the kickoff.
2. Read, in this order:
   - nearest `AGENTS.md`;
   - `DEVELOPMENT.md`;
   - `PLANS.md`;
   - `README.md`;
   - completed `superpowers/docs/plans/*v0.19.0*geometry-cleanup*.md`;
   - current optimizer, tube, ripple, cleanup, diagnostics, analyzer, and test sources.
3. Record:
   - `git rev-parse HEAD`;
   - branch/detached status;
   - `git status --short --untracked-files=all` in the original clone and worktree;
   - repository version and JOSM version from `gradle.properties`;
   - Java, Gradle, and Python versions;
   - operating system and architecture;
   - whether all Superpowers skills required by the prompt are available.
4. Create the isolated worktree without touching private archives.
5. Discover and hash local archives in the original clone.
6. Run the existing analyzers unchanged against a bounded small sample first, then the complete local set if safe.
7. Run the full baseline tests before production edits.
8. Capture exact baseline checksums or golden vectors for:
   - `LEGACY_V02` fixtures;
   - corridor-aware cleanup-disabled optimizer offsets/cost/state counts;
   - relevant cleanup-enabled synthetic fixtures;
   - candidate IDs/order where deterministic tests exist.
9. Record any pre-existing failures separately. Do not fix unrelated failures inside this workstream.

### Commands

```bash
set -euo pipefail
ORIGINAL_REPO_ROOT="${CALIBRATION_ARCHIVE_ROOT:-$(git rev-parse --show-toplevel)}"
cd "$(git rev-parse --show-toplevel)"

git rev-parse HEAD
git status --short --untracked-files=all
grep -E '^(pluginVersion|josmVersion)=' gradle.properties || true
java -version
sh gradlew --version
python3 --version

sh gradlew clean test build javadoc
python3 -m pytest -q scripts/tests
python3 -m py_compile \
  scripts/analyze-debug-bundles.py \
  scripts/analyze-slide-undulations.py \
  scripts/validate-sampling-scale.py \
  scripts/heatmap-palette-lab.py
python3 scripts/validate-sampling-scale.py --pretty

git diff --check
```

Existing private-data analyzers may be run only after archive safety is understood:

```bash
mkdir -p build/calibration-results
python3 scripts/analyze-debug-bundles.py \
  "$ORIGINAL_REPO_ROOT" \
  --raw-csv build/calibration-results/baseline-debug-candidates.csv
python3 scripts/analyze-slide-undulations.py \
  "$ORIGINAL_REPO_ROOT" \
  --csv build/calibration-results/baseline-undulations.csv \
  --json build/calibration-results/baseline-undulations.json
```

If passing the repository root causes unrelated ZIP discovery, stop and invoke the scripts with an explicit generated archive list or implement CP1’s orchestrator first. Do not broaden analyzer input indiscriminately.

### Completion criteria

- clean or fully documented baseline;
- complete test result captured;
- original/private files untouched;
- worktree path and corpus root recorded;
- archive count and outer SHA-256 manifest created;
- no secret value displayed;
- exact no-change vectors identified;
- any checkout differences from this plan documented;
- no production behavior changed.

### Stop conditions

Stop before code changes when:

- baseline tests fail in relevant modules and cause is unknown;
- current `AGENTS.md` contradicts a proposed invariant;
- archives contain likely live credentials and safe quarantine is not established;
- the worktree would overwrite an existing directory;
- the current checkout has unrelated edits in files this plan needs and ownership is unclear.

---

## CP1 — Safe local archive orchestration and parser hardening

### Purpose

Make private-corpus analysis deterministic, bounded, repeatable, and safe without changing plugin behavior.

### Preferred design

Add a small orchestration layer rather than overloading the two existing analyzer CLIs. Preserve their current command-line compatibility.

Suggested structure, adapted to repository conventions:

```text
scripts/calibrate-lateral-stability.py
scripts/wayheatmap_analysis/__init__.py
scripts/wayheatmap_analysis/safe_zip.py
scripts/wayheatmap_analysis/archive_manifest.py
scripts/wayheatmap_analysis/privacy.py
scripts/wayheatmap_analysis/io.py
scripts/tests/test_safe_zip.py
scripts/tests/test_archive_manifest.py
scripts/tests/test_privacy_scan.py
scripts/tests/test_calibrate_lateral_stability.py
```

If adding a package is disproportionate, use one focused helper module, but do not duplicate nested-ZIP logic across three scripts.

### Work

1. Refactor only pure parsing helpers from existing analyzers; preserve old CLI output and semantics.
2. Implement bounded nested-ZIP traversal with the limits in Section 10.
3. Canonicalize member paths and reject traversal/symlink/encrypted/limit violations.
4. Stream hashes; avoid loading large image members merely to inventory them.
5. Implement redacted privacy scanning.
6. Generate canonical JSON manifests with stable key ordering and no timestamps in checksummed content unless explicitly separated.
7. Add `--archive-root`, repeatable `--include`, `--exclude`, `--output-dir`, `--manifest-only`, and `--strict` options to the new orchestrator.
8. Default includes for this project may be:

```text
last-slide-debug*.zip
problems-*.zip
```

but only below the explicitly supplied root.
9. Never make private archive presence a requirement of normal CI or `gradlew test`.
10. Ensure all paths written to reports are relative or hashed; do not leak the user’s home path.

### Suggested command

```bash
python3 scripts/calibrate-lateral-stability.py \
  --archive-root "$CALIBRATION_ARCHIVE_ROOT" \
  --include 'last-slide-debug*.zip' \
  --include 'problems-*.zip' \
  --output-dir build/calibration-results \
  --manifest-only \
  --strict
```

### Tests

Create synthetic ZIP fixtures at test runtime covering:

- valid simple bundle;
- nested valid bundle through depth 8;
- depth 9 rejection;
- `../` and absolute path rejection;
- Windows drive/UNC path rejection;
- symlink entry rejection;
- encrypted-entry rejection;
- CRC/malformed archive failure;
- excessive member count;
- excessive uncompressed size and ratio;
- duplicate name with identical content and with conflicting content;
- secret-key name/value detection without value disclosure;
- stable manifest ordering and digest;
- old analyzer CLI remains compatible.

### Verification

```bash
python3 -m pytest -q scripts/tests/test_safe_zip.py \
  scripts/tests/test_archive_manifest.py \
  scripts/tests/test_privacy_scan.py \
  scripts/tests/test_calibrate_lateral_stability.py
python3 -m pytest -q scripts/tests
python3 -m py_compile scripts/*.py scripts/wayheatmap_analysis/*.py
git diff --check
```

### Completion criteria

- the full local archive set can be inventoried without extraction into the repo;
- unsafe archives are typed and skipped/quarantined;
- reports expose no secret value or absolute private path;
- existing analyzer tests and CLIs remain green;
- no Java/plugin behavior changes.

---

## CP2 — Lateral-movement attribution metrics and frozen corpus split

### Purpose

Determine where the lateral movement enters the pipeline and prevent tuning to a misleading aggregate metric.

### Work

1. Extend analysis to align, where available, the following per profile:
   - chainage metres;
   - source-pixel pitch;
   - raw/B3/B5 centers;
   - band center, core, shoulder, localization confidence and uncertainty;
   - local/stability/effective tube centers and uncertainty;
   - motion support and reason;
   - scale conflict and parent merge;
   - selected optimized offset;
   - per-profile data, continuity, relative-curvature, endpoint, and ripple costs;
   - raw and cleaned final-preview geometry;
   - direct/interpolated/predicted provenance;
   - cleanup outcome/rejection reason.
2. Implement the robust trend and residual metrics in analysis code first. Do not alter production code yet.
3. Produce per-profile and per-candidate metrics.
4. Implement deterministic suggested attribution using the taxonomy in Section 8.
5. Preserve human override capability.
6. Group repeated slides and palette/variant siblings.
7. Build and freeze `split-lock.json` before parameter tuning.
8. Generate a baseline report answering:
   - In how many target cases does the profile center already wiggle?
   - In how many does the stability tube wiggle?
   - In how many does the selected candidate add movement relative to the tube?
   - How often is cleanup disabled, skipped, rejected, or accepted?
   - How much does accepted cleanup reduce high-frequency residual and point count?
   - Are target cases concentrated in a palette, parent/sparse mode, weak signal, plateau, endpoint, or source scale?
   - Which bundles are replayable?
9. Add the existing negative tags/rating information to summaries without treating ratings as perfect ground truth.
10. Record a small set of representative target and preservation-control case hashes in the execution plan; do not record coordinates.

### Required report fields

At candidate level:

```text
outer_archive_sha256
nested_bundle_path_hash
debug_format
plugin_version
candidate_id
raw_parent_id
selected
applied
applicable
rating
negative_tags
primary_attribution
secondary_flags
replayability
source_pixel_authoritative
profile_count
length_meters
raw_profile_hf_rms_sp
local_tube_hf_rms_sp
stability_tube_hf_rms_sp
optimized_hf_rms_sp
cleaned_hf_rms_sp
optimized_to_cleaned_reduction
center_bias_sp
reversal_count
median_reversal_spacing_m
point_count_raw
point_count_cleaned
repeat_slide_p95_m
cleanup_outcome
warnings
```

At profile level add trend, residual, support, and cost decomposition.

### Evidence gate before production changes

Do not proceed to CP4 until at least one deterministic target case—synthetic or replayable private—shows a measurable failure consistent with one of the proposed causes. Screenshots alone are not sufficient.

### Verification

```bash
python3 -m pytest -q scripts/tests
python3 scripts/calibrate-lateral-stability.py \
  --archive-root "$CALIBRATION_ARCHIVE_ROOT" \
  --include 'last-slide-debug*.zip' \
  --include 'problems-*.zip' \
  --output-dir build/calibration-results \
  --analyze --strict

git diff --check
```

### Completion criteria

- every usable candidate has an attribution or explicit `INSUFFICIENT_EVIDENCE`;
- train/validation/holdout grouping is frozen;
- target and preservation-control sets exist;
- primary failure mechanisms are quantified;
- no production behavior changes;
- private reports remain untracked.

---

## CP3 — Deterministic synthetic regression and preservation fixtures

### Purpose

Create cheap, exact, reviewable tests that reproduce the failure and protect true geometry before changing the objective.

### Fixture principles

- no random data unless a fixed seed is recorded and the test remains deterministic;
- state all physical and raster units in fixture names and assertion messages;
- exercise multiple source-pixel pitches and profile spacings;
- keep synthetic signal simple enough that the intended center is mathematically known;
- test both optimizer offsets and final preview geometry;
- keep legacy fixtures entirely separate.

### Required target fixtures

At minimum:

1. straight broad corridor with alternating `0.20`, `0.40`, and `0.70` source-pixel center jitter;
2. wavelengths/reversal spacings around 2, 4, 6, 10, and 20 m;
3. source-pixel phase alternation where raw center shifts but B3/B5 consensus is stable;
4. broad flat plateau whose per-state `equivalentPeak` boundary changes across profiles;
5. asymmetric shoulder that moves the band center while the core midpoint is stable;
6. wiggling local/stability tube with low sustained-motion support;
7. selected optimizer following a wiggling tube at low relative-curvature cost;
8. dense straight precise geometry whose residual is small but whose point count exposes visual wiggle;
9. cleanup-enabled accepted path with insufficient suppression;
10. cleanup rejected because evidence is incomplete, proving fail-closed behavior.

### Required preservation controls

1. sustained low-amplitude sine;
2. broad S-curve;
3. single supported apex;
4. sharp but directly supported bend;
5. switchback;
6. weak but coherent sustained turn;
7. broad road with stable flat center;
8. two persistent parallel corridors;
9. sparse parent with alternating direct child support;
10. short direct gap bounded by reliable evidence;
11. endpoint approach with movement disabled;
12. movable endpoint/junction with existing constraints;
13. fixed/shared/tagged interior anchors;
14. candidate near connected-way crossing;
15. self-intersection/foldback temptation;
16. off-raster/no-signal and search-boundary-censored profiles.

### Scale matrix

Cover at least:

- source-pixel pitch: `0.5`, `1.0`, and `2.0` sampled-raster pixels per source pixel, or the repository’s equivalent fixture contract;
- profile spacing: approximately `1`, `2`, and `4` source pixels in ground-distance-equivalent controls;
- representative ground scales around managed z15 and at least one coarser/finer control;
- variable profile spacing, not only uniform spacing.

### Baseline assertions

Before production change, record which target test is red and why. Preservation controls must already be green or their intended baseline must be frozen. Add exact checks that a zero-strength configuration preserves:

- offset list;
- total and decomposed costs;
- maximum offset/pair-state counts;
- transition and profile-cost counts;
- candidate identity and tie result;
- final preview geometry.

### Focused tests

Use and extend existing classes rather than creating a parallel framework, likely including:

```text
UnsupportedRippleEvaluatorTest
LongitudinalCorridorTubeTest
CorridorCenterlineOptimizerTest
GeometryRippleRegularizationTest
CorridorRasterIntegrationTest
CorridorScaleInvarianceTest
GeometryCleanupCalibrationTest
HeatmapConstrainedLaplacianSmootherTest
HeatmapConstrainedSimplifierTest
GeometryCleanupServiceTest
GeometryPostProcessorTest
```

### Verification

```bash
sh gradlew test \
  --tests '*UnsupportedRippleEvaluatorTest' \
  --tests '*LongitudinalCorridorTubeTest' \
  --tests '*CorridorCenterlineOptimizerTest' \
  --tests '*GeometryRippleRegularizationTest' \
  --tests '*CorridorRasterIntegrationTest' \
  --tests '*CorridorScaleInvarianceTest' \
  --tests '*GeometryCleanupCalibrationTest'
git diff --check
```

### Completion criteria

- at least one focused red test reproduces each implemented root cause;
- preservation controls are green and measured;
- exact disabled-path vectors are frozen;
- no production code is changed except minimal test support with no runtime behavior.

---

## CP4 — Robust trend-residual unsupported-ripple evaluator

### Purpose

Replace reversal counting on the possibly wiggling local center with a better physical-distance attribution that distinguishes sustained movement from short residual oscillation.

### Production design

Prefer evolving `UnsupportedRippleEvaluator` and its immutable `RippleSupport` result rather than adding a second overlapping evaluator. Refactor only when it improves clarity and testability.

### Algorithm

For each eligible direct profile:

1. obtain a contiguous direct-evidence window limited by cumulative ground distance and the configured ripple scale;
2. require sufficient direct profiles and physical span;
3. fit a deterministic confidence/uncertainty-weighted robust affine trend to the selected reference center;
4. compute source-pixel-normalized residuals;
5. apply a source-pixel residual/deviation deadband;
6. count meaningful residual-slope reversals and physical reversal spacing;
7. compute robust amplitude;
8. calculate short-scale exposure;
9. reduce or eliminate intervention where existing direct evidence supports coherent direction, a single apex, a sustained turn, or a switchback;
10. calculate a separate trend-authorization score;
11. return a typed reason and complete diagnostics.

### Suggested `RippleSupport` fields

Adapt naming to Java conventions, but include equivalent information:

```java
int profileIndex;
double motionSupport;
double supportedTurnWeight;
double shortScaleExposure;
double residualAmplitudeSourcePixels;
double maximumResidualSourcePixels;
double directCoverage;
double trendCenterOffsetPx;
double trendSlopePxPerMeter;
double trendUncertaintyPx;
double trendAuthorization;
double unsupportedWeight;
double reversalSpacingMeters;
int reversalCount;
String reason;
```

Keep it immutable and validate finite values. Use `NaN` only where existing repository contracts explicitly use it; otherwise prefer optional/availability fields.

### Initial parameter seeds

These are experimental grids, not defaults:

| Parameter | Values to test |
| --- | --- |
| residual deadband | `0.10`, `0.12`, `0.15` source px |
| amplitude onset/full | `0.10/0.30`, `0.15/0.40`, `0.20/0.50` source px |
| trend window factor relative to configured ripple scale | `0.75`, `1.00`, `1.25` |
| minimum direct profiles | `4`, `5`, `6` |
| minimum direct physical span | `0.5R`, `0.65R`, `0.8R` |
| Huber threshold | repository current robust default, then ±25% |

Do not run the full Cartesian product. First tune classification with optimizer strength zero.

### Important gates

- non-direct target profile: no intervention;
- insufficient window: no intervention;
- ill-conditioned trend: no intervention;
- supported coherent motion/apex/switchback: unsupported weight approaches zero;
- parent/scale conflict: no positional pull without trend authorization;
- boundary-censored corridor: no trend pull;
- endpoint/junction ownership: attenuate or zero intervention as defined by existing endpoint context;
- all outputs bounded and finite.

### Baseline compatibility

When cleanup is disabled or ripple strength is zero:

- evaluator may return disabled diagnostics;
- optimizer must not use any new score;
- exact output and cost decomposition must remain unchanged for the patch-safe path.

### Tests

Add focused cases for:

- amplitude distinguishes tiny numerical zigzag from visible ripple;
- robust affine trend removes sustained slope from residual;
- a broad sustained curve is not mislabeled as ripple;
- a supported apex is preserved;
- irregular profile spacing produces similar physical result;
- source-pixel scale normalization is invariant;
- direct-window gaps split the window;
- predicted/interpolated profiles cannot authorize intervention;
- scale conflict/trend uncertainty lowers authorization;
- deterministic output and reason strings.

### Verification

```bash
sh gradlew test \
  --tests '*UnsupportedRippleEvaluatorTest' \
  --tests '*LongitudinalCorridorTubeTest' \
  --tests '*CorridorScaleInvarianceTest' \
  --tests '*GeometryRippleRegularizationTest'
python3 -m pytest -q scripts/tests
git diff --check
```

### Completion criteria

- target ripple classification improves on training/validation without using holdout;
- supported-turn false positives remain within hard limits;
- zero-strength exact baseline is unchanged;
- complexity remains bounded and approximately linear in profiles times bounded window size;
- every no-intervention path has a typed reason.

---

## CP5 — Gated absolute short-wave turn cost in the exact DP

### Purpose

Prevent the optimizer from cheaply following a wiggling tube when the tube-relative curvature term alone cannot distinguish real curve from short unsupported movement.

### Design constraints

- integrate into the existing exact second-order transition;
- no new optimizer pass;
- no third-order state;
- no beam pruning;
- no change to allowed offsets;
- no change to tie ordering;
- exact zero when disabled;
- physical/source-scale normalization;
- robust loss to avoid catastrophic over-penalization of a misclassified sharp supported turn.

### Candidate turn-rate term

For transition into profile `i`, let:

- `h_prev` be the previous candidate segment heading stored in the pair state;
- `h_cur` be the candidate heading from profile `i-1` to `i`;
- `deltaTheta = wrapToPi(h_cur - h_prev)`;
- `ds_prev` and `ds_cur` be adjacent spacing in source-pixel units;
- `ds = max(epsilon, 0.5 * (ds_prev + ds_cur))`;
- `kappa = abs(deltaTheta) / ds`, in radians per source pixel.

Apply a deadband and robust normalization:

\[
z = \frac{\max(0,\kappa-\kappa_{dead})}{\kappa_{scale}}.
\]

Use Huber loss:

\[
\rho(z)=
\begin{cases}
\tfrac12z^2, & z\le\delta\\
\delta(z-\tfrac12\delta), & z>\delta.
\end{cases}
\]

A length-normalized seed cost is:

\[
E_{abs,i}=\lambda_{abs}\,g_i\,\rho(z)\,ds,
\]

where:

\[
g_i=rippleStrength\cdot unsupportedWeight_i\cdot endpointGate_i.
\]

If tests show the `ds` factor worsens invariance relative to current objective scaling, compare an explicitly documented alternative. The final choice must be justified by scale-invariance tests, not intuition.

### Endpoint and support gate

`endpointGate_i` must avoid fighting an authoritative endpoint/junction approach. It may be derived from existing endpoint guide/constraint context, not a new arbitrary distance in profile counts. A protected fixed anchor remains fixed regardless.

The term is zero or strongly attenuated when:

- unsupported weight is zero;
- cleanup is disabled;
- ripple strength is zero;
- the profile is non-direct or the trend is unauthoritative;
- a supported turn/apex/switchback is established;
- endpoint/junction guidance owns the local geometry;
- required scale is unavailable.

### Interaction with existing costs

Keep these components separate in diagnostics:

1. data/intensity/core/center costs;
2. stability/tube positional cost;
3. continuity cost;
4. existing tube-relative curvature cost;
5. **new absolute short-wave turn cost**;
6. endpoint/junction constraint cost.

Do not hide the new cost inside the existing acceleration value. Existing summary totals may aggregate it, but the raw component must remain available.

Do not immediately remove the current `1 + 8 * strength * unsupportedWeight` acceleration multiplier. Tune in this order:

1. add the new term with weight zero and prove exact baseline;
2. tune `lambda_abs` on target/preservation fixtures;
3. inspect whether the existing multiplier causes double-regularization;
4. only then compare multiplier caps/coefficients such as `4`, `6`, and current `8`;
5. retain the simpler combination on the Pareto frontier.

### Initial tuning grid

| Parameter | Values |
| --- | --- |
| `lambda_abs` | `0`, `0.05`, `0.10`, `0.20`, `0.35` |
| `kappa_dead` | `0.01`, `0.02`, `0.03` rad/source-px |
| `kappa_scale` | `0.03`, `0.05`, `0.08` rad/source-px |
| Huber knee `delta` | `1.0`, `1.5` |
| existing acceleration multiplier coefficient | current, then `4`, `6`, `8` only after the new term is useful |

Use staged/fractional experiments, not a full Cartesian sweep.

### Implementation details

Likely changes:

- add a pure helper for wrapped heading/turn-rate cost, package-private and testable;
- extend `PairState` only if current spacing/heading data are insufficient; do not store unnecessary history;
- precompute profile-level gates outside innermost loops;
- avoid object allocation in the transition hot path;
- add finite-cost checks;
- preserve comparator/tie behavior;
- update `CostRow` and optimizer result diagnostics additively.

### Tests

Required assertions:

- zero strength yields exact old offsets, costs, state/transition counts, and ties;
- target ripple RMS decreases monotonically over a reasonable strength range;
- straight candidate center bias remains within limit;
- supported sine/apex/switchback amplitude remains above hard floor;
- persistent parallel corridor identity is unchanged;
- variable profile spacing/source pitch yields equivalent physical behavior;
- endpoint/junction controls are unchanged or improved;
- no extra optimizer call;
- transition and profile-cost counts remain unchanged for the same state tables;
- no non-finite cost;
- deterministic output.

### Verification

```bash
sh gradlew test \
  --tests '*CorridorCenterlineOptimizerTest' \
  --tests '*GeometryRippleRegularizationTest' \
  --tests '*CorridorRasterIntegrationTest' \
  --tests '*CorridorScaleInvarianceTest' \
  --tests '*CorridorEndpointApproachTest' \
  --tests '*CorridorQualityMetricsTest' \
  --tests '*GeometryCleanupCalibrationTest' \
  --tests '*GeometryCleanupPerformanceTest'
git diff --check
```

### Completion criteria

- measurable reduction on target fixtures;
- no hard-gate regression on preservation fixtures;
- exact cleanup-disabled baseline for patch path;
- one exact DP and unchanged state topology;
- separate diagnostics available;
- performance within provisional budget.

---

## CP6 — Conditional plateau-target stabilization

### Purpose

Address binary plateau/core-center target switching only when CP2 proves that it materially causes residual lateral movement. This checkpoint may be completed as `N/A — evidence gate not met`.

### Evidence gate

Implement only when both are true:

1. at least two independent replayable/private or deterministic synthetic target cases are attributed primarily to plateau target switching; and
2. CP4/CP5 cannot solve them without over-regularizing supported geometry.

A useful quantitative threshold is that plateau-related cases represent at least roughly 20% of independently grouped target cases or include a severe, reproducible case that the primary fix cannot address safely.

### Proposed continuous blend

The current objective should be inspected for a hard `equivalentPeak` branch. Replace it only in cleanup-enabled unsupported windows for the patch-safe path.

Let:

- `d = bandMaximum - stateIntensity`;
- `d0` be the existing plateau deadband;
- `d1` be a slightly larger upper transition bound;
- `alpha = 1 - smoothstep(d0, d1, d)`.

Then blend:

\[
centerTarget = \alpha\,supportedCoreCenter + (1-\alpha)\,bandCenter.
\]

Scale any plateau stability deadband continuously by `alpha` rather than toggling it.

Important:

- `alpha` must be deterministic and bounded;
- the transition must not flatten meaningful intensity differences across the whole shoulder;
- parent-corridor special handling remains intact;
- raw/B3/B5 and coarse-prior semantics remain intact;
- in a patch release, the blend is effective only where cleanup/ripple intervention is enabled and unsupported; cleanup-disabled output remains exact;
- if the best solution requires changing the raw objective globally, reclassify to `0.20.0`.

### Tests

- objective is continuous at both plateau boundaries;
- no profile-to-profile target mode jump for a slowly changing flat top;
- narrow peaked corridor remains centered on its peak;
- asymmetric shoulder does not pull away from high-intensity core;
- sparse parent semantics unchanged;
- supported curve retention and source-scale invariance;
- exact disabled baseline.

### Completion criteria

Either:

- implemented with focused evidence, tests, and no-regression gates; or
- marked `N/A` with baseline report showing that plateau switching was not a material cause.

Do not leave this checkpoint ambiguously pending.

---

## CP7 — Constrained smoothing and simplification calibration

### Purpose

Ensure the cleaned sibling removes residual visual movement and unnecessary vertices while retaining the heatmap-supported shape and all anchors.

### Principles

- use the existing constrained smoother and simplifier;
- do not add generic output smoothing;
- do not move points longitudinally;
- do not redistribute points uniformly;
- process protected-anchor intervals independently;
- do not let predicted/no-signal evidence authorize movement;
- preserve raw/B3/B5 fit and selected corridor identity;
- final topology checks remain authoritative;
- raw candidate remains available.

### Diagnose before tuning

For each cleanup attempt record:

- whether smoothing was eligible;
- accepted/rejected passes and backtracks;
- displacement p50/p95/max;
- fit before/after for raw/B3/B5;
- corridor containment margin;
- supported-turn retention;
- simplification chord attempts/acceptances;
- point count before/after;
- maximum removed-point deviation;
- rejection reason;
- final safety findings.

Separate these cases:

1. smoothing never attempted;
2. smoothing attempted but every pass rejected;
3. smoothing accepted but ripple remains;
4. smoothing succeeds but simplifier leaves excess points;
5. simplifier reduces points but introduces unacceptable chord geometry and is correctly rejected;
6. final topology rejects an otherwise plausible cleaned path.

### Parameter seeds

Do not change shipped presets before holdout. Use custom/internal experiment configs around:

| Parameter | Conservative tuning range |
| --- | --- |
| ripple scale | `8–12 m` for common visible short-wave cases, while retaining 6/10/20 m preset controls |
| ripple strength | `0.55–0.75` |
| Laplacian strength | `0.18–0.28` |
| pass count | `2–3` |
| simplification deviation | `0.5–1.0 m` |
| minimum fit retention | `0.94–0.97` |

These values are starting ranges, not mandated defaults. The existing Conservative/Balanced/Strong values must remain untouched until evidence and version classification permit a change.

### Tuning order

1. hold simplification off or effectively zero; tune DP ripple intervention;
2. tune Laplacian strength and pass count with fixed fit floor;
3. tune simplification deviation on already acceptable smoothed paths;
4. raise/lower fit retention only with explicit fit-loss analysis;
5. assess combined behavior and repeated-slide stability;
6. choose the simplest Pareto-optimal configuration.

### Preset decision

Preferred patch path:

- improve internals under existing explicit cleanup configuration;
- do not add a preset or change defaults.

Conditional minor path:

- add a new user-facing preset only when a stable parameter cluster materially outperforms all existing presets and custom settings are too obscure for normal use;
- update preference/UI/migration/docs tests;
- classify as `0.20.0`.

Do not silently redefine `Balanced` merely to make the new tests pass.

### Tests

- point count never increases;
- straight/ripple fixtures reduce points substantially;
- supported bends retain required geometry;
- no protected anchor moves or disappears;
- no corridor valley crossing;
- no topology regression;
- cleanup rejection leaves raw candidate usable;
- repeated cleaned slides converge;
- no hidden second optimizer;
- runtime/memory remain bounded.

### Verification

```bash
sh gradlew test \
  --tests '*HeatmapConstrainedLaplacianSmootherTest' \
  --tests '*HeatmapConstrainedSimplifierTest' \
  --tests '*GeometryCleanupServiceTest' \
  --tests '*GeometryPostProcessorTest' \
  --tests '*GeometryCleanupCalibrationTest' \
  --tests '*GeometryCleanupPerformanceTest' \
  --tests '*CorridorRasterIntegrationTest'
git diff --check
```

### Completion criteria

- target cleanup improves on validation data;
- fit, shape, anchor, topology and branch identity gates hold;
- parameter selection is reproducible;
- no shipped preset/default changes occur without minor-version reclassification.

---

## CP8 — Replayable private-corpus calibration and holdout

### Purpose

Tune against the actual local problem corpus without overstating what old exported bundles can reproduce.

### Replay implementation decision

Inspect bundle contents before writing replay code.

If `R1` or higher data are sufficient, add a test-only/offline replay path using current model constructors and exact transforms. Likely options:

- a `DebugBundleReplaySupport` test helper;
- a dedicated non-default Gradle calibration task controlled by `-PcalibrationArchiveRoot=...`;
- a small Java CLI under test tooling;
- Python orchestration that invokes the Java replay task for each manifest case.

Requirements:

- ordinary CI does not require private files;
- no archive is copied into resources;
- missing root causes an explicit skip/no-op in the dedicated calibration task, not a normal test failure;
- replay checksums input artifacts before use;
- exact settings, units, profile alignment and transforms are required;
- no network/tile fetch or credential use;
- output goes only to ignored `build/calibration-results/`.

If bundles are `R0` only:

- do not build a fake replay path;
- use them for baseline attribution;
- identify a bounded manual recapture set;
- run the new jar in JOSM on the same cases and export fresh bundles;
- pair old/new cases through local labels and geometry hashes.

### Parameter search protocol

Do not brute-force a full Cartesian product.

#### Stage A — evaluator classification

Vary only trend/deadband/amplitude parameters with optimizer intervention disabled. Optimize classification of target vs supported-control windows.

#### Stage B — exact-DP turn cost

Fix evaluator parameters. Sweep `lambda_abs`, turn deadband/scale, and only then existing acceleration multiplier interaction.

#### Stage C — constrained smoothing

Fix DP parameters. Sweep Laplacian strength/pass count with fit floor fixed.

#### Stage D — point reduction

Fix all prior parameters. Sweep simplification deviation and verify heatmap/topology constraints.

#### Stage E — combined confirmation

Evaluate a small Pareto set on validation. Select one configuration before opening holdout.

### Objective hierarchy

Hard constraints precede optimization. Reject any run that violates:

1. topology/anchor/apply safety;
2. branch/candidate identity on controls;
3. supported-turn hard floor;
4. fit floor;
5. center-bias hard limit;
6. cleanup-disabled baseline;
7. performance/memory bounds.

Among feasible runs:

1. minimize catastrophic/worse target cases;
2. maximize fraction of target cases improved;
3. minimize p95 high-frequency residual;
4. minimize center bias;
5. improve repeated-slide convergence;
6. reduce point count where safe;
7. prefer fewer/newer parameters and lower runtime.

### Suggested acceptance gates

These are stronger than the existing broad cleanup gate where practical, but Codex must report exact corpus coverage.

#### Isolation

- `LEGACY_V02`: zero change.
- Patch path cleanup-disabled corridor-aware: exact zero change.

#### Target unnecessary-undulation cases

- median optimized-to-cleaned or old-to-new high-frequency RMS reduction: at least `35%`;
- at least `80%` of independently grouped target cases improve by at least `20%`;
- no target case worsens by more than `10%` without a documented human-supported reason;
- no new branch switch or valley crossing;
- p95 residual target: approximately `<=0.40` source px for strong/medium direct corridors and `<=0.55` source px for weak/sparse cases, reported rather than forced when evidence is insufficient.

#### Center bias

- median absolute center bias: `<=0.10–0.15` source px;
- p95: `<=0.25–0.35` source px;
- hard maximum: `<=0.50` source px unless the case is explicitly excluded as ambiguous.

#### Supported shape

- median amplitude retention: `>=95%`;
- no supported sine/apex/switchback fixture below the existing hard `90%` floor;
- no true sharp bend converted into a shortcut or endpoint kink.

#### Heatmap fit

- meet configured fit floor for raw/B3/B5 bands;
- precision-oriented calibration target: `>=0.94` where the source evidence is authoritative;
- no lowering of fit floor to gain smoother appearance.

#### Good controls

- median bidirectional geometry drift: `<=0.10` source px;
- p95 drift: `<=0.25` source px;
- candidate/branch identity unchanged unless a human label explicitly judges the new result better;
- no warning suppression that makes an unsafe candidate applicable.

#### Repeated slides

- no systematic point growth;
- point-count growth `<=5%` after the first accepted cleaned result, preferably zero;
- p95 repeated-slide drift `<=0.25` source px and median `<=0.10` source px;
- candidate identity and warnings stable.

#### Safety

- zero new applicable unsafe candidates;
- exact protected-anchor coordinates;
- zero new self-intersection, foldback, remote touch, overlap, or connected-way crossing;
- preview/apply/redo geometry exact; undo exact.

### Holdout discipline

- freeze configuration before holdout;
- run holdout once for the final candidate configuration;
- if holdout fails, document failure, return to design/training, and create a new versioned split only for a genuinely new corpus—not by moving cases;
- do not tune on holdout while continuing to call it holdout.

### Completion criteria

- replayability is honestly classified;
- train/validation/holdout reports exist;
- selected configuration lies on the feasible Pareto frontier;
- private data remain untracked/redacted;
- all hard gates pass or the checkpoint is blocked with explicit evidence.

---

## CP9 — Preview, apply, undo/redo, ranking, anchors, and topology integration

### Purpose

Prove that improved geometry is not merely numerically smoother but safe and identical throughout the JOSM workflow.

### Work

1. Preserve raw candidate stable ID and geometry.
2. Preserve explicit raw/cleaned parent relation.
3. Generate no more than one cleaned sibling.
4. Recompute all quality metrics that depend on final geometry.
5. Build a fresh immutable proposed-node assignment map from the final cleaned preview.
6. Rerun every final-preview topology and protected-anchor check.
7. Confirm candidate selection in modeless preview uses stored slide-time geometry.
8. Confirm stale source geometry blocks switch/apply as before.
9. Confirm `ReplaceWaySegmentCommand` receives exactly the previewed points/assignments.
10. Test execute, undo and redo against a deep snapshot of all affected and nearby primitives.
11. Confirm dropped-node cleanup remains untagged-and-unreferenced only.
12. Confirm cleaned rejection never hides raw or lowers a warning.
13. Confirm ranking has no unconditional cleanup bonus and no detector-tier/ranking change unless explicitly reclassified to `0.20.0`.
14. Confirm `Move Existing Nodes` and legacy modes are unaffected unless current documented cleanup scope already includes them.
15. Confirm movable endpoint/junction targets remain frozen during cleanup and are not independently reselected.

### Required integration tests

Likely classes:

```text
AlignmentServiceTest
AlignWayActionTest
PreviewDialogTest
ReplaceWaySegmentCommandTest
GeometryCleanupServiceTest
GeometryPostProcessorTest
CorridorEndpointApproachTest
LastSlideDebugBundleTest
```

Test:

- raw selected, cleaned selected, switch back to raw;
- cleanup skip/reject/success;
- stale way while preview open;
- fixed endpoint;
- movable endpoint;
- shared junction;
- tagged/shared interior node;
- segment-only replacement;
- repeated node rejection;
- nearby connected-way crossing;
- self-intersection/foldback/vertex touch/overlap;
- local/no-download bypass unchanged;
- execute/undo/redo exactness;
- no mutation on Cancel.

### Verification

```bash
sh gradlew test \
  --tests '*AlignmentServiceTest' \
  --tests '*AlignWayActionTest' \
  --tests '*PreviewDialogTest' \
  --tests '*ReplaceWaySegmentCommandTest' \
  --tests '*GeometryCleanupServiceTest' \
  --tests '*GeometryPostProcessorTest' \
  --tests '*CorridorEndpointApproachTest' \
  --tests '*LastSlideDebugBundleTest'
git diff --check
```

### Completion criteria

- preview/apply/undo/redo geometry and assignments agree exactly;
- all protected/topology checks remain fail-closed;
- raw candidate remains available;
- no ranking/default behavior outside approved scope changes;
- no unrelated primitive mutation.

---

## CP10 — Debug schema, analyzers, documentation, and privacy compatibility

### Purpose

Make the new behavior explainable and reproducible while preserving old bundle compatibility and credential redaction.

### Schema decision

Use this explicit rule:

- If only optional columns are appended to an existing artifact and existing format-9 semantics and checksum rules permit it unambiguously, format 9 may remain.
- If a new required artifact is added, an existing column meaning changes, or replay/attribution semantics become part of the bundle contract, bump to **format 10**.
- Never change a format-9 field’s meaning while leaving the same name/version.
- Readers must continue to parse formats 1–9 and report new fields as unavailable.

A clean design is likely a format-10 additive artifact such as `lateral-stability.csv` plus a checksum entry, while retaining all format-9 cleanup files unchanged.

### Required diagnostics

Per profile or equivalent:

- chainage metres and source-pixel pitch;
- profile/band/raw/B3/B5 centers;
- local/stability/effective tube centers;
- robust trend center/slope/uncertainty;
- residual amplitude and reversal metrics;
- direct coverage;
- sustained-motion/turn/apex support;
- unsupported-ripple weight;
- trend authorization;
- relative-curvature cost;
- absolute short-wave turn cost;
- ripple positional cost;
- effective continuity/acceleration weights;
- endpoint gate;
- reason/skip code.

Candidate-level:

- raw/cleaned relation;
- attribution summary;
- before/after high-frequency metrics;
- point counts;
- fit/shape retention;
- cleanup outcome;
- final safety result;
- exact optimizer counts;
- configuration values and units.

### Analyzer behavior

- old missing fields are unavailable, never zero;
- validate checksums and CSV escaping;
- preserve nested archive support through the safe reader;
- report source-scale trust;
- separate raw/cleaned and selected/applied geometry;
- report actual top-ranked candidate, not only a retrospective oracle-best candidate;
- map existing negative tags into attribution without overwriting human ratings;
- keep output deterministic.

### Documentation

Update:

- `README.md`: explain residual ripple diagnostics, raw-vs-cleaned comparison, recommended use, and limitations;
- `DEVELOPMENT.md`: equations, units, gates, calibration protocol, performance, replayability, debug format and failure semantics;
- `PLANS.md`: mark completed work and retain deferred items;
- `AGENTS.md`: add only durable guardrails, for example the distinction between tube-relative and gated absolute short-wave cost, if it is likely to remain important;
- public/reusable Java/Python APIs: Javadoc/docstrings with units and failure behavior;
- execution plan ledger and implementation log.

Do not put private archive names beyond generic glob patterns, case coordinates, screenshots, or local paths into tracked docs.

### Privacy tests

- export a synthetic debug bundle and scan all members/text;
- ensure no cookie/header/token/signed URL;
- ensure no full local archive path;
- ensure diagnostic reason text cannot accidentally include raw URL/config values;
- verify checksum manifests.

### Verification

```bash
sh gradlew test --tests '*LastSlideDebugBundleTest'
python3 -m pytest -q scripts/tests
python3 -m py_compile scripts/*.py scripts/wayheatmap_analysis/*.py
sh gradlew javadoc
git diff --check
```

### Completion criteria

- schema decision documented;
- old bundles parse;
- new diagnostics fully explain intervention;
- privacy scan passes;
- docs match implementation;
- no private data tracked.

---

## CP11 — Performance, determinism, full verification, and independent review

### Purpose

Prove that the correction is safe enough to integrate and that no partial test success hides a regression.

### Performance requirements

Use existing 128/256/512-profile fixtures and representative maximum lateral state counts.

Record separately:

- tube construction;
- ripple evaluation/trend fitting;
- state table/profile cost construction;
- exact DP transition loop;
- constrained Laplacian;
- simplification;
- diagnostics serialization;
- peak retained memory/evidence estimate;
- transition/profile-cost/state counts.

Budgets relative to current baseline on the same machine/JDK:

- exact optimizer transition count: unchanged for same state tables;
- profile-cost count: unchanged for same state tables;
- optimizer-stage median slowdown: `<=5%` preferred, `<=10%` hard review threshold;
- optimizer-stage p95 slowdown: `<=10%` preferred;
- cleanup-stage median slowdown: `<=10%` relative to current cleanup;
- cleanup-stage p95 slowdown: `<=20%`;
- bounded approximately linear growth from 128 to 256 to 512 profiles with existing scheduler/GC slack;
- no duplicate full raster or unbounded per-candidate arrays;
- no second optimizer invocation.

If performance is noisy, use warmups and multiple iterations, report medians and raw samples, and avoid claiming precision beyond the fixture supports.

### Determinism requirements

Run identical cases repeatedly in one JDK/process and fresh processes. Require:

- same candidate IDs and ordering;
- same selected offsets or exact documented floating tolerance where serialization changes;
- same diagnostics checksum;
- same cleaned point indexes/count;
- same warnings/applicability;
- same command geometry.

Avoid parallel reductions or hash-order-dependent collections in scoring/tie paths.

### Full verification commands

Adapt test filters to actual classes, but the final gate must include:

```bash
set -euo pipefail
sh gradlew clean test build javadoc
python3 -m pytest -q scripts/tests
python3 -m py_compile scripts/*.py scripts/wayheatmap_analysis/*.py
python3 scripts/validate-sampling-scale.py --pretty
git diff --check
```

Run the private calibration command with the frozen configuration and holdout:

```bash
python3 scripts/calibrate-lateral-stability.py \
  --archive-root "$CALIBRATION_ARCHIVE_ROOT" \
  --include 'last-slide-debug*.zip' \
  --include 'problems-*.zip' \
  --output-dir build/calibration-results \
  --evaluate-frozen-config \
  --split-lock build/calibration-input/split-lock.json \
  --strict
```

If Java replay is implemented, run its dedicated non-default task with the absolute archive root.

### Independent review

Invoke `$requesting-code-review`. Ask the reviewer to focus on:

1. cleanup-disabled and legacy isolation;
2. physical/source-pixel unit correctness;
3. exact-DP topology, state bound, tie ordering and one-run guarantee;
4. whether the absolute cost can erase supported turns;
5. trend authorization and sparse/predicted evidence;
6. endpoint/junction/anchor behavior;
7. raw/cleaned candidate and ranking semantics;
8. preview/apply/undo/redo identity;
9. topology fail-closed behavior;
10. archive safety and credential redaction;
11. backward debug compatibility;
12. performance, determinism and test adequacy;
13. whether version classification is honest.

Block on every substantiated Critical or Important finding. Add a focused regression before fixing behavior.

### Completion criteria

- full Java/Python/build/Javadoc gate green;
- holdout passes hard gates;
- performance and determinism within bounds;
- no unresolved review blocker;
- implementation log reconciles commands, metrics, changed files, and known limitations;
- no release action taken.

---

## CP12 — Manual JOSM beta, version decision, and release-ready handoff

### Purpose

Validate the actual mapper workflow and decide whether the result is a patch-safe correction or a minor behavioral release.

### Isolated manual environment

Use a separate JOSM test profile/home where possible. Do not overwrite the normal plugin without a backup. Keep credentials out of logs and screenshots.

Build:

```bash
sh gradlew clean build
sha256sum build/libs/wayheatmaptracer.jar
unzip -p build/libs/wayheatmaptracer.jar META-INF/MANIFEST.MF | sed -n '/Plugin-Version/p'
```

Do not change the version before the decision gate.

### Manual matrix

Use at least:

- several representative `last-slide-debug*` recaptures;
- several `problems-*` cases;
- at least five known-good controls;
- narrow and broad corridors;
- weak/sparse and strong/direct corridors;
- a supported curve, S-curve, apex, and switchback;
- parallel traces;
- endpoint and junction cases;
- fixed/shared/tagged nodes;
- repeated slide after applying the cleaned candidate;
- at least the principal supported palettes represented in the local corpus.

For each case compare:

1. corridor-aware + precise + cleanup none;
2. corridor-aware + precise + current existing preset;
3. corridor-aware + precise + selected frozen custom/tuned configuration;
4. raw vs cleaned preview;
5. Apply, Undo, Redo;
6. second slide on the applied geometry;
7. exported diagnostics and privacy scan.

Do not apply questionable geometry to live OSM merely for testing. Use a local/test data layer or undo without upload.

### Version decision

Choose `0.19.3` only if the patch-safe conditions in Section 2.1 all hold. Otherwise choose `0.20.0` and add every required migration/UI/default test before release readiness.

### Release-ready handoff contents

- current commit and diff range;
- version recommendation and rationale;
- changed files by checkpoint;
- exact tests and results;
- training/validation/holdout metrics;
- manual case counts and results;
- performance/determinism report;
- independent review findings/disposition;
- private archive manifest hash, not archive contents;
- jar path and SHA-256;
- known limitations and deferred work;
- explicit statement: no push/tag/release performed.

### Completion criteria

- manual workflow validates the numerical result;
- no privacy or safety regression;
- version is honestly classified;
- user receives a release-ready handoff;
- actual release remains blocked until explicit authorization.

---

# 14. File-by-file change map

Codex must confirm actual paths and ownership with `rg` before editing. This map is a likely target, not permission to create duplicate abstractions.

## 14.1 Production Java

| File/class | Intended change | Must not change |
| --- | --- | --- |
| `service/UnsupportedRippleEvaluator.java` | robust trend residual, amplitude, separate unsupported/trend-authorization scores, typed diagnostics | non-direct evidence must not authorize movement |
| `service/CorridorCenterlineOptimizer.java` | add zero-safe gated absolute short-wave transition cost; separate cost diagnostics; optional plateau blend only if CP6 | exact DP, state bound, comparator/tie order, one-run behavior |
| `service/CorridorTubeBuilder.java` | preferably no behavioral change; expose/reuse robust-fit helper only if needed cleanly | established 5/12/32 m tube behavior unless separately proven |
| `model` or `service` tube-slice/result record | additive immutable fields only if evaluator cannot carry them separately | existing field meaning and serialization |
| `model/GeometryCleanupConfig.java` | ideally unchanged for patch path; internal derived parameters may live in optimizer parameters | no new preference/UI field for patch |
| `model/GeometryCleanupPreset.java` | no change until CP7/CP12 decision | do not silently redefine presets |
| `service/HeatmapConstrainedLaplacianSmoother.java` | calibration/diagnostics or narrowly justified acceptance logic | normal-only, protected-anchor, fit/topology contracts |
| `service/HeatmapConstrainedSimplifier.java` | source/ground-aware tuning and diagnostics where proven | no uniform redistribution; no protected-node loss |
| cleanup orchestration/service | propagate new metrics, retain raw, fail closed | no hidden rerun, no ranking bonus |
| debug exporter located by `LastSlideDebugBundleTest` | additive format/manifest artifact and redaction | old formats, credentials, geometry identity |

Avoid changing palette, source tile, legacy ridge, sampling, tracker association, ranking, or command classes unless an integration test proves a necessary propagation fix.

## 14.2 Java tests

Likely update/add:

```text
UnsupportedRippleEvaluatorTest
LongitudinalCorridorTubeTest
CorridorCenterlineOptimizerTest
GeometryRippleRegularizationTest
CorridorRasterIntegrationTest
CorridorScaleInvarianceTest
CorridorQualityMetricsTest
CorridorEndpointApproachTest
HeatmapConstrainedLaplacianSmootherTest
HeatmapConstrainedSimplifierTest
GeometryCleanupCalibrationTest
GeometryCleanupPerformanceTest
GeometryCleanupServiceTest
GeometryPostProcessorTest
AlignmentServiceTest
AlignWayActionTest
PreviewDialogTest
ReplaceWaySegmentCommandTest
LastSlideDebugBundleTest
HeatmapFixtureArchiveTest
FixtureRegressionTest
```

Add a private-corpus replay helper/task only when replayability supports it. Do not bind normal tests to local archives.

## 14.3 Python analysis

| File | Intended change |
| --- | --- |
| `scripts/analyze-slide-undulations.py` | consume/add attribution fields without breaking old CLI |
| `scripts/analyze-debug-bundles.py` | expose candidate/cost/support fields and current top-ranked behavior |
| `scripts/calibrate-lateral-stability.py` | new safe orchestration, manifest, split, parameter reports |
| helper module/package | bounded nested ZIP, privacy, metrics, deterministic output |
| `scripts/tests/*` | archive safety, old/new schema, metrics, CLI compatibility |

## 14.4 Documentation

```text
AGENTS.md                    # only durable new invariant(s)
DEVELOPMENT.md               # equations, units, calibration, debug, performance
PLANS.md                     # status and deferred work
README.md                    # user-visible behavior and limitations
superpowers/docs/plans/...   # execution plan and ledger
superpowers/docs/...         # implementation log according to repository convention
```

---

# 15. Detailed tuning protocol

## 15.1 Do not optimize on screenshots

Screenshots are useful to locate a case, not to fit weights. Every tuning run must use exported profile/geometry evidence or a deterministic synthetic fixture.

## 15.2 One parameter family at a time

Use the sequence:

1. trend/residual classification;
2. amplitude/deadband;
3. absolute turn cost;
4. interaction with existing acceleration multiplier;
5. Laplacian;
6. simplification;
7. combined confirmation.

Changing all families together makes the result uninterpretable and fragile.

## 15.3 Staged experiment matrix

### Experiment A — trend source

Compare, without production intervention:

- local tube center;
- stability tube center;
- robust consensus of raw/B3/B5 centers;
- effective tube center.

Select the source that best separates labelled ripple from supported turns while retaining authoritative coverage. Do not create a new blended source merely to improve one case.

### Experiment B — residual deadband and amplitude

Evaluate the three seed deadbands and amplitude pairs. Report confusion-like counts by independent case group, not by thousands of correlated profiles.

### Experiment C — absolute turn weight

With the evaluator fixed, sweep `lambda_abs` and turn scale. Stop increasing weight when target gains flatten or supported-shape/center bias worsens.

### Experiment D — existing multiplier interaction

Compare current multiplier with reduced coefficients only after a useful absolute term exists. Prefer eliminating duplicated pressure over stacking several large penalties.

### Experiment E — smoother

Tune strength first, then passes. More passes are not automatically better. Require monotonic or at least stable gain and track shrinkage/center bias.

### Experiment F — simplifier

Tune deviation after smoothing. Measure both point reduction and maximum heatmap/geometry deviation. A 2 m default may be too permissive for precision-oriented traces in some contexts; do not assume that lowering it always improves visual quality.

### Experiment G — repeated slides

Run the chosen Pareto configurations twice or three times on the same geometry. Reject configurations that keep walking sideways or adding points even if first-pass roughness is lower.

## 15.4 Case weighting

Use equal weight per independent corridor/session group. Do not let a long profile sequence or many palette variants dominate. Within a group, report the worst relevant candidate as well as median behavior.

## 15.5 Pareto selection

A configuration is dominated if another configuration is no worse on every hard/soft metric and better on at least one. Select among non-dominated configurations using this priority:

1. safety and branch identity;
2. supported-shape retention;
3. target improvement/worst-case avoidance;
4. center bias and fit;
5. repeated-slide stability;
6. point reduction;
7. runtime and parameter simplicity.

Do not choose solely by average RMS.

## 15.6 Uncertainty reporting

For small corpora, report:

- number of independent groups;
- median and per-case distribution;
- bootstrap intervals only when group count makes them meaningful;
- explicit limitations.

Do not claim population-level optimality from a handful of private examples.

---

# 16. Required tests by behavior

## 16.1 Exact isolation tests

- legacy candidate snapshot/checksum unchanged;
- cleanup-disabled corridor-aware offsets/cost/state/transition/candidate checksum unchanged for patch path;
- zero absolute-turn weight exactly equals old result;
- no new candidate when cleanup disabled;
- raw ID/order stable.

## 16.2 Mathematical unit tests

- wrapped angles around `-pi/pi`;
- Huber loss continuity and finite output;
- turn-rate scaling under doubled spacing/source pitch;
- robust trend under outlier;
- residual amplitude under irregular chainage;
- smoothstep bounds;
- no division by zero/non-finite costs.

## 16.3 Behavioral fixtures

- target ripples suppress;
- supported curves retain;
- broad plateaus center correctly;
- parallel tracks remain separate;
- sparse parents do not invent direct support;
- gap interpolation remains bounded;
- endpoints/junctions remain valid;
- search-boundary-censored profiles fail safely.

## 16.4 Postprocessing tests

- normal-only movement;
- protected anchors exact;
- no point count increase;
- fit retention;
- corridor containment;
- no topology contact;
- typed rejection and raw fallback;
- deterministic passes/chords.

## 16.5 Workflow tests

- preview switch raw/cleaned;
- stale source rejection;
- Apply/Cancel;
- Undo/Redo;
- no unrelated primitive changes;
- tagged/referenced node preservation;
- no hidden cleanup rerun.

## 16.6 Archive/schema/privacy tests

- safe ZIP limits/traversal;
- formats 1–9 readable;
- format 10 if selected;
- checksums/escaping;
- unavailable fields not zero;
- no credential export;
- deterministic manifest/report.

## 16.7 Performance/determinism tests

- 128/256/512 profile scaling;
- same transition counts;
- same output across repeated runs;
- no full-raster duplication;
- no second optimizer.

---

# 17. Stop conditions and rollback rules

Stop the current checkpoint and diagnose rather than weakening a gate when any of these occurs:

1. `LEGACY_V02` changes.
2. Cleanup-disabled corridor-aware output changes during a patch-target implementation.
3. The new cost requires a second optimizer, beam pruning, third-order unbounded state, or state-count increase.
4. A physical threshold derives from profile count, view pixels, raster oversampling, or projection units.
5. Supported sine/apex/switchback retention falls below 90%.
6. A persistent parallel corridor changes identity on a good control.
7. A cleaned path crosses a corridor valley, invents geometry from predicted/no-signal evidence, or fills an unbounded gap.
8. A fixed/shared/tagged/endpoint/junction anchor moves or disappears unexpectedly.
9. Final preview and applied command differ.
10. Undo/Redo touches unrelated primitives.
11. Cleanup rejection hides the raw candidate or suppresses a warning.
12. Fit retention is lowered to make smoothing pass.
13. Topology tolerance is widened to make a candidate applicable.
14. A secret appears in a report, log, test fixture, diff, or archive output.
15. A private archive or extracted member appears in `git status` as tracked/staged.
16. The holdout is used for iterative tuning.
17. Performance exceeds hard review thresholds without a justified optimization.
18. Output is nondeterministic.
19. A claimed replay omits authoritative input/transform fields.
20. The implementation broadens into palette, tracker, sampling, ranking, or rough-sketch redesign.

### Rollback strategy

Each behavioral checkpoint must be independently reversible:

- keep new parameters at zero/disabled until its tests pass;
- avoid combining evaluator, DP and postprocessor changes in one unreviewable commit;
- preserve baseline vectors;
- when a checkpoint fails, revert or disable only that checkpoint, retain diagnostic improvements, and document why;
- never “fix” a regression by deleting the fixture or moving it to exclusion without human justification.

---

# 18. Commit and handoff strategy

Local commits are optional and require authorization under the active Codex session’s rules. No push/tag/release is authorized by this plan.

If local checkpoint commits are authorized, use small, scoped commits such as:

1. `test: freeze lateral stability baseline`
2. `tools: harden private debug bundle analysis`
3. `tools: add lateral movement attribution`
4. `test: add unsupported ripple preservation fixtures`
5. `fix: improve unsupported ripple attribution`
6. `fix: penalize unsupported short-wave turns`
7. `fix: calibrate constrained precise cleanup`
8. `debug: export lateral stability diagnostics`
9. `docs: document lateral stability calibration`

Do not mix version bump or release metadata into implementation commits.

Every checkpoint handoff must state:

```text
Checkpoint:
Status:
Current commit/worktree:
Files changed:
Behavior changed:
Behavior explicitly unchanged:
Tests/commands run:
Exact results/counts:
Private calibration artifacts and manifest hash:
Metrics and acceptance gates:
Review findings:
Known risks/blockers:
Next checkpoint:
Authorization still required for commit/push/tag/release:
```

---

# 19. Definition of done

The implementation is done only when all applicable statements are true:

- [ ] Current checkout and nearest `AGENTS.md` were read and reconciled.
- [ ] Private archives were discovered only under the original clone, hashed, kept immutable and untracked.
- [ ] Safe nested-ZIP and privacy handling pass tests.
- [ ] Failure attribution distinguishes profile, tube, optimizer, postprocessor, output-density, branch, endpoint/junction and insufficient-evidence causes.
- [ ] Deterministic target and preservation fixtures exist.
- [ ] `LEGACY_V02` is unchanged.
- [ ] Cleanup-disabled corridor-aware output is exact for a patch release, or the work is explicitly reclassified to `0.20.0`.
- [ ] One exact bounded second-order DP remains authoritative.
- [ ] Robust trend/amplitude ripple attribution is physical-distance and source-resolution aware.
- [ ] The absolute short-wave turn cost is separately diagnosable and zero-safe.
- [ ] Supported bends, apexes, S-curves and switchbacks satisfy retention gates.
- [ ] Branch identity and parallel-corridor controls are preserved.
- [ ] Raw candidate remains selectable and at most one cleaned sibling exists.
- [ ] Cleanup failure remains fail-closed and never weakens warnings.
- [ ] Fixed/shared/tagged/endpoint/junction anchors are exact.
- [ ] Final topology checks pass on stored final geometry.
- [ ] Preview, Apply, Undo and Redo agree exactly.
- [ ] Repeated slides converge without point growth or lateral walk.
- [ ] Debug bundle compatibility and redaction pass.
- [ ] Training/validation/holdout discipline is documented and holdout passes.
- [ ] Performance and determinism budgets pass.
- [ ] Full Java, Python, build and Javadoc gates are green.
- [ ] Independent review has no unresolved blocker.
- [ ] Documentation and implementation log are complete.
- [ ] Version classification is honest.
- [ ] No push, tag, PR or release occurred without explicit authorization.

---

# 20. Deferred follow-up work

Keep these separate after the residual lateral stability release:

1. split longitudinal profile spacing from lateral cross-section sampling;
2. calibrate palette-to-latent-intensity observation models;
3. replace/augment heuristic longitudinal beam association with globally retained k-best hypotheses;
4. adaptive lateral state allocation and continuous subpixel refinement;
5. explicit rough-sketch 2-D global path initialization;
6. adaptive scale-space/filter ensembles;
7. detector ranking probability calibration;
8. broader nearby-way topology checks;
9. automated user-rating weight proposal across a much larger corpus.

Continuous refinement in particular must not be added until a smooth latent model is established; otherwise it can fit raster noise more precisely and worsen visible wiggle.

---

# 21. Source basis for this plan

Codex must use the current local checkout as authoritative. This plan was prepared from the following public repository material as observed on 2026-08-31:

- WayHeatmapTracer repository: <https://github.com/holubp/josm-wayheatmaptracer>
- repository guardrails: `AGENTS.md`
- development/calibration contracts: `DEVELOPMENT.md`
- current backlog/status: `PLANS.md`
- completed v0.19.0 cleanup plan under `superpowers/docs/plans/`
- `CorridorCenterlineOptimizer.java`
- `UnsupportedRippleEvaluator.java`
- `CorridorTubeBuilder.java`
- `GeometryCleanupConfig.java`
- `GeometryCleanupPreset.java`
- existing Java/Python tests and analyzers named above
- GPT-5.6 Codex Superpowers guide: <https://github.com/eagleagentic/superpowers-gpt-5.6>

The Superpowers workflow does not override the user, the nearest `AGENTS.md`, Codex’s native capabilities, or repository-specific safety rules.

---

## Appendix A — concise architectural rationale

The chosen correction is intentionally a **gated addition**, not a generic smoother:

- the robust trend removes sustained lateral slope before classifying residual oscillation;
- amplitude prevents tiny numerical reversals from receiving full penalty;
- motion/turn/apex support protects real geometry;
- trend authorization prevents uncertain sparse/parallel evidence from becoming a positional target;
- an absolute short-wave turn cost corrects the blind spot of a tube-relative curvature term;
- because it is a second-order transition cost, the exact pair-state DP remains exact over the same bounded state lattice;
- the existing constrained cleanup stage then removes residual visual movement and unnecessary vertices while preserving raw fallback and final topology checks.

Simply increasing continuity or Laplacian strength would be less safe because it cannot adequately distinguish a true switchback from a noisy left-right excursion. Simply reducing point count would hide some movement but would not fix a wrong centerline. Replacing the tracker would expose much larger branch, topology, calibration and performance risk. The staged approach makes the smallest evidence-supported change first.

## Appendix B — minimum final report table

The final private holdout report should include one row per independent case group:

| Group hash | Role | Replay level | Old HF RMS sp | New raw HF RMS sp | New cleaned HF RMS sp | Center bias sp | Shape retention | Fit min | Branch stable | Topology safe | Repeat p95 sp | Points old/new | Runtime delta | Result |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- | ---: | --- |

And aggregate:

- target groups improved/worsened;
- median/p95 suppression;
- center-bias distribution;
- preservation-control retention;
- fit and topology failures;
- repeated-slide stability;
- performance/determinism;
- exclusions with reasons;
- exact frozen configuration;
- manifest/split/report hashes.

## Appendix C — final completion prompt for Codex

At the end of implementation, use this internal checklist before answering the user:

```text
Invoke $verification-before-completion. Re-read the plan’s Definition of Done and every checkpoint handoff. Re-run the complete final command set from a clean build. Reconcile Java tests, Python tests, private holdout, performance, determinism, privacy scan, git status, diff check, documentation, and independent review. State exact evidence and any unmet criterion. Do not call the work complete if a hard gate is missing. Do not push, tag, publish, or release unless the user has explicitly authorized that action in a later message.
```
