# Public-only Engine B observation experiment

This branch carries a quarantined empirical observation model and its public
synthetic experiment. It does not activate the model in the plugin, qualify a
release, or claim that the model improves any private capture.

The current reviewed inventory of 27 source files has SHA-256
`ccf18c7650068b444824c444d92ba13404fd7079d8ab06653cb82d7cdc8ff958`.
It includes the transition-admission implementations, which were absent from the
original 25-file inventory. Final verification and independent review bind the
renewed run to this inventory before dispatch.
The fixed manifest has SHA-256
`6e44cdc2b75d6d960861bea90f0916a809ff10145341c7431dc52fcdedc16003`.
The isolated snapshot starts at release commit `117baaf`; it includes only the
reviewed source inventory, its existing production dependencies, public catalog,
focused tests and CI configuration. Private captures are not repository inputs.

The first local fitting attempt was externally interrupted after 112 training
and eight selection scene receipts. It had no terminal summary or model artifact.
Its partial scores have no selection authority. A hosted run is an operational
retry of the same sealed experiment, not permission to change its family, grid,
splits, bounds, or thresholds in response to those partial outputs.

The original 25-source run bound to
`cb423116e79008e13180f71d7d0a208f4bcd4ebcb3ba3cacdcd047d381ed085b`
also timed out in hosted run 39072511395 after 112 TRAIN and 56 SELECT cases.
Neither incomplete attempt produced a candidate. The renewed source changes
only computation reuse: allocation-free memo hits, shared immutable unknown
orientation, stage-local heading/pair scalars, and a charged indexed adjacent-pair
geometry memo. No family, grid, split, objective, score count or cap changes.
The original public numerical/work digest is retained. Equal-warm two-run mean
on the same 600-profile fixture changed from 8.120 s to 3.499 s (about 2.32x);
tracked peak changed from 14,455,408 to 3,601,048 bytes. These measurements do not
guarantee the fitting deadline. Cache refusal, cancellation and ownership remain
required tests; no private or holdout evidence was used for these optimizations.

The renewed local prefit check passed 185 tests across 22 suites, with no failures,
errors or skips; the dedicated fitting tool also compiled. Six Python CI boundary
and source-binding checks passed. Independent source review found no remaining
blocker after those checks. The previous broad public-validation failures remain
separate integration work; this prefit result does not establish project-wide
GREEN, a fitted candidate, holdout acceptance or release readiness.

The existing registered `Build` workflow accepts a manual dispatch on this
branch and calls `Public observation identification`. Ordinary build/JAR jobs
are skipped for that dispatch. The experiment workflow is also independently
dispatchable after registration. Neither path creates a release or JAR.

The dedicated `observationIdentify` Gradle source set compiles the exact tool
allowlist, without compiling unrelated tests. CATALOG first checks the manifest
and reviewed source hash. FIT_SELECT consumes that binding, the original default
admission, bounded MAP-only enumeration and unchanged 64 MiB/2 GiB/60-minute
internal limits. The enclosing job is capped at 60 minutes, with an external
3500-second fitting timeout to reserve time for evidence upload. A killed or
incomplete run is a failed CI job, not an accepted candidate.

The upload allowlist contains only top-level JSON files produced by CATALOG and
FIT_SELECT. Those are synthetic receipts and, if generated, one
`FROZEN_PENDING_REGRESSION` artifact. No source directory, private archive, cache,
tile, credential, JAR or release asset is uploaded. HOLDOUT remains inaccessible.
Even a successful job establishes only a parameter freeze: production integration,
unchanged regression gates, sealed holdout and private comparison remain required.

Local verification:

```sh
python -m pytest scripts/tests/test_observation_ci_workflow.py scripts/tests/test_public_ci_workflow.py -q
sh ./gradlew compileJava compileObservationIdentifyJava --console=plain
```

All fitting attempts must retain their exact source/catalog/manifest bindings and
terminal outcome. A model rejection cannot trigger another parameter selection
using holdout or private results.
