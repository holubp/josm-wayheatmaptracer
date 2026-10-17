# Public-only Engine B observation experiment

This branch carries a quarantined empirical observation model and its public
synthetic experiment. It does not activate the model in the plugin, qualify a
release, or claim that the model improves any private capture.

The canonical reviewed inventory of 25 source files has SHA-256
`cb423116e79008e13180f71d7d0a208f4bcd4ebcb3ba3cacdcd047d381ed085b`.
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
